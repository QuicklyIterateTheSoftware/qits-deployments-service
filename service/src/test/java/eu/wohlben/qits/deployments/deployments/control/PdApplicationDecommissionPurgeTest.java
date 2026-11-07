package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDecommission;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.deployments.deployments.persistence.PdDecommissionRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdOwedReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The decommission's second half (qits-1941): once the relabel has committed, the application's
 * published artifacts are purged — one call, with the application name and the repositories the
 * body and the release ledger name — and every run is a {@code pd_decommission} row.
 *
 * <p>{@link PdApplicationRetirementTest}'s arrangement: rows written straight to the tables, and
 * the purge is {@link FakeArtifactPurge}. A tier row is not needed — the door reads deployment rows,
 * which carry no FK to one.
 */
@QuarkusTest
public class PdApplicationDecommissionPurgeTest {

  private static final String APPLICATIONS = "/deployments/api/applications/";
  private static final String TIER = "env-purge-tier";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PdDeploymentRepository deployments;
  @Inject PdOwedReleaseRepository owedReleases;
  @Inject PdDecommissionRepository decommissions;
  @Inject FakeArtifactPurge artifacts;

  @BeforeEach
  void reset() {
    artifacts.reset();
  }

  private String failedRow(String applicationName) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = TIER;
              row.commitSha = "e".repeat(40);
              row.status = PdDeploymentStatus.FAILED;
              row.detail = "boom";
              row.createdAt = Instant.now();
              row.finishedAt = Instant.now();
              deployments.persist(row);
            });
    return id;
  }

  private void accepted(String packageName, String repoName) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdOwedRelease row = new PdOwedRelease();
              row.id = UUID.randomUUID().toString();
              row.eventId = UUID.randomUUID().toString();
              row.applicationName = PackageNames.applicationOf(packageName);
              row.version = "2026.1107.1";
              row.packageName = packageName;
              row.repoName = repoName;
              row.acceptedAt = Instant.now();
              row.attempts = 1;
              row.settledAt = Instant.now();
              row.outcome = "DISCHARGED";
              owedReleases.persist(row);
            });
  }

  private List<PdDecommission> runsOf(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(() -> decommissions.listByApplication(applicationName));
  }

  private PdDeploymentStatus statusOf(String deploymentId) {
    return QuarkusTransaction.requiringNew().call(() -> deployments.findById(deploymentId).status);
  }

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  private static final ArtifactPurge.RemovedArtifact IMAGE =
      new ArtifactPurge.RemovedArtifact("oci", "oci", "qits/purge-a", List.of("2026.1.0", "2026.2.0"));

  @Test
  public void aDecommissionPurgesOnceWithTheNameAndEveryRepositoryAndRecordsWhatWent()
      throws Exception {
    failedRow("purge-a");
    // The ledger's repositories: two releases from one, one from another, one row with no name, and
    // a different package's that must not leak in.
    accepted("qits/purge-a", "purge-a-service");
    accepted("qits/purge-a", "purge-a-service");
    accepted("qits/purge-a", "purge-a-legacy");
    accepted("qits/purge-a", null);
    accepted("qits/purge-other", "purge-other-service");
    artifacts.publish("purge-a", List.of(IMAGE));

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"repositories\":[\"purge-a-docs\",\"purge-a-service\"]}")
        .when()
        .post(APPLICATIONS + TIER + ":purge-a/decommission")
        .then()
        .statusCode(200)
        .body("applicationName", Matchers.equalTo("purge-a"))
        .body("removedArtifacts.type", Matchers.contains("oci"))
        .body("removedArtifacts[0].repository", Matchers.equalTo("oci"))
        .body("removedArtifacts[0].name", Matchers.equalTo("qits/purge-a"))
        .body("removedArtifacts[0].versions", Matchers.contains("2026.1.0", "2026.2.0"));

    // Once, with the NAME rather than the <env>:<name> key, and the union deduplicated and sorted.
    assertEquals(
        List.of(
            new FakeArtifactPurge.Call(
                "purge-a", List.of("purge-a-docs", "purge-a-legacy", "purge-a-service"))),
        artifacts.callsFor("purge-a"));

    List<PdDecommission> runs = runsOf("purge-a");
    assertEquals(1, runs.size());
    PdDecommission run = runs.get(0);
    assertEquals(PdDecommission.PurgeOutcome.PURGED, run.purgeOutcome);
    assertEquals(TIER, run.environmentId);
    assertEquals(SuiteBearers.OPERATOR, run.actor);
    assertEquals(
        json("[\"purge-a-docs\",\"purge-a-legacy\",\"purge-a-service\"]"), json(run.repositories));
    assertEquals(
        json(
            "[{\"type\":\"oci\",\"repository\":\"oci\",\"name\":\"qits/purge-a\","
                + "\"versions\":[\"2026.1.0\",\"2026.2.0\"]}]"),
        json(run.removed));
    assertNull(run.failureDetail);
  }

  @Test
  public void aSecondDecommissionPurgesAgainFindsNothingAndRecordsASecondRun() throws Exception {
    failedRow("purge-twice");
    artifacts.publish("purge-twice", List.of(IMAGE));

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":purge-twice/decommission")
        .then()
        .statusCode(200)
        .body("removedArtifacts", Matchers.hasSize(1));
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":purge-twice/decommission")
        .then()
        .statusCode(200)
        .body("removedArtifacts", Matchers.empty());

    assertEquals(2, artifacts.callsFor("purge-twice").size());
    List<PdDecommission> runs = runsOf("purge-twice");
    assertEquals(2, runs.size(), "one row per run, never an update");
    assertEquals(PdDecommission.PurgeOutcome.PURGED, runs.get(1).purgeOutcome);
    assertEquals(json("[]"), json(runs.get(1).removed), "found nothing, which is not a failure");
  }

  @Test
  public void anEmptyPostStaysValidAndNamesTheLedgersRepositoriesAlone() {
    failedRow("purge-bare");
    accepted("qits/purge-bare", "purge-bare-service");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":purge-bare/decommission")
        .then()
        .statusCode(200)
        .body("removedArtifacts", Matchers.empty());

    assertEquals(
        List.of(new FakeArtifactPurge.Call("purge-bare", List.of("purge-bare-service"))),
        artifacts.callsFor("purge-bare"));
  }

  @Test
  public void aFailedPurgeIsA502OverACommittedRelabelAndCallingAgainRetries() throws Exception {
    String id = failedRow("purge-down");
    artifacts.failWith("purge-down", "qits-artifacts answered 500 to POST https://registry/x");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":purge-down/decommission")
        .then()
        .statusCode(502)
        .body("message", Matchers.containsString("is decommissioned"))
        .body("message", Matchers.containsString("qits-artifacts answered 500"));

    assertEquals(PdDeploymentStatus.DECOMMISSIONED, statusOf(id), "the relabel stays committed");
    List<PdDecommission> runs = runsOf("purge-down");
    assertEquals(1, runs.size());
    assertEquals(PdDecommission.PurgeOutcome.FAILED, runs.get(0).purgeOutcome);
    assertNull(runs.get(0).removed, "a failed purge reports no list");
    assertTrue(runs.get(0).failureDetail.contains("answered 500"), runs.get(0).failureDetail);

    // Both halves are idempotent, so the retry is the same door.
    artifacts.clearFailure("purge-down");
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":purge-down/decommission")
        .then()
        .statusCode(200);
    runs = runsOf("purge-down");
    assertEquals(2, runs.size());
    assertEquals(PdDecommission.PurgeOutcome.PURGED, runs.get(1).purgeOutcome);
  }

  @Test
  public void aRefusalPurgesNothingAndRecordsNothing() {
    // 409: still deployed.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = UUID.randomUUID().toString();
              row.applicationName = "purge-serving";
              row.environmentId = TIER;
              row.status = PdDeploymentStatus.ACTIVE;
              row.createdAt = Instant.now();
              deployments.persist(row);
            });
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":purge-serving/decommission")
        .then()
        .statusCode(409);
    // 404: nothing ever deployed.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":purge-never-deployed/decommission")
        .then()
        .statusCode(404);
    // 400: not an application id.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + "purge-not-an-id/decommission")
        .then()
        .statusCode(400);

    assertEquals(List.of(), artifacts.callsFor("purge-serving"));
    assertEquals(List.of(), artifacts.callsFor("purge-never-deployed"));
    assertEquals(List.of(), runsOf("purge-serving"));
    assertEquals(List.of(), runsOf("purge-never-deployed"));
  }

  @Test
  public void aBadRepositoryNameIsA400ThatRelabelsNothing() {
    String id = failedRow("purge-bad-body");

    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{\"repositories\":[\"../etc\"]}")
        .when()
        .post(APPLICATIONS + TIER + ":purge-bad-body/decommission")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("repository name"));

    assertEquals(PdDeploymentStatus.FAILED, statusOf(id), "refused before the relabel");
    assertEquals(List.of(), artifacts.callsFor("purge-bad-body"));
  }
}
