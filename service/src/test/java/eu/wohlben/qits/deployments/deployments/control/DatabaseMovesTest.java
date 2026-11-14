package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdDatabaseMoveRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When a deployment moves a database, and what a move does (qits-2175) — {@link
 * DatabaseMoves#beforeProvisioning} driven directly, over {@link FakeDatabaseContainers} and {@link
 * FakeDeploymentDriver}, the {@code ResourceProvisioningTest} shape.
 *
 * <p>Every test uses a tier and an application of its own: the suite shares one database, and the
 * claims are keyed by (application, tier, resource).
 */
@QuarkusTest
class DatabaseMovesTest {

  private static final String PASSWORD = "the-claims-own-password";
  private static final String PUBLIC_IMAGE = "registry.example/qits/qits-oci-postgresql:2026.1.1";
  private static final String PRIVATE_IMAGE = "registry.example/qits/qits-oci-postgresql:2026.1.2";

  @Inject DatabaseMoves moves;
  @Inject ResourceProvisioning provisioning;
  @Inject FakeDatabaseContainers containers;
  @Inject FakeDeploymentDriver driver;
  @Inject FakeResourceProvisioner provisioner;
  @Inject PdResourceRepository resources;
  @Inject PdDatabaseMoveRepository moveRows;

  @BeforeEach
  void reset() {
    containers.reset();
    driver.reset();
    provisioner.reset();
  }

  /** Both servers running in {@code env}, so a container can be given their image. */
  private void serversRunningIn(String env) {
    driver.scriptRunningImage(env + "-qits-oci-postgresql", PUBLIC_IMAGE);
    driver.scriptRunningImage(env + "-qits-oci-postgresql-private", PRIVATE_IMAGE);
  }

  private void claim(String application, String env, String name, String database, String server) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdResource row = new PdResource();
              row.id = UUID.randomUUID().toString();
              row.applicationName = application;
              row.environmentName = env;
              row.resourceName = name;
              row.resourceType = "postgresql";
              row.databaseName = database;
              row.roleName = database;
              row.password = PASSWORD;
              row.server = server;
              row.createdAt = Instant.now();
              resources.persist(row);
            });
  }

  private PdResource claimOf(String application, String env, String name) {
    return QuarkusTransaction.requiringNew()
        .call(() -> resources.findOne(application, env, name).orElseThrow());
  }

  private List<PdDatabaseMove> movesOf(String application) {
    return QuarkusTransaction.requiringNew().call(() -> moveRows.listByApplication(application, 50));
  }

  private static List<ResourceProvisioning.Resolved> one(String name, String database) {
    return List.of(new ResourceProvisioning.Resolved(name, database));
  }

  @Test
  void aPinRemovedMovesTheDataRatherThanProvisioningAnEmptyDatabase() {
    // The reproduction the brief opens with: the claim says qits_platform_mvpin, the spec now
    // resolves qits_mvpin. Before qits-2175 provisioning answered with an EMPTY qits_mvpin.
    serversRunningIn("mv-a");
    claim("qits-mvpin", "mv-a", "db", "qits_platform_mvpin", "public");
    driver.scriptDeclaredReplicas("mv-a-qits-mvpin", 1);

    moves.beforeProvisioning(
        "qits-mvpin", "mv-a", null, DeploymentDriver.Exposure.PUBLIC, one("db", "qits_mvpin"));

    // The service was stopped, the source closed, copied and verified, the claim switched.
    assertTrue(driver.calls().contains("scale:mv-a-qits-mvpin=0"), driver.calls().toString());
    assertEquals(
        List.of("check", "size", "space", "admin", "extract", "ingest", "admin"),
        containers.shots().stream().map(DatabaseContainers.OneShot::purpose).toList());
    PdResource claim = claimOf("qits-mvpin", "mv-a", "db");
    assertEquals("qits_mvpin", claim.databaseName);
    assertEquals("qits_mvpin", claim.roleName, "the role follows the database");
    assertEquals("public", claim.server);
    assertEquals(PASSWORD, claim.password, "the credential does not change");

    PdDatabaseMove move = movesOf("qits-mvpin").get(0);
    assertEquals(PdDatabaseMoveState.MOVED, move.state);
    assertEquals(PdDatabaseMove.Origin.DEPLOYMENT, move.origin);
    assertEquals("qits_platform_mvpin", move.sourceDatabase);
    assertEquals("qits_mvpin", move.targetDatabase);
    assertTrue(move.keptDatabase.startsWith("qits_platform_mvpin_moved_"), move.keptDatabase);
    assertEquals("mv-a-qits-mvpin", move.serviceName);
    assertEquals(1, move.serviceReplicas);
    assertNotNull(move.sourceCounts);
    assertEquals(move.sourceCounts, move.targetCounts);
    assertNotNull(move.dropAfter);
    assertTrue(
        move.dropAfter.isAfter(Instant.now().plus(6, ChronoUnit.DAYS)),
        "the shipped retention is seven days: " + move.dropAfter);
    // The dump travelled in a volume of the move's own, which is gone again.
    assertEquals(List.of("qits-dbmove-" + move.id), containers.createdVolumes());
    assertTrue(containers.removedVolumes().contains("qits-dbmove-" + move.id));
    // The ingest is given the claim's password as a secret, never in its script.
    DatabaseContainers.OneShot ingest = containers.shots("ingest").get(0);
    assertEquals(PASSWORD, ingest.secrets().get(DatabaseScripts.ROLE_PASSWORD));
    assertFalse(ingest.script().contains(PASSWORD));
    assertTrue(ingest.script().contains("CREATE DATABASE qits_mvpin OWNER qits_mvpin"));
    assertTrue(ingest.script().contains("--no-owner"), ingest.script());

    // ...and provisioning then finds the claim where it now is, with the password it had.
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("qits-mvpin", "mv-a", one("db", "qits_mvpin"));
    assertEquals(PASSWORD, provisioner.requests().get(0).storedPassword());
    assertEquals(
        "jdbc:postgresql://mv-a-qits-oci-postgresql:5432/qits_mvpin", bindings.get(0).value("URL"));
  }

  @Test
  void anExposureThatBecamePrivateMovesTheDatabaseOntoThePrivateServer() {
    serversRunningIn("mv-b");
    claim("qits-mvpriv", "mv-b", "db", "qits_mvpriv", "public");

    moves.beforeProvisioning(
        "qits-mvpriv", "mv-b", null, DeploymentDriver.Exposure.PRIVATE, one("db", "qits_mvpriv"));

    PdResource claim = claimOf("qits-mvpriv", "mv-b", "db");
    assertEquals("private", claim.server);
    assertEquals("qits_mvpriv", claim.databaseName);
    // Each container joins exactly the network of the server it talks to — and only that one.
    DatabaseContainers.OneShot extract = containers.shots("extract").get(0);
    DatabaseContainers.OneShot ingest = containers.shots("ingest").get(0);
    assertEquals("qits-net", extract.network());
    assertEquals("mv-b-qits-oci-postgresql", extract.environment().get("PGHOST"));
    assertEquals("qits-private", ingest.network());
    assertEquals("mv-b-qits-oci-postgresql-private", ingest.environment().get("PGHOST"));
    assertEquals(extract.volume(), ingest.volume(), "they share the dump through one volume");
    assertEquals(PUBLIC_IMAGE, ingest.image(), "one image for both: the source server's");
    assertEquals("not-a-real-credential", ingest.secrets().get("PGPASSWORD"));
    // Nothing ran as the service: nothing was scaled when nothing was running.
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")));

    // The binding the next container gets is the private host's.
    List<DeploymentDriver.ResourceBinding> recorded =
        provisioning.recordedBindings("qits-mvpriv", "mv-b");
    assertEquals(
        "jdbc:postgresql://mv-b-qits-oci-postgresql-private:5432/qits_mvpriv",
        recorded.get(0).value("URL"));
  }

  @Test
  void aMoveOntoAServerThatIsNotDeployedIsDeferredAndTheDeploymentUsesTheClaimAsItIs() {
    // The rollout gap: this deployer is live before qits-oci-postgresql-private is. Only the public
    // server runs in mv-o.
    driver.scriptRunningImage("mv-o-qits-oci-postgresql", PUBLIC_IMAGE);
    claim("qits-mvdefer", "mv-o", "db", "qits_platform_mvdefer", "public");
    driver.scriptDeclaredReplicas("mv-o-qits-mvdefer", 1);

    List<ResourceProvisioning.Resolved> effective =
        moves.beforeProvisioning(
            "qits-mvdefer", "mv-o", null, DeploymentDriver.Exposure.PRIVATE, one("db", "qits_mvdefer"));

    assertEquals(List.of(), containers.shots(), "nothing was asked of any postgres");
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")));
    assertEquals(List.of(), movesOf("qits-mvdefer"));
    assertEquals("qits_platform_mvdefer", effective.get(0).database(), "the claim, as it is");
    PdResource claim = claimOf("qits-mvdefer", "mv-o", "db");
    assertEquals("public", claim.server);
    assertEquals("qits_platform_mvdefer", claim.databaseName);

    // ...and provisioning deploys against it exactly as before the move existed.
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("qits-mvdefer", "mv-o", null, DeploymentDriver.Exposure.PRIVATE, effective, null);
    assertEquals(PASSWORD, provisioner.requests().get(0).storedPassword());
    assertEquals(
        "jdbc:postgresql://mv-o-qits-oci-postgresql:5432/qits_platform_mvdefer",
        bindings.get(0).value("URL"));

    // The next deployment after the server exists moves it.
    driver.scriptRunningImage("mv-o-qits-oci-postgresql-private", PRIVATE_IMAGE);
    moves.beforeProvisioning(
        "qits-mvdefer", "mv-o", null, DeploymentDriver.Exposure.PRIVATE, one("db", "qits_mvdefer"));
    PdResource moved = claimOf("qits-mvdefer", "mv-o", "db");
    assertEquals("private", moved.server);
    assertEquals("qits_mvdefer", moved.databaseName);
  }

  @Test
  void aNewClaimOfAPrivateApplicationStillFailsWhenThePrivateServerIsNotDeployed() {
    driver.scriptRunningImage("mv-p-qits-oci-postgresql", PUBLIC_IMAGE);

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    "qits-mvnoprivate", "mv-p", null, DeploymentDriver.Exposure.PRIVATE,
                    one("db", "qits_mvnoprivate"), null));

    assertTrue(refused.getMessage().contains("private postgres is not deployed"), refused.getMessage());
  }

  @Test
  void aClaimAlreadyWhereTheSpecSaysMovesNothing() {
    serversRunningIn("mv-c");
    claim("qits-mvsame", "mv-c", "db", "qits_mvsame", "public");

    moves.beforeProvisioning(
        "qits-mvsame", "mv-c", null, DeploymentDriver.Exposure.PUBLIC, one("db", "qits_mvsame"));

    assertEquals(List.of(), containers.shots());
    assertEquals(List.of(), driver.calls());
    assertEquals(List.of(), movesOf("qits-mvsame"));
  }

  @Test
  void aResourceWithNoClaimIsProvisioningsAndNotAMove() {
    moves.beforeProvisioning(
        "qits-mvnew", "mv-d", null, DeploymentDriver.Exposure.PRIVATE, one("db", "qits_mvnew"));

    assertEquals(List.of(), containers.shots());
    assertEquals(List.of(), movesOf("qits-mvnew"));
  }

  @Test
  void theDeployersOwnDatabaseIsRefusedBeforeAnythingStops() {
    serversRunningIn("mv-e");
    claim("qits-deployments", "mv-e", "db", "qits_deployments", "public");
    driver.scriptDeclaredReplicas("mv-e-qits-deployments", 1);

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-deployments",
                    "mv-e",
                    null,
                    DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_deployments_moved_away")));

    assertTrue(refused.getMessage().contains("own database"), refused.getMessage());
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")));
    assertEquals(List.of(), containers.shots());
    assertEquals("qits_deployments", claimOf("qits-deployments", "mv-e", "db").databaseName);
    assertTrue(
        movesOf("qits-deployments").stream()
            .anyMatch(row -> row.state == PdDatabaseMoveState.REFUSED && "mv-e".equals(row.environmentName)));
  }

  @Test
  void aTargetThatAlreadyExistsOnTheTargetServerIsRefused() {
    serversRunningIn("mv-f");
    claim("qits-mvexists", "mv-f", "db", "qits_mvexists_old", "public");
    containers.script("check", shot -> new DatabaseContainers.Outcome(0, "qits-exists=t\n", false));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-mvexists",
                    "mv-f",
                    null,
                    DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_mvexists")));

    assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
    assertEquals(List.of("check"), containers.shots().stream().map(DatabaseContainers.OneShot::purpose).toList());
    assertEquals("qits_mvexists_old", claimOf("qits-mvexists", "mv-f", "db").databaseName);
  }

  @Test
  void aCountMismatchAbortsRestoresTheSourceAndScalesTheServiceBack() {
    serversRunningIn("mv-g");
    claim("qits-mvbad", "mv-g", "db", "qits_mvbad_old", "public");
    driver.scriptDeclaredReplicas("mv-g-qits-mvbad", 1);
    containers.script(
        "ingest",
        shot -> FakeDatabaseContainers.printing("public.items=2\npublic.flyway_schema_history=2"));

    ResourceException aborted =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-mvbad",
                    "mv-g",
                    null,
                    DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_mvbad")));

    assertTrue(aborted.getMessage().contains("public.items"), aborted.getMessage());
    assertTrue(aborted.getMessage().contains("3 rows on the source and 2"), aborted.getMessage());
    // The claim never moved...
    PdResource claim = claimOf("qits-mvbad", "mv-g", "db");
    assertEquals("qits_mvbad_old", claim.databaseName);
    assertEquals("public", claim.server);
    // ...the target was dropped and the source re-opened, after the extract and ingest...
    List<String> admin =
        containers.shots("admin").stream().map(DatabaseContainers.OneShot::script).toList();
    assertTrue(
        admin.stream().anyMatch(s -> s.contains("DROP DATABASE IF EXISTS qits_mvbad WITH (FORCE)")),
        admin.toString());
    assertTrue(
        admin.stream().anyMatch(s -> s.contains("GRANT CONNECT ON DATABASE qits_mvbad_old TO qits_mvbad_old")),
        admin.toString());
    assertTrue(admin.stream().noneMatch(s -> s.contains("RENAME TO")), "nothing was renamed");
    // ...and the service came back up on the untouched source.
    List<String> calls = driver.calls();
    assertTrue(calls.contains("scale:mv-g-qits-mvbad=0"), calls.toString());
    assertTrue(calls.contains("scale:mv-g-qits-mvbad=1"), calls.toString());
    assertTrue(calls.indexOf("scale:mv-g-qits-mvbad=0") < calls.indexOf("scale:mv-g-qits-mvbad=1"));
    PdDatabaseMove move = movesOf("qits-mvbad").get(0);
    assertEquals(PdDatabaseMoveState.ABORTED, move.state);
    assertTrue(move.detail.contains("public.items"), move.detail);
    assertNotNull(move.targetCounts, "the disagreeing manifest is kept for the reader");
  }

  @Test
  void aFailedIngestAbortsTooAndNoPasswordReachesTheRow() {
    serversRunningIn("mv-h");
    claim("qits-mvfail", "mv-h", "db", "qits_mvfail_old", "public");
    containers.script(
        "ingest",
        shot ->
            new DatabaseContainers.Outcome(
                3,
                DatabaseContainers.redact(
                    "ERROR: role creation refused near '" + PASSWORD + "'", shot.secrets()),
                false));

    ResourceException aborted =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-mvfail",
                    "mv-h",
                    null,
                    DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_mvfail")));

    assertTrue(aborted.getMessage().contains("exit 3"), aborted.getMessage());
    assertFalse(aborted.getMessage().contains(PASSWORD));
    PdDatabaseMove move = movesOf("qits-mvfail").get(0);
    assertEquals(PdDatabaseMoveState.ABORTED, move.state);
    assertFalse(move.detail.contains(PASSWORD), move.detail);
  }

  @Test
  void aRenameWithoutAPinMovesThePredecessorsDatabaseUnderTheSameResourceName() {
    // renamed_from with no claim of its own: the claim is the predecessor's for the same name, and
    // the convention name followed the application — a move, not a refusal and not an empty one.
    serversRunningIn("mv-i");
    claim("qits-mvold", "mv-i", "db", "qits_mvold", "public");
    driver.scriptDeclaredReplicas("mv-i-qits-mvold", 1);

    moves.beforeProvisioning(
        "qits-mvnewname", "mv-i", "qits-mvold", DeploymentDriver.Exposure.PUBLIC, one("db", "qits_mvnewname"));

    assertTrue(driver.calls().contains("scale:mv-i-qits-mvold=0"), "the predecessor is what stops");
    PdResource moved =
        QuarkusTransaction.requiringNew().call(() -> resources.listByDatabase("qits_mvnewname").get(0));
    assertEquals("qits-mvold", moved.applicationName, "the transfer is provisioning's, a step later");

    // ...and provisioning transfers it with its password.
    provisioning.ensureAll("qits-mvnewname", "mv-i", "qits-mvold", one("db", "qits_mvnewname"));
    assertEquals(PASSWORD, provisioner.requests().get(0).storedPassword());
    assertEquals("qits-mvnewname", claimOf("qits-mvnewname", "mv-i", "db").applicationName);
  }

  @Test
  void aRenameThatStopsDeclaringAResourceIsStillRefusedBeforeAnythingStops() {
    serversRunningIn("mv-j");
    claim("qits-mvgone", "mv-j", "cache", "qits_mvgone_cache", "public");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-mvgone2",
                    "mv-j",
                    "qits-mvgone",
                    DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_mvgone2")));

    assertTrue(refused.getMessage().contains("`cache`"), refused.getMessage());
    assertEquals(List.of(), containers.shots());
  }

  @Test
  void aMoveARestartInterruptedBeforeTheSwitchIsAbortedAndTheServiceScaledBack() {
    serversRunningIn("mv-k");
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = "qits-mvcrash";
              row.environmentName = "mv-k";
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = "qits_mvcrash";
              row.targetServer = "private";
              row.targetDatabase = "qits_mvcrash";
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = PdDatabaseMoveState.INGESTING;
              row.serviceName = "mv-k-qits-mvcrash";
              row.serviceReplicas = 1;
              row.createdAt = Instant.now();
              moveRows.persist(row);
            });

    moves.settleInterrupted(true);

    PdDatabaseMove settled = QuarkusTransaction.requiringNew().call(() -> moveRows.findById(id));
    assertEquals(PdDatabaseMoveState.ABORTED, settled.state);
    assertTrue(settled.detail.contains("restarted"), settled.detail);
    assertTrue(driver.calls().contains("scale:mv-k-qits-mvcrash=1"), driver.calls().toString());
    DatabaseContainers.OneShot drop = containers.shots("admin").get(0);
    assertEquals("qits-private", drop.network(), "the target is dropped on its own server");
    assertTrue(drop.script().contains("DROP DATABASE IF EXISTS qits_mvcrash"), drop.script());
  }

  @Test
  void theSweepDropsAKeptCopyOnceItsRetentionIsOver() {
    serversRunningIn("mv-l");
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = "qits-mvkept";
              row.environmentName = "mv-l";
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = "qits_mvkept_old";
              row.targetServer = "public";
              row.targetDatabase = "qits_mvkept";
              row.keptDatabase = "qits_mvkept_old_moved_202601010000";
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = PdDatabaseMoveState.MOVED;
              row.createdAt = Instant.now().minus(10, ChronoUnit.DAYS);
              row.finishedAt = row.createdAt;
              row.dropAfter = Instant.now().minus(3, ChronoUnit.DAYS);
              moveRows.persist(row);
            });

    moves.sweepExpired();

    PdDatabaseMove swept = QuarkusTransaction.requiringNew().call(() -> moveRows.findById(id));
    assertNotNull(swept.droppedAt);
    String drop = containers.shots("admin").get(0).script();
    assertTrue(drop.contains("DROP DATABASE IF EXISTS qits_mvkept_old_moved_202601010000"), drop);
    // The name changed and no claim on that server uses the old role, so it goes with the copy.
    assertTrue(drop.contains("DROP ROLE IF EXISTS qits_mvkept_old"), drop);

    containers.reset();
    moves.sweepExpired();
    assertEquals(List.of(), containers.shots(), "a dropped copy is not dropped twice");
  }

  @Test
  void aPrivateClaimIsProvisionedOnThePrivateServerByAContainerOnItsNetworkAlone() {
    serversRunningIn("mv-m");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll(
            "qits-mvfresh", "mv-m", null, DeploymentDriver.Exposure.PRIVATE, one("db", "qits_mvfresh"), null);

    assertEquals(List.of(), provisioner.requests(), "the public server's JDBC seam was not asked");
    DatabaseContainers.OneShot provision = containers.shots("provision").get(0);
    assertEquals("qits-private", provision.network());
    assertEquals(PRIVATE_IMAGE, provision.image());
    assertEquals("mv-m-qits-oci-postgresql-private", provision.environment().get("PGHOST"));
    String fresh = provision.secrets().get(DatabaseScripts.ROLE_PASSWORD);
    assertNotNull(fresh);
    assertEquals("not-a-real-credential", provision.secrets().get("PGPASSWORD"));
    assertFalse(provision.script().contains(fresh), "no password in the script");
    assertFalse(provision.script().contains("not-a-real-credential"));
    assertEquals("private", claimOf("qits-mvfresh", "mv-m", "db").server);
    assertEquals(fresh, bindings.get(0).value("PASSWORD"));
    assertEquals(
        "jdbc:postgresql://mv-m-qits-oci-postgresql-private:5432/qits_mvfresh",
        bindings.get(0).value("URL"));
  }

  @Test
  void anOperatorsQueuedMoveARestartLostIsRefusedAtBootButNotOnATick() {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = "qits-mvqueued";
              row.environmentName = "mv-n";
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = "qits_mvqueued";
              row.targetServer = "private";
              row.targetDatabase = "qits_mvqueued";
              row.origin = PdDatabaseMove.Origin.OPERATOR;
              row.state = PdDatabaseMoveState.QUEUED;
              row.createdAt = Instant.now();
              moveRows.persist(row);
            });

    moves.settleInterrupted(false);
    assertEquals(
        PdDatabaseMoveState.QUEUED,
        QuarkusTransaction.requiringNew().call(() -> moveRows.findById(id)).state,
        "on a tick it is waiting in the queue behind the pass");

    moves.settleInterrupted(true);
    PdDatabaseMove settled = QuarkusTransaction.requiringNew().call(() -> moveRows.findById(id));
    assertEquals(PdDatabaseMoveState.REFUSED, settled.state);
    assertNull(settled.serviceName);
  }

  // --- the space check (qits-2243) -------------------------------------------------------------

  private static final long GIB = 1L << 30;

  @Test
  void theIncidentsMoveIsRefusedBeforeItsServiceStops() {
    // 2026-11-14: public/qits_platform_mirror -> public/qits_mirror, 19 GB on a filesystem with 42
    // GB free. The dump and the restored copy shared that filesystem and filled it.
    serversRunningIn("sp-a");
    claim("qits-spmirror", "sp-a", "db", "qits_platform_spmirror", "public");
    driver.scriptDeclaredReplicas("sp-a-qits-spmirror", 1);
    containers.script("size", shot -> FakeDatabaseContainers.sized(19 * GIB));
    containers.script("space", shot -> FakeDatabaseContainers.oneFilesystem(42 * GIB));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-spmirror", "sp-a", null, DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_spmirror")));

    assertEquals(
        "refusing to move public/qits_platform_spmirror to public/qits_spmirror: it is 19.0 GiB, and"
            + " the target's filesystem has 42.0 GiB free where the dump and the copy need 46.8 GiB"
            + " (qits.deployments.database-move.space-factor 1.1, space-reserve 5 GiB); free space"
            + " first",
        refused.getMessage());
    // Nothing stopped, nothing was copied, the claim is where it was.
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")), driver.calls().toString());
    assertEquals(
        List.of("check", "size", "space"),
        containers.shots().stream().map(DatabaseContainers.OneShot::purpose).toList());
    assertEquals("qits_platform_spmirror", claimOf("qits-spmirror", "sp-a", "db").databaseName);
    // The size is asked of the source, the free space of the target with the volume mounted...
    DatabaseContainers.OneShot size = containers.shots("size").get(0);
    assertTrue(size.script().contains("pg_database_size('qits_platform_spmirror')"), size.script());
    DatabaseContainers.OneShot space = containers.shots("space").get(0);
    assertTrue(space.script().contains("COPY qits_df (line) FROM PROGRAM :'qits_df_cmd'"), space.script());
    assertTrue(space.script().contains("df -Pk " + DatabaseContainers.MOUNT), space.script());
    // ...and the volume measured is the move's, removed again on the refusal.
    PdDatabaseMove row = movesOf("qits-spmirror").get(0);
    assertEquals(PdDatabaseMoveState.REFUSED, row.state);
    assertEquals(refused.getMessage(), row.detail);
    assertEquals("qits-dbmove-" + row.id, space.volume());
    assertEquals(List.of("qits-dbmove-" + row.id), containers.createdVolumes());
    assertEquals(List.of("qits-dbmove-" + row.id), containers.removedVolumes());
    assertEquals(19 * GIB, row.sourceBytes);
    assertEquals(42 * GIB, row.targetFreeBytes);
    assertEquals(42 * GIB, row.volumeFreeBytes);
  }

  @Test
  void theSameMoveWithRoomToSpareProceedsAndRecordsWhatItMeasured() {
    serversRunningIn("sp-b");
    claim("qits-spfits", "sp-b", "db", "qits_platform_spfits", "public");
    driver.scriptDeclaredReplicas("sp-b-qits-spfits", 1);
    containers.script("size", shot -> FakeDatabaseContainers.sized(19 * GIB));
    containers.script("space", shot -> FakeDatabaseContainers.oneFilesystem(47 * GIB));

    moves.beforeProvisioning(
        "qits-spfits", "sp-b", null, DeploymentDriver.Exposure.PUBLIC, one("db", "qits_spfits"));

    PdDatabaseMove row = movesOf("qits-spfits").get(0);
    assertEquals(PdDatabaseMoveState.MOVED, row.state);
    assertEquals(19 * GIB, row.sourceBytes);
    assertEquals(47 * GIB, row.targetFreeBytes);
    assertEquals(47 * GIB, row.volumeFreeBytes);
    assertEquals(List.of("qits-dbmove-" + row.id), containers.createdVolumes(), "created once");
    assertEquals("qits_spfits", claimOf("qits-spfits", "sp-b", "db").databaseName);
  }

  @Test
  void separateFilesystemsAreCheckedEachForItsOwnCopy() {
    // The dump volume is on the node's disk, the private server's data directory on another: each
    // needs one copy and the reserve. 10 GiB → 16 GiB each. The volume's 20 GiB fits, the data's 15
    // does not.
    serversRunningIn("sp-c");
    claim("qits-spsplit", "sp-c", "db", "qits_spsplit", "public");
    containers.script("size", shot -> FakeDatabaseContainers.sized(10 * GIB));
    containers.script(
        "space",
        shot ->
            FakeDatabaseContainers.space(
                "/dev/vdb", 100 * GIB, 15 * GIB, "/dev/sda1", 150 * GIB, 20 * GIB));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-spsplit", "sp-c", null, DeploymentDriver.Exposure.PRIVATE,
                    one("db", "qits_spsplit")));

    assertTrue(
        refused.getMessage().contains(
            "the target's data directory filesystem has 15.0 GiB free where the copy needs 16.0 GiB"),
        refused.getMessage());
    assertEquals("public", claimOf("qits-spsplit", "sp-c", "db").server);

    // With room on both, the same move proceeds.
    containers.reset();
    containers.script("size", shot -> FakeDatabaseContainers.sized(10 * GIB));
    containers.script(
        "space",
        shot ->
            FakeDatabaseContainers.space(
                "/dev/vdb", 100 * GIB, 17 * GIB, "/dev/sda1", 150 * GIB, 17 * GIB));
    moves.beforeProvisioning(
        "qits-spsplit", "sp-c", null, DeploymentDriver.Exposure.PRIVATE, one("db", "qits_spsplit"));
    assertEquals("private", claimOf("qits-spsplit", "sp-c", "db").server);
  }

  @Test
  void aBatchsMovesAreSummedOnTheFilesystemTheyShare() {
    // Two databases of 10 GiB, one server, 40 GiB free: either alone needs 27 GiB and fits; both
    // need 49 GiB and do not.
    serversRunningIn("sp-d");
    claim("qits-sptwo", "sp-d", "db", "qits_platform_sptwo", "public");
    claim("qits-sptwo", "sp-d", "cache", "qits_platform_sptwo_cache", "public");
    driver.scriptDeclaredReplicas("sp-d-qits-sptwo", 1);
    containers.script("size", shot -> FakeDatabaseContainers.sized(10 * GIB));
    containers.script("space", shot -> FakeDatabaseContainers.oneFilesystem(40 * GIB));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-sptwo",
                    "sp-d",
                    null,
                    DeploymentDriver.Exposure.PUBLIC,
                    List.of(
                        new ResourceProvisioning.Resolved("db", "qits_sptwo"),
                        new ResourceProvisioning.Resolved("cache", "qits_sptwo_cache"))));

    assertTrue(
        refused.getMessage().contains("this batch's 2 dumps and 2 copies (2 moves) need 49.0 GiB"),
        refused.getMessage());
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")));
    List<PdDatabaseMove> rows = movesOf("qits-sptwo");
    assertEquals(2, rows.size());
    assertTrue(rows.stream().allMatch(row -> row.state == PdDatabaseMoveState.REFUSED), rows.toString());
    assertEquals(1, rows.stream().map(row -> row.batchId).distinct().count(), "one batch");
    assertEquals(2, containers.removedVolumes().size(), containers.removedVolumes().toString());
  }

  @Test
  void aMeasureThatFailsRefusesTheMove() {
    serversRunningIn("sp-e");
    claim("qits-spblind", "sp-e", "db", "qits_platform_spblind", "public");
    driver.scriptDeclaredReplicas("sp-e-qits-spblind", 1);
    containers.script("size", shot -> new DatabaseContainers.Outcome(2, "psql: connection refused", false));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-spblind", "sp-e", null, DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_spblind")));
    assertTrue(
        refused.getMessage().startsWith(
            "could not measure the size of public/qits_platform_spblind, so nothing was moved"),
        refused.getMessage());
    assertTrue(containers.shots("space").isEmpty());

    // A free-space reading nobody can parse refuses the same way.
    containers.reset();
    containers.script("space", shot -> new DatabaseContainers.Outcome(0, "qits-data-df=garbage\n", false));
    ResourceException unread =
        assertThrows(
            ResourceException.class,
            () ->
                moves.beforeProvisioning(
                    "qits-spblind", "sp-e", null, DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_spblind")));
    assertTrue(
        unread.getMessage().contains("could not measure the free space of the public postgres' data"
            + " directory and of the volume qits-dbmove-"),
        unread.getMessage());
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")));
    assertTrue(containers.shots("extract").isEmpty());
    assertEquals("qits_platform_spblind", claimOf("qits-spblind", "sp-e", "db").databaseName);
  }

  // --- the abort (qits-2243) -------------------------------------------------------------------

  @Test
  void anAbortWhileTheServiceDrainsEndsTheMoveAbortedAndScalesItBack() throws Exception {
    serversRunningIn("ab-a");
    claim("qits-abdrain", "ab-a", "db", "qits_platform_abdrain", "public");
    driver.scriptDeclaredReplicas("ab-a-qits-abdrain", 1);
    containers.scriptDrain(DatabaseContainers.Drain.RUNNING);

    java.util.concurrent.CompletableFuture<Throwable> worker =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                moves.beforeProvisioning(
                    "qits-abdrain", "ab-a", null, DeploymentDriver.Exposure.PUBLIC,
                    one("db", "qits_abdrain"));
                return null;
              } catch (Throwable thrown) {
                return thrown;
              }
            });
    String id = awaitState("qits-abdrain", PdDatabaseMoveState.STOPPING);

    PdDatabaseMove asked = moves.abort(id, "an-operator");
    assertEquals(PdDatabaseMoveState.STOPPING, asked.state);
    Throwable thrown = worker.get(30, java.util.concurrent.TimeUnit.SECONDS);

    assertTrue(thrown instanceof ResourceException, String.valueOf(thrown));
    assertEquals("aborted by an-operator", thrown.getMessage());
    PdDatabaseMove row = QuarkusTransaction.requiringNew().call(() -> moveRows.findById(id));
    assertEquals(PdDatabaseMoveState.ABORTED, row.state);
    assertEquals("aborted by an-operator", row.detail);
    assertTrue(driver.calls().contains("scale:ab-a-qits-abdrain=1"), driver.calls().toString());
    assertTrue(containers.removedVolumes().contains("qits-dbmove-" + id));
    assertEquals(List.of(id), containers.killed());
    assertTrue(containers.shots("extract").isEmpty(), "nothing was copied");
    assertEquals("qits_platform_abdrain", claimOf("qits-abdrain", "ab-a", "db").databaseName);

    // Finished now: a second abort is a conflict, not a flag nobody reads.
    assertThrows(
        eu.wohlben.qits.deployments.environments.error.ConflictException.class,
        () -> moves.abort(id, "an-operator"));
  }

  private String awaitState(String application, PdDatabaseMoveState state) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      for (PdDatabaseMove row : movesOf(application)) {
        if (row.state == state) {
          return row.id;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError(application + " never reached " + state + ": " + movesOf(application));
  }

  // --- drainWait (qits-2228) -----------------------------------------------------------------

  @Test
  void aLongStopGracePeriodRaisesTheWaitPastTheFixedStopTimeout() {
    // qits-edge-private's own stop_grace_period is 10m; the shipped stop-timeout alone (2m)
    // aborted the move every time while the service was still honouring its own drain.
    assertEquals(
        Duration.ofMinutes(10).plus(Duration.ofSeconds(30)),
        DatabaseMoves.drainWait(Duration.ofMinutes(2), Optional.of(Duration.ofMinutes(10))));
  }

  @Test
  void aShortStopGracePeriodLeavesTheConfiguredStopTimeoutAsTheFloor() {
    assertEquals(
        Duration.ofMinutes(2),
        DatabaseMoves.drainWait(Duration.ofMinutes(2), Optional.of(Duration.ofSeconds(30))));
  }

  @Test
  void anUnreadableGracePeriodIsTreatedAsDockersOwnTenSecondDefault() {
    assertEquals(
        Duration.ofMinutes(2), DatabaseMoves.drainWait(Duration.ofMinutes(2), Optional.empty()));
  }
}
