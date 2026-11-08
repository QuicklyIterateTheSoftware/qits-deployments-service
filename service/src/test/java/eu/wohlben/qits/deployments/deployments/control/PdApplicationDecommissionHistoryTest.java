package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The decommission door's history, read back (qits-1956): {@code GET
 * /applications/{applicationId}/decommissions} lists the {@code pd_decommission} rows {@link
 * PdApplicationDecommissionPurgeTest} proves are written — oldest first, per place, with the
 * FAILED/PURGED distinction in {@code removed} kept on the wire.
 *
 * <p>The same arrangement: rows written straight to {@code pd_deployment}, the purge is {@link
 * FakeArtifactPurge}, and the runs are produced by the real door rather than inserted, so what is
 * read is what the door wrote.
 */
@QuarkusTest
public class PdApplicationDecommissionHistoryTest {

  private static final String APPLICATIONS = "/deployments/api/applications/";
  private static final String TIER = "env-history-tier";
  private static final String OTHER_TIER = "env-history-other-tier";

  @Inject PdDeploymentRepository deployments;
  @Inject FakeArtifactPurge artifacts;

  @BeforeEach
  void reset() {
    artifacts.reset();
  }

  private void failedRow(String applicationName, String environmentId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = UUID.randomUUID().toString();
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.commitSha = "f".repeat(40);
              row.status = PdDeploymentStatus.FAILED;
              row.detail = "boom";
              row.createdAt = Instant.now();
              row.finishedAt = Instant.now();
              deployments.persist(row);
            });
  }

  private static void decommission(String applicationId, int status) {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + applicationId + "/decommission")
        .then()
        .statusCode(status);
  }

  private static final ArtifactPurge.RemovedArtifact IMAGE =
      new ArtifactPurge.RemovedArtifact(
          "oci", "oci", "qits/history-a", List.of("2026.1.0", "2026.2.0"));

  @Test
  public void aFailedPurgeAndItsRetryReadBackAsTwoRunsOldestFirst() {
    failedRow("history-a", TIER);
    artifacts.failWith("history-a", "qits-artifacts answered 500 to POST https://registry/x");
    decommission(TIER + ":history-a", 502);
    artifacts.clearFailure("history-a");
    artifacts.publish("history-a", List.of(IMAGE));
    decommission(TIER + ":history-a", 200);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(APPLICATIONS + TIER + ":history-a/decommissions")
        .then()
        .statusCode(200)
        .body("decommissions", Matchers.hasSize(2))
        .body("decommissions.purgeOutcome", Matchers.contains("FAILED", "PURGED"))
        .body("decommissions.applicationName", Matchers.everyItem(Matchers.equalTo("history-a")))
        .body("decommissions.environmentId", Matchers.everyItem(Matchers.equalTo(TIER)))
        .body("decommissions.actor", Matchers.everyItem(Matchers.equalTo(SuiteBearers.OPERATOR)))
        .body("decommissions.id", Matchers.everyItem(Matchers.notNullValue()))
        .body("decommissions.decommissionedAt", Matchers.everyItem(Matchers.notNullValue()))
        .body("decommissions.repositories", Matchers.everyItem(Matchers.empty()))
        // FAILED: a reason and NO list — null, not empty.
        .body("decommissions[0].failureDetail", Matchers.containsString("answered 500"))
        .body("decommissions[0]", Matchers.hasKey("removed"))
        .body("decommissions[0].removed", Matchers.nullValue())
        // PURGED: what went, and no reason.
        .body("decommissions[1].failureDetail", Matchers.nullValue())
        .body("decommissions[1].removed", Matchers.hasSize(1))
        .body("decommissions[1].removed[0].type", Matchers.equalTo("oci"))
        .body("decommissions[1].removed[0].repository", Matchers.equalTo("oci"))
        .body("decommissions[1].removed[0].name", Matchers.equalTo("qits/history-a"))
        .body(
            "decommissions[1].removed[0].versions", Matchers.contains("2026.1.0", "2026.2.0"));
  }

  @Test
  public void aPurgeThatFoundNothingIsAnEmptyListNotANull() {
    failedRow("history-empty", TIER);
    decommission(TIER + ":history-empty", 200);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(APPLICATIONS + TIER + ":history-empty/decommissions")
        .then()
        .statusCode(200)
        .body("decommissions.purgeOutcome", Matchers.contains("PURGED"))
        .body("decommissions[0].removed", Matchers.empty());
  }

  @Test
  public void aRunOfTheSameNameOnAnotherTierIsNotListed() {
    failedRow("history-tiers", TIER);
    failedRow("history-tiers", OTHER_TIER);
    decommission(OTHER_TIER + ":history-tiers", 200);
    decommission(TIER + ":history-tiers", 200);
    decommission(TIER + ":history-tiers", 200);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(APPLICATIONS + TIER + ":history-tiers/decommissions")
        .then()
        .statusCode(200)
        .body("decommissions", Matchers.hasSize(2))
        .body("decommissions.environmentId", Matchers.everyItem(Matchers.equalTo(TIER)));
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(APPLICATIONS + OTHER_TIER + ":history-tiers/decommissions")
        .then()
        .statusCode(200)
        .body("decommissions", Matchers.hasSize(1))
        .body("decommissions[0].environmentId", Matchers.equalTo(OTHER_TIER));
  }

  @Test
  public void anIdWithNoRunsIsAnEmptyListNotA404() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(APPLICATIONS + TIER + ":history-never-deployed/decommissions")
        .then()
        .statusCode(200)
        .body("decommissions", Matchers.empty());
  }

  @Test
  public void aMalformedIdIsA400() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(APPLICATIONS + "history-not-an-id/decommissions")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("is not an application id"));
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(APPLICATIONS + TIER + ":Not_A_Name/decommissions")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("application name"));
  }

  @Test
  public void anAgentMayReadIt() {
    failedRow("history-agent", TIER);
    decommission(TIER + ":history-agent", 200);

    given()
        .header("Authorization", SuiteBearers.bearer("dyn-history-agent", "qits:agent"))
        .when()
        .get(APPLICATIONS + TIER + ":history-agent/decommissions")
        .then()
        .statusCode(200)
        .body("decommissions", Matchers.hasSize(1));
  }
}
