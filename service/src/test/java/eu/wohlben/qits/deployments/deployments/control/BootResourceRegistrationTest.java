package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * What this component records about its OWN databases at boot — the rows that make its next
 * self-deploy a no-op instead of a password rotation against pools it is holding open.
 *
 * <p>The startup observer is skipped under TEST (a suite boots dozens of times and has no
 * deployment environment), so this drives the package-private {@code record} directly — the
 * {@code PdSweepAdoptionTest} arrangement. Each test uses its own environment name, because the
 * suite shares one database and the application name is a constant here by design. The two
 * platform-plane tests have no environment name to differ in — there is one null key per resource —
 * so they take one resource each.
 */
@QuarkusTest
public class BootResourceRegistrationTest {

  @Inject BootResourceRegistration registration;
  @Inject PdResourceRepository resources;
  @Inject EnvironmentService environments;

  private PdResource rowOf(String environmentName) {
    return rowOf(environmentName, BootResourceRegistration.RESOURCE_NAME);
  }

  private PdResource rowOf(String environmentName, String resourceName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                resources
                    .findOne(BootResourceRegistration.APPLICATION, environmentName, resourceName)
                    .orElseThrow(
                        () ->
                            new AssertionError(
                                "no " + resourceName + " resource row for " + environmentName)));
  }

  @Test
  public void aBootRecordsTheDatabaseAndTheCredentialItWasHanded() {
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-a-qits-oci-postgresql:5432/qits_deployments_a",
        "qits_deployments_a",
        "0123456789abcdef0123456789abcdef",
        "boot-a");

    PdResource row = rowOf("boot-a");
    assertEquals("qits-deployments", row.applicationName);
    assertEquals("db", row.resourceName);
    assertEquals("postgresql", row.resourceType);
    assertEquals("qits_deployments_a", row.databaseName, "the database comes out of the url path");
    assertEquals("qits_deployments_a", row.roleName, "the role is the username it connects as");
    assertEquals("0123456789abcdef0123456789abcdef", row.password);
    // This component provisioned nothing — the bootstrap did — so it claims no check it never made.
    assertNull(row.lastProvisionedAt, "boot registration is a record, not a provisioning");
  }

  @Test
  public void aBootRecordsTheServerItsUrlNames() {
    // qits-2209: after the deployer moved its own database to the private server, the claim must
    // say so from the successor's first boot, or its next self-deploy plans the same move again.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-s-qits-oci-postgresql:5432/qits_deployments_s",
        "qits_deployments_s",
        "a-password-for-the-server-test",
        "boot-s");
    assertEquals("public", rowOf("boot-s").server);

    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-s-qits-oci-postgresql-private:5432/qits_deployments_s",
        "qits_deployments_s",
        "a-password-for-the-server-test",
        "boot-s");
    assertEquals("private", rowOf("boot-s").server);

    // A host that is neither server leaves the column as it was.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://localhost:5432/qits_deployments_s",
        "qits_deployments_s",
        "a-password-for-the-server-test",
        "boot-s");
    assertEquals("private", rowOf("boot-s").server);
  }

  @Test
  public void aSecondBootRewritesTheOneRowRatherThanAddingAnother() {
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-b-qits-oci-postgresql:5432/qits_deployments_b",
        "qits_deployments_b",
        "first-password-that-was-recorded",
        "boot-b");
    String id = rowOf("boot-b").id;

    // An operator rotated the password in deployment config and restarted. The environment is the truth: a
    // row still naming the old one would send the next self-deploy down the reconcile arm against
    // a credential that already works.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-b-qits-oci-postgresql:5432/qits_deployments_b",
        "qits_deployments_b",
        "second-password-after-a-rotation",
        "boot-b");

    PdResource row = rowOf("boot-b");
    assertEquals(id, row.id, "the same row, rewritten");
    assertEquals("second-password-after-a-rotation", row.password);
    assertEquals(
        1,
        QuarkusTransaction.requiringNew()
            .call(() -> resources.listByDatabase("qits_deployments_b").size()),
        "one row for one database, however many times the process boots");
  }

  @Test
  public void aRewriteKeepsWhateverAProvisioningRecorded() {
    // The reverse order: a deploy provisioned and stamped the row, then the container restarted.
    // The stamp says when the role and the database were last CONFIRMED to exist, which a boot
    // cannot know and must not overwrite.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-c-qits-oci-postgresql:5432/qits_deployments_c",
        "qits_deployments_c",
        "a-password",
        "boot-c");
    Instant stamped = Instant.parse("2026-08-09T10:00:00Z");
    QuarkusTransaction.requiringNew()
        .run(() -> rowOfManaged("boot-c").lastProvisionedAt = stamped);

    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-c-qits-oci-postgresql:5432/qits_deployments_c",
        "qits_deployments_c",
        "a-password",
        "boot-c");

    assertEquals(stamped, rowOf("boot-c").lastProvisionedAt);
  }

  @Test
  public void theEventstreamStoreGetsARowOfItsOwn() {
    // The second resource the spec declares, handed over by the bootstrap exactly as `db` is. Its
    // own row is what keeps the first self-deploy from rotating the bus client's password while
    // this instance's outbox pool is holding it — the same failure `db` records against, one
    // datasource over.
    registration.record(
        BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME,
        "jdbc:postgresql://boot-d-qits-oci-postgresql:5432/qits_deployments_eventstream",
        "qits_deployments_eventstream",
        "an-eventstream-password",
        "boot-d");
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-d-qits-oci-postgresql:5432/qits_deployments_d",
        "qits_deployments_d",
        "a-registry-password",
        "boot-d");

    PdResource eventstream = rowOf("boot-d", BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME);
    assertEquals("qits_deployments_eventstream", eventstream.databaseName);
    assertEquals("an-eventstream-password", eventstream.password);
    // Two rows for one tier, one per resource — neither overwrites the other.
    assertEquals("qits_deployments_d", rowOf("boot-d").databaseName);
  }

  @Test
  public void aBootWithoutTheEnvironmentVariableResolvesTheDesignatedPlatformTier() {
    // THE BOOTSTRAP WINDOW, and the one arm this whole change turns on. This component is deployed
    // by the instance before it, so the first container carrying the new code was started by code
    // that wrote no QITS_ENVIRONMENT for a platform service — the absence used to MEAN "the
    // platform plane" and the rows went in under a null key.
    //
    // Read as a plane it is now wrong: the deployment that starts the successor names the
    // designated tier, and ResourceProvisioning looks the credential up by that name. A row left at
    // null would miss, the provisioner would take its reconcile arm, and both of this component's
    // own passwords would be rotated while its pools hold the old ones open. So the absence is
    // RESOLVED — from pd_environment.designated, the same designation that chose where to deploy it.
    QuarkusTransaction.requiringNew()
        .run(() -> environments.create("boot-designated", "qits-net", true));

    assertEquals(
        "boot-designated",
        registration.environmentName(),
        "no variable is the boot before the first deployment by the current code, not a plane");
  }

  @Test
  public void aBootWithTheVariableTakesItVerbatimWithoutAskingTheDatabase() {
    // The ordinary boot, and the one that must not become a query: what this container was started
    // with is the truth, and a designation moved since then says nothing about where it is running.
    QuarkusTransaction.requiringNew()
        .run(() -> environments.create("boot-elsewhere", "qits-net", true));
    System.setProperty(BootResourceRegistration.ENVIRONMENT_VARIABLE, "boot-started-with");
    try {
      assertEquals("boot-started-with", registration.environmentName());
    } finally {
      System.clearProperty(BootResourceRegistration.ENVIRONMENT_VARIABLE);
    }
  }

  @Test
  public void aPlatformBootRecordsOnThePlaneAndLeavesTheOldTierRowAlone() {
    // The row a platform that still ran this per tier has.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-e-qits-oci-postgresql:5432/qits_deployments_e",
        "qits_deployments_e",
        "the-tier-password",
        "boot-e");

    // The last-resort arm: an install with no designated tier has nothing to key a row by, so the
    // row goes in where the old code put it and the operator is warned. V8 moves it onto the tier's
    // name the moment one is designated.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-e-qits-oci-postgresql:5432/qits_deployments_plane",
        "qits_deployments_plane",
        "the-platform-password",
        null);

    PdResource plane = rowOf(null);
    assertNull(plane.environmentName, "nothing designated, so nothing to name");
    assertEquals("qits_deployments_plane", plane.databaseName);
    assertEquals("the-platform-password", plane.password);
    // The tier row is a leftover, not a conflict: same application name, so the cross-application
    // database check still passes, and nothing rewrites it.
    assertEquals("the-tier-password", rowOf("boot-e").password);
  }

  @Test
  public void aSecondPlatformBootRewritesTheOnePlaneRow() {
    registration.record(
        BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME,
        "jdbc:postgresql://boot-f-qits-oci-postgresql:5432/qits_deployments_plane_eventstream",
        "qits_deployments_plane_eventstream",
        "first-plane-password",
        null);
    String id = rowOf(null, BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME).id;

    registration.record(
        BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME,
        "jdbc:postgresql://boot-f-qits-oci-postgresql:5432/qits_deployments_plane_eventstream",
        "qits_deployments_plane_eventstream",
        "second-plane-password",
        null);

    PdResource row = rowOf(null, BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME);
    assertEquals(id, row.id, "the same row, rewritten — null is a value in the unique key");
    assertEquals("second-plane-password", row.password);
    assertEquals(
        1,
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    resources.listByDatabase("qits_deployments_plane_eventstream").size()),
        "one row for one database, however many times the process boots");
  }

  @Test
  public void theVariableNamesFollowTheResourceName() {
    // The generic contract: rename a resource in .config/qits/deployments.yml and the variables it
    // is injected as move with it. This is the one place that spelling is derived rather than typed.
    assertEquals(
        "QITS_RESOURCE_DB_URL",
        BootResourceRegistration.variable(BootResourceRegistration.RESOURCE_NAME, "URL"));
    assertEquals(
        "QITS_RESOURCE_EVENTSTREAM_PASSWORD",
        BootResourceRegistration.variable(
            BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME, "PASSWORD"));
    assertEquals(
        "QITS_RESOURCE_OBJECT_STORE_USERNAME",
        BootResourceRegistration.variable("object-store", "USERNAME"),
        "a resource name is a dns label, and a hyphen is an underscore in an environment key");
  }

  @Test
  public void theDatabaseIsTheLastPathSegmentAndAUrlWithoutOneIsRefused() {
    assertEquals(
        "qits_deployments",
        BootResourceRegistration.databaseOf(
            "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_deployments"));
    assertEquals(
        "qits_artifacts",
        BootResourceRegistration.databaseOf(
            "jdbc:postgresql://host:5432/qits_artifacts?ApplicationName=x&ssl=false"),
        "the query string is not part of the name");
    assertThrows(
        IllegalArgumentException.class,
        () -> BootResourceRegistration.databaseOf("jdbc:postgresql://host:5432/"));
  }

  @Test
  public void theIdpRowIsRecordedDirectly() {
    registration.recordIdp("boot-g-idp-client", "an-idp-secret", "boot-g");

    PdResource row = rowOf("boot-g", BootResourceRegistration.IDP_RESOURCE_NAME);
    assertEquals("qits-deployments", row.applicationName);
    assertEquals("idp-client", row.resourceType);
    assertEquals("boot-g-idp-client", row.clientId);
    assertEquals("an-idp-secret", row.password);
    assertNull(row.databaseName, "an idp client has no database");
    assertNull(row.roleName, "an idp client has no role");
  }

  @Test
  public void aBootWithTheFullQitsResourceIdpTripleRecordsTheRow() {
    System.setProperty(BootResourceRegistration.variable("idp", "URL"), "http://qits-platform-idp:8080/idp");
    System.setProperty(BootResourceRegistration.variable("idp", "CLIENT_ID"), "boot-h-idp-client");
    System.setProperty(BootResourceRegistration.variable("idp", "CLIENT_SECRET"), "the-resource-secret");
    try {
      registration.recordIdp("boot-h");
    } finally {
      System.clearProperty(BootResourceRegistration.variable("idp", "URL"));
      System.clearProperty(BootResourceRegistration.variable("idp", "CLIENT_ID"));
      System.clearProperty(BootResourceRegistration.variable("idp", "CLIENT_SECRET"));
    }

    PdResource row = rowOf("boot-h", BootResourceRegistration.IDP_RESOURCE_NAME);
    assertEquals("boot-h-idp-client", row.clientId);
    assertEquals("the-resource-secret", row.password);
  }

  @Test
  public void aBootWithOnlyTheConfigurationExtrasFallbackStillRecordsTheRow() {
    // This component does not declare idp:client for itself yet (D10), so every boot today takes
    // this arm — the pair the `configuration` named oidc client and the HTTP adapter both read.
    System.setProperty(BootResourceRegistration.FALLBACK_URL, "http://qits-platform-idp:8080/idp");
    System.setProperty(BootResourceRegistration.FALLBACK_CLIENT_ID, "boot-i-fallback-client");
    System.setProperty(BootResourceRegistration.FALLBACK_CLIENT_SECRET, "the-fallback-secret");
    try {
      registration.recordIdp("boot-i");
    } finally {
      System.clearProperty(BootResourceRegistration.FALLBACK_URL);
      System.clearProperty(BootResourceRegistration.FALLBACK_CLIENT_ID);
      System.clearProperty(BootResourceRegistration.FALLBACK_CLIENT_SECRET);
    }

    PdResource row = rowOf("boot-i", BootResourceRegistration.IDP_RESOURCE_NAME);
    assertEquals("boot-i-fallback-client", row.clientId);
    assertEquals("the-fallback-secret", row.password);
  }

  @Test
  public void aBootWithOnlyAPartialIdpPairRecordsNothing() {
    // Neither name family is complete — the client id alone, with no url and no secret from either
    // family. Warn-only: this component must still start, and there is nothing safe to record.
    System.setProperty(BootResourceRegistration.variable("idp", "CLIENT_ID"), "boot-j-partial-client");
    try {
      registration.recordIdp("boot-j");
    } finally {
      System.clearProperty(BootResourceRegistration.variable("idp", "CLIENT_ID"));
    }

    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    resources
                        .findOne(
                            BootResourceRegistration.APPLICATION,
                            "boot-j",
                            BootResourceRegistration.IDP_RESOURCE_NAME)
                        .isEmpty()),
        "a partial pair records nothing rather than a half-true row");
  }

  @Test
  public void theIdpRowNeverBlocksTheOtherTwoResourcesOnStartup() {
    // onStart's own arrangement: this is the shape, proven at the unit the startup observer calls
    // package-privately, the PdSweepAdoptionTest stance the rest of this class already takes. A
    // failure recording the idp row must not cost `db`/`eventstream` their rows — each resource
    // recorded independently, the postgres siblings' own note.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-k-qits-oci-postgresql:5432/qits_deployments_k",
        "qits_deployments_k",
        "a-registry-password",
        "boot-k");
    // No idp env at all: recordIdp is a no-op, and the postgres row above is untouched by it.
    registration.recordIdp("boot-k");

    assertEquals("qits_deployments_k", rowOf("boot-k").databaseName);
    assertFalse(
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    resources
                        .findOne(
                            BootResourceRegistration.APPLICATION,
                            "boot-k",
                            BootResourceRegistration.IDP_RESOURCE_NAME)
                        .isPresent()));
  }

  /** Inside a transaction, so the returned entity is managed and a field write is persisted. */
  private PdResource rowOfManaged(String environmentName) {
    return resources
        .findOne(
            BootResourceRegistration.APPLICATION,
            environmentName,
            BootResourceRegistration.RESOURCE_NAME)
        .orElseThrow();
  }
}
