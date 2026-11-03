package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /deployments/api/pins} over real deployments, driven through the intake exactly as a green
 * build drives it — the shape a garbage collector binds to, and the proof that the pins follow the
 * cutover rather than restating it.
 *
 * <p>The listing is instance-wide, so every case reads its own application's entry out of it rather
 * than asserting the whole document: the suite shares one database and other classes deploy too.
 *
 * <p>It reads nothing but deployment rows, and that independence is the claim this suite carries
 * over from the retired outage suite: qits-platform-artifacts' image GC is fail-closed on this
 * answer, so a pin that needed the service catalogue would tie garbage collection platform-wide to
 * a second query. Everything the rule reads — the name, the tier, the sha, the status — is on the
 * row.
 */
@QuarkusTest
public class PdPinApiTest {

  // Released versions, not commit shas: what a deployment is created from — and therefore what the
  // GC must keep — is the image tagged with the CalVer stamp the release minted.
  private static final String V_A = "2026.903.1";
  private static final String V_B = "2026.903.2";
  private static final String V_C = "2026.903.3";
  private static final String V_D = "2026.903.4";

  @Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  @Test
  public void theShasOfEveryEnvironmentRunningAnApplicationAreOneEntry() {
    // Two environments running one application name, each a rollback step deep: the entry carries
    // all four shas, because either environment's next restart pulls its own serving sha and either
    // rollback pulls its own predecessor.
    // Creating a tier designates it as the entry one, so each half of this runs against its own:
    // staging takes the first two releases, live the next two. That is what a promotion will look
    // like once there is one, and it is what makes the union below two histories rather than one.
    String staging = createEnvironment("pins-staging");
    deploy("repo-pins", V_A, staging, 1);
    deploy("repo-pins", V_B, staging, 2);
    String live = createEnvironment("pins-live");
    deploy("repo-pins", V_C, live, 1);
    deploy("repo-pins", V_D, live, 2);

    // Serving shas sorted, then rollback shas sorted — a union over environments has no recency to
    // order by, so the answer is stable rather than pretending to be a sequence.
    assertEquals(List.of(V_B, V_D, V_A, V_C), shasOf("repo-pins"));
  }

  @Test
  public void aFailedGateLeavesThePinsWhereItLeavesTheApplication() {
    // The anchor to the real rollback: a successor that never converges is reverted by the
    // orchestrator, so the previous deployment is still ACTIVE and serving
    // (PdSwarmDeployFlowTest.aRolledBackUpdateEndsTheRowRolledBackCarryingSwarmsOwnWords). The pins
    // say exactly that — the serving sha, and no rollback target, because nothing ever served
    // before it. The failed sha is pinned by nothing: nothing was ever created from it.
    String environmentId = createEnvironment("pins-gate");
    deploy("repo-pins-gate", V_A, environmentId, 1);

    driver.scriptConvergence(
        DeploymentDriver.Convergence.rolledBack("the successor never went healthy"));
    deploy("repo-pins-gate", V_B, environmentId, 2);

    assertEquals(List.of(V_A), shasOf("repo-pins-gate"));
  }

  @Test
  public void aCutoverMovesThePinsExactlyOneStep() {
    // Three green builds in a row: the newest serves, the one before it is the rollback target, and
    // the oldest is pinned by nothing — one rollback step is what cd can actually perform.
    String environmentId = createEnvironment("pins-steps");
    deploy("repo-pins-steps", V_A, environmentId, 1);
    deploy("repo-pins-steps", V_B, environmentId, 2);

    assertEquals(List.of(V_B, V_A), shasOf("repo-pins-steps"));

    deploy("repo-pins-steps", V_C, environmentId, 3);

    assertEquals(List.of(V_C, V_B), shasOf("repo-pins-steps"));
  }

  @Test
  public void aRepositoryWithAUuidStorageIdIsPinnedByItsNameAndNeverByTheUuid() {
    // The regression the identity rollback could have caused, and the one it would have caused
    // silently. The pins ARE the GC keep-set, and qits-artifacts matches them against tags every
    // pipeline pushed as a literal qits/<name>:<sha>. A pin naming a storage UUID would match no
    // tag at all, so the collector would delete the image the running container was created from.
    String environmentId = createEnvironment("pins-uuid");
    deployNamed(
        "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88", "repo-pins-named", V_A, environmentId, 1);
    deployNamed(
        "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88", "repo-pins-named", V_B, environmentId, 2);

    assertEquals(List.of(V_B, V_A), shasOf("repo-pins-named"));
    assertEquals(
        List.of(),
        pins().stream()
            .filter(pin -> "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88".equals(pin.get("applicationName")))
            .toList(),
        "no pin is keyed by the storage id");
  }

  @Test
  public void anApplicationThatNeverDeployedIsAbsentRatherThanEmpty() {
    // An environment created and nothing green yet: there is no serving sha, so there is no entry.
    // An empty one would read as "this name is pinned" to a collector that keeps what it is told.
    createEnvironment("pins-idle");

    assertEquals(
        List.of(),
        pins().stream()
            .filter(pin -> "repo-pins-idle".equals(pin.get("applicationName")))
            .toList());
  }

  @SuppressWarnings("unchecked")
  private List<String> shasOf(String applicationName) {
    return pins().stream()
        .filter(pin -> applicationName.equals(pin.get("applicationName")))
        .map(pin -> (List<String>) pin.get("shas"))
        .findFirst()
        .orElseGet(() -> fail("no pin for " + applicationName + " in " + pins()));
  }

  private List<Map<String, Object>> pins() {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .when().get("/deployments/api/pins").then().statusCode(200).extract().jsonPath().getList("pins");
  }

  private String createEnvironment(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        // The entry tier: creating one moves the designation, and a release lands there.
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  /** One release, awaited to a settled row — the pins are read off finished deployments. */
  private void deploy(String repoId, String version, String environmentId, int expected) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-pins", "repoId", repoId, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    settle(environmentId, expected);
  }

  /** The same, with an opaque storage id and the repository's public name beside it. */
  private void deployNamed(
      String repoId, String repoName, String version, String environmentId, int expected) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "run-pins",
                "repoId", repoId,
                "projectId", "qits",
                "repoName", repoName,
                "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    settle(environmentId, expected);
  }

  private void settle(String environmentId, int expected) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments =
          given()
              .header("Authorization", SuiteBearers.operator())
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      boolean settled =
          deployments.size() == expected
              && deployments.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    fail("deployments of " + environmentId + " did not settle to " + expected);
  }
}
