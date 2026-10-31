package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.dto.PdSettingDto;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rename cutover (qits-1399), end to end through the real intake and the real worker: a
 * successor whose spec declares {@code renamed_from: P}, deployed while P is deployed in the same
 * tier, takes P's place and retires it.
 *
 * <p>Every predecessor here is deployed through the same intake first, so its rows, its catalogue
 * row, its idp client and its database claim are the ones a real deployment writes — the cutover is
 * then asserted against state nobody hand-built. The bus is on and aimed at a closed port, the
 * {@link PdSwarmDeployFlowTest} arrangement, so a published frame is an outbox row.
 */
@QuarkusTest
@TestProfile(PdSwarmDeployFlowTest.SwarmOrchestrator.class)
public class PdRenameCutoverTest {

  private static final String VERSION = "2026.903.193059";

  private static final ResourceSpec IDP = new ResourceSpec("idp", null, ResourceSpec.Type.IDP_CLIENT);

  /** {@code volumes: private:data:/data} — what an application holding its own data declares. */
  private static final DeploymentSpec.VolumeSpec PRIVATE_DATA =
      new DeploymentSpec.VolumeSpec(DeploymentSpec.VolumeSpec.Scope.PRIVATE, "data", "/data", false);

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver fake;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject PdDeploymentRepository deployments;
  @Inject ServiceCatalog catalog;
  @Inject Settings settings;
  @Inject ResourceProvisioning resourceProvisioning;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
    fake.reset();
    clearOutbox();
  }

  @Test
  public void aStartFirstSwapGoesLiveBesideThePredecessorAndThenRetiresIt() {
    String env = "rn-swap";
    String environmentId = createEnvironment(env);
    String old = "rn-swap-old";
    String fresh = "rn-swap-new";
    List<ResourceSpec> resources = List.of(new ResourceSpec("db", "qits_rn_swap_db"), IDP);
    deployPredecessor(environmentId, old, resources);
    settings.put(old, env, "GREETING", "hello", false, "an operator");
    fake.reset();
    clearOutbox();

    specs.script(fresh, spec(resources, null, old, null));
    postRelease("run-swap", fresh);
    awaitSettled(environmentId, 2);

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    PdDeployment retired = newest(old, environmentId);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, retired.status);
    assertTrue(retired.detail.startsWith("[decommissioned by rename to " + fresh), retired.detail);

    // start-first: the successor is created and gated BESIDE the predecessor, which goes after.
    List<String> calls = fake.calls();
    int apply = calls.indexOf("apply:" + env + "-" + fresh);
    int await = calls.indexOf("await:" + env + "-" + fresh);
    int remove = calls.indexOf("removeService:" + env + "-" + old);
    assertTrue(apply >= 0 && apply < await && await < remove, calls.toString());
    assertEquals(1, calls.stream().filter(c -> c.startsWith("removeService:")).count(), calls.toString());
    DeploymentDriver.ServiceSpec applied = fake.applied().get(0);
    assertEquals(old, applied.renamedFrom());
    assertTrue(applied.takesOverPredecessor(), "a serving predecessor's address is taken over");

    // The retirement, every step of it.
    assertTrue(catalog.find(old).isEmpty(), "the predecessor's catalogue row is gone");
    assertTrue(catalog.find(fresh).isPresent());
    assertTrue(settings.list(old, env).isEmpty(), "the predecessor's settings are retired");
    List<PdSettingDto> copied = settings.list(fresh, env);
    assertEquals(1, copied.size(), copied.toString());
    assertEquals("hello", copied.get(0).value());
    assertEquals("rename from " + old, copied.get(0).updatedBy());
    assertEquals(List.of(env + "-" + old), idpProvisioner.deleteCalls());
    assertFalse(resourceProvisioning.holdsIdpClient(old, env));
    assertTrue(resourceProvisioning.holdsIdpClient(fresh, env), "the successor keeps its own");

    // The edge: the predecessor's empty snapshot first, then the successor's frame.
    List<OutboxEvent> actives = actives();
    assertEquals(2, actives.size(), actives.toString());
    assertTrue(actives.get(0).payload.contains("\"applicationName\":\"" + old + "\""));
    assertTrue(actives.get(0).payload.contains("\"endpoints\":[]"), actives.get(0).payload);
    assertTrue(actives.get(1).payload.contains("\"applicationName\":\"" + fresh + "\""));
  }

  @Test
  public void aSuccessorThatPublishesAPortStopsThePredecessorFirstAndPullsBeforeThat() {
    String env = "rn-stop";
    String environmentId = createEnvironment(env);
    String old = "rn-stop-old";
    String fresh = "rn-stop-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    fake.reset();

    specs.script(fresh, spec(List.of(IDP), null, old, publishing()));
    postRelease("run-stop", fresh);
    awaitSettled(environmentId, 2);

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(old, environmentId).status);
    List<String> calls = fake.calls();
    int pull = indexOfPrefix(calls, "pull:");
    int remove = calls.indexOf("removeService:" + env + "-" + old);
    int apply = calls.indexOf("apply:" + env + "-" + fresh);
    assertTrue(pull >= 0 && pull < remove && remove < apply, calls.toString());
    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertTrue(catalog.find(old).isEmpty());
  }

  @Test
  public void aStopFirstSuccessorThatFailsItsGateRestoresThePredecessor() {
    String env = "rn-fail";
    String environmentId = createEnvironment(env);
    String old = "rn-fail-old";
    String fresh = "rn-fail-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    String predecessorId = newest(old, environmentId).id;
    settings.put(old, env, "GREETING", "hello", false, "an operator");
    fake.reset();
    fake.scriptConvergence(DeploymentDriver.Convergence.failed("the task never went healthy"));

    specs.script(fresh, spec(List.of(IDP), DeploymentDriver.UpdateOrder.STOP_FIRST, old, null));
    postRelease("run-fail", fresh);
    awaitSettled(environmentId, 2);

    PdDeployment failed = newest(fresh, environmentId);
    assertEquals(PdDeploymentStatus.FAILED, failed.status);
    assertTrue(
        failed.detail.contains(
            old + " was restored from its last ACTIVE deployment " + predecessorId),
        failed.detail);
    assertTrue(failed.detail.contains("the task never went healthy"), failed.detail);
    assertEquals(PdDeploymentStatus.ACTIVE, newest(old, environmentId).status, "untouched");

    // stop-old, then the failed successor out of the way, then the predecessor back — no pull.
    List<String> calls = fake.calls();
    int stop = calls.indexOf("removeService:" + env + "-" + old);
    int apply = calls.indexOf("apply:" + env + "-" + fresh);
    int removeFailed = calls.indexOf("removeService:" + env + "-" + fresh);
    int restore = calls.indexOf("apply:" + env + "-" + old);
    assertTrue(stop < apply && apply < removeFailed && removeFailed < restore, calls.toString());
    assertEquals(1, fake.pulled().size(), "the predecessor's image is on the host: " + fake.pulled());
    DeploymentDriver.ServiceSpec restored = fake.applied().get(1);
    assertEquals(old, restored.applicationName());
    assertTrue(restored.imageRef().endsWith("/qits/" + old + ":" + VERSION), restored.imageRef());
    assertEquals(predecessorId, restored.deploymentId());
    assertEquals(null, restored.renamedFrom());

    // Nothing is retired: the predecessor serves again.
    assertTrue(catalog.find(old).isPresent());
    assertEquals(1, settings.list(old, env).size());
    assertEquals(List.of(), idpProvisioner.deleteCalls());
    assertEquals(0, actives().stream().filter(e -> e.payload.contains(fresh)).count());
  }

  @Test
  public void aStartFirstSuccessorWithPrivateDataMountsThePredecessorsVolumeAndStopsItFirst() {
    // qits-1772: the successor's own <new>-data does not exist and the predecessor's does, so it
    // mounts the predecessor's rather than a fresh, empty one — and because it is the SAME volume,
    // start-first would run two writers on it, so the swap is forced stop-old even though nothing
    // here asks for stop-first or publishes a port.
    String env = "rn-vol";
    String environmentId = createEnvironment(env);
    String old = "rn-vol-old";
    String fresh = "rn-vol-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    fake.reset();
    fake.scriptExistingVolume(old + "-data");

    specs.script(fresh, spec(List.of(IDP), null, old, null, List.of(PRIVATE_DATA)));
    List<String> logged =
        logsOf(
            () -> {
              postRelease("run-vol", fresh);
              awaitSettled(environmentId, 2);
            });

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(old, environmentId).status);
    DeploymentDriver.ServiceSpec applied = fake.applied().get(0);
    assertEquals(fresh, applied.applicationName());
    assertEquals(
        List.of(new DeploymentDriver.VolumeMount(old + "-data", "/data", false)),
        applied.volumes(),
        "the predecessor's data, never a fresh " + fresh + "-data");
    List<String> calls = fake.calls();
    int pull = indexOfPrefix(calls, "pull:");
    int remove = calls.indexOf("removeService:" + env + "-" + old);
    int apply = calls.indexOf("apply:" + env + "-" + fresh);
    assertTrue(pull >= 0 && pull < remove && remove < apply, calls.toString());
    assertTrue(
        logged.stream()
            .anyMatch(
                line -> line.contains("stop-old order forced") && line.contains(old + "-data")),
        logged.toString());
  }

  @Test
  public void aSuccessorWithPrivateDataThatFailsItsGateRestoresThePredecessorOnItsOwnVolume() {
    // The forced stop-old swap's failure path: the predecessor comes back mounting <old>-data,
    // which the successor's pinned spec already named — nothing is rewritten onto a third name.
    String env = "rn-volfail";
    String environmentId = createEnvironment(env);
    String old = "rn-volfail-old";
    String fresh = "rn-volfail-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    String predecessorId = newest(old, environmentId).id;
    fake.reset();
    fake.scriptExistingVolume(old + "-data");
    fake.scriptConvergence(DeploymentDriver.Convergence.failed("the task never went healthy"));

    specs.script(fresh, spec(List.of(IDP), null, old, null, List.of(PRIVATE_DATA)));
    postRelease("run-volfail", fresh);
    awaitSettled(environmentId, 2);

    assertEquals(PdDeploymentStatus.FAILED, newest(fresh, environmentId).status);
    assertEquals(PdDeploymentStatus.ACTIVE, newest(old, environmentId).status, "untouched");
    List<String> calls = fake.calls();
    int stop = calls.indexOf("removeService:" + env + "-" + old);
    int apply = calls.indexOf("apply:" + env + "-" + fresh);
    int restore = calls.indexOf("apply:" + env + "-" + old);
    assertTrue(stop >= 0 && stop < apply && apply < restore, calls.toString());
    DeploymentDriver.ServiceSpec restored = fake.applied().get(1);
    assertEquals(old, restored.applicationName());
    assertEquals(predecessorId, restored.deploymentId());
    assertEquals(
        List.of(new DeploymentDriver.VolumeMount(old + "-data", "/data", false)),
        restored.volumes());
  }

  @Test
  public void aCompletedRenameWhoseOwnVolumeExistsKeepsItAndIsNotForcedStopOld() {
    // The qits-edge / qits-stt case qits-1772's first fix broke: the rename was cut over before
    // the pin existed, the file still declares renamed_from, the predecessor is long retired and
    // the application has run on its own <new>-data since. A stale <old>-data may still sit on the
    // host. The own volume wins, and nothing about the swap is forced.
    String env = "rn-done";
    String environmentId = createEnvironment(env);
    String old = "rn-done-old";
    String fresh = "rn-done-new";
    fake.scriptExistingVolume(fresh + "-data");
    fake.scriptExistingVolume(old + "-data");

    specs.script(fresh, spec(List.of(), null, old, null, List.of(PRIVATE_DATA)));
    List<String> logged =
        logsOf(
            () -> {
              postRelease("run-done", fresh);
              awaitSettled(environmentId, 1);
            });

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertEquals(
        List.of(new DeploymentDriver.VolumeMount(fresh + "-data", "/data", false)),
        fake.applied().get(0).volumes(),
        "its own data, never the predecessor's stale " + old + "-data");
    List<String> calls = fake.calls();
    assertTrue(calls.contains("volumeExists:" + fresh + "-data"), calls.toString());
    assertFalse(calls.contains("volumeExists:" + old + "-data"), "own present settles it: " + calls);
    assertFalse(calls.stream().anyMatch(c -> c.startsWith("removeService:")), calls.toString());
    assertFalse(
        logged.stream().anyMatch(line -> line.contains("stop-old order forced")), logged.toString());
    assertTrue(
        logged.stream()
            .anyMatch(line -> line.contains("mounts its own volume") && line.contains(fresh + "-data")),
        logged.toString());
  }

  @Test
  public void aRenamedVolumeTheRuntimeCannotAnswerAboutFailsTheRowBeforeAnythingMoves() {
    String env = "rn-volunk";
    String environmentId = createEnvironment(env);
    String old = "rn-volunk-old";
    String fresh = "rn-volunk-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    fake.reset();
    fake.scriptUnknownVolume(fresh + "-data", "Cannot connect to the Docker daemon");

    specs.script(fresh, spec(List.of(IDP), null, old, null, List.of(PRIVATE_DATA)));
    postRelease("run-volunk", fresh);
    awaitSettled(environmentId, 2);

    PdDeployment failed = newest(fresh, environmentId);
    assertEquals(PdDeploymentStatus.FAILED, failed.status);
    assertTrue(failed.detail.contains(fresh + "-data"), failed.detail);
    assertTrue(failed.detail.contains("Cannot connect to the Docker daemon"), failed.detail);
    assertEquals(List.of(), fake.pulled());
    assertEquals(List.of(), fake.applied());
    assertEquals(PdDeploymentStatus.ACTIVE, newest(old, environmentId).status, "untouched");
    assertTrue(catalog.find(old).isPresent());
  }

  @Test
  public void aScaledDownShellIsAnOrdinaryDeploymentFollowedByTheRetirement() {
    String env = "rn-shell";
    String environmentId = createEnvironment(env);
    String old = "rn-shell-old";
    String fresh = "rn-shell-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    scaledToZero(old, environmentId);
    settings.put(old, env, "GREETING", "hello", false, "an operator");
    fake.reset();

    // Published ports and all — the qits-edge shape — and still no stop-old: nothing serves.
    specs.script(fresh, spec(List.of(IDP), null, old, publishing()));
    postRelease("run-shell", fresh);
    awaitSettled(environmentId, 2);

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(old, environmentId).status);
    List<String> calls = fake.calls();
    int await = calls.indexOf("await:" + env + "-" + fresh);
    int remove = calls.indexOf("removeService:" + env + "-" + old);
    assertTrue(await >= 0 && await < remove, "the shell goes after the gate: " + calls);
    assertFalse(
        calls.contains("removeService:" + env + "-" + fresh), "never the successor: " + calls);
    assertFalse(fake.applied().get(0).takesOverPredecessor(), "a shell's address is not worth a recreate");
    assertEquals(1, settings.list(fresh, env).size(), "a first deployment takes the settings");
    assertTrue(settings.list(old, env).isEmpty());
    assertTrue(catalog.find(old).isEmpty());
    assertEquals(List.of(env + "-" + old), idpProvisioner.deleteCalls());
  }

  @Test
  public void aSuccessorDeployedBeforeIsNotHandedThePredecessorsSettings() {
    // The six live successors were configured by hand; a key-wise copy from their shells must not
    // resurrect a key somebody removed.
    String env = "rn-later";
    String environmentId = createEnvironment(env);
    String old = "rn-later-old";
    String fresh = "rn-later-new";
    specs.script(fresh, spec(List.of(), null, null, null));
    postRelease("run-later-1", fresh);
    awaitSettled(environmentId, 1);
    deployPredecessor(environmentId, old, List.of(IDP), 2);
    scaledToZero(old, environmentId);
    settings.put(old, env, "GREETING", "hello", false, "an operator");
    fake.reset();

    specs.script(fresh, spec(List.of(), null, old, null));
    postRelease("run-later-2", fresh);
    awaitSettled(environmentId, 3);

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertTrue(settings.list(fresh, env).isEmpty(), "nothing copied onto a configured successor");
    assertTrue(settings.list(old, env).isEmpty(), "the predecessor's are retired all the same");
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(old, environmentId).status);
    assertTrue(fake.calls().contains("removeService:" + env + "-" + old), fake.calls().toString());
  }

  @Test
  public void aFailedRetirementStepLeavesTheSuccessorLiveAndIsFinishedByTheNextObservation() {
    String env = "rn-retry";
    String environmentId = createEnvironment(env);
    String old = "rn-retry-old";
    String fresh = "rn-retry-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    settings.put(old, env, "GREETING", "hello", false, "an operator");
    fake.reset();
    idpProvisioner.scriptDeleteResult(
        new IdpClientProvisioner.Result(false, false, null, "qits-idp answered 503"));

    specs.script(fresh, spec(List.of(IDP), null, old, null));
    postRelease("run-retry", fresh);
    awaitSettled(environmentId, 2);

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertEquals(1, actives().stream().filter(e -> e.payload.contains(fresh)).count());
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(old, environmentId).status);
    assertTrue(catalog.find(old).isEmpty());
    assertTrue(settings.list(old, env).isEmpty());
    assertTrue(resourceProvisioning.holdsIdpClient(old, env), "the refused step keeps its row");

    idpProvisioner.scriptDeleteResult(null);
    fake.scriptObservation(env + "-" + fresh, "running/healthy");
    deployService.enqueueObservation();
    awaitIdle();

    assertFalse(resourceProvisioning.holdsIdpClient(old, env), "finished on the next observation");
    assertEquals(List.of(env + "-" + old, env + "-" + old), idpProvisioner.deleteCalls());
    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);

    // Fully retired: the next pass has nothing to do and asks nothing.
    deployService.enqueueObservation();
    awaitIdle();
    assertEquals(2, idpProvisioner.deleteCalls().size());
  }

  @Test
  public void aPredecessorsIdpClientThatASettingStillNamesIsNotDeleted() {
    // The live shape: qits-orchestrator's settings carried its predecessor's client id, copied by
    // hand. Deleting that client would refuse the successor's next token, so the retirement leaves
    // it — and treats the step as decided, so nothing retries it.
    String env = "rn-ref";
    String environmentId = createEnvironment(env);
    String old = "rn-ref-old";
    String fresh = "rn-ref-new";
    String clientId = env + "-" + old;
    deployPredecessor(environmentId, old, List.of(IDP));
    settings.put(fresh, env, "QUARKUS_OIDC_CLIENT_CLIENT_ID", clientId, false, "an operator");
    // A secret holding the same string is never compared, and never named.
    settings.put("rn-ref-other", env, "SOME_SECRET", clientId, true, "an operator");
    fake.reset();

    specs.script(fresh, spec(List.of(IDP), null, old, null));
    List<String> logs =
        logsOf(
            () -> {
              postRelease("run-ref", fresh);
              awaitSettled(environmentId, 2);
            });

    assertEquals(PdDeploymentStatus.ACTIVE, newest(fresh, environmentId).status);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(old, environmentId).status);
    assertTrue(catalog.find(old).isEmpty(), "every other step still ran");
    assertEquals(List.of(), idpProvisioner.deleteCalls(), "the named client is not deleted");
    assertTrue(resourceProvisioning.holdsIdpClient(old, env), "and its row is kept");
    String warn =
        logs.stream()
            .filter(line -> line.startsWith("Not retiring the idp client"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no WARN in " + logs));
    assertTrue(warn.contains(clientId), warn);
    assertTrue(warn.contains(fresh + ":QUARKUS_OIDC_CLIENT_CLIENT_ID"), warn);
    assertFalse(warn.contains("SOME_SECRET"), "a secret row is never compared: " + warn);

    // Decided, not failed: the next observation runs nothing and deletes nothing.
    fake.scriptObservation(env + "-" + fresh, "running/healthy");
    deployService.enqueueObservation();
    awaitIdle();
    assertEquals(List.of(), idpProvisioner.deleteCalls());
    assertTrue(resourceProvisioning.holdsIdpClient(old, env));
  }

  /** Every log record DeployService writes while {@code body} runs: message plus parameters. */
  private static List<String> logsOf(Runnable body) {
    List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(DeployService.class.getName());
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            lines.add(
                record.getMessage() + " " + java.util.Arrays.toString(record.getParameters()));
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
    return List.copyOf(lines);
  }

  @Test
  public void renamingTheDeployerIsRefusedBeforeAnythingMoves() {
    String env = "rn-self";
    String environmentId = createEnvironment(env);
    String old = "rn-self-old";
    String fresh = "rn-self-new";
    deployPredecessor(environmentId, old, List.of(IDP));
    settings.put(old, env, "GREETING", "hello", false, "an operator");
    fake.reset();
    fake.scriptOwnService(env + "-" + fresh);

    specs.script(fresh, spec(List.of(IDP), null, old, null));
    postRelease("run-self", fresh);
    awaitSettled(environmentId, 2);

    PdDeployment refused = newest(fresh, environmentId);
    assertEquals(PdDeploymentStatus.FAILED, refused.status);
    assertEquals(DeploymentDriver.selfRenameRefusal(fresh, old), refused.detail);
    assertEquals(List.of(), fake.applied());
    assertEquals(List.of(), fake.pulled());
    assertEquals(PdDeploymentStatus.ACTIVE, newest(old, environmentId).status);
    assertTrue(settings.list(fresh, env).isEmpty(), "nothing copied");
    assertTrue(catalog.find(old).isPresent());
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static DeploymentSpec spec(
      List<ResourceSpec> resources,
      DeploymentDriver.UpdateOrder order,
      String renamedFrom,
      DeclaredShape shape) {
    return spec(resources, order, renamedFrom, shape, List.of());
  }

  private static DeploymentSpec spec(
      List<ResourceSpec> resources,
      DeploymentDriver.UpdateOrder order,
      String renamedFrom,
      DeclaredShape shape,
      List<DeploymentSpec.VolumeSpec> volumes) {
    return new DeploymentSpec(
        false,
        null,
        null,
        null,
        resources,
        volumes,
        order,
        null,
        List.of(),
        8080,
        null,
        false,
        List.of(),
        null,
        null,
        renamedFrom,
        null,
        shape);
  }

  /** The edge's kind of service: a published host port. */
  private static DeclaredShape publishing() {
    return new DeclaredShape(
        List.of(), List.of("8080:8080"), List.of());
  }

  private void deployPredecessor(String environmentId, String name, List<ResourceSpec> resources) {
    deployPredecessor(environmentId, name, resources, 1);
  }

  private void deployPredecessor(
      String environmentId, String name, List<ResourceSpec> resources, int rowsAfter) {
    specs.script(name, spec(resources, null, null, null));
    postRelease("run-" + name, name);
    awaitSettled(environmentId, rowsAfter);
    assertEquals(PdDeploymentStatus.ACTIVE, newest(name, environmentId).status);
  }

  private void scaledToZero(String name, String environmentId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                deployments.newestForPlace(name, environmentId).orElseThrow().status =
                    PdDeploymentStatus.SCALED_TO_ZERO);
  }

  private PdDeployment newest(String name, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.newestForPlace(name, environmentId).orElseThrow();
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              return copy;
            });
  }

  private static int indexOfPrefix(List<String> calls, String prefix) {
    for (int index = 0; index < calls.size(); index++) {
      if (calls.get(index).startsWith(prefix)) {
        return index;
      }
    }
    return -1;
  }

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postRelease(String runId, String repoId) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", runId, "repoId", repoId, "version", VERSION))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private List<Map<String, Object>> deploymentsOf(String environmentId) {
    return given()
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments");
  }

  private void awaitSettled(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows = deploymentsOf(environmentId);
      boolean settled =
          rows.size() == count
              && rows.stream()
                  .noneMatch(
                      row ->
                          "QUEUED".equals(row.get("status")) || "STARTING".equals(row.get("status")));
      if (settled) {
        awaitIdle();
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    fail("the deployments of " + environmentId + " did not settle to " + count);
  }

  private void awaitIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private void clearOutbox() {
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  private List<OutboxEvent> actives() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList()
                    .stream()
                    .filter(row -> "DeploymentActive".equals(row.name))
                    .sorted(Comparator.comparing(row -> row.createdAt))
                    .toList());
  }
}
