package eu.wohlben.qits.deployments.stories.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.deployments.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.deployments.stories.support.StoryIdentities;
import eu.wohlben.qits.deployments.stories.support.StoryPeers;
import eu.wohlben.qits.deployments.stories.support.StoryPlatform;
import eu.wohlben.qits.deployments.stories.support.StoryProfile;
import eu.wohlben.qits.deployments.stories.support.StorySwarm;
import eu.wohlben.qits.deployments.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>A green build, end to end</b> — the walk this component exists for, from the event qits-ci
 * sends to a service the orchestrator is running and a row an operator can read.
 *
 * <p>The three stories are the three shapes a build takes, and each is a different diagram:
 *
 * <ul>
 *   <li>the application is <b>new to the tier</b>: the spec is read at the built sha, the
 *       configuration is read for the argv, and the orchestrator is asked to CREATE a service whose
 *       name is the wire alias peers will dial;
 *   <li>the application is <b>already there</b>: the same walk ends in a {@code service update} of
 *       that same service — a swarm service's name IS its address, so a replace is an update in
 *       place and not a second service beside the first. One extra question is asked on the way
 *       ({@code service env}), and it is the whole of what "config is the source" costs;
 *   <li>the registry <b>published nothing</b>: one {@code docker pull}, a row that says so, and
 *       <b>nothing else at all</b> — no configuration read, no service, no network touched. That is
 *       the strongest claim in this catalogue, because a deployment that had already created
 *       something before it found out would have to undo it.
 * </ul>
 *
 * <p><b>The orchestrator edges are observed, not declared.</b> {@code deployments/control/PdProcess}
 * spawns the docker CLI and reads its pipes, so {@code stories.support.StorySwarm} stands in for the
 * binary and records every argv with the exit code it answered. The one edge that <b>is</b> declared
 * is this component's own postgres: no tap on this side can see a JDBC round trip, and the ordering
 * it guarantees — the row is written before anything is attempted, which is what makes a failed
 * deployment diagnosable — is the invariant everything else here rests on.
 *
 * <p><b>The stories are ordered and the order is load-bearing</b>: the second is about the service
 * the first created, and running it alone would be a story about a create claiming to be an update.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BuildDeploymentIT {

  static final String CATEGORY = "deployments";

  static final String CREATED_SLUG =
      "a-green-build-rolls-the-application-onto-the-tier-that-listens-to-its-branch";
  static final String UPDATED_SLUG =
      "deploying-the-same-application-again-updates-the-service-it-already-has";
  static final String UNPUBLISHED_SLUG =
      "a-build-whose-image-nothing-published-is-recorded-and-nothing-is-created";

  /** The repository this class deploys twice. Its storage id never reaches a label — see below. */
  public static final String WEB = StoryPeers.WEB;

  static final String WEB_REPO_ID = "story-web-storage-id";

  public static final String UNPUBLISHED = StoryPeers.UNPUBLISHED;

  static final String UNPUBLISHED_REPO_ID = "story-unpublished-storage-id";

  /** Literal commits. A sha IS generated, so the labels carrying one are template-shaped. */
  public static final String FIRST_VERSION = "2026.903.104";
  public static final String SECOND_VERSION = "2026.903.105";
  static final String UNPUBLISHED_VERSION = "2026.903.106";

  /** What the orchestrator calls this application's service — and what peers dial it by. */
  static final String WEB_SERVICE = StoryTarget.wireAlias(WEB);

  static final String UNPUBLISHED_SERVICE = StoryTarget.wireAlias(UNPUBLISHED);

  /** The store behind every row — declared, because nothing on this side can observe it. */
  static final String STORE = "postgresql";

  static final String STORE_LABEL = "the deployment row, written before anything is attempted";

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapEveryHopADeploymentMakes() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StorySwarm.installSource();
    StoryPeers.install();
  }

  /**
   * The tier, provisioned through a client no tap is attached to.
   *
   * <p>{@code @BeforeEach} rather than {@code @BeforeAll} because it builds a url out of {@code
   * RestAssured.port}, which the Quarkus integration-test extension sets in beforeEach.
   */
  @BeforeEach
  void theTierExists() {
    StoryPlatform.provision();
  }

  @UserStory(
      value = "A green build rolls the application onto the tier that listens to its branch",
      category = CATEGORY)
  @UserStoryDescription(
      """
      qits-ci finishes a pipeline green and POSTs one triple — repository, branch, commit — to this
      component and forgets about it. Everything after that is here. The branch decides WHERE: a
      green build deploys wherever an environment listens to it, and one tier listening is one
      deployment. The repository's own .config/qits/deployments.yml, read from the git host at the
      BUILT sha, decides WHAT: which plane, which gate, and how the cutover is allowed to overlap.
      The application's configuration, read from qits-configuration with this component's own
      machine credential, decides what the container gets beyond its image. Only then is the
      orchestrator asked for anything — and what it is asked for is a service named after the wire
      alias, because under swarm the name IS the address.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aGreenBuildBecomesAServiceTheOrchestratorRuns(Interactions story, Network network) {
    // This story's own starting line in the orchestrator's recording: the catalogue shares one
    // launched process, so "nothing was created" has to be a claim about THIS deployment.
    int before = StorySwarm.mark();
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.machineToken(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(softwareReleased("run-story-web-1", WEB_REPO_ID, WEB, FIRST_VERSION))
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(202);
    story
        .note(
            "qits-ci announces one release of " + WEB + " into " + StoryTarget.TIER
                + " and gets 202 — the sender never learns what came of it, which is why every"
                + " outcome below has to be a row")
        .as("build-announced");

    JsonNode row = StoryPlatform.awaitSettled(WEB, FIRST_VERSION);
    assertEquals("ACTIVE", row.path("status").asText(), "the deployment did not go live: " + row);
    assertEquals(
        WEB_SERVICE,
        row.path("containerName").asText(),
        "the row does not name the service the orchestrator was asked for");
    story
        .note(
            "the spec is read at the built sha, the configuration for the argv, and then one"
                + " service is created — " + WEB_SERVICE + ", which is the wire alias peers dial")
        .as("service-created");

    List<String> calls = StorySwarm.callsSince(before);
    assertTrue(
        calls.contains(StorySwarm.label("service create " + WEB_SERVICE, "0")),
        "no service was created; the orchestrator was asked " + calls);
    assertFalse(
        calls.contains(StorySwarm.label("service update " + WEB_SERVICE, "0")),
        "the first deployment of an application must be a create, not an update");

    // The one spec value a story can watch travel all the way into the orchestrator's own words:
    // this repository cannot be two processes at once, and only the repository knows that.
    List<String> argv = StorySwarm.argvOf("service create " + WEB_SERVICE);
    assertEquals(
        "stop-first",
        StorySwarm.flagValue(argv, "--update-order"),
        "the repository's update_order never reached the orchestrator: " + argv);
    assertEquals(
        StoryTarget.imageRef(WEB, FIRST_VERSION),
        argv.getLast(),
        "the service was not created at the image this commit derives: " + argv);
    story
        .note(
            "update_order: stop-first from the repository reaches the orchestrator as"
                + " --update-order, and the image is derived from the commit rather than sent")
        .as("spec-reached-the-argv");

    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryIdentities.person(given(), "story-operator")
        .get(StoryTarget.DEPLOYMENTS_PATH + "?environmentId=" + StoryPlatform.tierId())
        .then()
        .statusCode(200)
        .body("deployments.version", hasItem(FIRST_VERSION));
    story
        .note(
            "and an operator reads the whole attempt back on the tier's own listing — the only"
                + " surface a fire-and-forget build event ever answers on")
        .as("deployment-readable");

    // The dependency no tap on this side can see, and the one whose ORDERING is the invariant: the
    // row exists before the pull, so a deployment that failed anywhere still has somewhere to say
    // so.
    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

  @UserStory(
      value = "Deploying the same application again updates the service it already has",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The second green build of a repository is not a second service. A swarm service's name is its
      address, so a replace is `docker service update --image` on the same name and the cutover is
      the orchestrator's own — start-first or stop-first, a monitor window, and a rollback if the
      successor never goes healthy. This component issues one command and then reads a verdict, and
      the verdict it reads has to be about THIS update: the daemon answers with the previous
      cutover's terminal state until it has taken the new one in, which once declared a deployment
      live 43 milliseconds after it was issued. So the update is matched by when it was started.

      One question is asked on the update path that a create never asks — what environment the live
      service carries — and it is the whole of what "configuration is the source" costs: an update
      states the environment in full, so it must know what to state a REMOVAL for.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void asecondBuildReplacesTheServiceInPlace(Interactions story, Network network) {
    // This story's own starting line in the orchestrator's recording: the catalogue shares one
    // launched process, so "nothing was created" has to be a claim about THIS deployment.
    int before = StorySwarm.mark();
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.machineToken(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(softwareReleased("run-story-web-2", WEB_REPO_ID, WEB, SECOND_VERSION))
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(202);
    story.note("a second green build of " + WEB + ", at a different commit").as("second-build");

    JsonNode row = StoryPlatform.awaitSettled(WEB, SECOND_VERSION);
    assertEquals("ACTIVE", row.path("status").asText(), "the replace did not go live: " + row);
    assertEquals(
        WEB_SERVICE,
        row.path("containerName").asText(),
        "both rows of a cutover name the same service, because a replace is in place");

    List<String> calls = StorySwarm.callsSince(before);
    assertTrue(
        calls.contains(StorySwarm.label("service update " + WEB_SERVICE, "0")),
        "the replace was not an update of the existing service; it asked " + calls);
    assertFalse(
        calls.contains(StorySwarm.label("service create " + WEB_SERVICE, "0")),
        "a second service was created beside the one that was serving");
    story
        .note(
            "the orchestrator is asked to UPDATE " + WEB_SERVICE + " rather than to create a"
                + " second one, so the address never moves and the predecessor is never orphaned")
        .as("updated-in-place");

    List<String> argv = StorySwarm.argvOf("service update " + WEB_SERVICE);
    assertEquals(
        StoryTarget.imageRef(WEB, SECOND_VERSION),
        StorySwarm.flagValue(argv, "--image"),
        "the update did not carry this commit's image: " + argv);
    assertFalse(
        argv.contains("--env-rm"),
        "nothing was removed from this application's environment, so nothing may be stated: " + argv);
    story
        .note(
            "the image is the change; the environment is re-stated in full and states no removal,"
                + " because configuration still says everything the live service carries")
        .as("update-states-the-image");

    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryIdentities.person(given(), "story-operator")
        .get(StoryTarget.DEPLOYMENTS_PATH + "?environmentId=" + StoryPlatform.tierId())
        .then()
        .statusCode(200)
        .body("deployments.status", hasItem("DECOMMISSIONED"));
    story
        .note(
            "and the predecessor is DECOMMISSIONED rather than deleted — history outlives the"
                + " containers it describes, which is what the rollback pins are read off")
        .as("predecessor-decommissioned");

    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

  @UserStory(
      value = "A build whose image nothing published is recorded, and nothing is created",
      category = CATEGORY)
  @UserStoryDescription(
      """
      A green pipeline does not always publish an image, and the registry having none for this
      commit is an expected outcome rather than a failure of the deployer. So it gets a word of its
      own — IMAGE_MISSING — and it costs exactly one `docker pull`. That the pull comes FIRST is the
      design: nothing orchestrator-side has happened when the answer arrives, so there is no service
      to remove, no predecessor stopped, and no configuration read that would have been a request to
      a peer for a deployment that was never going to happen. The tier keeps whatever was serving,
      and an operator reads the row and goes to the pipeline.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(3)
  void anUnpublishedImageCostsOnePullAndChangesNothing(Interactions story, Network network) {
    // This story's own starting line in the orchestrator's recording: the catalogue shares one
    // launched process, so "nothing was created" has to be a claim about THIS deployment.
    int before = StorySwarm.mark();
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.machineToken(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            softwareReleased(
                "run-story-unpublished-1", UNPUBLISHED_REPO_ID, UNPUBLISHED, UNPUBLISHED_VERSION))
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(202);
    story
        .note("a green build of " + UNPUBLISHED + ", whose pipeline published no image")
        .as("build-announced");

    JsonNode row = StoryPlatform.awaitSettled(UNPUBLISHED, UNPUBLISHED_VERSION);
    assertEquals(
        "IMAGE_MISSING",
        row.path("status").asText(),
        "a registry with no such image is its own outcome, not a generic failure: " + row);
    assertTrue(
        row.path("detail").asText().contains("manifest unknown"),
        "the row does not carry the registry's own words: " + row);
    story
        .note(
            "the row says IMAGE_MISSING and carries the registry's own words, so an operator is"
                + " sent to the pipeline rather than to the orchestrator")
        .as("row-says-image-missing");

    List<String> calls = StorySwarm.callsSince(before);
    assertFalse(
        calls.contains(StorySwarm.label("service create " + UNPUBLISHED_SERVICE, "0")),
        "a service was created for a build that had no image: " + calls);
    story
        .note(
            "and nothing was created: the pull is asked first precisely so that this answer costs"
                + " one call and leaves the tier exactly as it was")
        .as("nothing-created");

    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryIdentities.person(given(), "story-operator")
        .get(StoryTarget.DEPLOYMENTS_PATH + "?environmentId=" + StoryPlatform.tierId())
        .then()
        .statusCode(200)
        .body("deployments.applicationName", hasItem(UNPUBLISHED));
    story
        .note("the attempt is on the tier's listing like any other — a refusal is history too")
        .as("refusal-is-history");

    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

  /**
   * The intake payload, in the shape the cross-repo contract fixes.
   *
   * <p>{@code projectId} and {@code repoName} are the repository's PUBLIC address and are what every
   * identifier below is derived from — the image tag, the wire alias, the provisioned database, the
   * pin. {@code repoId} is the opaque storage key and travels beside them; a release carrying the
   * name pair never reads by it (the spec read has no id fallback since qits-883), which is why it
   * never appears in a label here.
   */
  private static Map<String, Object> softwareReleased(
      String runId, String repoId, String repoName, String version) {
    return Map.of(
        "runId", runId,
        "repoId", repoId,
        "projectId", StoryTarget.PROJECT,
        "repoName", repoName,
        "version", version);
  }

  @AfterAll
  static void everyDeploymentStoryIsComplete() {
    // --- the application that was new ------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, CREATED_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of("build-announced", "service-created", "spec-reached-the-argv", "deployment-readable")) {
      ReportAssertions.assertStepId(CATEGORY, CREATED_SLUG, step);
    }
    intake(CREATED_SLUG);
    peer(CREATED_SLUG, StoryPeers.GIT_HOST, StoryPeers.specLabel(WEB, FIRST_VERSION, 200));
    // The second blob at the same tag, asked ONCE and answered: story-web declares no
    // configuration, which is the ordinary state of a repository that has not been migrated. It
    // seeds nothing, and one 404 is the whole of what asking costs — see StoryPeers on the id-route
    // fallback this read used to make and the hour-long hold it bought.
    peer(CREATED_SLUG, StoryPeers.GIT_HOST, StoryPeers.declarationMissLabel(WEB, FIRST_VERSION));
    // The configuration read, and NOT the mint that authorised it: quarkus-oidc-client caches the
    // token, so the POST /idp/token arrow belongs to whichever deployment found the cache cold —
    // stories.configuration's first, in a full run. See StoryPeers on why that is the deployer's own
    // property rather than the stand-in's.
    peer(CREATED_SLUG, StoryPeers.CONFIGURATION, StoryPeers.resolvedLabel(WEB, StoryTarget.TIER, 200));
    for (String call : StorySwarm.createCalls(WEB_SERVICE, WEB, FIRST_VERSION)) {
      swarm(CREATED_SLUG, call);
    }
    operatorRead(CREATED_SLUG);
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, CREATED_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    // One event in, two peers read — the git host TWICE, because a release reads two files and each
    // is asked for once — eight questions to the orchestrator, one read back and one store behind
    // all of it. Nothing else: this component asks nobody who qits-ci is — the bearer is judged on
    // keys fetched once, at startup.
    ReportAssertions.assertEdgeCount(CATEGORY, CREATED_SLUG, 14);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY,
        CREATED_SLUG,
        List.of(StoryIdentities.CI, StoryIdentities.OPERATOR, StoryTarget.SERVICE));

    // --- the same application again ---------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, UPDATED_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of(
            "second-build",
            "updated-in-place",
            "update-states-the-image",
            "predecessor-decommissioned")) {
      ReportAssertions.assertStepId(CATEGORY, UPDATED_SLUG, step);
    }
    intake(UPDATED_SLUG);
    peer(UPDATED_SLUG, StoryPeers.GIT_HOST, StoryPeers.specLabel(WEB, SECOND_VERSION, 200));
    peer(UPDATED_SLUG, StoryPeers.GIT_HOST, StoryPeers.declarationMissLabel(WEB, SECOND_VERSION));
    peer(UPDATED_SLUG, StoryPeers.CONFIGURATION, StoryPeers.resolvedLabel(WEB, StoryTarget.TIER, 200));
    for (String call : StorySwarm.updateCalls(WEB_SERVICE, WEB, SECOND_VERSION)) {
      swarm(UPDATED_SLUG, call);
    }
    operatorRead(UPDATED_SLUG);
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, UPDATED_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    ReportAssertions.assertEdgeCount(CATEGORY, UPDATED_SLUG, 14);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY,
        UPDATED_SLUG,
        List.of(StoryIdentities.CI, StoryIdentities.OPERATOR, StoryTarget.SERVICE));

    // --- the image nobody published ---------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, UNPUBLISHED_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of("build-announced", "row-says-image-missing", "nothing-created", "refusal-is-history")) {
      ReportAssertions.assertStepId(CATEGORY, UNPUBLISHED_SLUG, step);
    }
    intake(UNPUBLISHED_SLUG);
    peer(
        UNPUBLISHED_SLUG,
        StoryPeers.GIT_HOST,
        StoryPeers.specLabel(UNPUBLISHED, UNPUBLISHED_VERSION, 200));
    peer(
        UNPUBLISHED_SLUG,
        StoryPeers.GIT_HOST,
        StoryPeers.declarationMissLabel(UNPUBLISHED, UNPUBLISHED_VERSION));
    swarm(
        UNPUBLISHED_SLUG,
        StorySwarm.label("pull " + templated(UNPUBLISHED, UNPUBLISHED_VERSION), "1"));
    operatorRead(UNPUBLISHED_SLUG);
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, UNPUBLISHED_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    // SIX, and the two absences are the story. qits-configuration was never asked — a deployment
    // that is not going to happen must not spend a peer's answer on itself, and this repository
    // declares nothing to seed either — and the orchestrator was asked exactly once.
    ReportAssertions.assertEdgeCount(CATEGORY, UNPUBLISHED_SLUG, 6);
    ReportAssertions.assertNoEdgesTo(CATEGORY, UNPUBLISHED_SLUG, StoryPeers.CONFIGURATION);
    ReportAssertions.assertNoEdgesTo(CATEGORY, UNPUBLISHED_SLUG, StoryPeers.IDP);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY,
        UNPUBLISHED_SLUG,
        List.of(StoryIdentities.CI, StoryIdentities.OPERATOR, StoryTarget.SERVICE));

    // No bearer this class minted is anywhere in the bundle it publishes.
    for (String slug : List.of(CREATED_SLUG, UPDATED_SLUG, UNPUBLISHED_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }

  private static String templated(String application, String sha) {
    return StorySwarm.image(StoryTarget.imageRef(application, sha));
  }

  private static void intake(String slug) {
    ReportAssertions.assertEdge(
        CATEGORY,
        slug,
        NetworkEdge.HTTP,
        StoryIdentities.CI,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.SOFTWARE_RELEASED_PATH + " -> 202");
  }

  private static void operatorRead(String slug) {
    ReportAssertions.assertEdge(
        CATEGORY,
        slug,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.DEPLOYMENTS_PATH + " -> 200");
  }

  private static void peer(String slug, String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }

  private static void swarm(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, StorySwarm.KIND, StoryTarget.SERVICE, StorySwarm.ORCHESTRATOR, label);
  }
}
