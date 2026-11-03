package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The third lever: an application that is over says so, and its history survives saying it.
 *
 * <p>Package-local and writing rows straight to the table for the reason {@link
 * PdApplicationScaleTest} is: what a retirement acts on is an application that was deployed and is
 * not any more, and staging a real green build to reach that state would assert the deployment path
 * a second time rather than this one.
 *
 * <p><b>Nothing here waits on the worker</b>, and that is the assertion rather than an omission:
 * this door issues no orchestrator call, so it answers 200 with the write already done. A test that
 * needed {@code awaitIdle()} would mean the door had grown a queue it has no reason to have.
 */
@QuarkusTest
public class PdApplicationRetirementTest {

  private static final String SHA = "d".repeat(40);

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

  @jakarta.inject.Inject PdDeploymentRepository deployments;
  @jakarta.inject.Inject PdEnvironmentRepository environments;

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      String detail) {
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
              row.detail = detail;
              row.createdAt = Instant.now();
              row.finishedAt = Instant.now();
              deployments.persist(row);
            });
    return id;
  }

  /**
   * The ordinary shape, and the one every fixture here takes now: a row in a tier.
   *
   * <p>It was {@code platformRow} — a row with NO tier at all, which was how the platform plane
   * spelled itself and was addressed {@code platform:<name>}. The plane is deleted, so a row names
   * the tier it ran in and the id is that tier's. The tier id is a literal rather than a created
   * environment because a deployment row has no FK to one (V1's rule: history outlives the topology),
   * and these doors read the rows.
   */
  private static final String TIER = "env-retire-tier";

  private String tieredRow(String applicationName, PdDeploymentStatus status, String detail) {
    return deployment(applicationName, TIER, status, null, detail);
  }

  private PdDeployment rowOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.findById(deploymentId);
              assertNotNull(row, "the row is still there — a retirement deletes nothing");
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              copy.commitSha = row.commitSha;
              copy.containerName = row.containerName;
              copy.createdAt = row.createdAt;
              copy.finishedAt = row.finishedAt;
              return copy;
            });
  }

  private int rowCount(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.listForPlaceNewestFirst(applicationName, TIER).size());
  }

  // --- what it writes -----------------------------------------------------------------------------

  @Test
  public void theCurrentRowBecomesDecommissionedAndEveryOlderRowKeepsTheWordItEarned() {
    // The shape this door exists for: a repository that was renamed, whose old application name was
    // left behind with the failures of the builds that discovered the rename. Three rows, and the
    // newest is what the listing calls the application's state.
    String failure = "[resource provisioning failed: the database is already provisioned]";
    String oldest = tieredRow("retire-renamed", PdDeploymentStatus.FAILED, failure);
    String middle = tieredRow("retire-renamed", PdDeploymentStatus.FAILED, failure);
    String newest = tieredRow("retire-renamed", PdDeploymentStatus.FAILED, failure);
    int before = rowCount("retire-renamed");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":retire-renamed/decommission")
        .then()
        .statusCode(200)
        .body("applicationName", Matchers.equalTo("retire-renamed"))
        .body("currentDeploymentId", Matchers.equalTo(newest))
        // The word it replaced, reported rather than looked up afterwards: this call is the only
        // place it survives at all.
        .body("previousStatus", Matchers.equalTo("FAILED"))
        .body("decommissionedIds", Matchers.contains(newest));

    assertEquals(before, rowCount("retire-renamed"), "a retirement is not a delete");

    PdDeployment current = rowOf(newest);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, current.status);
    assertTrue(current.detail.startsWith("[decommissioned by "), current.detail);
    assertTrue(current.detail.contains("this application is retired"), current.detail);
    // The failure text under the stamp is what made the rename findable in the first place, and it
    // is exactly what a retirement must not take with it.
    assertTrue(current.detail.endsWith(failure), current.detail);
    assertEquals(SHA, current.commitSha, "the attempt is unchanged; only the word on it is new");

    // History is history. Relabelling it would have destroyed the record this door refuses to
    // delete, and a listing of six identical DECOMMISSIONED rows says nothing at all.
    assertEquals(PdDeploymentStatus.FAILED, rowOf(oldest).status);
    assertEquals(failure, rowOf(oldest).detail);
    assertEquals(PdDeploymentStatus.FAILED, rowOf(middle).status);
  }

  @Test
  public void theReadSurfaceSaysDecommissionedRatherThanTheStaleFailure() {
    String id = tieredRow("retire-on-the-surface", PdDeploymentStatus.FAILED, "boom");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when().post(APPLICATIONS + TIER + ":retire-on-the-surface/decommission").then().statusCode(200);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + TIER)
        .then()
        .statusCode(200)
        .body(
            "deployments.find { it.id == '" + id + "' }.status",
            Matchers.equalTo("DECOMMISSIONED"));
  }

  @Test
  public void aSpecUnreadableRowStopsBeingRetriedWhereverItSitsInTheHistory() {
    // SPEC_UNREADABLE is the one word here that is not terminal: DeployService re-reads such a row
    // on the observation's cadence until the git host answers. A retired application's spec never
    // will, so leaving one would be a retry running for ever against an address nobody maintains.
    String stranded = tieredRow("retire-stranded", PdDeploymentStatus.SPEC_UNREADABLE, "403");
    String newest = tieredRow("retire-stranded", PdDeploymentStatus.FAILED, "boom");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":retire-stranded/decommission")
        .then()
        .statusCode(200)
        // Newest first, and both of them: the current row because it is the state, the older one
        // because its word keeps asking a question nobody will answer.
        .body("decommissionedIds", Matchers.contains(newest, stranded));

    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rowOf(stranded).status);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rowOf(newest).status);
  }

  @Test
  public void retiringTwiceSaysTheSameThingOnceRatherThanGrowingTheColumn() {
    String failure = "[deployment spec unreadable: line 42]";
    String id = tieredRow("retire-twice", PdDeploymentStatus.FAILED, failure);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when().post(APPLICATIONS + TIER + ":retire-twice/decommission").then().statusCode(200);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":retire-twice/decommission")
        .then()
        .statusCode(200)
        // The second call finds the row already retired and says so — it is the current row, so it
        // is settled again, which is what makes the door safe to re-run.
        .body("previousStatus", Matchers.equalTo("DECOMMISSIONED"));

    PdDeployment row = rowOf(id);
    assertEquals(1, countOf(row.detail, "[decommissioned by "), row.detail);
    assertTrue(row.detail.endsWith(failure), row.detail);
  }

  private static int countOf(String haystack, String needle) {
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
  }

  @Test
  public void aFormerPlatformApplicationIsAddressedByTheTIERItRunsIn() {
    // What replaced `aPlatformApplicationIsFoundByItsPlaneEvenThoughItNamesTheDesignatedTier`. That
    // test held that `platform:<name>` is how such an application is addressed, and that reading the
    // plane as "the rows with no tier" would settle a pre-V8 row and leave the live one alone. The
    // plane is deleted: the id is `<tier>:<name>` for everything, and the rows of the nine that were
    // the plane already name the designated tier, because V8 put them there.
    String id =
        deployment(
            "retire-planed", "env-retire-designated", PdDeploymentStatus.FAILED, null, "boom");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + "env-retire-designated:retire-planed/decommission")
        .then()
        .statusCode(200)
        .body("environmentId", Matchers.equalTo("env-retire-designated"))
        .body("currentDeploymentId", Matchers.equalTo(id));

    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rowOf(id).status);
  }

  @Test
  public void aCachedPlatformIdNamesNoTierAndIsRefusedRatherThanMatchedLoosely() {
    // The other end of that cutover, and it is worth pinning rather than assuming. `platform:` is no
    // longer a stand-in for an environment id, so a client still holding one parses it as a TIER
    // literally called `platform` — which names no row. The door must answer "no such application"
    // rather than fall through to something that looks close enough.
    deployment("retire-cached", "env-retire-cached", PdDeploymentStatus.FAILED, null, "boom");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + "platform:retire-cached/decommission")
        .then()
        .statusCode(404);
  }

  @Test
  public void anEnvironmentApplicationIsAddressedByTheKeyTheListingCarries() {
    String id =
        deployment(
            "retire-tiered", "env-retire-tiered", PdDeploymentStatus.FAILED, null, null);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of("env-retire-tiered", "retire-tiered")
                + "/decommission")
        .then()
        .statusCode(200)
        .body("currentDeploymentId", Matchers.equalTo(id));

    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, row.status);
    assertTrue(row.detail.startsWith("[decommissioned by "), row.detail);
  }

  // --- what it refuses ----------------------------------------------------------------------------

  @Test
  public void anApplicationThatIsSTILLDEPLOYEDIsRefused() {
    // The id names a place by string, and a typo names a live application just as well as a dead
    // one. This is the refusal that makes the door safe to hand an operator.
    tieredRow("retire-serving", PdDeploymentStatus.ACTIVE, null);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":retire-serving/decommission")
        .then()
        .statusCode(409)
        .body("message", Matchers.containsString("still deployed"));

    assertEquals(
        PdDeploymentStatus.ACTIVE,
        QuarkusTransaction.requiringNew()
            .call(() -> deployments.listForPlaceNewestFirst("retire-serving", TIER).get(0).status),
        "a refusal writes nothing");
  }

  @Test
  public void aStoppedApplicationStillHasAServiceAndIsRefusedToo() {
    // SCALED_TO_ZERO is not terminal in the sense the others are: the swarm service exists, holds
    // its ports and its volumes, and one scale back up makes the row ACTIVE again. Calling that
    // decommissioned would leave the service with nothing pointing at it.
    tieredRow("retire-stopped", PdDeploymentStatus.SCALED_TO_ZERO, null);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":retire-stopped/decommission")
        .then()
        .statusCode(409)
        .body("message", Matchers.containsString("SCALED_TO_ZERO"));
  }

  @Test
  public void anInFlightDeploymentIsTheWorkersToSettleAndNotThisDoors() {
    // QUEUED and STARTING belong to the worker's state machine, and it is about to write the next
    // word. A row settled out from under it would be overwritten a second later and the operator
    // would never learn their action did nothing.
    String id = tieredRow("retire-in-flight", PdDeploymentStatus.QUEUED, null);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":retire-in-flight/decommission")
        .then()
        .statusCode(409)
        .body("message", Matchers.containsString("deploy worker"));

    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.QUEUED, row.status);
    assertNull(row.detail, "a refusal stamps nothing");
  }

  @Test
  public void aMalformedApplicationIdIsRefusedBeforeAnythingIsRead() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + "not-an-application-id/decommission")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("is not an application id"));
  }

  @Test
  public void anApplicationNothingEverDeployedHasNothingToRetire() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(APPLICATIONS + TIER + ":retire-never-deployed-anywhere/decommission")
        .then()
        .statusCode(404)
        .body("message", Matchers.containsString("nothing to retire"));
  }
}
