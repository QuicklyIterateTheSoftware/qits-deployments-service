package eu.wohlben.qits.deployments.stories.operations;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.deployments.stories.deployment.BuildDeploymentIT;
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
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>What the platform looks like once things have been deployed onto it</b> — the two readers of
 * this surface, and the two very different questions they ask.
 *
 * <ul>
 *   <li><b>A person</b> reads the whole shape: the tiers, the catalogue, the applications and one
 *       tier's deployment history. Four routes, four answers, and every one of them comes out of one
 *       database in one process — a claim the ancestors could not make, because the topology used to
 *       be a second service on the far side of an HTTP call.
 *   <li><b>A machine</b>, qits-platform-artifacts' OCI garbage collector, asks the far smaller
 *       question its sweep depends on: which image shas must survive. Answering it costs this
 *       component <b>nothing but rows</b> — no orchestrator call, no peer, no topology read — which
 *       is exactly why a collector can afford to ask before every sweep and to abort when it
 *       cannot.
 * </ul>
 *
 * <p><b>These stories read what {@code stories.deployment} deployed</b>, so they run after it. That
 * is stated with {@code @UserflowRunsAfter} as well as being true of the package names, and it is a
 * real dependency rather than tidiness: an overview of a platform nothing has been deployed onto
 * would be an empty page asserting nothing. Run this class on its own and it fails loudly, which is
 * the right way for that assumption to break.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PlatformOverviewIT {

  static final String CATEGORY = "operations";

  static final String OVERVIEW_SLUG = "an-operator-reads-what-is-deployed-where-and-at-which-commit";
  static final String PINS_SLUG = "the-image-collector-reads-what-must-survive-a-sweep";

  static final String STORE = "postgresql";

  static final String OVERVIEW_STORE_LABEL =
      "the topology and the deployment history, one database in one process";

  static final String PINS_STORE_LABEL = "the deployment rows, and nothing else at all";

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapWhatAReaderSends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StorySwarm.installSource();
    StoryPeers.install();
  }

  @BeforeEach
  void theTierExists() {
    StoryPlatform.provision();
  }

  @UserStory(
      value = "An operator reads what is deployed, where, and at which commit",
      category = CATEGORY)
  @UserStoryDescription(
      """
      Nothing on this surface is open, and the role says who a caller is meant to be: all four
      reads below take qits:admin, which reaches this service only as a forwarded X-Qits-Roles
      header from an authenticated admin session. A machine bearer never carries that role, so the
      operator's half of the surface is a person's and stays one — the two reads a machine peer may
      also ask, the rollback pins and the deployment-request listing, are granted to qits:system by
      name and are not among these.

      The four reads answer four different questions and are deliberately not one endpoint. The
      tiers say which places exist, and which of them a release enters at. The catalogue says which
      services exist and which tiers each is linked into — one row per service, and it round-trips
      into the upsert. The applications listing is the same data one row per (service, tier), flat
      because a platform service carries no link and reading through the tiers would leave out the
      two a reader most wants to find. And the deployment listing is history, scoped to one plane,
      reporting every attempt rather than only what is serving.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, BuildDeploymentIT.class})
  @Order(1)
  void thePlatformIsReadableAsRowsRatherThanAsContainers(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);

    StoryIdentities.person(given(), "story-operator")
        .get(StoryTarget.ENVIRONMENTS_PATH)
        .then()
        .statusCode(200)
        .body("environments.name", hasItem(StoryTarget.TIER));
    story
        .note(
            "the tiers, and which of them is the designated one — which is the whole of where a"
                + " release lands, on both planes")
        .as("tiers-listed");

    StoryIdentities.person(given(), "story-operator")
        .get(StoryTarget.SERVICES_PATH)
        .then()
        .statusCode(200)
        .body("services.name", hasItem(BuildDeploymentIT.WEB));
    story
        .note(
            "the catalogue, one row per service with its link set — and every row in it was DERIVED"
                + " from a repository's own deployments.yml on a green build, never typed in")
        .as("catalogue-listed");

    StoryIdentities.person(given(), "story-operator")
        .get(StoryTarget.APPLICATIONS_PATH)
        .then()
        .statusCode(200)
        .body("applications.name", hasItem(BuildDeploymentIT.WEB));
    story
        .note(
            "the same catalogue one row per (service, tier), flat: a platform service belongs to no"
                + " tier, so reading through the tiers would leave out the ones that serve them all")
        .as("applications-listed");

    JsonPath history =
        StoryIdentities.person(given(), "story-operator")
            .get(StoryTarget.DEPLOYMENTS_PATH + "?environmentId=" + StoryPlatform.tierId())
            .then()
            .statusCode(200)
            .body("deployments.status", hasItem("ACTIVE"))
            .body("deployments.status", hasItem("IMAGE_MISSING"))
            .extract()
            .jsonPath();
    assertTrue(
        history.getList("deployments").size() >= 4,
        "the tier's history is shorter than the attempts made against it: " + history.prettify());
    story
        .note(
            "and one tier's history — every attempt, not only what is serving: an IMAGE_MISSING and"
                + " a superseded predecessor are as much a part of it as today's ACTIVE row")
        .as("history-listed");

    // One database, one process, both domains. The ancestors answered these four questions out of
    // two stores with an HTTP call between them, and this edge is the whole of what that merge
    // bought.
    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, OVERVIEW_STORE_LABEL);
  }

  @UserStory(
      value = "The image collector reads what must survive a sweep",
      category = CATEGORY)
  @UserStoryDescription(
      """
      qits-platform-artifacts deletes an image tag only when no pin names it, and this component is
      the only thing that knows what is running. So the keep-set lives here, beside the code that
      performs a rollback: the GC's keep-set and the rollback target are one definition rather than
      two that drift, and drift there deletes an image a restart is about to pull.

      Per application it is the sha that is serving, and then the previous DISTINCT sha — one
      rollback step, not a history. An application with no ACTIVE deployment is serving nothing and
      pins nothing, which is why the build whose image nobody published is absent from this answer
      entirely.

      It is a machine peer's read and not a person's: the collector holds a bearer for this
      platform audience with qits:system in it, and no browser session has business here.
      And it reads NOTHING but the deployment rows — no orchestrator, no topology, no peer — which
      is what lets a collector ask before every sweep and abort the whole plan when the answer does
      not come.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, BuildDeploymentIT.class})
  @Order(2)
  void theKeepSetIsTheRollbackTargetRatherThanASecondDefinition(
      Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.COLLECTOR);
    String bearer = StoryIdentities.machineToken(StoryIdentities.COLLECTOR);
    MINTED.add(bearer);

    JsonPath pins =
        StoryIdentities.bearer(given(), bearer)
            .get(StoryTarget.PINS_PATH)
            .then()
            .statusCode(200)
            .body("pins.applicationName", hasItem(BuildDeploymentIT.WEB))
            // Nothing published an image for it and nothing is serving it, so it keeps no tag alive.
            .body("pins.applicationName", not(hasItem(BuildDeploymentIT.UNPUBLISHED)))
            .extract()
            .jsonPath();
    story
        .note(
            "the collector presents its own machine bearer and gets one entry per application that"
                + " is serving something — an application serving nothing pins nothing")
        .as("pins-served");

    List<String> shas = pins.getList("pins.find { it.applicationName == '" + BuildDeploymentIT.WEB + "' }.shas");
    assertTrue(
        shas.contains(BuildDeploymentIT.SECOND_VERSION),
        "the sha that is serving is not pinned: " + shas);
    assertTrue(
        shas.contains(BuildDeploymentIT.FIRST_VERSION),
        "the sha a rollback would put back is not pinned: " + shas);
    story
        .note(
            "and it pins two shas for " + BuildDeploymentIT.WEB + ": the one serving, and the one"
                + " distinct predecessor a rollback would pull again — one step, never a history")
        .as("rollback-step-pinned");

    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, PINS_STORE_LABEL);
  }

  @AfterAll
  static void everyOperationsStoryIsComplete() {
    // --- the overview ------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, OVERVIEW_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of("tiers-listed", "catalogue-listed", "applications-listed", "history-listed")) {
      ReportAssertions.assertStepId(CATEGORY, OVERVIEW_SLUG, step);
    }
    for (String path :
        List.of(
            StoryTarget.ENVIRONMENTS_PATH,
            StoryTarget.SERVICES_PATH,
            StoryTarget.APPLICATIONS_PATH,
            StoryTarget.DEPLOYMENTS_PATH)) {
      read(OVERVIEW_SLUG, StoryIdentities.OPERATOR, "GET " + path + " -> 200");
    }
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, OVERVIEW_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, OVERVIEW_STORE_LABEL);
    // Four reads and one store. Nothing left this process to answer any of them — the topology is a
    // repository query now, and the deployment rows name their service and their tier as plain
    // strings so history keeps answering whatever the catalogue says today.
    ReportAssertions.assertEdgeCount(CATEGORY, OVERVIEW_SLUG, 5);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, OVERVIEW_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNoEdgesTo(CATEGORY, OVERVIEW_SLUG, StorySwarm.ORCHESTRATOR);

    // --- the keep-set -------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, PINS_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, PINS_SLUG, "pins-served");
    ReportAssertions.assertStepId(CATEGORY, PINS_SLUG, "rollback-step-pinned");
    read(PINS_SLUG, StoryIdentities.COLLECTOR, "GET " + StoryTarget.PINS_PATH + " -> 200");
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, PINS_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, PINS_STORE_LABEL);
    // TWO, and the absences are what a fail-closed caller is buying: one read, one store, and no
    // hop this component could have been made to wait on while a sweep was being planned.
    ReportAssertions.assertEdgeCount(CATEGORY, PINS_SLUG, 2);
    ReportAssertions.assertNoEdgesTo(CATEGORY, PINS_SLUG, StorySwarm.ORCHESTRATOR);
    ReportAssertions.assertNoEdgesTo(CATEGORY, PINS_SLUG, StoryPeers.GIT_HOST);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, PINS_SLUG, List.of(StoryIdentities.COLLECTOR, StoryTarget.SERVICE));

    for (String slug : List.of(OVERVIEW_SLUG, PINS_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }

  private static void read(String slug, String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }
}
