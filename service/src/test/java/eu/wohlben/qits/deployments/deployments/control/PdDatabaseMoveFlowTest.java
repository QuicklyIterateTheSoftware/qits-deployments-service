package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.OptionalInt;
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
  @Inject FakeIdpClientProvisioner idp;
  @Inject DeployService deployService;
  @Inject DatabaseMoves databaseMoves;
  @Inject PdResourceRepository resources;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    containers.reset();
    idp.reset();
  }

  /** A spec declaring one postgres resource {@code db} on its conventional database. */
  private static DeploymentSpec declaring(DeploymentDriver.Exposure exposure) {
    return declaring(exposure, List.of(new ResourceSpec("db", null)));
  }

  /** A spec declaring exactly {@code resources}. */
  private static DeploymentSpec declaring(
      DeploymentDriver.Exposure exposure, List<ResourceSpec> resources) {
    return new DeploymentSpec(
        false,
        null,
        null,
        null,
        resources,
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

  private static List<String> details(String environmentId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments.detail");
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
  void aRedeployThatFailsBeforeTheStopMovesNothingAndStopsNothing() throws Exception {
    // qits-2249: the pull runs BEFORE the move's stop, so an image the registry will not hand over
    // is no reason to have stopped a service — the move never starts and the door's row is refused.
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-p-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-pull-" + token;
    String database = "qits_dbmv_pull_" + token;
    String override = "qits_dbmv_pulled_" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    String service = environment + "-" + application;
    driver.scriptDeclaredReplicas(service, 1);

    driver.scriptPull(
        new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.ERROR, "registry unreachable"));
    String id = requestMove(Map.of("application", application, "database", override)).getString("move.id");
    deployService.awaitIdle();

    assertEquals("REFUSED", move(id).getString("move.state"));
    assertEquals(database, claim(application, environment).databaseName);
    assertEquals(List.of("FAILED", "ACTIVE"), statuses(environmentId));
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:" + service)), driver.calls().toString());
    assertEquals(List.of(), containers.shots("rename"));
  }

  @Test
  void aRenameInPlaceWhoseDeploymentNeverConvergesIsRenamedBackAndThePreviousVersionRestored()
      throws Exception {
    // qits-2249 (a): the move switched, the redeploy applied its spec naming the moved database,
    // and the service never converged. The deployer renames the database back, switches the claim
    // back, re-applies the last ACTIVE deployment's spec on the source — and only then says FAILED.
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-f-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-fail-" + token;
    String database = "qits_dbmv_fail_" + token;
    String override = "qits_dbmv_away_" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    String service = environment + "-" + application;
    String activeId = driver.applied().get(driver.applied().size() - 1).deploymentId();
    String password = claim(application, environment).password;
    driver.scriptDeclaredReplicas(service, 1);

    driver.scriptConvergence(DeploymentDriver.Convergence.failed("container still starting after 120s"));
    String away = requestMove(Map.of("application", application, "database", override)).getString("move.id");
    deployService.awaitIdle();

    // The database is back under its source name, and the claim on it.
    JsonPath rolledBack = move(away);
    assertEquals("ABORTED", rolledBack.getString("move.state"));
    assertTrue(rolledBack.getString("move.detail").startsWith("rolled back after the switch"), rolledBack.getString("move.detail"));
    assertNull(rolledBack.get("move.dropAfter"));
    List<DatabaseContainers.OneShot> renames = containers.shots("rename");
    assertEquals(2, renames.size(), "the rename, then the rename back");
    assertTrue(renames.get(1).script().contains("ALTER DATABASE " + override + " RENAME TO " + database + ";"), renames.get(1).script());
    assertFalse(renames.get(1).script().contains("DROP DATABASE"), "a rename back never drops");
    PdResource back = claim(application, environment);
    assertEquals(database, back.databaseName);
    assertEquals(database, back.roleName);
    assertEquals(password, back.password);
    // The failed spec named the moved database; the restore is the last ACTIVE deployment's, on the
    // source, at the replicas the move stopped.
    List<DeploymentDriver.ServiceSpec> applied = driver.applied();
    DeploymentDriver.ServiceSpec failed = applied.get(applied.size() - 2);
    DeploymentDriver.ServiceSpec restored = applied.get(applied.size() - 1);
    assertEquals(
        "jdbc:postgresql://" + environment + "-qits-oci-postgresql:5432/" + override,
        failed.resources().get(0).value("URL"));
    assertEquals(activeId, restored.deploymentId());
    assertEquals("2026.101.120000", restored.version());
    assertEquals(
        "jdbc:postgresql://" + environment + "-qits-oci-postgresql:5432/" + database,
        restored.resources().get(0).value("URL"));
    assertEquals(OptionalInt.of(1), driver.declaredReplicasOf(service));
    // ...and only then FAILED, saying it was rolled back; the version that served is still ACTIVE.
    assertEquals(List.of("FAILED", "ACTIVE"), statuses(environmentId));
    String detail = details(environmentId).get(0);
    assertTrue(detail.startsWith("[rolled back:"), detail);
    assertTrue(detail.contains(service + " restored from its last ACTIVE deployment " + activeId), detail);
    assertTrue(detail.contains("container still starting after 120s"), detail);

    // The door sees past the FAILED row to the deployment that served, and the same move, asked
    // again with a service that converges, goes through.
    driver.scriptConvergence(DeploymentDriver.Convergence.converged(List.of()));
    JsonPath again = requestMove(Map.of("application", application, "database", override));
    deployService.awaitIdle();
    assertEquals("MOVED", move(again.getString("move.id")).getString("move.state"));
    assertEquals(override, claim(application, environment).databaseName);
    List<String> after = statuses(environmentId);
    assertEquals("ACTIVE", after.get(0));
    assertEquals(3, after.size());
  }

  @Test
  void aCopyWhoseMovedResourceCannotBeProvisionedRestoresTheKeptSourceAndScalesTheServiceBack()
      throws Exception {
    // qits-2249 (b): a copy onto the private server switched, then provisioning the moved resource
    // there failed. Nothing was applied, so the service still has its original spec: the kept source
    // is renamed back and re-opened, the claim switched back, the target dropped, the service scaled
    // back to the replicas it ran.
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-c-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-copy-" + token;
    String database = "qits_dbmv_copy_" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    String service = environment + "-" + application;
    String password = claim(application, environment).password;
    int appliedBefore = driver.applied().size();
    driver.scriptDeclaredReplicas(service, 1);

    containers.script("provision", shot -> new DatabaseContainers.Outcome(2, "FATAL: the private postgres is full", false));
    String id = requestMove(Map.of("application", application, "server", "private")).getString("move.id");
    deployService.awaitIdle();

    JsonPath rolledBack = move(id);
    assertEquals("ABORTED", rolledBack.getString("move.state"));
    assertTrue(rolledBack.getString("move.detail").startsWith("rolled back after the switch"), rolledBack.getString("move.detail"));
    String kept = rolledBack.getString("move.keptDatabase");
    assertTrue(kept.startsWith(database + "_moved_"), kept);
    List<String> admin = containers.shots("admin").stream().map(DatabaseContainers.OneShot::script).toList();
    assertTrue(
        admin.stream().anyMatch(sql -> sql.contains("ALTER DATABASE " + kept + " RENAME TO " + database + "'")
            && sql.contains("ALTER DATABASE " + kept + " WITH ALLOW_CONNECTIONS true")
            && sql.contains("GRANT CONNECT ON DATABASE " + database + " TO " + database)),
        admin.toString());
    assertTrue(
        admin.stream().anyMatch(sql -> sql.contains("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)")),
        "the copy on the private server is dropped: " + admin);
    PdResource back = claim(application, environment);
    assertEquals("public", back.server);
    assertEquals(database, back.databaseName);
    assertEquals(password, back.password);
    // Nothing was applied, so the original spec is still the service's: a scale is the whole restore.
    assertEquals(appliedBefore, driver.applied().size());
    List<String> calls = driver.calls();
    assertTrue(calls.indexOf("scale:" + service + "=0") < calls.lastIndexOf("scale:" + service + "=1"), calls.toString());
    assertEquals(OptionalInt.of(1), driver.declaredReplicasOf(service));
    assertEquals(List.of("FAILED", "ACTIVE"), statuses(environmentId));
    String detail = details(environmentId).get(0);
    assertTrue(detail.startsWith("[rolled back:"), detail);
    assertTrue(detail.contains(service + " scaled back to 1 on its original spec"), detail);
    assertTrue(detail.contains("[resource provisioning failed:"), detail);
  }

  @Test
  void aServiceWhoseOwnIdpClientNeedsItselfIsNeverAskedWhileItsMoveHasItStopped() throws Exception {
    // qits-2249: on 2026-11-14 qits-idp's deploy scaled idp to 0 for its move and then asked the
    // stopped idp for its own client. Everything no move touches is provisioned BEFORE the stop.
    // The fake idp here answers only while this application's service runs — the idp's own shape —
    // and the name is not qits-idp, so it is the ORDER that is held, not idp's own shortcut.
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = "dbmv-i-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = "dbmv-idp-" + token;
    String override = "qits_dbmv_idpmoved_" + token;
    specs.script(
        application,
        declaring(
            DeploymentDriver.Exposure.PUBLIC,
            List.of(new ResourceSpec("db", null), new ResourceSpec("idp", null, ResourceSpec.Type.IDP_CLIENT))));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    release(application, "2026.101.120000");
    String service = environment + "-" + application;
    driver.scriptDeclaredReplicas(service, 1);
    idp.scriptReachable(() -> driver.declaredReplicasOf(service).orElse(1) > 0);
    int checksBefore = idp.presenceChecks().size();

    String id = requestMove(Map.of("application", application, "database", override)).getString("move.id");
    deployService.awaitIdle();

    assertEquals("MOVED", move(id).getString("move.state"));
    assertEquals(List.of("ACTIVE", "DECOMMISSIONED"), statuses(environmentId));
    assertEquals(checksBefore + 1, idp.presenceChecks().size(), "asked once, while it still ran");
    DeploymentDriver.ServiceSpec redeployed = driver.applied().get(driver.applied().size() - 1);
    assertEquals(
        List.of("db", "idp"),
        redeployed.resources().stream().map(DeploymentDriver.ResourceBinding::name).toList(),
        "the bindings keep the file's order across the two halves");
    assertEquals(
        "jdbc:postgresql://" + environment + "-qits-oci-postgresql:5432/" + override,
        redeployed.resources().get(0).value("URL"));
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
