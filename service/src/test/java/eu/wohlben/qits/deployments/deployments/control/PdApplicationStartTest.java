package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The break-glass start (qits-2249): {@code POST /deployments/api/applications/{id}/start} redeploys
 * the newest {@code ACTIVE} version through the ordinary deploy path on the database claims as they
 * are — provisioning included, no database move planned — and refuses what would race it.
 *
 * <p>Package-local, the {@code PdApplicationScaleTest} shape: the door answers 202 and the work runs
 * on the deploy worker, so every assertion waits on {@link DeployService#awaitIdle()}.
 */
@QuarkusTest
class PdApplicationStartTest {

  private static final String APPLICATIONS = "/deployments/api/applications/";
  private static final String V1 = "2026.101.120000";
  private static final String V2 = "2026.102.120000";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeDatabaseContainers containers;
  @Inject FakeIdpClientProvisioner idp;
  @Inject DeployService deployService;
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

  private static DeploymentSpec declaring(DeploymentDriver.Exposure exposure) {
    return new DeploymentSpec(
        false, null, null, null, List.of(new ResourceSpec("db", null)), List.of(), null, null, null,
        8080, null, false, null, null, null, null, null, null, exposure, null, null);
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
        .body(Map.of("runId", "run-start", "repoId", application, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    deployService.awaitIdle();
  }

  /** {@code [environment, environmentId, application]} of an application live at {@link #V1}. */
  private String[] live(String prefix) throws Exception {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = prefix + "-" + token;
    String environmentId = createDesignatedEnvironment(environment);
    String application = prefix + "-app-" + token;
    specs.script(application, declaring(DeploymentDriver.Exposure.PUBLIC));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", "registry.example/qits/pg:1");
    release(application, V1);
    return new String[] {environment, environmentId, application};
  }

  private static String id(String[] live) {
    return live[1] + ":" + live[2];
  }

  private List<PdDeployment> place(String[] live) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deploymentRows.listForPlaceNewestFirst(live[2], live[1]));
  }

  private PdResource claim(String[] live) {
    return QuarkusTransaction.requiringNew()
        .call(() -> resources.findOne(live[2], live[0], "db").orElseThrow());
  }

  private static io.restassured.response.ValidatableResponse start(String bearer, String applicationId) {
    return given()
        .header("Authorization", bearer)
        .when()
        .post(APPLICATIONS + applicationId + "/start")
        .then();
  }

  @Test
  void theStartRedeploysTheNewestActiveVersionOnTheClaimsAsTheyAreAndPlansNoMove() throws Exception {
    String[] live = live("dbst-a");
    // V2 never went live: the registry has no image for it, and V1 is still ACTIVE.
    driver.scriptPull(new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.IMAGE_MISSING, "no such tag"));
    release(live[2], V2);
    driver.scriptPull(new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.OK, null));
    String active = place(live).get(1).id;
    assertEquals(PdDeploymentStatus.ACTIVE, place(live).get(1).status);
    // Its spec now says private — a deployment would MOVE the database. The start must not.
    specs.script(live[2], declaring(DeploymentDriver.Exposure.PRIVATE));
    int applied = driver.applied().size();

    JsonPath accepted =
        start(SuiteBearers.operator(), id(live)).statusCode(202).extract().jsonPath();
    assertEquals(id(live), accepted.getString("applicationId"));
    assertEquals(live[2], accepted.getString("applicationName"));
    assertEquals(live[1], accepted.getString("environmentId"));
    assertEquals(active, accepted.getString("deploymentId"));
    assertEquals(V1, accepted.getString("version"));
    deployService.awaitIdle();

    PdDeployment started = place(live).get(0);
    assertEquals(PdDeploymentStatus.ACTIVE, started.status, started.detail);
    assertEquals(V1, started.version, "the newest ACTIVE version, not the newest release");
    assertNotEquals(active, started.id, "a deployment of its own");
    assertEquals(applied + 1, driver.applied().size());
    DeploymentDriver.ServiceSpec spec = driver.applied().get(driver.applied().size() - 1);
    PdResource claim = claim(live);
    assertEquals("public", claim.server, "no move planned: the claim is where it was");
    assertEquals(
        "jdbc:postgresql://" + live[0] + "-qits-oci-postgresql:5432/" + claim.databaseName,
        spec.resources().get(0).value("URL"),
        "the binding is rendered from the claim it found");
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> moveRows.listByApplication(live[2], 10)).isEmpty(),
        "no move row at all");
    assertTrue(driver.calls().stream().noneMatch(call -> call.startsWith("scale:")), driver.calls().toString());
    assertTrue(containers.shots("extract").isEmpty() && containers.shots("rename").isEmpty());
  }

  @Test
  void theStartTakesTheOperatorRolesAndRefusesAnAgentAndAMachine() throws Exception {
    String[] live = live("dbst-r");
    start(SuiteBearers.bearer("start-agent", "qits:agent"), id(live)).statusCode(403);
    start(SuiteBearers.platform(), id(live)).statusCode(403);
    for (String role : List.of("qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk")) {
      start(SuiteBearers.bearer("start-" + role, role), id(live)).statusCode(202);
      deployService.awaitIdle();
    }
    assertEquals(PdDeploymentStatus.ACTIVE, place(live).get(0).status);
  }

  @Test
  void theStartAnswersWhatItCannotStart() throws Exception {
    start(SuiteBearers.operator(), "not-an-id").statusCode(400);
    String[] live = live("dbst-n");
    start(SuiteBearers.operator(), live[1] + ":dbst-nobody-" + UUID.randomUUID().toString().substring(0, 8))
        .statusCode(404);
    start(SuiteBearers.operator(), live[1] + ":qits-deployments").statusCode(409);

    // No ACTIVE deployment: the only one failed.
    String[] failed = live("dbst-f");
    QuarkusTransaction.requiringNew()
        .run(() -> deploymentRows.findById(place(failed).get(0).id).status = PdDeploymentStatus.FAILED);
    String noActive = start(SuiteBearers.operator(), id(failed)).statusCode(409).extract().asString();
    assertTrue(noActive.contains("no ACTIVE deployment"), noActive);
  }

  @Test
  void theStartWaitsForADeploymentInFlight() throws Exception {
    String[] live = live("dbst-q");
    String queued = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = queued;
              row.applicationName = live[2];
              row.environmentId = live[1];
              row.version = V2;
              row.status = PdDeploymentStatus.STARTING;
              row.createdAt = Instant.now();
              deploymentRows.persist(row);
            });
    try {
      String answer = start(SuiteBearers.operator(), id(live)).statusCode(409).extract().asString();
      assertTrue(answer.contains(queued) && answer.contains("STARTING"), answer);
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> deploymentRows.findById(queued).status = PdDeploymentStatus.FAILED);
    }
  }

  @Test
  void theStartWaitsForADatabaseMoveThatIsNotSettled() throws Exception {
    String[] live = live("dbst-m");
    for (PdDatabaseMoveState state :
        List.of(PdDatabaseMoveState.QUEUED, PdDatabaseMoveState.STOPPING, PdDatabaseMoveState.SWITCHED, PdDatabaseMoveState.ROLLING_BACK)) {
      String move = UUID.randomUUID().toString();
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                PdDatabaseMove row = new PdDatabaseMove();
                row.id = move;
                row.batchId = move;
                row.applicationName = live[2];
                row.environmentName = live[0];
                row.resourceName = "db";
                row.sourceServer = "public";
                row.sourceDatabase = "qits_dbst_m_source";
                row.targetServer = "private";
                row.targetDatabase = "qits_dbst_m_source";
                row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
                row.state = state;
                row.createdAt = Instant.now();
                moveRows.persist(row);
              });
      try {
        String answer = start(SuiteBearers.operator(), id(live)).statusCode(409).extract().asString();
        assertTrue(answer.contains(move) && answer.contains(state.name()), answer);
      } finally {
        // Settled out of every other test's way: nothing here is a real move.
        QuarkusTransaction.requiringNew()
            .run(() -> moveRows.findById(move).state = PdDatabaseMoveState.REFUSED);
      }
    }
    start(SuiteBearers.operator(), id(live)).statusCode(202);
    deployService.awaitIdle();
  }
}
