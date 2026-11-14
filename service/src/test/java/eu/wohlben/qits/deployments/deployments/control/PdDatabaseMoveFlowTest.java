package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The database move end to end (qits-2175): a release provisions a claim, the operator's door moves
 * it through a redeploy of the live version, the read surface shows the move, and the next release
 * moves an override back to what the spec says.
 *
 * <p>Package-local, the {@code PdApplicationScaleTest} shape: the door answers 202 and the work runs
 * on the deploy worker, so every assertion waits on {@link DeployService#awaitIdle()}.
 */
@QuarkusTest
class PdDatabaseMoveFlowTest {

  private static final String MOVES = "/deployments/api/database-moves";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeDatabaseContainers containers;
  @Inject DeployService deployService;
  @Inject DatabaseMoves databaseMoves;
  @Inject PdResourceRepository resources;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    containers.reset();
  }

  /** A spec declaring one postgres resource {@code db} on its conventional database. */
  private static DeploymentSpec declaring(DeploymentDriver.Exposure exposure) {
    return new DeploymentSpec(
        false,
        null,
        null,
        null,
        List.of(new ResourceSpec("db", null)),
        List.of(),
        null,
        null,
        null,
        8080,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        exposure,
        null,
        null);
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
        .body(Map.of("runId", "run-dbmove", "repoId", application, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    deployService.awaitIdle();
  }

  private static List<String> statuses(String environmentId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments.status");
  }

  private PdResource claim(String application, String environment) {
    return QuarkusTransaction.requiringNew()
        .call(() -> resources.findOne(application, environment, "db").orElseThrow());
  }

  private static JsonPath move(String id) {
    return given()
        .header("Authorization", SuiteBearers.bearer("dbmove-agent", "qits:agent"))
        .when()
        .get(MOVES + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  @Test
  void anAgentMovesALiveApplicationsDatabaseToThePrivateServerAndTheNextReleaseMovesItBack()
      throws Exception {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-app-" + token;
    String database = "qits_dbmv_app_" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", "registry.example/qits/pg:1");

    release(application, "2026.101.120000");
    assertEquals(List.of("ACTIVE"), statuses(environmentId));
    assertEquals("public", claim(application, environment).server);
    String password = claim(application, environment).password;
    driver.scriptDeclaredReplicas(environment + "-" + application, 1);

    // The agent's door: an override of the spec, which says public.
    JsonPath accepted =
        given()
            .header("Authorization", SuiteBearers.bearer("dbmove-agent", "qits:agent"))
            .contentType(ContentType.JSON)
            .body(Map.of("application", application, "server", "private"))
            .when()
            .post(MOVES)
            .then()
            .statusCode(202)
            .extract()
            .jsonPath();
    String id = accepted.getString("move.id");
    assertEquals("QUEUED", accepted.getString("move.state"));
    assertEquals("OPERATOR", accepted.getString("move.origin"));
    assertEquals("private", accepted.getString("move.targetServer"));
    assertEquals(database, accepted.getString("move.targetDatabase"));
    assertEquals("public", accepted.getString("move.specServer"));
    assertTrue(
        accepted.getString("note").contains("moves it back to public/" + database),
        accepted.getString("note"));
    assertEquals("dbmove-agent", accepted.getString("move.requestedBy"));

    deployService.awaitIdle();

    JsonPath moved = move(id);
    assertEquals("MOVED", moved.getString("move.state"));
    assertEquals(5L, moved.getLong("move.sourceRows"));
    assertEquals(5L, moved.getLong("move.targetRows"));
    assertNotNull(moved.getString("move.dropAfter"));
    assertTrue(moved.getString("move.keptDatabase").startsWith(database + "_moved_"));
    // Between the servers it is still a dump and a restore.
    assertEquals(1, containers.shots("extract").size());
    assertEquals(1, containers.shots("ingest").size());
    assertEquals(List.of(), containers.shots("rename"));
    assertNull(moved.get("note"));
    PdResource onPrivate = claim(application, environment);
    assertEquals("private", onPrivate.server);
    assertEquals(password, onPrivate.password);
    // The redeploy is what hands the service the new address.
    assertEquals(List.of("ACTIVE", "DECOMMISSIONED"), statuses(environmentId));
    DeploymentDriver.ServiceSpec redeployed = driver.applied().get(driver.applied().size() - 1);
    assertEquals(
        "jdbc:postgresql://" + environment + "-qits-oci-postgresql-private:5432/" + database,
        redeployed.resources().get(0).value("URL"));

    List<String> listed =
        given()
            .header("Authorization", SuiteBearers.bearer("dbmove-agent", "qits:agent"))
            .queryParam("application", application)
            .when()
            .get(MOVES)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("moves.id");
    assertEquals(List.of(id), listed);

    // Asked again for where it already is: an answer, not a 202.
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body(Map.of("application", application, "server", "private"))
        .when()
        .post(MOVES)
        .then()
        .statusCode(409);

    // The next release follows its spec again: public, by a deployment's own move.
    release(application, "2026.102.120000");
    assertEquals("ACTIVE", statuses(environmentId).get(0));
    assertEquals("public", claim(application, environment).server);
    List<Map<String, Object>> all =
        given()
            .header("Authorization", SuiteBearers.operator())
            .queryParam("application", application)
            .when()
            .get(MOVES)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("moves");
    assertEquals(2, all.size());
    assertEquals("DEPLOYMENT", all.get(0).get("origin"));
    assertEquals("MOVED", all.get(0).get("state"));
    assertEquals("private", all.get(0).get("sourceServer"));
  }

  /** The URL the newest applied service spec was handed for its one resource. */
  private String lastBoundUrl() {
    DeploymentDriver.ServiceSpec applied = driver.applied().get(driver.applied().size() - 1);
    return applied.resources().get(0).value("URL");
  }

  private static JsonPath requestMove(Map<String, Object> body) {
    return given()
        .header("Authorization", SuiteBearers.bearer("dbmove-agent", "qits:agent"))
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(MOVES)
        .then()
        .statusCode(202)
        .extract()
        .jsonPath();
  }

  @Test
  void anOverrideToAnotherNameOnTheSameServerIsProvisionedThereAndTheNextReleaseMovesItBack()
      throws Exception {
    // Live on 2026-11-13 (qits-orchestrator): the override switched the claim, then provisioning's
    // backstop compared it with the SPEC's database, refused, and left the service stopped.
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-o-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-ovr-" + token;
    String database = "qits_dbmv_ovr_" + token;
    String override = "qits_dbmv_held_" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    assertEquals(database, claim(application, environment).databaseName);
    String password = claim(application, environment).password;
    driver.scriptDeclaredReplicas(environment + "-" + application, 1);

    JsonPath accepted = requestMove(Map.of("application", application, "database", override));
    String id = accepted.getString("move.id");
    assertEquals("public", accepted.getString("move.targetServer"));
    assertEquals(override, accepted.getString("move.targetDatabase"));
    assertTrue(
        accepted.getString("note").contains("moves it back to public/" + database),
        accepted.getString("note"));
    deployService.awaitIdle();

    JsonPath renamed = move(id);
    assertEquals("MOVED", renamed.getString("move.state"));
    // Two names on one server (qits-2193): renamed in place — no dump, no restore, no kept copy.
    assertEquals(List.of(), containers.shots("extract"));
    assertEquals(List.of(), containers.shots("ingest"));
    assertEquals(1, containers.shots("rename").size());
    assertTrue(
        containers.shots("rename").get(0).script().contains("ALTER DATABASE " + database + " RENAME TO " + override + ";"));
    assertNull(renamed.get("move.keptDatabase"));
    assertNull(renamed.get("move.dropAfter"));
    assertNull(renamed.get("move.sourceRows"));
    assertEquals(DatabaseMoves.RENAMED_IN_PLACE, renamed.getString("move.detail"));
    PdResource held = claim(application, environment);
    assertEquals("public", held.server);
    assertEquals(override, held.databaseName);
    assertEquals(password, held.password);
    // The redeploy provisioned the override and went live on it.
    assertEquals(List.of("ACTIVE", "DECOMMISSIONED"), statuses(environmentId));
    assertEquals(
        "jdbc:postgresql://" + environment + "-qits-oci-postgresql:5432/" + override,
        lastBoundUrl());

    // The next release follows its spec: the override is moved back to the spec's name.
    release(application, "2026.102.120000");
    assertEquals("ACTIVE", statuses(environmentId).get(0));
    PdResource back = claim(application, environment);
    assertEquals(database, back.databaseName);
    assertEquals(password, back.password);
    assertEquals(
        "jdbc:postgresql://" + environment + "-qits-oci-postgresql:5432/" + database,
        lastBoundUrl());
  }

  @Test
  void theDoorRedeploysTheVersionThatServedWhenTheNewestDeploymentFailedAfterItsMove()
      throws Exception {
    // A redeploy that fails AFTER its move switched leaves the service stopped and a FAILED row on
    // top. The door sees past it to the deployment that served, so a move back recovers the place.
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-f-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-fail-" + token;
    String database = "qits_dbmv_fail_" + token;
    String override = "qits_dbmv_away_" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    driver.scriptDeclaredReplicas(environment + "-" + application, 1);

    driver.scriptPull(
        new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.ERROR, "registry unreachable"));
    String away = requestMove(Map.of("application", application, "database", override)).getString("move.id");
    deployService.awaitIdle();
    assertEquals("MOVED", move(away).getString("move.state"));
    assertEquals(override, claim(application, environment).databaseName);
    assertEquals(List.of("FAILED", "ACTIVE"), statuses(environmentId));

    // The newest row is FAILED; the one under it served. The door takes that one.
    driver.scriptPull(new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.OK, null));
    JsonPath accepted = requestMove(Map.of("application", application));
    assertEquals(database, accepted.getString("move.targetDatabase"));
    assertNull(accepted.get("note"));
    deployService.awaitIdle();

    assertEquals("MOVED", move(accepted.getString("move.id")).getString("move.state"));
    assertEquals(database, claim(application, environment).databaseName);
    List<String> after = statuses(environmentId);
    assertEquals("ACTIVE", after.get(0));
    assertEquals(3, after.size());
    assertEquals(
        "jdbc:postgresql://" + environment + "-qits-oci-postgresql:5432/" + database,
        lastBoundUrl());
  }

  @Test
  void theDoorAnswers409WhileTheTargetServerIsNotDeployed() throws Exception {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-d-" + token;
    createDesignatedEnvironment(environment);
    String application = "dbmv-defer-" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");

    String refusal =
        given()
            .header("Authorization", SuiteBearers.operator())
            .contentType(ContentType.JSON)
            .body(Map.of("application", application, "server", "private"))
            .when()
            .post(MOVES)
            .then()
            .statusCode(409)
            .extract()
            .asString();
    assertTrue(
        refusal.contains("deferred: the private postgres is not deployed in " + environment),
        refusal);
    assertEquals("public", claim(application, environment).server);
  }

  @Test
  void aMoveARestartInterruptedAfterItsSwitchRedeploysTheStoppedService() throws Exception {
    // The restart finished the switch, so the claim points at the private server while the
    // service, stopped for the move, still runs nothing. The boot settle redeploys it.
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-s-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-switch-" + token;
    String database = "qits_dbmv_switch_" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PRIVATE));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    assertEquals("private", claim(application, environment).server);
    // Stage what a restart mid-switch leaves: the source still public, the row SWITCHING.
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove row =
                  new eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = application;
              row.environmentName = environment;
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = database;
              row.targetServer = "private";
              row.targetDatabase = database;
              row.keptDatabase = database + "_moved_202601010000";
              row.origin = eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.SWITCHING;
              row.serviceName = environment + "-" + application;
              row.serviceReplicas = 1;
              row.createdAt = java.time.Instant.now();
              row.persist();
            });
    int appliedBefore = driver.applied().size();

    databaseMoves.settleInterrupted(true);
    deployService.awaitIdle();

    assertEquals("MOVED", move(id).getString("move.state"));
    assertEquals(appliedBefore + 1, driver.applied().size(), "the stopped service was redeployed");
    assertEquals("ACTIVE", statuses(environmentId).get(0));
    assertEquals(2, statuses(environmentId).size());
  }

  // --- the space check and the abort (qits-2243) ---------------------------------------------------

  private static final long GIB = 1L << 30;

  /** A live application holding {@code db} on the public server, its service running one replica. */
  private String[] liveApplication(String prefix) throws Exception {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = prefix + "-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = prefix + "-app-" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    driver.scriptDeclaredReplicas(environment + "-" + application, 1);
    return new String[] {environment, environmentId, application, token};
  }

  @Test
  void aMoveThatDoesNotFitIsRefusedTheDeploymentFailsAndTheServiceKeepsRunningOnTheSource()
      throws Exception {
    String[] live = liveApplication("dbsp");
    String environment = live[0];
    String application = live[2];
    String database = claim(application, environment).databaseName;
    containers.script("size", shot -> FakeDatabaseContainers.sized(19 * GIB));
    containers.script("space", shot -> FakeDatabaseContainers.oneFilesystem(42 * GIB));

    String id =
        // Between the servers: only a copy is measured (a same-server move is a rename, qits-2193).
        requestMove(Map.of("application", application, "server", "private"))
            .getString("move.id");
    deployService.awaitIdle();

    JsonPath refused = move(id);
    assertEquals("REFUSED", refused.getString("move.state"));
    String detail = refused.getString("move.detail");
    assertTrue(detail.contains("it is 19.0 GiB"), detail);
    assertTrue(detail.contains("42.0 GiB free where the dump and the copy need 46.8 GiB"), detail);
    assertEquals(19 * GIB, refused.getLong("move.sourceBytes"));
    assertEquals(42 * GIB, refused.getLong("move.targetFreeBytes"));
    assertEquals(42 * GIB, refused.getLong("move.volumeFreeBytes"));
    // The deployment failed naming it, and nothing stopped: the service runs on the source.
    List<Map<String, Object>> rows =
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get("/deployments/api/deployments?environmentId=" + live[1])
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("deployments");
    assertEquals("FAILED", rows.get(0).get("status"));
    assertTrue(String.valueOf(rows.get(0).get("detail")).contains("free space first"), rows.toString());
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")), driver.calls().toString());
    assertTrue(containers.shots("extract").isEmpty());
    assertEquals(database, claim(application, environment).databaseName);
    assertEquals("public", claim(application, environment).server);
  }

  @Test
  void anAbortKillsTheRunningExtractAndTheServiceComesBackOnTheSource() throws Exception {
    String[] live = liveApplication("dbab");
    String environment = live[0];
    String application = live[2];
    String database = claim(application, environment).databaseName;
    java.util.concurrent.CountDownLatch killed = new java.util.concurrent.CountDownLatch(1);
    containers.onKill(killed::countDown);
    containers.script(
        "extract",
        shot -> {
          try {
            killed.await(30, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new DatabaseContainers.Outcome(-1, "the container was removed", false);
        });

    String id =
        requestMove(Map.of("application", application, "server", "private"))
            .getString("move.id");
    long deadline = System.currentTimeMillis() + 15_000;
    while (!"EXTRACTING".equals(move(id).getString("move.state"))) {
      assertTrue(System.currentTimeMillis() < deadline, "the extract never started");
      Thread.sleep(50);
    }

    JsonPath asked =
        given()
            .header("Authorization", SuiteBearers.bearer("dbmove-operator", "qits:admin"))
            .when()
            .post(MOVES + "/" + id + "/abort")
            .then()
            .statusCode(202)
            .extract()
            .jsonPath();
    assertEquals(id, asked.getString("move.id"));
    assertEquals("EXTRACTING", asked.getString("move.state"));
    deployService.awaitIdle();

    JsonPath aborted = move(id);
    assertEquals("ABORTED", aborted.getString("move.state"));
    assertEquals("aborted by dbmove-operator", aborted.getString("move.detail"));
    assertEquals(List.of(id), containers.killed());
    assertTrue(
        driver.calls().contains("scale:" + environment + "-" + application + "=1"),
        driver.calls().toString());
    assertTrue(containers.shots("ingest").isEmpty(), "nothing was restored");
    assertEquals(database, claim(application, environment).databaseName);
    assertEquals("FAILED", statuses(live[1]).get(0));

    // Ended: asked again, a conflict.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(MOVES + "/" + id + "/abort")
        .then()
        .statusCode(409);
  }

  /** A move row in {@code state}, written straight to the table. */
  private static String staged(eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState state) {
    String id = UUID.randomUUID().toString();
    String token = id.substring(0, 8);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove row =
                  new eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = "dbab-staged-" + token;
              row.environmentName = "dbab-staged-" + token;
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = "qits_dbab_staged_" + token;
              row.targetServer = "private";
              row.targetDatabase = "qits_dbab_staged_" + token;
              row.origin = eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove.Origin.OPERATOR;
              row.state = state;
              row.createdAt = java.time.Instant.now();
              row.persist();
            });
    return id;
  }

  private static void settle(String id) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove
                    .<eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove>findById(id)
                    .state =
                    eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.MOVED);
  }

  @Test
  void theAbortDoorAnswersWhatItCannotAbort() {
    String abort = MOVES + "/%s/abort";
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(String.format(abort, UUID.randomUUID()))
        .then()
        .statusCode(404);
    for (var state :
        List.of(
            eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.QUEUED,
            eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.MOVED,
            eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.REFUSED,
            eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.SWITCHING)) {
      String id = staged(state);
      try {
        String answer =
            given()
                .header("Authorization", SuiteBearers.operator())
                .when()
                .post(String.format(abort, id))
                .then()
                .statusCode(409)
                .extract()
                .asString();
        if (state == eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.SWITCHING) {
          assertTrue(answer.contains("past its switch"), answer);
        }
      } finally {
        settle(id);
      }
    }
    assertEquals(List.of(), containers.killed(), "nothing was killed for a refusal");
    // The operator levers' roles: never a plain agent, never a machine.
    String running = staged(eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState.QUEUED);
    try {
      given()
          .header("Authorization", SuiteBearers.bearer("dbmove-agent", "qits:agent"))
          .when()
          .post(String.format(abort, running))
          .then()
          .statusCode(403);
      given()
          .header("Authorization", SuiteBearers.platform())
          .when()
          .post(String.format(abort, running))
          .then()
          .statusCode(403);
    } finally {
      settle(running);
    }
  }

  @Test
  void theDoorRefusesWhatItCanTellIsWrongBeforeQueueingAnything() {
    createDesignatedEnvironment("dbmv-r-" + UUID.randomUUID().toString().substring(0, 8));
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body(Map.of("server", "private"))
        .when()
        .post(MOVES)
        .then()
        .statusCode(400);
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body(Map.of("application", "qits-deployments"))
        .when()
        .post(MOVES)
        .then()
        .statusCode(409);
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body(Map.of("application", "dbmv-nobody-" + UUID.randomUUID().toString().substring(0, 8)))
        .when()
        .post(MOVES)
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body(Map.of("application", "dbmv-any", "server", "elsewhere"))
        .when()
        .post(MOVES)
        .then()
        .statusCode(400);
    // A machine bearer may not stop an application as a side effect of holding a service token.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("application", "dbmv-any"))
        .when()
        .post(MOVES)
        .then()
        .statusCode(403);
    given()
        .header("Authorization", SuiteBearers.bearer("dbmove-agent", "qits:agent"))
        .when()
        .get(MOVES + "/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }
}
