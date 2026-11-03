package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /deployments/api/deployment-requests} over real releases, driven through the
 * intake exactly as a release drives it.
 *
 * <p>What this surface exists to say, and what nothing else can: <b>a version was asked for here,
 * and this is what the gate did with it</b>. The deployments listing answers a different question —
 * what ran — and a request the gate refused never becomes a row in it, so the refusal would be
 * invisible. The gate is a placeholder that says yes to everything today, which is why the cases
 * below assert the shape of a MET request and its edge back to the deployment rather than a
 * refusal: the refusal has no producer yet, and inventing one in a test would assert a fiction.
 *
 * <p>The listing is tier-scoped, so every case makes its own environment. Creating one MOVES the
 * designation, which is what makes a release land in it.
 */
@QuarkusTest
public class PdDeploymentRequestApiTest {

  // Released versions: what a request is about. Never a commit sha.
  private static final String V_A = "2026.903.11";
  private static final String V_B = "2026.903.12";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void aReleaseLeavesARequestThatPointsAtTheDeploymentItProduced() {
    String environmentId = createEnvironment("req-one");
    release("repo-req", V_A, environmentId, 1);

    Map<String, Object> request = onlyRequestOf(environmentId, "repo-req");
    assertEquals("repo-req", request.get("applicationName"));
    assertEquals(V_A, request.get("version"), "a request is about a version, never a sha");
    assertEquals(environmentId, request.get("environmentId"));
    assertEquals("MET", request.get("qualityGate"), "the placeholder gate says yes");
    assertNull(request.get("gateDetail"), "a silent yes has nothing to say");
    assertNotNull(request.get("gateSettledAt"), "the gate answered, and when is recorded");

    // The edge that makes the lifecycle readable end to end: request → gate → deployment.
    String deploymentId = (String) request.get("deploymentId");
    assertNotNull(deploymentId, "a met gate queues a deployment and the request points at it");
    assertTrue(
        deployments(environmentId).stream().anyMatch(d -> deploymentId.equals(d.get("id"))),
        "the deployment the request names is in the tier's deployment listing");
  }

  @Test
  public void theRequestsOfOneTierComeBackNewestFirst() {
    String environmentId = createEnvironment("req-order");
    release("repo-req-order", V_A, environmentId, 1);
    release("repo-req-order", V_B, environmentId, 2);

    assertEquals(
        List.of(V_B, V_A),
        requests(environmentId, null).stream()
            .filter(r -> "repo-req-order".equals(r.get("applicationName")))
            .map(r -> r.get("version"))
            .toList(),
        "seq desc, because the requests of one release land in one tick");
  }

  @Test
  public void oneApplicationsReleaseHistoryIsAskedForWithApplicationName() {
    String environmentId = createEnvironment("req-filter");
    release("repo-req-mine", V_A, environmentId, 1);
    release("repo-req-other", V_A, environmentId, 2);

    List<Map<String, Object>> mine = requests(environmentId, "repo-req-mine");
    assertEquals(1, mine.size());
    assertEquals("repo-req-mine", mine.get(0).get("applicationName"));
  }

  @Test
  public void aPlatformServicesRequestIsInTheTierItIsDeployedInto() {
    // The whole of V8, from this surface: a platform service belongs to no tier in the catalogue
    // and is deployed INTO the main environment, so its request names that environment like its
    // deployment does. There is no `?environmentId=platform` here — a request records no plane —
    // and this is what makes the plane's releases visible at all.
    String environmentId = createEnvironment("req-plane");
    specs.script(
        "repo-req-plane",
        new SpecSource.DeploymentSpec(false, null, null, null, null));
    release("repo-req-plane", V_A, environmentId, 1);

    Map<String, Object> request = onlyRequestOf(environmentId, "repo-req-plane");
    assertEquals(environmentId, request.get("environmentId"));
    assertNotNull(request.get("deploymentId"));
  }

  @Test
  public void theTierIsRequiredAndOneThatDoesNotExistIsA404() {
    // The deployments listing's shape verbatim: an unscoped listing would answer with every request
    // on the instance, and a missing tier must say so rather than answer with an empty list.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests")
        .then()
        .statusCode(400);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests?environmentId=no-such-tier")
        .then()
        .statusCode(404);
  }

  @Test
  public void theWordPlatformIsNotAnEnvironmentHere() {
    // Its sibling accepts `platform` because a deployment records which plane it is on. A request
    // does not, so the word names no tier this table can answer for and is a 404 rather than a
    // silently empty list.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests?environmentId=platform")
        .then()
        .statusCode(404);
  }

  @Test
  public void aRequestCarriesTheStatusOfTheDeploymentItProduced() {
    // The join that turns two rows into one lifecycle. The gate lives on the request and the
    // container lives on the deployment, and a reader asking "is the platform still doing something
    // about this release" needs both — so the request DTO carries the status of the row it points
    // at, and a client folding a listing into pending and settled makes no second request.
    String environmentId = createEnvironment("req-status");
    release("repo-req-status", V_A, environmentId, 1);

    Map<String, Object> request = onlyRequestOf(environmentId, "repo-req-status");
    Map<String, Object> deployment =
        deployments(environmentId).stream()
            .filter(d -> request.get("deploymentId").equals(d.get("id")))
            .findFirst()
            .orElseThrow();
    assertEquals(
        deployment.get("status"),
        request.get("deploymentStatus"),
        "the request says what became of what it asked for");
  }

  @Test
  public void aRequestCarriesThePriorityTheReleaseDeclaredAndNullWhenItDeclaredNone() {
    // The read surface half of the priority: it comes off qits-projects' release request, through
    // qits-ci, onto this row, and out here for a client to draw a badge from. Nothing on this
    // surface orders anything by it — the worker is FIFO — so the claim is exactly that the value
    // arrives, and that its absence is a null rather than an invented default.
    String environmentId = createEnvironment("req-priority");
    release("repo-req-priority", V_A, environmentId, null, "BLOCKING", 1);
    release("repo-req-plain-priority", V_A, environmentId, null, null, 2);

    assertEquals("BLOCKING", onlyRequestOf(environmentId, "repo-req-priority").get("priority"));
    assertNull(
        onlyRequestOf(environmentId, "repo-req-plain-priority").get("priority"),
        "a release that declared none says none, on the wire as in the row");
  }

  @Test
  public void aRequestWhoseDeploymentIsGoneCarriesNoStatus() {
    // Null is a real answer and not a gap. A refusal queues nothing, and an environment teardown
    // forgets the deployment rows while the requests outlive them by design — no FK, on purpose —
    // so both arrive here as "asked for, and there is no row to have a status".
    String environmentId = createEnvironment("req-orphan");
    release("repo-req-orphan", V_A, environmentId, 1);
    // Designating another tier is what makes the first one deletable: tearing down the platform
    // environment is a 409, because a release would then enter nowhere.
    createEnvironment("req-orphan-successor");
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .delete("/deployments/api/environments/" + environmentId)
        .then()
        .statusCode(204);

    Map<String, Object> request = onlyByRelease("repo-req-orphan", V_A);
    assertNotNull(request.get("deploymentId"), "the request still names the row it queued");
    assertNull(request.get("deploymentStatus"), "and there is no row left to have a status");
  }

  @Test
  public void aRequestIsReadableByItsOwnIdWithTheDeploymentItProduced() {
    // The detail read, and the reason the deployment is INLINE: this component has no
    // deployment-by-id endpoint, and minting one to serve a single screen would put a second,
    // unscoped door onto pd_deployment.
    String environmentId = createEnvironment("req-detail");
    release("repo-req-detail", V_A, environmentId, 1);
    String id = (String) onlyRequestOf(environmentId, "repo-req-detail").get("id");

    Map<String, Object> answer =
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get("/deployments/api/deployment-requests/" + id)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getMap("$");

    @SuppressWarnings("unchecked")
    Map<String, Object> request = (Map<String, Object>) answer.get("deploymentRequest");
    @SuppressWarnings("unchecked")
    Map<String, Object> deployment = (Map<String, Object>) answer.get("deployment");
    assertEquals(id, request.get("id"));
    assertEquals(V_A, request.get("version"));
    assertNotNull(deployment, "a met gate queued a deployment and it travels in this answer");
    assertEquals(request.get("deploymentId"), deployment.get("id"));
    assertEquals("repo-req-detail", deployment.get("applicationName"));
  }

  @Test
  public void anIdNothingWroteIsA404() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests/no-such-request")
        .then()
        .statusCode(404);
  }

  @Test
  public void aProjectListingCarriesEveryPendingRequestAndCapsTheSettledOnesAtTen() {
    // What a project screen is for: what is happening now, and what happened last. The pending half
    // is complete — a release the platform has not finished with must never be the one the cap
    // dropped — and the settled half is the ten newest, capped HERE rather than by a client that
    // would download a year of history to throw it away.
    String environmentId = createEnvironment("req-project");
    String projectId = "project-req-cap";
    for (int n = 1; n <= 12; n++) {
      release("repo-req-cap", "2026.903." + (100 + n), environmentId, projectId, n);
    }
    // One held release, and it is held on the git host rather than in the gate: SPEC_UNREADABLE is
    // the one terminal-looking word that is not terminal, so the deployer is still working on it.
    // The repository has to be registered already — an unreadable spec records failures only where
    // the catalogue already knows the application — which the twelve above have seen to.
    specs.scriptRetryableFailure("repo-req-cap", "the git host answered 403");
    release("repo-req-cap", "2026.903.113", environmentId, projectId, 13);

    List<Map<String, Object>> answer = requestsOfProject(projectId);
    assertEquals(11, answer.size(), "every pending one, and ten settled");
    assertEquals(
        "2026.903.113",
        answer.get(0).get("version"),
        "seq desc across the whole answer: the held release is the newest thing asked for");
    assertEquals("SPEC_UNREADABLE", answer.get(0).get("deploymentStatus"));
    assertEquals(
        List.of(
            "2026.903.112",
            "2026.903.111",
            "2026.903.110",
            "2026.903.109",
            "2026.903.108",
            "2026.903.107",
            "2026.903.106",
            "2026.903.105",
            "2026.903.104",
            "2026.903.103"),
        answer.stream().skip(1).map(r -> r.get("version")).toList(),
        "the ten NEWEST settled ones, and the two oldest are the ones dropped");

    // Let the held release go, and it is a requirement of the suite rather than tidiness — the same
    // one PdSpecRetryTest states: the held set lives on the application-scoped DeployService, so a
    // release abandoned here would deploy into the NEXT class's tier the moment `specs.reset()`
    // takes its scripted failure away. The exit taken here is the ordinary one, supersession by a
    // newer version, which needs nothing but this class's own door.
    specs.recover("repo-req-cap");
    release("repo-req-cap", "2026.903.114", environmentId, projectId, 14);
  }

  @Test
  public void aProjectNothingWasReleasedForIsAnEmptyListRatherThanA404() {
    // The asymmetry with the tier filter, and it is the honest one: a tier is this component's own
    // row and a missing one is a real answer, while a project is qits-projects' row and this
    // component holds none. "No request here carries that project" is all this surface can say.
    assertEquals(
        List.of(),
        requestsOfProject("project-nothing-was-ever-released-for"),
        "an empty list, not a claim about a catalogue this service does not read");
  }

  @Test
  public void aReleaseIsFoundByTheRepositoryAndVersionItWasCutFrom() {
    // The edge followed the other way: qits-projects holds a released version and a repository and
    // asks what this platform did with it. The pair is (repoId, version) because that is what the
    // far side has — the application name may be the repository's or the spec's `application:`
    // override, and only this table knows which.
    String environmentId = createEnvironment("req-release");
    release("repo-req-release", V_A, environmentId, 1);
    release("repo-req-release", V_B, environmentId, 2);

    Map<String, Object> found = onlyByRelease("repo-req-release", V_B);
    assertEquals(V_B, found.get("version"));
    assertEquals("repo-req-release", found.get("applicationName"));
    assertEquals(
        List.of(),
        byRelease("repo-req-release", "2026.903.99"),
        "a version nothing was asked for here is empty — a library releases and deploys nothing");
  }

  @Test
  public void halfOfTheRepositoryVersionPairIsA400() {
    // A lone repoId would silently answer with a repository's whole history and a lone version with
    // every repository's, so neither is a filter this surface accepts.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests?repoId=repo-req-half")
        .then()
        .statusCode(400);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests?version=" + V_A)
        .then()
        .statusCode(400);
  }

  private Map<String, Object> onlyRequestOf(String environmentId, String applicationName) {
    List<Map<String, Object>> mine =
        requests(environmentId, null).stream()
            .filter(r -> applicationName.equals(r.get("applicationName")))
            .toList();
    assertEquals(1, mine.size(), "requests of " + applicationName);
    return mine.get(0);
  }

  private List<Map<String, Object>> requests(String environmentId, String applicationName) {
    String query =
        "?environmentId=" + environmentId
            + (applicationName == null ? "" : "&applicationName=" + applicationName);
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests" + query)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deploymentRequests");
  }

  /** One project's answer: every pending request, and the ten newest settled ones. */
  private List<Map<String, Object>> requestsOfProject(String projectId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests?projectId=" + projectId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deploymentRequests");
  }

  private List<Map<String, Object>> byRelease(String repoId, String version) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(
            "/deployments/api/deployment-requests?repoId="
                + repoId
                + "&version="
                + version)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deploymentRequests");
  }

  private Map<String, Object> onlyByRelease(String repoId, String version) {
    List<Map<String, Object>> found = byRelease(repoId, version);
    assertEquals(1, found.size(), "requests for " + repoId + "@" + version);
    return found.get(0);
  }

  private List<Map<String, Object>> deployments(String environmentId) {
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

  /** The entry tier: creating one moves the designation, and a release lands there. */
  private String createEnvironment(String name) {
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

  /** One release, awaited to a settled deployment — the request is written before it. */
  private void release(String repoId, String version, String environmentId, int expected) {
    release(repoId, version, environmentId, null, expected);
  }

  /**
   * The same, announcing the project the repository belongs to.
   *
   * <p>The project travels beside the repository as identity enrichment and is a key to nothing in
   * the deploy path — {@code repoName} is deliberately left out, so the spec read stays
   * id-addressed exactly as it is without it.
   */
  private void release(
      String repoId, String version, String environmentId, String projectId, int expected) {
    release(repoId, version, environmentId, projectId, null, expected);
  }

  /** The same again, declaring the priority the release request carried. Null states none. */
  private void release(
      String repoId,
      String version,
      String environmentId,
      String projectId,
      String priority,
      int expected) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("runId", "run-req");
    body.put("repoId", repoId);
    body.put("version", version);
    if (projectId != null) {
      body.put("projectId", projectId);
    }
    if (priority != null) {
      body.put("priority", priority);
    }
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    settle(environmentId, expected);
  }

  private void settle(String environmentId, int expected) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments = deployments(environmentId);
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
