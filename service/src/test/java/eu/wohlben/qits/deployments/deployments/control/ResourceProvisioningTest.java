package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
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
 * The orchestration between the registry and the postgres seam: which password is sent, which one
 * comes back into the row, which address the resources are provisioned at, and what is refused.
 *
 * <p>The seam itself is {@link FakeResourceProvisioner} here — what a real postgres does with these
 * requests is proven against a real one in {@code PgResourceProvisionerTest}. What is under test is
 * the pair the drift cases hang on: <b>what the registry knew</b> going in, and <b>what it records</b>
 * coming out.
 */
@QuarkusTest
public class ResourceProvisioningTest {

  @Inject ResourceProvisioning provisioning;
  @Inject FakeResourceProvisioner provisioner;
  @Inject PdResourceRepository resources;
  @Inject EnvironmentService environments;

  @BeforeEach
  void reset() {
    provisioner.reset();
  }

  private Optional<PdResource> row(String application, String environment, String name) {
    return QuarkusTransaction.requiringNew()
        .call(() -> resources.findOne(application, environment, name));
  }

  private void existingRow(
      String application, String environment, String name, String database, String password) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdResource row = new PdResource();
              row.id = UUID.randomUUID().toString();
              row.applicationName = application;
              row.environmentName = environment;
              row.resourceName = name;
              row.resourceType = "postgresql";
              row.databaseName = database;
              row.roleName = database;
              row.password = password;
              row.createdAt = Instant.now();
              resources.persist(row);
            });
  }

  private static List<ResourceProvisioning.Resolved> one(String name, String database) {
    return List.of(new ResourceProvisioning.Resolved(name, database));
  }

  @Test
  public void aResourceNothingHasRecordedIsCreatedWithAFreshPasswordAndWrittenDown() {
    // The matrix's second row: no registry row, nothing on the server. The provisioner is told
    // there is no stored password, answers with the fresh one, and the row that lands holds it.
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-new", "prov-a", one("db", "qits_prov_new"));

    ResourceProvisioner.Request request = provisioner.requests().get(0);
    assertNull(request.storedPassword(), "the registry knew nothing, and said so");
    assertEquals("qits_prov_new", request.databaseName());
    assertEquals("qits_prov_new", request.roleName(), "the role IS the database");
    assertEquals("postgres", request.adminUsername());
    assertEquals(32, request.freshPassword().length());
    assertTrue(request.freshPassword().matches("[0-9a-f]{32}"), request.freshPassword());

    PdResource row = row("prov-new", "prov-a", "db").orElseThrow();
    assertEquals(request.freshPassword(), row.password);
    assertEquals("qits_prov_new", row.databaseName);
    assertEquals("postgresql", row.resourceType);
    assertNotEquals(null, row.lastProvisionedAt, "a provisioning stamps when it confirmed");

    assertEquals(1, bindings.size());
    assertEquals("db", bindings.get(0).name());
    assertEquals(
        "jdbc:postgresql://prov-a-qits-oci-postgresql:5432/qits_prov_new", bindings.get(0).value("URL"));
    assertEquals("qits_prov_new", bindings.get(0).value("USERNAME"));
    assertEquals(request.freshPassword(), bindings.get(0).value("PASSWORD"));
  }

  @Test
  public void aRedeploymentSendsTheRecordedPasswordAndInjectsItBack() {
    // The matrix's first row, and the one that must never rotate anything: the application is
    // running on that credential right now.
    existingRow("prov-known", "prov-b", "db", "qits_prov_known", "an-already-working-password");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-known", "prov-b", one("db", "qits_prov_known"));

    assertEquals("an-already-working-password", provisioner.requests().get(0).storedPassword());
    assertEquals("an-already-working-password", bindings.get(0).value("PASSWORD"));
    assertEquals("an-already-working-password", row("prov-known", "prov-b", "db").orElseThrow().password);
  }

  @Test
  public void aSelfHealKeepsTheRecordedPasswordAndAReconcileTakesTheFreshOne() {
    // The two drift arms, told apart by what the SEAM answers rather than by what the caller
    // guessed: this component records whatever came back, which is what keeps the row and the
    // server agreeing after either kind of reset.
    existingRow("prov-heal", "prov-c", "db", "qits_prov_heal", "the-recorded-one");
    provisioner.scriptResult(new ResourceProvisioner.Result(true, "the-recorded-one", null));
    provisioning.ensureAll("prov-heal", "prov-c", one("db", "qits_prov_heal"));
    assertEquals("the-recorded-one", row("prov-heal", "prov-c", "db").orElseThrow().password);

    // ...and the reconcile: the server rotated the role because nothing knew its password.
    provisioner.reset();
    provisioner.scriptResult(new ResourceProvisioner.Result(true, "a-rotated-one", null));
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-reconcile", "prov-c", one("db", "qits_prov_reconcile"));
    assertEquals("a-rotated-one", row("prov-reconcile", "prov-c", "db").orElseThrow().password);
    assertEquals("a-rotated-one", bindings.get(0).value("PASSWORD"), "and the container is told the new one");
  }

  @Test
  public void aRefusedProvisioningIsAFailureWithPostgresOwnWordsAndNoPasswordInIt() {
    provisioner.scriptResult(
        new ResourceProvisioner.Result(false, null, "postgres refused: permission denied"));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("prov-refused", "prov-d", one("db", "qits_prov_refused")));

    assertTrue(refused.getMessage().contains("qits_prov_refused"), refused.getMessage());
    assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
    assertTrue(row("prov-refused", "prov-d", "db").isEmpty(), "and nothing was written down");
  }

  @Test
  public void anotherApplicationsDatabaseIsRefusedRatherThanTakenOver() {
    // Two repositories naming one database is the one collision the qits_ allowlist cannot prevent,
    // so the registry answers it — and it answers with a FAILED deployment rather than a silent
    // no-op that would hand one application's store to another.
    existingRow("prov-owner", "prov-e", "db", "qits_prov_shared", "the-owners-password");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("prov-thief", "prov-e", one("db", "qits_prov_shared")));

    assertTrue(refused.getMessage().contains("prov-owner"), refused.getMessage());
    assertTrue(refused.getMessage().contains("qits_prov_shared"), refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "the seam was never even called");
  }

  @Test
  public void aDeclaredPredecessorTransfersTheClaimAndTheCredentialWithIt() {
    // The rename: qits-platform-mirror becomes qits-mirror, the file keeps naming the same database
    // because abandoning the data was never on the table, and the successor says whose it used to be.
    //
    // THE ASSERTION THAT MATTERS IS THE STORED PASSWORD, not the absence of the refusal. Getting
    // past the check is the cheap half; the claim row is the single authority for the provisioned
    // credential, so a transfer that moved the claim and lost the password would send `null` here,
    // the reconcile arm would rotate the role, and the predecessor — still running, still holding
    // open pools on it — would go down as a side effect of its own successor's first deployment.
    existingRow("prov-renamed-old", "prov-r", "db", "qits_prov_renamed", "the-predecessors-password");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll(
            "prov-renamed-new", "prov-r", "prov-renamed-old", one("db", "qits_prov_renamed"));

    assertEquals(
        "the-predecessors-password",
        provisioner.requests().get(0).storedPassword(),
        "the transferred row is read back inside the same bracket, so the stored password is sent");
    assertEquals("the-predecessors-password", bindings.get(0).value("PASSWORD"));

    PdResource row = row("prov-renamed-new", "prov-r", "db").orElseThrow();
    assertEquals("qits_prov_renamed", row.databaseName, "the same store, not a fresh one");
    assertEquals("the-predecessors-password", row.password);
    assertTrue(
        row("prov-renamed-old", "prov-r", "db").isEmpty(),
        "the claim MOVED — a copy would leave two applications claiming one database");
  }

  @Test
  public void aPredecessorNobodyDeclaredIsStillRefusedWhenAnotherIsDeclared() {
    // The narrowing is exact rather than a mood: declaring A does not soften the check against B.
    // This is the arm a typo in `renamed_from` lands on, and it has to answer the same way a file
    // with no key at all does.
    existingRow("prov-renamed-owner", "prov-s", "db", "qits_prov_notyours", "the-owners-password");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    "prov-renamed-thief",
                    "prov-s",
                    "prov-somebody-else",
                    one("db", "qits_prov_notyours")));

    assertTrue(refused.getMessage().contains("prov-renamed-owner"), refused.getMessage());
    assertTrue(refused.getMessage().contains("cannot share one database"), refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "the seam was never even called");
    assertEquals(
        "the-owners-password",
        row("prov-renamed-owner", "prov-s", "db").orElseThrow().password,
        "and the owner's row is exactly as it was");
  }

  @Test
  public void aTransferOntoAKeyTheSuccessorAlreadyHoldsIsRefusedRatherThanGuessedAt() {
    // uq_pd_resource is (application, environment, resource_name), so a successor that already has
    // its own `db` row in that tier has nowhere for the claim to move to. Two rows both claiming to
    // be this application's is not a rename — it is two histories, and which credential is live is
    // not a question this component can answer from the rows alone.
    existingRow("prov-both-old", "prov-t", "db", "qits_prov_both", "the-predecessors-password");
    existingRow("prov-both-new", "prov-t", "db", "qits_prov_own", "its-own-password");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    "prov-both-new", "prov-t", "prov-both-old", one("db", "qits_prov_both")));

    // Since qits-2175 the successor's OWN claim of `db` is what is compared first, and it names
    // another database — so this is refused as an unmoved claim before the transfer is even tried.
    // Either way: refused, nothing provisioned, nothing transferred.
    assertTrue(refused.getMessage().contains("qits_prov_own"), refused.getMessage());
    assertTrue(refused.getMessage().contains("database move"), refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "the seam was never even called");
    assertEquals(
        "prov-both-old",
        QuarkusTransaction.requiringNew()
            .call(() -> resources.listByDatabase("qits_prov_both"))
            .get(0)
            .applicationName,
        "and the transfer was rolled back with the refusal");
  }

  @Test
  public void aRenameThatResolvesThePredecessorsResourceElsewhereIsAMoveThatProvisioningRefuses() {
    // The successor declares the predecessor's resource NAME and resolves it to another database.
    // That is a database move (qits-2175), which DatabaseMoves runs before provisioning. Reaching
    // provisioning without it — this test calls ensureAll alone — must not start the successor green
    // on a new, empty database while the data stays behind.
    existingRow("prov-drop-old", "prov-v", "db", "qits_prov_drop_old", "the-predecessors-password");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    "prov-drop-new", "prov-v", "prov-drop-old", one("db", "qits_prov_drop_new")));

    assertEquals(
        "the resource `db` of prov-drop-old is the database `qits_prov_drop_old`, and this"
            + " deployment resolves it to `qits_prov_drop_new` — that is a database move, and it has"
            + " not run, so nothing is provisioned under the new name",
        refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "nothing was provisioned, not even the new one");
    assertTrue(row("prov-drop-new", "prov-v", "db").isEmpty(), "and nothing was written down");
    assertEquals(
        "prov-drop-old",
        QuarkusTransaction.requiringNew()
            .call(() -> resources.listByDatabase("qits_prov_drop_old"))
            .get(0)
            .applicationName,
        "the predecessor's claim is exactly where it was");
  }

  @Test
  public void aRenameThatStopsDeclaringThePredecessorsResourceIsRefusedNamingIt() {
    // Abandonment is by resource NAME now: the predecessor's `db` has no counterpart in a spec that
    // only declares `cache`, so its data has nowhere to go.
    existingRow("prov-name-old", "prov-v2", "db", "qits_prov_name_old", "the-predecessors-password");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    "prov-name-new", "prov-v2", "prov-name-old", one("cache", "qits_prov_name_new")));

    assertEquals(
        "the predecessor `prov-name-old` holds the database `qits_prov_name_old` as its resource"
            + " `db`, which this spec does not declare; a rename would leave that data behind —"
            + " declare `db` in `resources:`",
        refused.getMessage());
    assertEquals(List.of(), provisioner.requests());
  }

  @Test
  public void aPinRemovedIsNeverAnEmptyDatabaseUnderTheNewName() {
    // The brief's reproduction (qits-2175): the claim says qits_platform_prov_x, the spec now
    // resolves qits_prov_x. Provisioning alone used to create an EMPTY qits_prov_x, rewrite the row
    // and leave the data orphaned; it refuses now, and the move that answers it runs first in a
    // deployment.
    existingRow("prov-x", "prov-pin", "db", "qits_platform_prov_x", "the-working-password");

    assertThrows(
        ResourceException.class,
        () -> provisioning.ensureAll("prov-x", "prov-pin", one("db", "qits_prov_x")));

    assertEquals(List.of(), provisioner.requests(), "nothing was provisioned");
    PdResource row = row("prov-x", "prov-pin", "db").orElseThrow();
    assertEquals("qits_platform_prov_x", row.databaseName, "and the claim is where it was");
    assertEquals("the-working-password", row.password);
  }

  @Test
  public void recordedBindingsAddressTheServerTheClaimIsOn() {
    existingRow("prov-srv-pub", "prov-srv", "db", "qits_prov_srv_pub", "p1");
    existingRow("prov-srv-priv", "prov-srv", "db", "qits_prov_srv_priv", "p2");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                resources.findOne("prov-srv-priv", "prov-srv", "db").orElseThrow().server = "private");

    assertEquals(
        "jdbc:postgresql://prov-srv-qits-oci-postgresql:5432/qits_prov_srv_pub",
        provisioning.recordedBindings("prov-srv-pub", "prov-srv").get(0).value("URL"));
    assertEquals(
        "jdbc:postgresql://prov-srv-qits-oci-postgresql-private:5432/qits_prov_srv_priv",
        provisioning.recordedBindings("prov-srv-priv", "prov-srv").get(0).value("URL"));
  }

  @Test
  public void aNewClaimOfAPublicOrBothApplicationIsOnThePublicServer() {
    provisioning.ensureAll(
        "prov-both", "prov-srv2", null, DeploymentDriver.Exposure.BOTH, one("db", "qits_prov_both2"), null);

    assertEquals("public", row("prov-both", "prov-srv2", "db").orElseThrow().server);
    assertEquals("prov-srv2-qits-oci-postgresql", provisioner.requests().get(0).host());
  }

  @Test
  public void aRenameThatDeclaresNoResourcesAtAllIsRefusedToo() {
    existingRow("prov-none-old", "prov-w", "db", "qits_prov_none_old", "the-predecessors-password");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("prov-none-new", "prov-w", "prov-none-old", List.of()));

    assertTrue(refused.getMessage().contains("qits_prov_none_old"), refused.getMessage());
    assertEquals(List.of(), provisioner.requests());
  }

  @Test
  public void aPredecessorsDatabaseInAnotherTierDoesNotRefuseThisOne() {
    // The check is about the tier being deployed into: a claim elsewhere is not data this
    // deployment would start without.
    existingRow("prov-tier-old", "prov-x-other", "db", "qits_prov_tier_old", "elsewhere");

    provisioning.ensureAll("prov-tier-new", "prov-x", "prov-tier-old", one("db", "qits_prov_tier_new"));

    assertEquals(1, provisioner.requests().size());
    assertEquals("qits_prov_tier_new", provisioner.requests().get(0).databaseName());
  }

  @Test
  public void withNoPredecessorDeclaredAnotherApplicationsDatabaseIsNoConcernOfTheRename() {
    // renamed_from absent: the predecessor-database check never runs, and an application holding
    // some other database in the tier changes nothing about this deployment.
    existingRow("prov-bystander", "prov-y", "db", "qits_prov_bystander", "theirs");

    provisioning.ensureAll("prov-plain", "prov-y", null, one("db", "qits_prov_plain"));

    assertEquals(1, provisioner.requests().size());
    assertTrue(row("prov-plain", "prov-y", "db").isPresent());
  }

  @Test
  public void declaringYourOwnNameAsThePredecessorChangesNothing() {
    // Inert rather than refused here: the parser catches it where it can see both keys, and the
    // transfer arm is only ever reached for a claim belonging to a DIFFERENT application.
    existingRow("prov-selfnamed", "prov-u", "db", "qits_prov_self", "its-own-password");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll(
            "prov-selfnamed", "prov-u", "prov-selfnamed", one("db", "qits_prov_self"));

    assertEquals("its-own-password", bindings.get(0).value("PASSWORD"));
    assertEquals(
        "its-own-password", row("prov-selfnamed", "prov-u", "db").orElseThrow().password);
  }

  @Test
  public void aPlatformPlaneResourceIsKeyedByTheTierItIsDeployedInto() {
    // A platform service is deployed INTO the designated environment since V8, so it arrives here
    // with that tier's name like every other application — and this method used to be the one that
    // reached for the designation on its behalf, off a null.
    //
    // BOTH HALVES MATTER AND ONLY ONE OF THEM IS VISIBLE. The address is the same string it always
    // was, because the null arm resolved to exactly this tier's postgres. What changed is the
    // registry KEY: the row is written under the tier's name, which is what the next deployment
    // will look it up by. Written under null while the deployment reads the tier, the lookup would
    // miss on every deploy, the provisioner would take its reconcile arm, and a working password
    // would be rotated under a running container's open pools.
    QuarkusTransaction.requiringNew()
        .run(() -> environments.create("prov-plane-tier", "qits-net", true));

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-plane", "prov-plane-tier", one("db", "qits_prov_plane"));

    assertEquals(
        "jdbc:postgresql://prov-plane-tier-qits-oci-postgresql:5432/qits_prov_plane",
        bindings.get(0).value("URL"));
    assertTrue(
        row("prov-plane", "prov-plane-tier", "db").isPresent(),
        "the row is keyed by the tier, which is the key the next deployment looks it up by");
    assertTrue(
        row("prov-plane", null, "db").isEmpty(),
        "and nothing is left under the key the plane used to write");
  }

  @Test
  public void aDeploymentWithNoTierAtAllIsRefusedRatherThanGuessedAt() {
    // The guard that survived the branch above. Both register arms come from entryTiers(), so a
    // queued deployment always carries a tier; a call that does not is a mid-bootstrap install with
    // no environment designated, and there is no postgres to name.
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("prov-tierless", null, one("db", "qits_prov_tierless")));
    assertTrue(refused.getMessage().contains("platform environment"), refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "the seam was never even called");
  }

  @Test
  public void anApplicationThatDeclaresNothingNeverTouchesTheSeam() {
    assertEquals(List.of(), provisioning.ensureAll("prov-none", "prov-f", List.of()));
    assertEquals(List.of(), provisioning.ensureAll("prov-none", "prov-f", null));
    assertEquals(List.of(), provisioner.requests());
  }

  @Test
  public void theDefaultDatabaseIsTheApplicationNameWithoutTheQitsPrefix() {
    assertEquals(
        List.of(new ResourceProvisioning.Resolved("db", "qits_artifacts")),
        ResourceProvisioning.resolve("qits-artifacts", List.of(new ResourceSpec("db", null))));
    // Dashes become underscores, because a dash is not a postgres identifier character here.
    assertEquals(
        "qits_platform_deployments", ResourceProvisioning.conventionDatabase("qits-platform-deployments"));
    // A name without the prefix keeps the whole of itself.
    assertEquals("qits_gateway", ResourceProvisioning.conventionDatabase("gateway"));
    // An explicit database wins over the convention, which is how a repository names a database it
    // shares nothing with — qits-artifacts asking for qits_artifacts rather than the default.
    assertEquals(
        List.of(new ResourceProvisioning.Resolved("db", "qits_chosen")),
        ResourceProvisioning.resolve(
            "qits-something", List.of(new ResourceSpec("db", "qits_chosen"))));
  }

  @Test
  public void twoResourcesWhoseDefaultsCollideAreRefusedAfterResolution() {
    // The duplicate the parser could not see: two entries with no database named both resolve to
    // the convention, which is one store for two of a repository's own resources.
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                ResourceProvisioning.resolve(
                    "qits-twice",
                    List.of(new ResourceSpec("primary", null), new ResourceSpec("replica", null))));
    assertTrue(refused.getMessage().contains("qits_twice"), refused.getMessage());
    assertTrue(refused.getMessage().contains("name one of them explicitly"), refused.getMessage());
  }
}
