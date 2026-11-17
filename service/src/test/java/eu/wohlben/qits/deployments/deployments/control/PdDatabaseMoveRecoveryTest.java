package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdDatabaseMoveRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A deployment with a database move ENDS with the service running (qits-2249, the owner's ruling of
 * 2026-11-14) — live on the target, or rolled back onto the source — whatever comes between the switch
 * and the cutover: a deployer restart before the deployment's service name was written, after it but
 * before the apply, in the middle of a rollback; a rename back that fails and works later; a scale
 * back the runtime refuses once.
 *
 * <p>The restart cases seed what a dying deployer leaves — a {@code STARTING} deployment row, the move
 * rows it ran, the claim where the switch put it, the service at zero — and then drive what a boot
 * does: {@link DeployService#sweepInFlight()}, then {@link DatabaseMoves#settleInterrupted}. The
 * retry cases run a real release through the worker and drive the observation tick's settle by hand.
 * Every test has a tier and an application of its own: the suite shares one database.
 */
@QuarkusTest
class PdDatabaseMoveRecoveryTest {

  private static final String V1 = "2026.101.120000";
  private static final String V2 = "2026.102.120000";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeDatabaseContainers containers;
  @Inject FakeIdpClientProvisioner idp;
  @Inject DeployService deployService;
  @Inject DatabaseMoves databaseMoves;
  @Inject PdResourceRepository resources;
  @Inject PdDatabaseMoveRepository moveRows;
  @Inject PdDeploymentRepository deploymentRows;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    containers.reset();
    idp.reset();
  }

  /** A spec declaring exactly {@code resources}. */
  private static DeploymentSpec declaring(
      DeploymentDriver.Exposure exposure, List<ResourceSpec> resources) {
    return new DeploymentSpec(
        false, null, null, null, resources, List.of(), null, null, null, 8080, null, false, null,
        null, null, null, null, null, exposure, null, null);
  }

  private static DeploymentSpec declaring(DeploymentDriver.Exposure exposure) {
    return declaring(exposure, List.of(new ResourceSpec("db", null)));
  }

  private static String createDesignatedEnvironment(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "designated", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void release(String application, String version) throws Exception {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-dbrecover", "repoId", application, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    deployService.awaitIdle();
  }

  private static String image(String application, String version) {
    return "registry.example/qits/" + application + ":" + version;
  }

  /** One application live at {@link #V1} on the public server, its service running one replica. */
  private record Live(
      String environment, String environmentId, String application, String service, String database) {}

  private Live live(String prefix) throws Exception {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = prefix + "-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = prefix + "-app-" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", "registry.example/qits/pg:1");
    release(application, V1);
    String service = environment + "-" + application;
    driver.scriptDeclaredReplicas(service, 1);
    driver.scriptRunningImage(service, image(application, V1));
    return new Live(environment, environmentId, application, service, claim(application, environment).databaseName);
  }

  private PdResource claim(String application, String environment) {
    return QuarkusTransaction.requiringNew()
        .call(() -> resources.findOne(application, environment, "db").orElseThrow());
  }

  private PdDatabaseMove moveRow(String id) {
    return QuarkusTransaction.requiringNew().call(() -> moveRows.findById(id));
  }

  private PdDeployment deployment(String id) {
    return QuarkusTransaction.requiringNew().call(() -> deploymentRows.findById(id));
  }

  private List<PdDeployment> place(Live live) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deploymentRows.listForPlaceNewestFirst(live.application(), live.environmentId()));
  }

  /** The deployment a dying deployer left {@code STARTING} at {@link #V2}; {@code name} null or set. */
  private String startingDeployment(Live live, String containerName) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = live.application();
              row.environmentId = live.environmentId();
              row.version = V2;
              row.status = PdDeploymentStatus.STARTING;
              row.containerName = containerName;
              row.createdAt = Instant.now();
              deploymentRows.persist(row);
            });
    return id;
  }

  /**
   * What the switch left: the claim on the private server, and the move — a copy, its source kept
   * aside — in {@code state} for {@code deploymentId}, with the service it stopped.
   */
  private String switched(Live live, String deploymentId, PdDatabaseMoveState state, String detail) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdResource claim = resources.findOne(live.application(), live.environment(), "db").orElseThrow();
              claim.server = "private";
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.deploymentId = deploymentId;
              row.applicationName = live.application();
              row.environmentName = live.environment();
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = live.database();
              row.targetServer = "private";
              row.targetDatabase = live.database();
              row.keptDatabase = live.database() + "_moved_202611160000";
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = state;
              row.detail = detail;
              row.serviceName = live.service();
              row.serviceReplicas = 1;
              row.createdAt = Instant.now();
              row.startedAt = row.createdAt;
              moveRows.persist(row);
            });
    // The move stopped the service; nothing has started it since.
    driver.scriptDeclaredReplicas(live.service(), 0);
    return id;
  }

  /** The kept source put back under its own name on the public server — the copy's way back. */
  private boolean keptSourceRestored(Live live) {
    return containers.shots("admin").stream()
        .anyMatch(
            shot ->
                shot.script().contains(live.database() + "_moved_202611160000 RENAME TO " + live.database()));
  }

  // --- a restart between the switch and the cutover ---------------------------------------------

  @Test
  void aRestartBeforeTheServiceNameWasWrittenRollsTheMoveBackAndScalesTheServiceUp() throws Exception {
    Live live = live("dbrc-a");
    int applied = driver.applied().size();
    String interrupted = startingDeployment(live, null);
    String move = switched(live, interrupted, PdDatabaseMoveState.SWITCHED, null);

    // The boot: the sweep first, then the settle on the worker.
    deployService.sweepInFlight();
    assertEquals(PdDeploymentStatus.FAILED, deployment(interrupted).status);
    databaseMoves.settleInterrupted(true);
    deployService.awaitIdle();

    PdDatabaseMove settled = moveRow(move);
    assertEquals(PdDatabaseMoveState.ABORTED, settled.state);
    assertTrue(
        settled.detail.startsWith("rolled back after the switch: deployment " + interrupted + " ended FAILED"),
        settled.detail);
    assertNull(settled.dropAfter);
    assertTrue(keptSourceRestored(live), containers.shots().toString());
    PdResource claim = claim(live.application(), live.environment());
    assertEquals("public", claim.server, "the claim is back on its source");
    assertEquals(live.database(), claim.databaseName);
    // Its spec was never touched, so it is scaled back — never redeployed.
    assertTrue(driver.calls().contains("scale:" + live.service() + "=1"), driver.calls().toString());
    assertEquals(applied, driver.applied().size(), "nothing applied: the original spec names the source");
    PdDeployment failed = deployment(interrupted);
    assertEquals(PdDeploymentStatus.FAILED, failed.status);
    assertTrue(failed.detail.startsWith("[rolled back: deployment " + interrupted), failed.detail);
    assertEquals(PdDeploymentStatus.ACTIVE, place(live).get(1).status, "the version that served still does");
  }

  @Test
  void aRestartAfterTheNameButBeforeTheApplyRollsTheMoveBackAndScalesTheServiceUp() throws Exception {
    Live live = live("dbrc-b");
    int applied = driver.applied().size();
    // The name is written, the apply never ran: the service still runs V1's spec, at zero.
    String interrupted = startingDeployment(live, live.service());
    String move = switched(live, interrupted, PdDatabaseMoveState.SWITCHED, null);

    deployService.sweepInFlight();
    // V1 still runs under the name and is OLDER than the row's V2: the predecessor, not a successor
    // (qits-2306), so the row is the interrupted FAILED — rolled back all the same.
    assertEquals(PdDeploymentStatus.FAILED, deployment(interrupted).status);
    assertTrue(deployment(interrupted).interrupted);
    databaseMoves.settleInterrupted(true);
    deployService.awaitIdle();

    assertEquals(PdDatabaseMoveState.ABORTED, moveRow(move).state);
    assertEquals("public", claim(live.application(), live.environment()).server);
    assertTrue(driver.calls().contains("scale:" + live.service() + "=1"), driver.calls().toString());
    assertEquals(applied, driver.applied().size());
    assertTrue(deployment(interrupted).detail.startsWith("[rolled back:"), deployment(interrupted).detail);
  }

  @Test
  void aRestartAfterTheApplyWithTheNewVersionRunningConfirmsTheMove() throws Exception {
    // The other side of the line: the service runs the deployment's own image, so the sweep adopts
    // it ACTIVE — and the moves it switched are MOVED with it, in the same bracket.
    Live live = live("dbrc-c");
    String interrupted = startingDeployment(live, live.service());
    String move = switched(live, interrupted, PdDatabaseMoveState.SWITCHED, null);
    driver.scriptRunningImage(live.service(), image(live.application(), V2));

    deployService.sweepInFlight();

    assertEquals(PdDeploymentStatus.ACTIVE, deployment(interrupted).status);
    PdDatabaseMove confirmed = moveRow(move);
    assertEquals(PdDatabaseMoveState.MOVED, confirmed.state);
    assertTrue(confirmed.dropAfter != null && confirmed.finishedAt != null, "the retention starts now");
    databaseMoves.settleInterrupted(true);
    assertEquals(PdDatabaseMoveState.MOVED, moveRow(move).state);
    assertEquals("private", claim(live.application(), live.environment()).server);
    assertFalse(driver.calls().contains("scale:" + live.service() + "=1"), driver.calls().toString());
  }

  @Test
  void aRestartInTheMiddleOfARollbackResumesItAndStartsTheService() throws Exception {
    Live live = live("dbrc-d");
    // The deployment applied V2 (its spec is on the service), did not go live, and its finish had
    // begun the rollback — the row ROLLING_BACK, the service stopped again — when the deployer died.
    String interrupted = startingDeployment(live, live.service());
    String move =
        switched(
            live,
            interrupted,
            PdDatabaseMoveState.ROLLING_BACK,
            "rolling back after the switch: the new version never got healthy");
    driver.scriptRunningImage(live.service(), image(live.application(), V2));
    int movesBefore = QuarkusTransaction.requiringNew().call(() -> moveRows.listByApplication(live.application(), 50)).size();

    deployService.sweepInFlight();
    // Never adopted on the image it names: the deployment failed, and its rollback is owed.
    PdDeployment swept = deployment(interrupted);
    assertEquals(PdDeploymentStatus.FAILED, swept.status);
    assertFalse(swept.interrupted, "decided, not interrupted: the release is not re-driven");
    assertEquals(PdDeploymentStatus.ACTIVE, place(live).get(1).status, "V1 is not decommissioned");

    databaseMoves.settleInterrupted(true);
    deployService.awaitIdle();

    PdDatabaseMove settled = moveRow(move);
    assertEquals(PdDatabaseMoveState.ABORTED, settled.state);
    assertEquals("rolled back after the switch: the new version never got healthy", settled.detail);
    assertTrue(keptSourceRestored(live), containers.shots().toString());
    assertEquals("public", claim(live.application(), live.environment()).server);
    // V2's spec is on the service, so it is not scaled: V1 is started on the claims as they are.
    List<PdDeployment> rows = place(live);
    assertEquals(PdDeploymentStatus.ACTIVE, rows.get(0).status, rows.get(0).detail);
    assertEquals(V1, rows.get(0).version);
    DeploymentDriver.ServiceSpec started = driver.applied().get(driver.applied().size() - 1);
    assertEquals(
        "jdbc:postgresql://" + live.environment() + "-qits-oci-postgresql:5432/" + live.database(),
        started.resources().get(0).value("URL"));
    assertEquals(
        movesBefore,
        QuarkusTransaction.requiringNew().call(() -> moveRows.listByApplication(live.application(), 50)).size(),
        "the start planned no move of its own");
    assertTrue(deployment(interrupted).detail.startsWith("[rolled back:"), deployment(interrupted).detail);
  }

  // --- a rollback step that fails is retried until the service runs -----------------------------

  @Test
  void anIncompleteRenameBackIsRetriedOnTheNextPassUntilTheServiceRuns() throws Exception {
    // DatabaseMovesTest.aSwitchedRenameThatCannotBeRenamedBackKeepsItsClaimAndHandsNoServiceBack,
    // through a real deployment: V2 pins another name on the same server — a rename in place — and
    // never gets healthy; the rename back fails while the postgres is down.
    Live live = live("dbrc-e");
    String renamed = live.database() + "_v2";
    specs.script(
        live.application(),
        declaring(DeploymentDriver.Exposure.PUBLIC, List.of(new ResourceSpec("db", renamed))));
    driver.scriptConvergence(DeploymentDriver.Convergence.failed("the new version never got healthy"));
    containers.script(
        "rename",
        shot ->
            shot.script().contains("RENAME TO " + live.database() + ";")
                ? new DatabaseContainers.Outcome(1, "FATAL: the server is down", false)
                : new DatabaseContainers.Outcome(0, "", false));

    release(live.application(), V2);

    PdDatabaseMove owed = QuarkusTransaction.requiringNew().call(() -> moveRows.listByApplication(live.application(), 1).get(0));
    assertEquals(PdDatabaseMoveState.ROLLING_BACK, owed.state);
    assertTrue(owed.detail.contains("[the rollback is incomplete:"), owed.detail);
    assertEquals(renamed, claim(live.application(), live.environment()).databaseName, "the claim stays with the data");
    assertFalse(driver.calls().contains("scale:" + live.service() + "=1"), "nothing starts on a missing source");
    PdDeployment failed = place(live).get(0);
    assertEquals(PdDeploymentStatus.FAILED, failed.status);
    assertTrue(failed.detail.contains("INCOMPLETE"), failed.detail);
    // A next release waits for it rather than planning from a claim a pass is about to move.
    release(live.application(), "2026.103.120000");
    PdDeployment refused = place(live).get(0);
    assertEquals(PdDeploymentStatus.FAILED, refused.status);
    assertTrue(refused.detail.contains("is still ROLLING_BACK"), refused.detail);
    assertEquals(PdDatabaseMoveState.ROLLING_BACK, moveRow(owed.id).state);

    // The postgres is back; the service still carries V2's spec. The next observation tick's pass.
    containers.reset();
    driver.scriptConvergence(DeploymentDriver.Convergence.converged(List.of()));
    driver.scriptRunningImage(live.service(), image(live.application(), V2));
    databaseMoves.settleInterrupted(false);
    deployService.awaitIdle();

    PdDatabaseMove settled = moveRow(owed.id);
    assertEquals(PdDatabaseMoveState.ABORTED, settled.state, settled.detail);
    assertTrue(settled.detail.startsWith("rolled back after the switch: "), settled.detail);
    assertTrue(
        containers.shots("rename").get(0).script().contains("ALTER DATABASE " + renamed + " RENAME TO " + live.database() + ";"),
        containers.shots("rename").toString());
    assertEquals(live.database(), claim(live.application(), live.environment()).databaseName);
    PdDeployment running = place(live).get(0);
    assertEquals(PdDeploymentStatus.ACTIVE, running.status, running.detail);
    assertEquals(V1, running.version, "the version that served, started on its source");
    assertTrue(
        driver.applied().get(driver.applied().size() - 1).resources().get(0).value("URL").endsWith("/" + live.database()));
    PdDeployment rolledBack = deployment(failed.id);
    assertTrue(rolledBack.detail.startsWith("[rolled back:"), rolledBack.detail);
  }

  @Test
  void aScaleBackTheRuntimeRefusesIsRetriedOnTheNextPass() throws Exception {
    Live live = live("dbrc-f");
    String renamed = live.database() + "_v2";
    specs.script(
        live.application(),
        declaring(DeploymentDriver.Exposure.PUBLIC, List.of(new ResourceSpec("db", renamed))));
    // Swarm reverts V2 itself, so the service is back on the spec the move scaled to zero — and the
    // runtime refuses the scale back once. The refusal is armed by the forward rename, after the
    // move's own stop.
    driver.scriptConvergence(DeploymentDriver.Convergence.rolledBack("the update was rolled back"));
    containers.script(
        "rename",
        shot -> {
          if (shot.script().contains("RENAME TO " + renamed + ";")) {
            driver.scriptScale(
                new DeploymentDriver.ScaleResult(
                    DeploymentDriver.ScaleOutcome.REFUSED, "the swarm manager is unreachable"));
          }
          return new DatabaseContainers.Outcome(0, "", false);
        });

    release(live.application(), V2);

    PdDatabaseMove owed = QuarkusTransaction.requiringNew().call(() -> moveRows.listByApplication(live.application(), 1).get(0));
    assertEquals(PdDatabaseMoveState.ROLLING_BACK, owed.state);
    assertTrue(owed.detail.contains("[the service start is owed:"), owed.detail);
    assertEquals(live.database(), claim(live.application(), live.environment()).databaseName, "the database is back");
    assertTrue(place(live).get(0).detail.contains("could NOT be scaled back"), place(live).get(0).detail);

    // The runtime answers again; the service runs V1's spec. The next pass scales it — and only that.
    driver.scriptScale(new DeploymentDriver.ScaleResult(DeploymentDriver.ScaleOutcome.SCALED, null));
    driver.scriptRunningImage(live.service(), image(live.application(), V1));
    containers.reset();
    int applied = driver.applied().size();
    databaseMoves.settleInterrupted(false);

    assertEquals(PdDatabaseMoveState.ABORTED, moveRow(owed.id).state);
    assertEquals(1, driver.declaredReplicasOf(live.service()).getAsInt());
    assertTrue(containers.shots("rename").isEmpty(), "the database half is not run twice");
    assertEquals(applied, driver.applied().size(), "nothing redeployed: a scale was all it took");
    assertNotEquals(PdDeploymentStatus.ACTIVE, place(live).get(0).status);
  }
}
