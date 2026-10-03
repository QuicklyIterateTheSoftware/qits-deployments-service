package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The idp-client matrix — {@link ResourceProvisioning}'s dispatch of {@link
 * IdpClientProvisioner}, over {@link FakeIdpClientProvisioner}. What a real qits-idp does with
 * these calls is {@code HttpIdpClientProvisionerTest}'s; this is the four arms, the 409 fallback,
 * the self-rotate refusal, and what the row ends up holding.
 */
@QuarkusTest
public class IdpClientProvisioningTest {

  @Inject ResourceProvisioning provisioning;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject PdResourceRepository resources;

  @BeforeEach
  void reset() {
    idpProvisioner.reset();
  }

  private static List<ResourceProvisioning.Resolved> idpClient() {
    return List.of(new ResourceProvisioning.Resolved("idp", null, ResourceSpec.Type.IDP_CLIENT));
  }

  private Optional<PdResource> row(String application, String environment) {
    return QuarkusTransaction.requiringNew()
        .call(() -> resources.findOne(application, environment, "idp"));
  }

  private void existingIdpRow(String application, String environment, String clientId, String secret) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdResource row = new PdResource();
              row.id = UUID.randomUUID().toString();
              row.applicationName = application;
              row.environmentName = environment;
              row.resourceName = "idp";
              row.resourceType = "idp-client";
              row.clientId = clientId;
              row.password = secret;
              row.createdAt = Instant.now();
              resources.persist(row);
            });
  }

  @Test
  void presentPresentDoesNothingAndInjectsTheStoredSecret() {
    existingIdpRow("idp-known", "idp-a", "idp-a-idp-known", "an-already-working-secret");
    idpProvisioner.seedDatabaseClient("idp-a-idp-known");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("idp-known", "idp-a", idpClient());

    assertEquals(List.of("idp-a-idp-known"), idpProvisioner.presenceChecks());
    assertEquals(List.of(), idpProvisioner.createCalls(), "nothing to converge");
    assertEquals(List.of(), idpProvisioner.rotateCalls());
    assertEquals("an-already-working-secret", bindings.get(0).value("CLIENT_SECRET"));
    assertEquals(
        "an-already-working-secret", row("idp-known", "idp-a").orElseThrow().password);
  }

  @Test
  void presentAbsentCreatesAndOverwritesTheStaleSecret() {
    // idp lost its database row (a reset or a restore) but this registry still holds the old
    // secret. qits-idp never accepts a caller-supplied secret, so the fix is a fresh client.
    existingIdpRow("idp-stale", "idp-b", "idp-b-idp-stale", "a-secret-idp-no-longer-knows");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("idp-stale", "idp-b", idpClient());

    assertEquals(List.of("idp-b-idp-stale"), idpProvisioner.createCalls());
    assertEquals(List.of(), idpProvisioner.rotateCalls());
    String fresh = bindings.get(0).value("CLIENT_SECRET");
    assertFalse("a-secret-idp-no-longer-knows".equals(fresh));
    assertEquals(fresh, row("idp-stale", "idp-b").orElseThrow().password);
  }

  @Test
  void absentPresentRotatesAndWritesTheRow() {
    // This registry lost its row, but idp already knows the client.
    idpProvisioner.seedDatabaseClient("idp-c-idp-forgotten");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("idp-forgotten", "idp-c", idpClient());

    assertEquals(List.of(), idpProvisioner.createCalls());
    assertEquals(List.of("idp-c-idp-forgotten"), idpProvisioner.rotateCalls());
    assertTrue(row("idp-forgotten", "idp-c").isPresent());
    assertEquals(
        bindings.get(0).value("CLIENT_SECRET"), row("idp-forgotten", "idp-c").orElseThrow().password);
  }

  @Test
  void absentAbsentCreatesFreshAndWritesTheRow() {
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("idp-fresh", "idp-d", idpClient());

    assertEquals(List.of("idp-d-idp-fresh"), idpProvisioner.createCalls());
    assertEquals(List.of(), idpProvisioner.rotateCalls());
    assertTrue(row("idp-fresh", "idp-d").isPresent());
    assertEquals(
        bindings.get(0).value("CLIENT_SECRET"), row("idp-fresh", "idp-d").orElseThrow().password);
  }

  @Test
  void aConflictOnCreateFallsBackToRotateOnce() {
    // absent/absent, but the 409 says idp actually has it — a drift the presence check did not
    // catch, such as another process creating the client between the two calls.
    idpProvisioner.scriptCreateResult(
        new IdpClientProvisioner.Result(false, true, null, "a database row already exists"));
    idpProvisioner.scriptRotateResult(
        new IdpClientProvisioner.Result(true, false, "rotated-after-conflict", null));

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("idp-race", "idp-e", idpClient());

    assertEquals(List.of("idp-e-idp-race"), idpProvisioner.createCalls());
    assertEquals(List.of("idp-e-idp-race"), idpProvisioner.rotateCalls(), "the fallback, and only once");
    assertEquals("rotated-after-conflict", bindings.get(0).value("CLIENT_SECRET"));
    assertEquals("rotated-after-conflict", row("idp-race", "idp-e").orElseThrow().password);
  }

  @Test
  void theRotateArmIsRefusedForTheDeployersOwnClient() {
    // D9: qits-deployments never rotates the idp client it is itself signed in as —
    // `idp-self-qits-deployments`, the suite's qits.deployments.idp.client-id. idp already knows it
    // (the row is absent, which would otherwise take the rotate arm) — the refusal happens before
    // any call.
    idpProvisioner.seedDatabaseClient(
        "idp-self-" + BootResourceRegistration.APPLICATION); // matches the derived alias below

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    BootResourceRegistration.APPLICATION, "idp-self", idpClient()));

    assertTrue(refused.getMessage().contains("never rotates"), refused.getMessage());
    assertTrue(refused.getMessage().contains("idp-self-qits-deployments"), refused.getMessage());
    assertEquals(List.of(), idpProvisioner.rotateCalls(), "the seam's rotate was never called");
    assertTrue(row(BootResourceRegistration.APPLICATION, "idp-self").isEmpty());
  }

  @Test
  void anotherClientOfQitsDeploymentsIsRotatedRatherThanRefused() {
    // The guard compares the id, not the application. `idp-other-qits-deployments` is a client of
    // qits-deployments that this process is NOT signed in as — the recovery path's shape, where the
    // deployer still runs as its bootstrap client and its derived client has to be rotated.
    idpProvisioner.seedDatabaseClient("idp-other-" + BootResourceRegistration.APPLICATION);

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll(BootResourceRegistration.APPLICATION, "idp-other", idpClient());

    assertEquals(
        List.of("idp-other-qits-deployments"), idpProvisioner.rotateCalls(), "rotated, not refused");
    assertEquals(
        bindings.get(0).value("CLIENT_SECRET"),
        row(BootResourceRegistration.APPLICATION, "idp-other").orElseThrow().password);
  }

  @Test
  void aRowNamingAnotherClientIsNoRowSoTheDerivedClientIsRotatedAndTheRowReKeyed() {
    // The rollback D9 has to survive from the other side: BootResourceRegistration wrote the old
    // container's pair (`qits-deployments`) back onto the deployer's own row, while the derived id is
    // `<tier>-qits-deployments` and idp already holds that client. Reusing the stored secret would
    // start the successor as one client holding another's secret. The row counts as absent, the
    // derived client is rotated (it is not the id this process is signed in as), and the SAME row is
    // overwritten — one row, re-keyed, no second insert against uq_pd_resource.
    existingIdpRow(
        BootResourceRegistration.APPLICATION, "idp-rb", "qits-deployments", "the-bootstrap-secret");
    idpProvisioner.seedDatabaseClient("idp-rb-qits-deployments");
    String rowId = row(BootResourceRegistration.APPLICATION, "idp-rb").orElseThrow().id;

    List<String> logged =
        logsOf(
            () -> {
              List<DeploymentDriver.ResourceBinding> bindings =
                  provisioning.ensureAll(
                      BootResourceRegistration.APPLICATION, "idp-rb", idpClient());
              String injected = bindings.get(0).value("CLIENT_SECRET");
              assertEquals("idp-rb-qits-deployments", bindings.get(0).value("CLIENT_ID"));
              assertFalse("the-bootstrap-secret".equals(injected), "never the other client's");
              PdResource row = row(BootResourceRegistration.APPLICATION, "idp-rb").orElseThrow();
              assertEquals(rowId, row.id, "the same row, updated in place");
              assertEquals("idp-rb-qits-deployments", row.clientId);
              assertEquals(injected, row.password);
              assertFalse(String.join("\n", logsSoFar).contains(injected), "fresh secret logged");
            });

    assertEquals(List.of("idp-rb-qits-deployments"), idpProvisioner.rotateCalls());
    assertEquals(List.of(), idpProvisioner.createCalls());
    assertTrue(
        logged.stream().anyMatch(l -> l.contains("qits-deployments") && l.contains("idp-rb")),
        String.valueOf(logged));
    assertTrue(
        logged.stream().noneMatch(l -> l.contains("the-bootstrap-secret")),
        "a secret was logged: " + logged);
  }

  @Test
  void aRowNamingAnotherClientWithNothingAtIdpCreatesTheDerivedClient() {
    // The same mismatch where idp has never heard of the derived client — the first self-deploy that
    // declares idp:client, run by a deployer still signed in as its bootstrap client.
    existingIdpRow(
        BootResourceRegistration.APPLICATION, "idp-first", "qits-deployments", "the-bootstrap-secret");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll(BootResourceRegistration.APPLICATION, "idp-first", idpClient());

    assertEquals(List.of("idp-first-qits-deployments"), idpProvisioner.createCalls());
    assertEquals(List.of(), idpProvisioner.rotateCalls());
    PdResource row = row(BootResourceRegistration.APPLICATION, "idp-first").orElseThrow();
    assertEquals("idp-first-qits-deployments", row.clientId);
    assertEquals(bindings.get(0).value("CLIENT_SECRET"), row.password);
    assertFalse("the-bootstrap-secret".equals(row.password));
  }

  @Test
  void aMatchingRowStillShortCircuitsForTheDeployersOwnClient() {
    // The steady state after the deployer declares idp:client: BootResourceRegistration wrote the
    // derived pair it runs on, idp has it, and the self-deploy injects exactly that — no write.
    existingIdpRow(
        BootResourceRegistration.APPLICATION, "idp-self", "idp-self-qits-deployments", "running-on");
    idpProvisioner.seedDatabaseClient("idp-self-qits-deployments");
    try {
      List<DeploymentDriver.ResourceBinding> bindings =
          provisioning.ensureAll(BootResourceRegistration.APPLICATION, "idp-self", idpClient());

      assertEquals("running-on", bindings.get(0).value("CLIENT_SECRET"));
      assertEquals(List.of(), idpProvisioner.createCalls());
      assertEquals(List.of(), idpProvisioner.rotateCalls());
    } finally {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  resources
                      .findOne(BootResourceRegistration.APPLICATION, "idp-self", "idp")
                      .ifPresent(resources::delete));
    }
  }

  /** What has been captured so far by the {@link #logsOf} in progress. */
  private final List<String> logsSoFar = new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * Everything {@link ResourceProvisioning} logs while {@code body} runs, as message plus
   * parameters plus any throwable's message — so a secret cannot hide in a format argument whether
   * or not the log manager formats before the handler sees the record.
   */
  private List<String> logsOf(Runnable body) {
    logsSoFar.clear();
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ResourceProvisioning.class.getName());
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            logsSoFar.add(
                record.getMessage()
                    + " "
                    + java.util.Arrays.toString(record.getParameters())
                    + (record.getThrown() == null ? "" : " " + record.getThrown()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      body.run();
    } finally {
      logger.removeHandler(handler);
    }
    return List.copyOf(logsSoFar);
  }

  @Test
  void createFallingBackToRotateIsAlsoRefusedForTheDeployersOwnClient() {
    // The other path into the rotate arm — the 409 fallback — carries the same refusal. The same
    // tier as above: the guard refuses the one id this process is signed in as, and only that one.
    idpProvisioner.scriptCreateResult(
        new IdpClientProvisioner.Result(false, true, null, "a database row already exists"));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    BootResourceRegistration.APPLICATION, "idp-self", idpClient()));

    assertTrue(refused.getMessage().contains("never rotates"), refused.getMessage());
    assertEquals(List.of(), idpProvisioner.rotateCalls());
  }

  @Test
  void aFailureNamesNoSecretAndTheRowIsUntouched() {
    idpProvisioner.scriptCreateResult(
        new IdpClientProvisioner.Result(false, false, null, "qits-idp refused the request"));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("idp-refused", "idp-f", idpClient()));

    assertTrue(refused.getMessage().contains("qits-idp refused the request"), refused.getMessage());
    assertFalse(refused.getMessage().toLowerCase().contains("secret"), refused.getMessage());
    assertTrue(row("idp-refused", "idp-f").isEmpty(), "nothing was written down");
  }

  @Test
  void theRowShapeHasNoDatabaseOrRoleAndCarriesTheClientId() {
    provisioning.ensureAll("idp-shape", "idp-g", idpClient());

    PdResource row = row("idp-shape", "idp-g").orElseThrow();
    assertEquals("idp-client", row.resourceType);
    assertNull(row.databaseName);
    assertNull(row.roleName);
    assertEquals("idp-g-idp-shape", row.clientId);
    assertTrue(row.password != null && !row.password.isBlank());
  }

  @Test
  void aClientIdHeldByAnotherApplicationIsRefusedRatherThanTakenOver() {
    // The derivation is one-to-one on (application, environment, plane), so this should never
    // happen in practice — the refusal is the belt for a derivation bug, the postgres arm's own
    // cross-check applied to the resource type that has no database to collide on instead.
    existingIdpRow("idp-owner", "idp-h", "idp-h-idp-thief", "the-owners-secret");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("idp-thief", "idp-h", idpClient()));

    assertTrue(refused.getMessage().contains("idp-owner"), refused.getMessage());
    assertTrue(refused.getMessage().contains("idp-h-idp-thief"), refused.getMessage());
    assertEquals(List.of(), idpProvisioner.presenceChecks(), "the seam was never even called");
  }

  @Test
  void theClientIdAndTheIdpAddressAreBOTHTierQualifiedNow() {
    // What replaced `thePlatformPlaneDerivesTheBareClientId`. That test held that a platform-plane
    // application's idp client id is the BARE application name and that the injected URL is
    // `http://qits-platform-idp:8080/idp` — both of them the plane's un-tiered spelling, both derived
    // through `PdNetworks.alias(PLATFORM, …)`. The plane is deleted, so the one derivation left
    // answers `<tier>-<app>`, and the address a provisioned container is handed has to carry the tier
    // or it resolves to nothing once qits-platform-idp's bare-named service is retired. (The
    // application itself was later renamed to `qits-idp`.)
    //
    // The client id moving is a real cutover rather than a cosmetic one: qits-idp keys a service
    // client by it, so the first deployment of a former platform application under this code creates
    // a NEW client rather than finding the old one. That is the ordinary create arm and it is safe —
    // the registry row is rewritten with the fresh secret in the same pass — but it is why the id is
    // spelled in exactly one place.
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("idp-plat", "idp-tier", idpClient());

    assertEquals(
        List.of("idp-tier-idp-plat"), idpProvisioner.createCalls(), "the tier is the qualifier");
    assertEquals("idp-tier-idp-plat", bindings.get(0).value("CLIENT_ID"));
    assertEquals("http://idp-tier-qits-idp:8080/idp", bindings.get(0).value("URL"));
  }
}
