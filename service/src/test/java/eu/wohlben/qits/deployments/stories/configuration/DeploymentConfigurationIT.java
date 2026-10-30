package eu.wohlben.qits.deployments.stories.configuration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import eu.wohlben.qits.userflows.Labels;
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
 * <b>Where a deployed container's configuration comes from</b>: the settings store, a table in this
 * component's own database (qits-1393, epic qits-765).
 *
 * <p>The direction is the first thing to read off the diagram: <b>nothing pushes anything into a
 * deployment</b>. An operator states a setting — one environment variable for one application in
 * one tier — and that is a row and nothing else; the running service is not touched. The NEXT
 * deployment of that place reads the rows while it assembles the orchestrator's argv, once per
 * argv, and the container comes up carrying them.
 *
 * <p>It used to be qits-configuration, read over HTTP with a machine credential minted at the idp,
 * plus a declaration seeded into it before every deployment. That service, the credential and the
 * seed are gone, and so are their arrows: the settings read is a JDBC edge to the store this
 * component already has.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeploymentConfigurationIT {

  static final String CATEGORY = "configuration";

  static final String READ_SLUG = "a-deployment-carries-the-settings-stated-for-its-place";

  static final String CONFIGURED = StoryPeers.CONFIGURED;

  static final String CONFIGURED_REPO_ID = "story-configured-storage-id";

  static final String CONFIGURED_VERSION = "2026.903.101";

  static final String CONFIGURED_SERVICE = StoryTarget.wireAlias(CONFIGURED);

  /** The one setting the story states, and the variable it becomes on the argv. */
  static final String SETTING_KEY = "QITS_FEATURE_FLAGS";

  static final String SETTING_VALUE = "trace-headers";

  static final String SETTING_VARIABLE = SETTING_KEY + "=" + SETTING_VALUE;

  static final String SETTING_PATH =
      StoryTarget.API_PATH + "/settings/" + CONFIGURED + "/" + StoryTarget.TIER + "/" + SETTING_KEY;

  static final String STORE = "postgresql";

  static final String STORE_LABEL =
      "the setting and the deployment row, and the settings the argv is assembled from";

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapEveryHopADeploymentMakes() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StorySwarm.installSource();
    StoryPeers.install();
  }

  @BeforeEach
  void theTierExists() {
    StoryPlatform.provision();
  }

  @UserStory(value = "A deployment carries the settings stated for its place", category = CATEGORY)
  @UserStoryDescription(
      """
      An application needs more than its image: an address of a peer, a feature flag, a secret.
      None of that is in the repository — it is a property of the installation, not of the code — so
      an operator states it in this component's settings store: one environment variable for one
      application in one tier.

      Stating it changes nothing that is running. The NEXT deployment of that place reads the
      settings while it assembles the orchestrator's argv, once per argv, and the container comes up
      carrying them — after the deployer's own identity variables, so a setting outranks a default.
      A setting deleted later is removed from the service by the deployment after that, because the
      store is the whole of what an application's environment is.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void theArgvIsAssembledFromTheSettingsStore(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryIdentities.person(given(), "story-operator")
        .contentType(ContentType.JSON)
        .body(Map.of("value", SETTING_VALUE, "secret", false))
        .put(SETTING_PATH)
        .then()
        .statusCode(200);
    story
        .note(
            "an operator states " + SETTING_KEY + " for " + CONFIGURED + " in " + StoryTarget.TIER
                + " — a row, and nothing running is touched")
        .as("setting-stated");

    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.machineToken(StoryIdentities.CI);
    MINTED.add(bearer);
    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            softwareReleased(
                "run-story-configured-1", CONFIGURED_REPO_ID, CONFIGURED, CONFIGURED_VERSION))
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(202);
    story.note("a green build of " + CONFIGURED).as("build-announced");

    JsonNode row = StoryPlatform.awaitSettled(CONFIGURED, CONFIGURED_VERSION);
    assertEquals("ACTIVE", row.path("status").asText(), "the deployment did not go live: " + row);

    List<String> argv = StorySwarm.argvOf("service create " + CONFIGURED_SERVICE);
    List<String> environment = StorySwarm.flagValues(argv, "--env");
    assertTrue(
        environment.contains(SETTING_VARIABLE), "the setting never reached the argv: " + argv);
    story
        .note(
            "the setting is on the created service as " + SETTING_VARIABLE
                + ", read from the store while the argv was assembled")
        .as("setting-reached-the-container");

    // This component writes its own variables on every argv, BEFORE the settings, because docker
    // keeps the last assignment of a repeated key — so they are defaults a setting overrides.
    assertTrue(
        environment.indexOf("QITS_APPLICATION=" + CONFIGURED) < environment.indexOf(SETTING_VARIABLE),
        "the deployer's own variables must be written before the settings: " + environment);
    story
        .note(
            "the deployer's own identity variables are written first, so a setting outranks what"
                + " this component defaults — the ordering IS the precedence rule")
        .as("setting-outranks-the-default");

    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

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
  static void everyConfigurationStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY, READ_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of(
            "setting-stated",
            "build-announced",
            "setting-reached-the-container",
            "setting-outranks-the-default")) {
      ReportAssertions.assertStepId(CATEGORY, READ_SLUG, step);
    }
    ReportAssertions.assertEdge(
        CATEGORY,
        READ_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        Labels.scrub("PUT " + SETTING_PATH + " -> 200"));
    ReportAssertions.assertEdge(
        CATEGORY,
        READ_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.CI,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.SOFTWARE_RELEASED_PATH + " -> 202");
    ReportAssertions.assertEdge(
        CATEGORY,
        READ_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryPeers.GIT_HOST,
        StoryPeers.specLabel(CONFIGURED, CONFIGURED_VERSION, 200));
    for (String call : StorySwarm.createCalls(CONFIGURED_SERVICE, CONFIGURED, CONFIGURED_VERSION)) {
      ReportAssertions.assertEdge(
          CATEGORY, READ_SLUG, StorySwarm.KIND, StoryTarget.SERVICE, StorySwarm.ORCHESTRATOR, call);
    }
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, READ_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    // One setting written, one event in, one peer read (the spec), eight questions to the
    // orchestrator, one store. No configuration service and no credential mint: the settings are
    // rows in the store this component already has.
    ReportAssertions.assertEdgeCount(CATEGORY, READ_SLUG, 12);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY,
        READ_SLUG,
        List.of(StoryIdentities.CI, StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    for (String bearer : MINTED) {
      ReportAssertions.assertNotLeaked(CATEGORY, READ_SLUG, bearer);
    }
  }
}
