package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.bus.PdDeployPublishTest;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdOwedReleaseRepository;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A held release can be DECLINED</b> (qits-2271): {@code POST /deployment-requests/{id}/decline}
 * settles the held request {@code UNMET} with {@code declinedBy}/{@code declinedAt}, settles every
 * other still-held request of the same release request with it, queues nothing, and announces one
 * {@code DeploymentRequestDeclined} per settled row.
 *
 * <p>It runs on {@link PdDeployPublishTest}'s bus-enabled profile, so the announcements land in the
 * outbox where they can be read, and shares that application instance rather than starting another.
 * Every method creates its own entry tier, applications and release request: the suite shares one
 * database.
 */
@QuarkusTest
@TestProfile(PdDeployPublishTest.BusEnabled.class)
public class PdDeployDeclineTest {

  private static final String VERSION = "2026.1116.193059";
  private static final String STORAGE_UUID = "7d1c2b3a-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String A_DEAD_PROCESS = "00000000-dead-dead-dead-000000000000";
  private static final String DECLINED = "DeploymentRequestDeclined";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject OwedReleaseSweep sweep;
  @Inject PdOwedReleaseRepository owed;
  @Inject PdDeploymentRequestRepository requests;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  // --- the decline -------------------------------------------------------------------------------

  @Test
  public void aDeclineSettlesUnmetWithWhoAndWhenAndQueuesNothing() {
    String environmentId = createEntryTier("decline-one");
    String qid = "decline-one-rr-1";
    postRelease("decline-one-app", VERSION, true, qid);
    awaitWorkerIdle();
    String id = newestRequest("decline-one-app").id;

    JsonPath declined =
        given()
            .header("Authorization", SuiteBearers.bearer("declining-agent", "qits:agent"))
            .contentType(ContentType.JSON)
            .body("{\"note\":\"  the change is wrong  \"}")
            .when()
            .post("/deployments/api/deployment-requests/" + id + "/decline")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals("UNMET", declined.getString("deploymentRequest.qualityGate"));
    assertEquals(
        "declined by declining-agent: the change is wrong",
        declined.getString("deploymentRequest.gateDetail"));
    assertEquals("declining-agent", declined.getString("deploymentRequest.declinedBy"));
    assertNotNull(declined.getString("deploymentRequest.declinedAt"));
    assertEquals(
        declined.getString("deploymentRequest.declinedAt"),
        declined.getString("deploymentRequest.gateSettledAt"));
    assertNull(declined.get("deploymentRequest.approvedBy"), "a decline is never an approval");
    assertNull(declined.get("deploymentRequest.approvedAt"));
    assertNull(declined.get("deploymentRequest.deploymentId"));
    assertNull(declined.get("deployment"));

    awaitWorkerIdle();
    assertEquals(List.of(), driver.applied(), "a declined release deploys nothing");
    assertEquals(0, deploymentsOf(environmentId).size(), "and writes no deployment row");

    PdDeploymentRequest row = requestById(id);
    assertEquals(PdQualityGate.UNMET, row.qualityGate);
    assertNotNull(row.gateSettledAt);
    assertEquals("declining-agent", row.declinedBy);
    assertNotNull(row.declinedAt);
    assertNull(row.approvedAt);

    List<OutboxEvent> events = declinedEvents();
    assertEquals(1, events.size());
    String payload = events.get(0).payload;
    assertTrue(payload.contains("\"deploymentRequestId\":\"" + id + "\""), payload);
    assertTrue(payload.contains("\"releaseRequestQualifiedId\":\"" + qid + "\""), payload);
    assertTrue(payload.contains("\"appName\":\"decline-one-app\""), payload);
    assertTrue(payload.contains("\"environment\":\"decline-one\""), payload);
    assertTrue(payload.contains("\"version\":\"" + VERSION + "\""), payload);
    assertTrue(payload.contains("\"declinedBy\":\"declining-agent\""), payload);
    assertTrue(payload.contains("\"note\":\"the change is wrong\""), payload);
    assertTrue(payload.contains("\"declinedAt\":"), payload);
  }

  @Test
  public void anEmptyBodyOrAnEmptyObjectIsADeclineWithoutANote() {
    createEntryTier("decline-body");
    postRelease("decline-body-a", VERSION, true, "decline-body-rr-1");
    postRelease("decline-body-b", VERSION, true, "decline-body-rr-2");
    awaitWorkerIdle();

    String bare =
        declineAs(SuiteBearers.operator(), newestRequest("decline-body-a").id)
            .statusCode(200)
            .extract()
            .path("deploymentRequest.gateDetail");
    assertTrue(bare.startsWith("declined by "), bare);
    assertFalse(bare.contains(":"), bare);

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/deployments/api/deployment-requests/" + newestRequest("decline-body-b").id + "/decline")
        .then()
        .statusCode(200);
    assertFalse(declinedEvents().stream().anyMatch(e -> e.payload.contains("\"note\"")));
  }

  @Test
  public void aBodyThatIsNotANoteIsA400AndDeclinesNothing() {
    createEntryTier("decline-bad");
    postRelease("decline-bad-app", VERSION, true, "decline-bad-rr-1");
    awaitWorkerIdle();
    String id = newestRequest("decline-bad-app").id;

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("[1,2]")
        .when()
        .post("/deployments/api/deployment-requests/" + id + "/decline")
        .then()
        .statusCode(400);
    assertNull(requestById(id).gateSettledAt);
  }

  @Test
  public void aSecondDeclineAndAnApprovalAfterTheDeclineAre409() {
    createEntryTier("decline-twice");
    postRelease("decline-twice-app", VERSION, true, "decline-twice-rr-1");
    awaitWorkerIdle();
    String id = newestRequest("decline-twice-app").id;
    declineAs(SuiteBearers.bearer("first", "qits:desk"), id).statusCode(200);

    declineAs(SuiteBearers.bearer("second", "qits:desk"), id).statusCode(409);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post("/deployments/api/deployment-requests/" + id + "/approve")
        .then()
        .statusCode(409);

    awaitWorkerIdle();
    assertEquals(List.of(), driver.applied());
    PdDeploymentRequest row = requestById(id);
    assertEquals("first", row.declinedBy, "the first answer stands");
    assertNull(row.approvedAt);
    assertEquals(1, declinedEvents().size(), "the refused second decline announced nothing");
  }

  @Test
  public void anUnknownRequestIsA404() {
    declineAs(SuiteBearers.operator(), UUID.randomUUID().toString()).statusCode(404);
  }

  @Test
  public void aRequestThatWasNotHeldCannotBeDeclined() {
    String environmentId = createEntryTier("decline-none");
    postRelease("decline-none-app", VERSION, null, "decline-none-rr-1");
    awaitDeployments(environmentId, 1);

    declineAs(SuiteBearers.operator(), newestRequest("decline-none-app").id).statusCode(409);
    assertEquals(List.of(), declinedEvents());
  }

  @Test
  public void aSupersededHeldRequestCanStillBeDeclined() {
    // No tip check: declining a held request a newer release overtook is how it gets cleared.
    String environmentId = createEntryTier("decline-stale");
    postRelease("decline-stale-app", "2026.1115.93059", true, "decline-stale-rr-1");
    awaitWorkerIdle();
    String held = newestRequest("decline-stale-app").id;
    postRelease("decline-stale-app", VERSION, null, null);
    awaitDeployments(environmentId, 1);

    declineAs(SuiteBearers.operator(), held).statusCode(200);
    assertNotNull(requestById(held).declinedAt);
  }

  @Test
  public void aReleaseRequestWithAnApprovedSiblingCannotBeDeclined() {
    String environmentId = createEntryTier("decline-approved");
    String qid = "decline-approved-rr-1";
    postRelease("decline-approved-a", VERSION, true, qid);
    postRelease("decline-approved-b", VERSION, true, qid);
    awaitWorkerIdle();
    String a = newestRequest("decline-approved-a").id;
    String b = newestRequest("decline-approved-b").id;
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post("/deployments/api/deployment-requests/" + a + "/approve")
        .then()
        .statusCode(200);
    awaitDeployments(environmentId, 1);
    reset();

    declineAs(SuiteBearers.operator(), b).statusCode(409);

    PdDeploymentRequest still = requestById(b);
    assertNull(still.gateSettledAt, "the refused decline wrote nothing");
    assertNull(still.declinedAt);
    assertEquals(List.of(), declinedEvents());
  }

  @Test
  public void heldSiblingsOfTheReleaseRequestAreSettledTooAndEachIsAnnounced() {
    createEntryTier("decline-siblings");
    String qid = "decline-siblings-rr-1";
    postRelease("decline-siblings-a", VERSION, true, qid);
    postRelease("decline-siblings-b", VERSION, true, qid);
    postRelease("decline-siblings-c", VERSION, true, qid);
    // Another release request's held row is left alone.
    postRelease("decline-siblings-other", VERSION, true, "decline-siblings-rr-2");
    awaitWorkerIdle();
    String a = newestRequest("decline-siblings-a").id;
    String b = newestRequest("decline-siblings-b").id;
    String c = newestRequest("decline-siblings-c").id;
    String other = newestRequest("decline-siblings-other").id;

    declineAs(SuiteBearers.bearer("sibling-decliner", "qits:admin"), b).statusCode(200);

    for (String id : List.of(a, b, c)) {
      PdDeploymentRequest row = requestById(id);
      assertEquals(PdQualityGate.UNMET, row.qualityGate, id);
      assertNotNull(row.gateSettledAt, id);
      assertEquals("sibling-decliner", row.declinedBy, id);
      assertEquals("declined by sibling-decliner", row.gateDetail, id);
      assertNull(row.deploymentId, id);
    }
    assertEquals(requestById(a).declinedAt, requestById(b).declinedAt, "one transaction, one time");
    PdDeploymentRequest untouched = requestById(other);
    assertNull(untouched.gateSettledAt);
    assertNull(untouched.declinedAt);

    List<OutboxEvent> events = declinedEvents();
    assertEquals(3, events.size(), "one announcement per settled row");
    for (String id : List.of(a, b, c)) {
      assertEquals(
          1,
          events.stream()
              .filter(e -> e.payload.contains("\"deploymentRequestId\":\"" + id + "\""))
              .count(),
          id);
    }
    awaitWorkerIdle();
    assertEquals(List.of(), driver.applied());
  }

  @Test
  public void aDeclinedVersionAnnouncedAgainIsHeldAgainAndNeverDeployed() {
    String environmentId = createEntryTier("decline-again");
    String qid = "decline-again-rr-1";
    postRelease("decline-again-app", VERSION, true, qid);
    awaitWorkerIdle();
    String declined = newestRequest("decline-again-app").id;
    declineAs(SuiteBearers.operator(), declined).statusCode(200);

    // A rerun of the same version, still carrying the release request's flag: a decline is not an
    // approval, so findApproved finds nothing and the gate holds it again.
    postRelease("decline-again-app", VERSION, true, qid);
    awaitWorkerIdle();

    PdDeploymentRequest rerun = newestRequest("decline-again-app");
    assertFalse(declined.equals(rerun.id), "a new request for the re-announcement");
    assertEquals(PdQualityGate.UNMET, rerun.qualityGate);
    assertNull(rerun.gateSettledAt, "held, not MET");
    assertNull(rerun.deploymentId);
    assertNotNull(requestById(declined).declinedAt, "the decline stays where it was");
    assertEquals(List.of(), driver.applied());
    assertEquals(0, deploymentsOf(environmentId).size());
  }

  @Test
  public void theDoorAdmitsTheOperatorsAndTheAgentAndNotAServiceOrAnonymous() {
    String unknown = "/deployments/api/deployment-requests/" + UUID.randomUUID() + "/decline";
    for (String role :
        List.of("qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:agent")) {
      given()
          .header("Authorization", SuiteBearers.bearer("decline-roles-" + role, role))
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

  // --- the owed sweep ----------------------------------------------------------------------------

  @Test
  public void aDeclinedRequestStillCountsAsDecidedAndIsNotReDriven() {
    createEntryTier("decline-owed");
    String qid = "decline-owed-rr-1";
    postRelease("decline-owed-app", VERSION, true, qid);
    awaitWorkerIdle();
    declineAs(SuiteBearers.operator(), newestRequest("decline-owed-app").id).statusCode(200);
    String eventId = orphan("decline-owed-app", VERSION, qid);

    assertEquals(0, sweep.sweep(), "nothing was announced again");
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "nor deployed behind the person's back");
    assertEquals(1, requestCount("decline-owed-app"));
    assertEquals(
        ReleaseAcceptance.Outcome.ALREADY_REQUESTED.name(),
        QuarkusTransaction.requiringNew()
            .call(() -> owed.findByEventId(eventId).orElseThrow().outcome));
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static ValidatableResponse declineAs(String bearer, String id) {
    return given()
        .header("Authorization", bearer)
        .when()
        .post("/deployments/api/deployment-requests/" + id + "/decline")
        .then();
  }

  private List<OutboxEvent> declinedEvents() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery(
                        "select o from OutboxEvent o where o.name = :name", OutboxEvent.class)
                    .setParameter("name", DECLINED)
                    .getResultList());
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
  private String orphan(String applicationName, String version, String qid) {
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
              row.deployHold = true;
              row.releaseRequestQualifiedId = qid;
              row.repoId = STORAGE_UUID;
              row.acceptedBy = A_DEAD_PROCESS;
              row.acceptedAt = Instant.now();
              row.attempts = 1;
              owed.persist(row);
            });
    return eventId;
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

  private long requestCount(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(() -> (long) requests.listByApplicationNewestFirst(applicationName).size());
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
