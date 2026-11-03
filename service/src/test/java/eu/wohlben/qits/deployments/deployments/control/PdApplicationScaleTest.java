package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.persistence.PdEnvironmentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The operator's two levers, end to end: the door, the worker, the orchestrator call and the row.
 *
 * <p>Package-local for the reason {@link PdDeploymentObservationTest} is — the recovery half of a
 * scale is an observation pass, and driving one directly is the only way to assert it without a
 * ticker. Rows are written straight to the table for the same reason too: what these levers act on
 * is an application that is already deployed, and staging a real green build to reach that state
 * would be asserting the deployment path a second time.
 *
 * <p><b>Every call is answered 202 and the work happens on the deploy worker</b>, so each assertion
 * waits on {@link DeployService#awaitIdle()} rather than on the response — which is exactly what a
 * caller of this API is told to do, and what makes these tests immune to the worker's timing.
 */
@QuarkusTest
public class PdApplicationScaleTest {

  private static final String SHA = "c".repeat(40);

  private static final String APPLICATIONS = "/deployments/api/applications/";

  /**
   * The tier the fixtures below name, as a REAL environment row.
   *
   * <p>It did not have to exist while these fixtures stood for the platform plane: their rows carried
   * no tier, they were addressed {@code platform:<name>}, and the deployment listing's {@code
   * ?environmentId=platform} arm skipped the tier check because the plane was not a row and so could
   * not be missing. The plane is deleted, so a place is a tier — and the listing's own rule is that a
   * tier which does not exist is a 404 rather than an empty list. The deployment ROWS still need no
   * environment row (V1: no FK, so history outlives the topology); the LISTING does.
   */
  @BeforeEach
  void theTierExists() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (environments.findByIdOptional(TIER).isEmpty()) {
                PdEnvironment tier = new PdEnvironment();
                tier.id = TIER;
                tier.name = TIER;
                tier.network = "qits-env-" + TIER;
                // Never the designated one: designation is moved by creating a tier through the
                // door, and a fixture that took it would decide where every other class's release
                // lands.
                tier.designated = false;
                tier.createdAt = Instant.now();
                environments.persist(tier);
              }
            });
  }

  @Inject FakeDeploymentDriver driver;
  @Inject PdEnvironmentRepository environments;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject DeploymentObserver observer;
  @Inject DeployService deployService;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
  }

  /**
   * One settled deployment of one application in one place, as the world it acts on.
   *
   * <p><b>There was a second arm that stated the PLANE independently of the tier</b>, because a
   * fixture with no tier was a platform row and a designated install had rows carrying both. The
   * plane is deleted, so a place is a tier and one arm says everything.
   */
  /**
   * The tier every fixture here uses unless it names its own. It was {@code null} on the rows that
   * stood for the platform plane, addressed {@code platform:<name>}; the plane is deleted, so a row
   * names a tier and the id is that tier's. A literal rather than a created environment, because a
   * deployment row has no FK to one (V1's rule) and this door reads the rows.
   */
  private static final String TIER = "env-scale-tier";

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.commitSha = SHA;
              row.status = status;
              row.containerName = containerName;
              row.createdAt = Instant.now();
              row.finishedAt = Instant.now();
              deployments.persist(row);
            });
    return id;
  }

  private PdDeployment rowOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.findById(deploymentId);
              assertNotNull(row, "the row is still there");
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              copy.commitSha = row.commitSha;
              copy.containerName = row.containerName;
              copy.finishedAt = row.finishedAt;
              return copy;
            });
  }

  private int rowCount(String applicationName, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.listByApplication(applicationName, environmentId).size());
  }

  private void awaitWorker() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- scaling ------------------------------------------------------------------------------------

  @Test
  public void scalingToZeroStopsTheWorkloadAndTheReadSurfaceSaysSo() {
    // The listing this asserts against used to be reached as `?environmentId=platform` — the plane,
    // which needed no tier row created first. That filter value is gone with the plane, so the row is
    // written into a literal tier id and read back by it; a deployment row has no FK to an
    // environment (V1's rule), so there is still nothing to create.
    String service = "scale-down";
    String id = deployment("scale-down", TIER, PdDeploymentStatus.ACTIVE, service);

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(TIER, "scale-down")
                + "/scale")
        .then()
        .statusCode(202)
        .body("serviceName", org.hamcrest.Matchers.equalTo(service))
        .body("replicas", org.hamcrest.Matchers.equalTo(0))
        // The row it acts on, named in the answer: a bounce and a scale are about a DEPLOYMENT, and
        // a caller that could not tell which one would have to guess from a listing.
        .body("deploymentId", org.hamcrest.Matchers.equalTo(id));

    awaitWorker();

    assertTrue(driver.calls().contains("scale:" + service + "=0"), driver.calls().toString());
    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.SCALED_TO_ZERO, row.status);
    assertTrue(row.detail.contains("scaled to 0"), row.detail);
    // The deployment kept its identity: this is a stopped application, not a failed one.
    assertEquals(SHA, row.commitSha);
    assertEquals(service, row.containerName);

    // ...and the listing a client reads says so rather than claiming the place is serving.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + TIER)
        .then()
        .statusCode(200)
        .body("deployments.find { it.id == '" + id + "' }.status", org.hamcrest.Matchers.equalTo("SCALED_TO_ZERO"));
  }

  @Test
  public void scalingBackUpAsksTheOrchestratorAndTheObservationSettlesTheRow() {
    // The row is deliberately NOT written ACTIVE here: this component reaches a health verdict in
    // exactly one place, and it is the observation. A scale that wrote ACTIVE would be claiming a
    // gate it never ran.
    String service = "scale-up";
    String id = deployment("scale-up", TIER, PdDeploymentStatus.SCALED_TO_ZERO, service);
    driver.scriptDeclaredReplicas(service, 0);

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"replicas\":1}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(TIER, "scale-up")
                + "/scale")
        .then()
        .statusCode(202);
    awaitWorker();

    assertTrue(driver.calls().contains("scale:" + service + "=1"), driver.calls().toString());
    assertEquals(
        PdDeploymentStatus.SCALED_TO_ZERO,
        rowOf(id).status,
        "still stopped as far as this component knows: no task is healthy yet");

    // The tasks come up, and the ordinary recovery arm — the one FAILED and GONE already use —
    // takes the row back without anything about the deployment changing.
    driver.scriptObservation(service, "running/healthy");
    observer.observeOnce();

    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertEquals(SHA, row.commitSha, "the same deployment, come back");
  }

  @Test
  public void aScaleTheOrchestratorRefusesLeavesTheRowAlone() {
    String service = "env-scale-refused-scale-refused";
    String id =
        deployment("scale-refused", "env-scale-refused", PdDeploymentStatus.ACTIVE, service);
    driver.scriptScale(
        new DeploymentDriver.ScaleResult(
            DeploymentDriver.ScaleOutcome.REFUSED, "Error response from daemon: rpc error"));

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of("env-scale-refused", "scale-refused")
                + "/scale")
        .then()
        .statusCode(202);
    awaitWorker();

    // Nothing was stopped, so nothing may say it was. The 202 was honest — it queued the attempt —
    // and the row is where an operator reads that the attempt did not land.
    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertNull(row.detail, "a refused action stamps nothing");
  }

  // --- restarting ---------------------------------------------------------------------------------

  @Test
  public void aRestartBouncesTheServiceAndCreatesNoDeploymentRow() {
    String service = "env-bounce-bounce";
    String id = deployment("bounce", "env-bounce", PdDeploymentStatus.ACTIVE, service);
    int before = rowCount("bounce", "env-bounce");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of("env-bounce", "bounce")
                + "/restart")
        .then()
        .statusCode(202)
        .body("serviceName", org.hamcrest.Matchers.equalTo(service))
        .body("replicas", org.hamcrest.Matchers.nullValue());
    awaitWorker();

    assertTrue(driver.calls().contains("restart:" + service), driver.calls().toString());
    assertEquals(before, rowCount("bounce", "env-bounce"), "a bounce is not a deployment");

    // The row keeps everything that identifies the deployment, and gains one line saying somebody
    // bounced it. That line is the whole audit trail, and it is deliberately not a status.
    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertEquals(SHA, row.commitSha);
    assertEquals(service, row.containerName);
    assertTrue(row.detail.contains("restarted by"), row.detail);
    assertTrue(row.detail.contains("this deployment is unchanged"), row.detail);
  }

  @Test
  public void anOperatorStampReplacesThePreviousOneAndKeepsTheDeploymentsOwnDiagnosis() {
    // The stamp is a statement about now; the text under it is what made eaa34fbc findable, and it
    // is what must survive every bounce anybody performs.
    String diagnosis = "[unexpected: JDBCConnectionException: Unable to acquire JDBC Connection]";
    String first = ApplicationScaling.stamped("[restarted by alice at t1]", diagnosis);
    String second = ApplicationScaling.stamped("[scaled to 0 by bob at t2]", first);

    assertTrue(second.startsWith("[scaled to 0 by bob at t2]"), second);
    assertFalse(second.contains("alice"), "yesterday's stamp is not kept");
    assertTrue(second.endsWith(diagnosis), second);
  }

  // --- what the door refuses ----------------------------------------------------------------------

  @Test
  public void aMalformedApplicationIdIsRefusedBeforeAnythingIsQueued() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(APPLICATIONS + "not-an-application-id/scale")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("is not an application id"));
    awaitWorker();
    assertEquals(List.of(), driver.calls());
  }

  @Test
  public void aReplicaCountAboveOneIsRefusedWithTheReasonThisPlatformHasOne() {
    deployment("too-many", "env-too-many", PdDeploymentStatus.ACTIVE, "env-too-many-too-many");

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"replicas\":2}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of("env-too-many", "too-many")
                + "/scale")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("single task"));
  }

  @Test
  public void aScaleWithNoCountIsRefusedRatherThanReadAsZero() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(APPLICATIONS + TIER + ":no-count/scale")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("replicas is required"));
  }

  @Test
  public void anApplicationNothingEverDeployedHasNothingToScale() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(APPLICATIONS + TIER + ":never-deployed-anywhere/scale")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":never-deployed-anywhere/restart")
        .then()
        .statusCode(404);
  }

  @Test
  public void aDeploymentThatNeverReachedTheOrchestratorCarriesNoServiceToActOn() {
    // IMAGE_MISSING is the everyday shape of this: the row exists, the pull failed, and there is no
    // service anywhere carrying the application. A 409 rather than a 404 — the application is
    // known, the state is wrong.
    deployment("never-ran", TIER, PdDeploymentStatus.IMAGE_MISSING, null);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(TIER, "never-ran")
                + "/restart")
        .then()
        .statusCode(409)
        .body("message", org.hamcrest.Matchers.containsString("never reached the orchestrator"));
  }

  @Test
  public void anApplicationIsAddressedByTheSameKeyItIsListedUnder() {
    // The lever takes the id a client is already holding rather than a second spelling. It was
    // `aPlatformApplicationIsAddressedByTheSameKeyItIsListedUnder` and the key was `platform:<name>`,
    // the stand-in the plane's applications carried; the stand-in is gone with the plane and the key
    // is the tier's, on both sides of the join.
    String service = "platform-lever";
    String id = deployment("platform-lever", TIER, PdDeploymentStatus.ACTIVE, service);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + ApplicationKeys.of(TIER, "platform-lever") + "/restart")
        .then()
        .statusCode(202)
        .body("environmentId", org.hamcrest.Matchers.equalTo(TIER))
        .body("deploymentId", org.hamcrest.Matchers.equalTo(id));
    awaitWorker();

    assertTrue(driver.calls().contains("restart:" + service), driver.calls().toString());
  }

  @Test
  public void aPlatformApplicationIsFOUNDBYITSPLANEEvenThoughItNamesTheDesignatedTier() {
    // The regression this pins was V8's and is now one question shorter. It held that an operator's
    // lever on a platform application has to find the row by PLANE, because the rows carried the
    // designated tier while the id said `platform:` — so a tier-shaped read would have found the
    // pre-V8 rows alone and acted on a deployment years old, or 404'd an application that was plainly
    // serving. The plane is deleted, the id is the tier's, and the rows already name that tier: one
    // question, asked once, and this is the claim that the lever still reaches the live row.
    String service = "dev-planed-lever";
    String id = deployment("planed-lever", "env-designated", PdDeploymentStatus.ACTIVE, service);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + "env-designated:planed-lever/restart")
        .then()
        .statusCode(202)
        .body("environmentId", org.hamcrest.Matchers.equalTo("env-designated"))
        .body("deploymentId", org.hamcrest.Matchers.equalTo(id));
    awaitWorker();

    assertTrue(driver.calls().contains("restart:" + service), driver.calls().toString());
  }
}
