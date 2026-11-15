package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.bus.PdSoftwareReleaseSubscriber;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdOwedReleaseRepository;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A release can be HELD for a person's approval</b> (qits-2268): a release request flagged "hold
 * deploy" reaches this component with {@code deployHold: true}, its deployment request waits at the
 * quality gate — {@code UNMET}, unsettled, no deployment — and {@code POST
 * /deployment-requests/{id}/approve} is what lets it through.
 *
 * <p>Every method creates its own entry tier and releases its own application: the suite shares one
 * database, and {@link ReleaseTips} remembers per process what it announced.
 */
@QuarkusTest
public class PdDeployHoldTest {

  private static final String OLDER = "2026.1115.93059";
  private static final String NEWER = "2026.1115.193059";
  private static final String STORAGE_UUID = "7d1c2b3a-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String A_DEAD_PROCESS = "00000000-dead-dead-dead-000000000000";
  private static final String QID = "qits-hold-service-rr-12";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject OwedReleaseSweep sweep;
  @Inject PdOwedReleaseRepository owed;
  @Inject PdDeploymentRequestRepository requests;
  @Inject PdSoftwareReleaseSubscriber subscriber;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  // --- the intake: absent is not held, forever --------------------------------------------------

  @Test
  public void aHeldReleaseOffTheBusIsRecordedUnsettledAndQueuesNothing() {
    String environmentId = createEntryTier("hold-bus");
    String eventId = UUID.randomUUID().toString();

    subscriber.onFrame(frame(eventId, "qits/hold-bus-app", NEWER, true, QID));
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "a held release deploys nothing");
    PdDeploymentRequest request = newestRequest("hold-bus-app");
    assertNotNull(request);
    assertEquals(environmentId, request.environmentId);
    assertEquals(PdQualityGate.UNMET, request.qualityGate);
    assertEquals("held for manual approval on " + QID, request.gateDetail);
    assertNull(request.gateSettledAt, "a pending gate, not a refusal");
    assertNull(request.deploymentId);
    assertTrue(request.deployHold);
    assertEquals(QID, request.releaseRequestQualifiedId);
    assertEquals(0, deploymentsOf(environmentId).size(), "and no deployment row");

    PdOwedRelease row = obligation(eventId);
    assertTrue(row.deployHold, "the ledger keeps the hold for a re-drive");
    assertEquals(QID, row.releaseRequestQualifiedId);
    assertEquals(
        ReleaseAcceptance.Outcome.DISCHARGED.name(), row.outcome, "a hold is a decision too");

    JsonPath read = getRequest(request.id);
    assertEquals(true, read.getBoolean("deploymentRequest.deployHold"));
    assertEquals(QID, read.getString("deploymentRequest.releaseRequestQualifiedId"));
    assertNull(read.get("deploymentRequest.gateSettledAt"));
    assertNull(read.get("deploymentRequest.approvedBy"));
    assertNull(read.get("deploymentRequest.approvedAt"));
  }

  @Test
  public void aReleaseWithoutTheKeysOrWithFalseIsNotHeld() {
    String environmentId = createEntryTier("hold-absent");

    subscriber.onFrame(
        frame(UUID.randomUUID().toString(), "qits/hold-absent-app", OLDER, null, null));
    awaitDeployments(environmentId, 1);
    PdDeploymentRequest plain = newestRequest("hold-absent-app");
    assertEquals(PdQualityGate.MET, plain.qualityGate);
    assertFalse(plain.deployHold);
    assertNull(plain.releaseRequestQualifiedId);
    assertNotNull(plain.deploymentId);

    subscriber.onFrame(
        frame(UUID.randomUUID().toString(), "qits/hold-absent-app", NEWER, false, QID));
    awaitDeployments(environmentId, 2);
    PdDeploymentRequest unflagged = newestRequest("hold-absent-app");
    assertEquals(NEWER, unflagged.version);
    assertEquals(PdQualityGate.MET, unflagged.qualityGate);
    assertFalse(unflagged.deployHold);
    assertEquals(QID, unflagged.releaseRequestQualifiedId, "recorded whether or not held");
    assertNotNull(unflagged.deploymentId);
  }

  @Test
  public void theHttpIntakeHoldsTooAndSaysSoWithoutAQualifiedId() {
    createEntryTier("hold-http");

    postRelease("hold-http-app", NEWER, true, null);
    awaitWorkerIdle();

    PdDeploymentRequest request = newestRequest("hold-http-app");
    assertEquals(PdQualityGate.UNMET, request.qualityGate);
    assertEquals("held for manual approval", request.gateDetail);
    assertNull(request.gateSettledAt);
    assertTrue(request.deployHold);
    assertNull(request.releaseRequestQualifiedId);
    assertEquals(List.of(), driver.applied());

    createEntryTier("hold-http-qid");
    postRelease("hold-http-qid-app", NEWER, true, QID);
    awaitWorkerIdle();
    assertEquals(
        "held for manual approval on " + QID, newestRequest("hold-http-qid-app").gateDetail);
  }

  // --- the approval ------------------------------------------------------------------------------

  @Test
  public void anApprovalQueuesTheDeploymentAndTheWorkerRunsIt() {
    String environmentId = createEntryTier("hold-approve");
    postRelease("hold-approve-app", NEWER, true, QID);
    awaitWorkerIdle();
    String id = newestRequest("hold-approve-app").id;

    JsonPath approved =
        given()
            .header("Authorization", SuiteBearers.bearer("approving-agent", "qits:agent"))
            .when()
            .post("/deployments/api/deployment-requests/" + id + "/approve")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals("MET", approved.getString("deploymentRequest.qualityGate"));
    assertEquals("approved by approving-agent", approved.getString("deploymentRequest.gateDetail"));
    assertEquals("approving-agent", approved.getString("deploymentRequest.approvedBy"));
    assertNotNull(approved.getString("deploymentRequest.approvedAt"));
    assertNotNull(approved.getString("deploymentRequest.gateSettledAt"));
    assertEquals(true, approved.getBoolean("deploymentRequest.deployHold"));
    String deploymentId = approved.getString("deploymentRequest.deploymentId");
    assertNotNull(deploymentId, "the approval wrote the deployment and pointed at it");
    assertEquals(deploymentId, approved.getString("deployment.id"));

    awaitApplied(1);
    assertTrue(
        driver.applied().get(0).imageRef().endsWith("/hold-approve-app:" + NEWER),
        driver.applied().get(0).imageRef());
    awaitDeployments(environmentId, 1);
    assertEquals("ACTIVE", deploymentsOf(environmentId).get(0).get("status"));
    assertEquals(deploymentId, deploymentsOf(environmentId).get(0).get("id"));

    // The gate settled, so a second approval is a 409 and deploys nothing more.
    approveAs(SuiteBearers.operator(), id).statusCode(409);
    awaitWorkerIdle();
    assertEquals(1, driver.applied().size());
  }

  @Test
  public void anApprovalAcceptsAnEmptyObjectBody() {
    createEntryTier("hold-body");
    postRelease("hold-body-app", NEWER, true, QID);
    awaitWorkerIdle();
    String id = newestRequest("hold-body-app").id;

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/deployments/api/deployment-requests/" + id + "/approve")
        .then()
        .statusCode(200);
    awaitApplied(1);
  }

  @Test
  public void aRequestThatWasNotHeldCannotBeApproved() {
    String environmentId = createEntryTier("hold-none");
    postRelease("hold-none-app", NEWER, null, null);
    awaitDeployments(environmentId, 1);

    approveAs(SuiteBearers.operator(), newestRequest("hold-none-app").id).statusCode(409);
  }

  @Test
  public void aHeldVersionANewerReleaseOvertookCannotBeApproved() {
    String environmentId = createEntryTier("hold-stale");
    postRelease("hold-stale-app", OLDER, true, QID);
    awaitWorkerIdle();
    String held = newestRequest("hold-stale-app").id;
    postRelease("hold-stale-app", NEWER, null, null);
    awaitDeployments(environmentId, 1);
    driver.reset();

    approveAs(SuiteBearers.operator(), held).statusCode(409);
    awaitWorkerIdle();
    assertEquals(List.of(), driver.applied(), "approving it would have been a rollback");
    assertNull(requestById(held).approvedAt);
  }

  @Test
  public void anUnknownRequestIsA404() {
    approveAs(SuiteBearers.operator(), UUID.randomUUID().toString()).statusCode(404);
  }

  @Test
  public void theDoorAdmitsTheOperatorsAndTheAgentAndNotAServiceOrAnonymous() {
    String unknown = "/deployments/api/deployment-requests/" + UUID.randomUUID() + "/approve";
    for (String role :
        List.of("qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:agent")) {
      given()
          .header("Authorization", SuiteBearers.bearer("hold-roles-" + role, role))
          .when()
          .post(unknown)
          .then()
          .statusCode(404);
    }
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .post(unknown)
        .then()
        .statusCode(403);
    given().when().post(unknown).then().statusCode(401);
  }

  @Test
  public void anApprovedVersionReAnnouncedWithTheHoldIsNotHeldAgain() {
    String environmentId = createEntryTier("hold-again");
    postRelease("hold-again-app", NEWER, true, QID);
    awaitWorkerIdle();
    String id = newestRequest("hold-again-app").id;
    approveAs(SuiteBearers.bearer("hold-approver", "qits:admin"), id).statusCode(200);
    awaitDeployments(environmentId, 1);

    // A rerun of the same version through the manual door, still carrying the release request's
    // flag: the version a person already approved here.
    postRelease("hold-again-app", NEWER, true, QID);
    awaitDeployments(environmentId, 2);

    PdDeploymentRequest rerun = newestRequest("hold-again-app");
    assertEquals(PdQualityGate.MET, rerun.qualityGate);
    assertEquals("approved by hold-approver on the earlier request " + id, rerun.gateDetail);
    assertNotNull(rerun.deploymentId);
    assertNull(rerun.approvedAt, "the approval stays on the request it answered");
  }

  // --- the owed sweep ----------------------------------------------------------------------------

  @Test
  public void aHeldRequestCountsAsAlreadyRequestedAndIsNotReDriven() {
    createEntryTier("hold-owed");
    postRelease("hold-owed-app", NEWER, true, QID);
    awaitWorkerIdle();
    String eventId = orphan("hold-owed-app", NEWER, true, QID);

    assertEquals(0, sweep.sweep(), "nothing was announced again");
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "nor deployed behind the person's back");
    assertEquals(1, requestCount("hold-owed-app", NEWER));
    assertEquals(ReleaseAcceptance.Outcome.ALREADY_REQUESTED.name(), obligation(eventId).outcome);
  }

  @Test
  public void aReDrivenObligationKeepsItsHold() {
    createEntryTier("hold-redrive");
    String eventId = orphan("hold-redrive-app", NEWER, true, QID);

    assertEquals(1, sweep.sweep());
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "the re-driven release is held, not deployed");
    PdDeploymentRequest request = newestRequest("hold-redrive-app");
    assertEquals(PdQualityGate.UNMET, request.qualityGate);
    assertNull(request.gateSettledAt);
    assertTrue(request.deployHold);
    assertEquals(QID, request.releaseRequestQualifiedId);
    assertEquals(ReleaseAcceptance.Outcome.DISCHARGED.name(), obligation(eventId).outcome);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static io.restassured.response.ValidatableResponse approveAs(String bearer, String id) {
    return given()
        .header("Authorization", bearer)
        .when()
        .post("/deployments/api/deployment-requests/" + id + "/approve")
        .then();
  }

  /** A docker {@code SoftwareRelease}; canonical JSON is alphabetical and NON_NULL. */
  private static EventFrame frame(
      String eventId, String packageName, String version, Boolean hold, String qid) {
    return new EventFrame(
        eventId,
        "SoftwareRelease",
        Instant.now(),
        ("{%s\"packageName\":\"%s\",\"packageType\":\"docker\",\"projectId\":\"qits\","
                + "%s\"repoId\":\"%s\",\"repository\":\"%s\",\"version\":\"%s\"}")
            .formatted(
                hold == null ? "" : "\"deployHold\":" + hold + ",",
                packageName,
                qid == null ? "" : "\"releaseRequestQualifiedId\":\"" + qid + "\",",
                STORAGE_UUID,
                STORAGE_UUID,
                version),
        null,
        null,
        null);
  }

  private static void postRelease(String application, String version, Boolean hold, String qid) {
    Map<String, Object> body = new HashMap<>();
    body.put("repoId", STORAGE_UUID);
    body.put("application", application);
    body.put("version", version);
    if (hold != null) {
      body.put("deployHold", hold);
    }
    if (qid != null) {
      body.put("releaseRequestQualifiedId", qid);
    }
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  /** The row a process that died holding an obligation leaves behind. Returns its event id. */
  private String orphan(String applicationName, String version, boolean hold, String qid) {
    String eventId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdOwedRelease row = new PdOwedRelease();
              row.id = UUID.randomUUID().toString();
              row.eventId = eventId;
              row.applicationName = applicationName;
              row.version = version;
              row.packageName = "qits/" + applicationName;
              row.deployHold = hold;
              row.releaseRequestQualifiedId = qid;
              row.repoId = STORAGE_UUID;
              row.acceptedBy = A_DEAD_PROCESS;
              row.acceptedAt = Instant.now();
              row.attempts = 1;
              owed.persist(row);
            });
    return eventId;
  }

  private PdOwedRelease obligation(String eventId) {
    return QuarkusTransaction.requiringNew().call(() -> owed.findByEventId(eventId).orElse(null));
  }

  private static JsonPath getRequest(String id) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  private PdDeploymentRequest requestById(String id) {
    return QuarkusTransaction.requiringNew().call(() -> requests.findById(id));
  }

  private PdDeploymentRequest newestRequest(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                requests.listByApplicationNewestFirst(applicationName).stream()
                    .findFirst()
                    .orElse(null));
  }

  private long requestCount(String applicationName, String version) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                requests.listByApplicationNewestFirst(applicationName).stream()
                    .filter(request -> version.equals(request.version))
                    .count());
  }

  private static String createEntryTier(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private static List<Map<String, Object>> deploymentsOf(String environmentId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments");
  }

  private void awaitApplied(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.applied().size() < count && System.currentTimeMillis() < deadline) {
      sleep();
    }
    assertEquals(count, driver.applied().size(), "applied services");
  }

  private void awaitDeployments(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows = deploymentsOf(environmentId);
      if (rows.size() == count
          && rows.stream()
              .noneMatch(
                  d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")))) {
        awaitWorkerIdle();
        return;
      }
      sleep();
    }
    throw new IllegalStateException(
        "deployments of " + environmentId + " did not settle to " + count);
  }

  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
