package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.bus.PdDeployPublishTest;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.deployments.persistence.PdDatabaseMoveRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery desk's rollback (qits-2779): {@code POST /deployments/api/applications/{id}/rollback}
 * puts one application in one tier back on an earlier version that served there — that application
 * alone, start-first only, on the coordinates its own deployment request recorded — and refuses
 * everything else on the request thread.
 *
 * <p>The {@link PdApplicationStartTest} shape: the door answers 202 and the work runs on the deploy
 * worker, so every assertion about the new row waits on {@link DeployService#awaitIdle()}. It runs on
 * {@link PdDeployPublishTest}'s bus-enabled profile, as {@code PdDeployDeclineTest} does, so the
 * {@code ApplicationRollbackRequested} it announces lands in the outbox where it can be read.
 */
@QuarkusTest
@TestProfile(PdDeployPublishTest.BusEnabled.class)
class PdApplicationRollbackTest {

  private static final String APPLICATIONS = "/deployments/api/applications/";
  private static final String V1 = "2026.101.120000";
  private static final String V2 = "2026.102.120000";
  private static final String V3 = "2026.103.120000";
  private static final String ROLLBACK = "ApplicationRollbackRequested";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idp;
  @Inject DeployService deployService;
  @Inject OwedReleaseSweep owedSweep;
  @Inject ReleaseTips tips;
  @Inject PdDeploymentRepository deploymentRows;
  @Inject PdDeploymentRequestRepository requestRows;
  @Inject PdDatabaseMoveRepository moveRows;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    idp.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  // --- set-up --------------------------------------------------------------------------------------

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
        .body(Map.of("runId", "run-rollback", "repoId", application, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    deployService.awaitIdle();
  }

  /** A place: {@code [environment, environmentId, application]}. */
  private record Place(String environment, String environmentId, String application) {
    String id() {
      return environmentId + ":" + application;
    }
  }

  /** An application that served {@link #V1} and serves {@link #V2} now, in a tier of its own. */
  private Place servingTwo(String prefix) throws Exception {
    Place place = fresh(prefix);
    release(place.application(), V1);
    release(place.application(), V2);
    List<PdDeployment> rows = rows(place);
    assertEquals(PdDeploymentStatus.ACTIVE, rows.get(0).status, rows.get(0).detail);
    assertEquals(V2, rows.get(0).version);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rows.get(1).status);
    return place;
  }

  private Place fresh(String prefix) {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = prefix + "-" + token;
    return new Place(environment, createDesignatedEnvironment(environment), prefix + "-app-" + token);
  }

  private List<PdDeployment> rows(Place place) {
    return rows(place.application(), place.environmentId());
  }

  private List<PdDeployment> rows(String application, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deploymentRows.listForPlaceNewestFirst(application, environmentId));
  }

  private static ValidatableResponse rollback(String bearer, String applicationId, Object body) {
    return given()
        .header("Authorization", bearer)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(APPLICATIONS + applicationId + "/rollback")
        .then();
  }

  private static ValidatableResponse rollback(String applicationId, String version) {
    return rollback(
        SuiteBearers.operator(), applicationId, Map.of("version", version, "reason", "v2 answers 500"));
  }

  private List<OutboxEvent> outboxed(String name) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery(
                        "select o from OutboxEvent o where o.name = :name order by o.createdAt",
                        OutboxEvent.class)
                    .setParameter("name", name)
                    .getResultList());
  }

  // --- the happy path ------------------------------------------------------------------------------

  @Test
  void aRollbackPutsTheEarlierServedVersionBackAndAnnouncesTheDecision() throws Exception {
    Place place = servingTwo("rb-ok");
    String serving = rows(place).get(0).id;
    int applied = driver.applied().size();

    JsonPath accepted = rollback(place.id(), V1).statusCode(202).extract().jsonPath();
    assertEquals(place.id(), accepted.getString("applicationId"));
    assertEquals(place.application(), accepted.getString("applicationName"));
    assertEquals(place.environmentId(), accepted.getString("environmentId"));
    assertEquals(V2, accepted.getString("fromVersion"));
    assertEquals(V1, accepted.getString("toVersion"));
    assertEquals(serving, accepted.getString("deploymentId"), "the ACTIVE row it replaces");
    deployService.awaitIdle();

    List<PdDeployment> rows = rows(place);
    PdDeployment rolledBack = rows.get(0);
    assertEquals(PdDeploymentStatus.ACTIVE, rolledBack.status, rolledBack.detail);
    assertEquals(V1, rolledBack.version);
    assertNotEquals(serving, rolledBack.id, "a deployment of its own");
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rows.get(1).status, "V2 was cut over");
    assertEquals(applied + 1, driver.applied().size(), "exactly one application moved");
    assertEquals(V1, driver.applied().get(driver.applied().size() - 1).version());

    // The decision, announced once, and the new row's lifecycle hangs off it.
    List<OutboxEvent> announced = outboxed(ROLLBACK);
    assertEquals(1, announced.size());
    String payload = announced.get(0).payload;
    for (String field :
        List.of(
            "\"applicationName\":\"" + place.application() + "\"",
            "\"environmentId\":\"" + place.environmentId() + "\"",
            "\"environmentName\":\"" + place.environment() + "\"",
            "\"fromVersion\":\"" + V2 + "\"",
            "\"toVersion\":\"" + V1 + "\"",
            "\"reason\":\"v2 answers 500\"",
            "\"deploymentId\":\"" + serving + "\"")) {
      assertTrue(payload.contains(field), payload);
    }
    assertTrue(payload.contains("\"requestedBy\":"), payload);
    assertEquals(announced.get(0).id, String.valueOf(rolledBack.causationId));
    assertTrue(
        outboxed("DeploymentQueued").stream()
            .anyMatch(
                queued ->
                    queued.payload.contains(rolledBack.id)
                        && announced.get(0).id.equals(queued.parentId)),
        "DeploymentQueued of the new row names the rollback as its cause");

    // Rolling forward again is the same rule: V2 served there.
    JsonPath forward = rollback(place.id(), V2).statusCode(202).extract().jsonPath();
    assertEquals(V1, forward.getString("fromVersion"));
    assertEquals(V2, forward.getString("toVersion"));
    deployService.awaitIdle();
    assertEquals(V2, rows(place).get(0).version);
    assertEquals(PdDeploymentStatus.ACTIVE, rows(place).get(0).status);
  }

  @Test
  void theRolledBackVersionStaysThroughObservationsSweepsAndAStaleCatchUp() throws Exception {
    Place place = servingTwo("rb-stay");
    rollback(place.id(), V1).statusCode(202);
    deployService.awaitIdle();
    int applied = driver.applied().size();

    deployService.enqueueObservation();
    owedSweep.sweep();
    deployService.awaitIdle();
    // The cross-restart floor is the highest version ever requested, not the newest request row:
    // the rollback's V1 request must not let a replay of V2 past the collapse.
    assertFalse(tips.claim(place.application(), V2), "a stale V2 is still below the floor");

    PdDeployment newest = rows(place).get(0);
    assertEquals(V1, newest.version);
    assertEquals(PdDeploymentStatus.ACTIVE, newest.status, newest.detail);
    assertEquals(applied, driver.applied().size(), "nothing converged the place forward");
  }

  @Test
  void aRollbackWhoseImageIsGoneEndsImageMissingAndTheServingDeploymentStaysActive()
      throws Exception {
    Place place = servingTwo("rb-img");
    String serving = rows(place).get(0).id;
    driver.scriptPull(
        new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.IMAGE_MISSING, "no such tag"));

    rollback(place.id(), V1).statusCode(202);
    deployService.awaitIdle();

    List<PdDeployment> rows = rows(place);
    assertEquals(PdDeploymentStatus.IMAGE_MISSING, rows.get(0).status, rows.get(0).detail);
    assertEquals(V1, rows.get(0).version);
    PdDeployment stillServing = rows.stream().filter(row -> row.id.equals(serving)).findFirst().orElseThrow();
    assertEquals(PdDeploymentStatus.ACTIVE, stillServing.status, "start-first: V2 never stopped");
  }

  @Test
  void aVariantIsRolledBackAloneAndTheMainAloneWithoutItsVariants() throws Exception {
    Place place = fresh("rb-var");
    String main = place.application();
    String variant = main + "-worker";
    specs.script(main, DeploymentSpecParser.parse("variants: worker\n", "deployments.yml of " + main));
    specs.scriptAt(
        main,
        SpecSource.variantPath("worker"),
        DeploymentSpecParser.parse("application: " + variant + "\n", "deployments.worker.yml of " + main));
    release(main, V1);
    release(main, V2);
    assertEquals(V2, rows(main, place.environmentId()).get(0).version);
    assertEquals(V2, rows(variant, place.environmentId()).get(0).version);
    assertEquals(PdDeploymentStatus.ACTIVE, rows(variant, place.environmentId()).get(0).status);

    int applied = driver.applied().size();
    JsonPath accepted =
        rollback(place.environmentId() + ":" + variant, V1).statusCode(202).extract().jsonPath();
    assertEquals(variant, accepted.getString("applicationName"));
    deployService.awaitIdle();
    assertEquals(applied + 1, driver.applied().size(), "the variant alone");
    assertEquals(variant, driver.applied().get(driver.applied().size() - 1).applicationName());
    assertEquals(V1, rows(variant, place.environmentId()).get(0).version);
    assertEquals(PdDeploymentStatus.ACTIVE, rows(variant, place.environmentId()).get(0).status);
    assertEquals(V2, rows(main, place.environmentId()).get(0).version, "the main did not move");

    applied = driver.applied().size();
    rollback(place.environmentId() + ":" + main, V1).statusCode(202);
    deployService.awaitIdle();
    assertEquals(applied + 1, driver.applied().size(), "the main alone, without its variant");
    assertEquals(main, driver.applied().get(driver.applied().size() - 1).applicationName());
    assertEquals(V1, rows(main, place.environmentId()).get(0).version);
    assertEquals(PdDeploymentStatus.ACTIVE, rows(main, place.environmentId()).get(0).status);
    assertEquals(V1, rows(variant, place.environmentId()).get(0).version, "the variant stayed put");
    assertEquals(3, rows(variant, place.environmentId()).size(), "no variant row was written");
  }

  // --- who may ---------------------------------------------------------------------------------------

  @Test
  void theRollbackTakesTheOperatorRolesAndRefusesAnAgentAndAMachine() throws Exception {
    Place place = servingTwo("rb-role");
    Map<String, String> body = Map.of("version", V1, "reason", "roles");
    rollback(SuiteBearers.bearer("rollback-agent", "qits:agent"), place.id(), body).statusCode(403);
    rollback(SuiteBearers.platform(), place.id(), body).statusCode(403);
    String to = V1;
    for (String role : List.of("qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk")) {
      rollback(SuiteBearers.bearer("rollback-" + role, role), place.id(), Map.of("version", to, "reason", role))
          .statusCode(202);
      deployService.awaitIdle();
      assertEquals(to, rows(place).get(0).version);
      to = to.equals(V1) ? V2 : V1;
    }
  }

  // --- what it refuses -------------------------------------------------------------------------------

  @Test
  void theBodyIsCheckedBeforeAnythingIsRead() throws Exception {
    Place place = servingTwo("rb-body");
    rollback(SuiteBearers.operator(), "not-an-id", Map.of("version", V1, "reason", "x")).statusCode(400);
    rollback(SuiteBearers.operator(), place.id(), Map.of("reason", "no version")).statusCode(400);
    rollback(SuiteBearers.operator(), place.id(), Map.of("version", "  ", "reason", "x")).statusCode(400);
    rollback(SuiteBearers.operator(), place.id(), Map.of("version", V1)).statusCode(400);
    rollback(SuiteBearers.operator(), place.id(), Map.of("version", V1, "reason", " ")).statusCode(400);
    String tooLong =
        rollback(SuiteBearers.operator(), place.id(), Map.of("version", V1, "reason", "x".repeat(501)))
            .statusCode(400)
            .extract()
            .asString();
    assertTrue(tooLong.contains("500"), tooLong);
    rollback(SuiteBearers.operator(), place.id(), Map.of("version", V1, "reason", "x".repeat(500)))
        .statusCode(202);
    deployService.awaitIdle();
  }

  @Test
  void theRollbackAnswersWhatItCannotRollBack() throws Exception {
    Place place = servingTwo("rb-n");
    rollback(place.environmentId() + ":rb-nobody-" + UUID.randomUUID().toString().substring(0, 8), V1)
        .statusCode(404);
    String self = rollback(place.environmentId() + ":qits-deployments", V1).statusCode(409).extract().asString();
    assertTrue(self.contains("this deployer"), self);

    // No ACTIVE deployment: its serving one failed afterwards.
    QuarkusTransaction.requiringNew()
        .run(() -> deploymentRows.findById(rows(place).get(0).id).status = PdDeploymentStatus.FAILED);
    String noActive = rollback(place.id(), V1).statusCode(409).extract().asString();
    assertTrue(noActive.contains("no ACTIVE deployment"), noActive);
    assertTrue(outboxed(ROLLBACK).isEmpty(), "a refusal announces nothing");
  }

  @Test
  void onlyAVersionThatServedHereAndIsNotServingNowIsAccepted() throws Exception {
    Place place = servingTwo("rb-v");
    // V3 was attempted and never went live.
    driver.scriptPull(
        new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.IMAGE_MISSING, "no such tag"));
    release(place.application(), V3);
    driver.scriptPull(new DeploymentDriver.PullResult(DeploymentDriver.PullOutcome.OK, null));
    assertEquals(PdDeploymentStatus.IMAGE_MISSING, rows(place).get(0).status);

    String never = rollback(place.id(), V3).statusCode(409).extract().asString();
    assertTrue(never.contains(V3 + " never served") && never.contains(V1), never);
    assertFalse(never.contains(V2 + ","), "the serving version is not offered: " + never);
    String unknown = rollback(place.id(), "2026.999.1").statusCode(409).extract().asString();
    assertTrue(unknown.contains("the versions that served there are " + V1), unknown);
    String now = rollback(place.id(), V2).statusCode(409).extract().asString();
    assertTrue(now.contains("serving") && now.contains("now"), now);

    // One release only: nothing else ever served there.
    Place once = fresh("rb-once");
    release(once.application(), V1);
    String alone = rollback(once.id(), "2026.1.1").statusCode(409).extract().asString();
    assertTrue(alone.contains("no other version ever served there"), alone);
  }

  @Test
  void aStopFirstOrUndeclaredApplicationIsRolledBackByAPerson() throws Exception {
    Place place = servingTwo("rb-sf");
    specs.script(
        place.application(),
        DeploymentSpecParser.parse("update_order: stop-first\n", "deployments.yml of " + place.application()));
    String stopFirst = rollback(place.id(), V1).statusCode(409).extract().asString();
    assertTrue(
        stopFirst.contains(
            place.application()
                + " is stop-first at "
                + V1
                + ": a rollback would stop it before its successor starts; roll it back by a person"),
        stopFirst);

    specs.scriptNoSpec(place.application());
    String undeclared = rollback(place.id(), V1).statusCode(409).extract().asString();
    assertTrue(undeclared.contains("declares no .config/qits/deployments.yml"), undeclared);

    specs.scriptFailure(place.application(), "the git host answered 500");
    rollback(place.id(), V1).statusCode(502);
    deployService.awaitIdle();
    assertEquals(V2, rows(place).get(0).version, "nothing was queued");
  }

  @Test
  void aVersionNoRequestIsOnRecordForIsRefused() throws Exception {
    Place place = servingTwo("rb-req");
    String v1Row = rows(place).get(1).id;
    QuarkusTransaction.requiringNew().run(() -> requestRows.delete("deploymentId = ?1", v1Row));
    String answer = rollback(place.id(), V1).statusCode(409).extract().asString();
    assertTrue(answer.contains("no release request is on record"), answer);
  }

  @Test
  void theRollbackWaitsForADeploymentInFlightAndForADatabaseMove() throws Exception {
    Place place = servingTwo("rb-q");
    String starting = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = starting;
              row.applicationName = place.application();
              row.environmentId = place.environmentId();
              row.version = V3;
              row.status = PdDeploymentStatus.STARTING;
              row.createdAt = Instant.now();
              deploymentRows.persist(row);
            });
    try {
      String answer = rollback(place.id(), V1).statusCode(409).extract().asString();
      assertTrue(answer.contains(starting) && answer.contains("STARTING"), answer);
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> deploymentRows.findById(starting).status = PdDeploymentStatus.FAILED);
    }

    String move = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = move;
              row.batchId = move;
              row.applicationName = place.application();
              row.environmentName = place.environment();
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = "qits_rb_q_source";
              row.targetServer = "private";
              row.targetDatabase = "qits_rb_q_source";
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = PdDatabaseMoveState.SWITCHED;
              row.createdAt = Instant.now();
              moveRows.persist(row);
            });
    try {
      String answer = rollback(place.id(), V1).statusCode(409).extract().asString();
      assertTrue(answer.contains(move) && answer.contains("SWITCHED"), answer);
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> moveRows.findById(move).state = PdDatabaseMoveState.REFUSED);
    }
    rollback(place.id(), V1).statusCode(202);
    deployService.awaitIdle();
    assertEquals(V1, rows(place).get(0).version);
  }

  // --- the narrow redeploy, on the worker -------------------------------------------------------------

  @Test
  void theNarrowRedeployRefusesAnUndeclaredTagRatherThanDeployingDefaults() throws Exception {
    Place place = servingTwo("rb-one");
    specs.scriptNoSpec(place.application());
    int applied = driver.applied().size();
    deployService.enqueueOperation(
        "test",
        () ->
            deployService.redeployOne(
                new RepositoryRef(place.application(), null, null),
                place.application(),
                place.application(),
                place.environmentId(),
                V1,
                null,
                null,
                null));
    deployService.awaitIdle();
    assertEquals(applied, driver.applied().size(), "nothing deployed");
    assertEquals(V2, rows(place).get(0).version);
    PdDeploymentRequest refused =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requestRows
                        .find(
                            "applicationName = ?1 and environmentId = ?2 order by seq desc",
                            place.application(),
                            place.environmentId())
                        .firstResult());
    assertEquals(PdQualityGate.UNMET, refused.qualityGate);
    assertEquals(V1, refused.version);
    assertTrue(refused.gateDetail.contains("carries no .config/qits/deployments.yml"), refused.gateDetail);
    assertEquals(null, refused.deploymentId);
  }
}
