package eu.wohlben.qits.deployments.stories.refusals;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.deployments.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.deployments.stories.support.StoryIdentities;
import eu.wohlben.qits.deployments.stories.support.StoryPeers;
import eu.wohlben.qits.deployments.stories.support.StoryPlatform;
import eu.wohlben.qits.deployments.stories.support.StoryProfile;
import eu.wohlben.qits.deployments.stories.support.StorySwarm;
import eu.wohlben.qits.deployments.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
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
 * <b>The two role sets, and the fact that they do not overlap.</b>
 *
 * <p>Every endpoint of this surface carries a {@code @RolesAllowed} and there are exactly two roles.
 * {@code qits:system} is a machine's and arrives only in an idp-minted bearer; it opens the
 * build intake, every topology write, the rollback pins and the deployment-request listing
 * qits-projects draws a release's deploy phase from. {@code qits:admin} is a
 * person's and arrives in a signed-in person's bearer; it opens every read.
 *
 * <p>A machine token never carries the admin role and a browser session never carries the system
 * one — so each credential is refused by the other's doors, and it is refused with <b>403 rather
 * than 401</b>: the caller authenticated perfectly, and what it is missing is a grant. Knowing which
 * of the two shut is how a missing grant is debugged, and it is why these are three stories rather
 * than one.
 *
 * <p><b>Every story here also claims an absence, and the absence is the point.</b> A refused build
 * event must reach neither the git host nor the orchestrator: a component that read a repository's
 * spec before deciding whether the caller may deploy would be doing work on behalf of anybody who
 * can reach its port. So each diagram is one arrow in and nothing out at all.
 *
 * <p>None of this is provable in a {@code @QuarkusTest}. {@code qits-auth-core}'s {@code %test} dev
 * identity holds every platform role and is {@code LaunchMode}-guarded, so inside the suite no route
 * here refuses anybody; a launched artifact runs in {@code NORMAL} mode with {@code
 * qits.auth.machine.required=true}, and that is the only posture in which these answers exist.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AccessRefusalIT {

  static final String CATEGORY = "refusals";

  static final String MACHINE_SLUG = "a-machine-token-opens-no-operators-read";
  static final String PERSON_SLUG = "a-persons-session-opens-no-machine-door";
  static final String ANONYMOUS_SLUG = "a-build-event-nobody-may-send-deploys-nothing";

  /** A service name nothing has ever registered — an authored literal, so the label is stable. */
  static final String UNREGISTERED = "story-refused";

  /**
   * A commit that must never be deployed. It is a real sha shape, because the refusal has to happen
   * before anything validates it — a 403 that depended on the payload being well-formed would be a
   * 403 the intake had already thought about.
   */
  static final String REFUSED_VERSION = "2026.903.103";

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapWhatARefusedCallerSends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StorySwarm.installSource();
    StoryPeers.install();
  }

  @BeforeEach
  void theTierExists() {
    StoryPlatform.provision();
  }

  @UserStory(value = "A machine token opens no operators read", category = CATEGORY)
  @UserStoryDescription(
      """
      qits-ci holds a perfectly good bearer for this service: the right issuer, the right signature,
      the right audience, and qits:system in its groups claim — the credential that opens
      the build intake it uses every day. It opens none of the operator's reads.

      That is a decision rather than an omission. The read surface describes the whole platform —
      which applications exist, which tiers they are in, what was deployed and what failed — and it
      is a person's, projected into the client an admin session reaches. A machine that could read
      it would be a machine that could enumerate the platform with a credential granted for one
      narrow write. So the two sets are disjoint, and a peer asking the wrong question is told 403
      rather than 401: it is known, and it may not.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aPlatformPeersBearerIsRefusedByTheOperatorsReads(Interactions story) {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.machineToken(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .get(StoryTarget.APPLICATIONS_PATH)
        .then()
        .statusCode(403);
    story
        .note(
            "the same bearer that opens the build intake is refused the application listing — 403,"
                + " so the caller knows the credential was understood and the grant is missing")
        .as("applications-refused");

    StoryIdentities.bearer(given(), bearer)
        .get(StoryTarget.DEPLOYMENTS_PATH + "?environmentId=" + StoryPlatform.tierId())
        .then()
        .statusCode(403);
    story
        .note(
            "and the deployment history with it: what ran, what failed and at which commit is what"
                + " an operator is shown, not what a peer is entitled to enumerate")
        .as("history-refused");
  }

  @UserStory(value = "A persons session opens no machine door", category = CATEGORY)
  @UserStoryDescription(
      """
      The mirror image, and it is the half that would be tempting to soften. An administrator is the
      most privileged person on this platform, and their session carries qits:admin —
      which opens every read here and not one write.

      The machine surface is a machine's because of what is behind it: the intake queues a
      deployment of whatever commit it names, and a topology write decides which branch rolls which
      tier. Those are things a service does on the platform's behalf, with a credential a client was
      granted, and not things a browser session should be able to do because somebody is logged in.
      A person who needs a deployment triggers a build; a person who needs a tier uses the bootstrap.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void anAdminSessionIsRefusedByEveryWrite(Interactions story) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);

    StoryIdentities.person(given(), "story-operator")
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId",
                "run-refused",
                "repoId",
                UNREGISTERED,
                "projectId",
                StoryTarget.PROJECT,
                "repoName",
                UNREGISTERED,
                "version",
                REFUSED_VERSION))
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(403);
    story
        .note(
            "an admin session cannot announce a release: the intake is qits-ci's door, and what comes"
                + " through it deploys whatever commit it names")
        .as("intake-refused");

    StoryIdentities.person(given(), "story-operator")
        .contentType(ContentType.JSON)
        .body(Map.of("deploymentTarget", "ENVIRONMENT", "availableOnEnv", false))
        .put(StoryTarget.SERVICES_PATH + "/" + UNREGISTERED)
        .then()
        .statusCode(403);
    story
        .note(
            "nor register a service: the catalogue is DERIVED from each repository's own"
                + " deployments.yml on a green build, and this door exists for the machine that"
                + " does the deriving")
        .as("catalogue-write-refused");
  }

  @UserStory(value = "A build event nobody may send deploys nothing", category = CATEGORY)
  @UserStoryDescription(
      """
      The intake is a cross-repo contract and it is fire-and-forget: qits-ci POSTs and never learns
      what came of it. That makes the refusal path the one worth being precise about — a sender is
      told nothing useful either way, so what matters is that a caller who may not send one changes
      nothing at all.

      Two credentials are refused here and both are 401 rather than 403, because neither ever
      becomes an identity: a request with no Authorization header is anonymous, and a token minted
      for another service's audience is rejected by quarkus-oidc before any identity exists. And
      the diagram is the claim: one arrow in, and nothing out. The git host was not asked for a
      spec, the orchestrator was not asked anything, and no row was written — this component does no
      work on behalf of a caller it has not admitted.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(3)
  void anUnadmittedSenderChangesNothing(Interactions story) {
    NetworkCapture.actor(StoryIdentities.IMPOSTOR);

    given()
        .contentType(ContentType.JSON)
        .body(payload())
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(401);
    story
        .note("a build event with no credential at all is 401 — nothing here is open")
        .as("anonymous-refused");

    String foreign = StoryIdentities.foreignAudienceToken("a-confused-peer");
    MINTED.add(foreign);
    StoryIdentities.bearer(given(), foreign)
        .contentType(ContentType.JSON)
        .body(payload())
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(401);
    // Both refusals are the same edge — same actor, same route, same status — so the diagram draws
    // one arrow and the notes are what keep the two credentials apart. That is the right division:
    // the graph says who reached what and got what, the steps say why.
    story
        .note(
            "and a bearer minted for another service's audience is 401 too, refused by"
                + " quarkus.oidc.token.audience before any identity exists")
        .as("wrong-audience-refused");
  }

  private static Map<String, Object> payload() {
    return Map.of(
        "runId", "run-refused",
        "repoId", UNREGISTERED,
        "projectId", StoryTarget.PROJECT,
        "repoName", UNREGISTERED,
        "version", REFUSED_VERSION);
  }

  @AfterAll
  static void everyRefusalStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY, MACHINE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, MACHINE_SLUG, "applications-refused");
    ReportAssertions.assertStepId(CATEGORY, MACHINE_SLUG, "history-refused");
    refused(MACHINE_SLUG, StoryIdentities.CI, "GET " + StoryTarget.APPLICATIONS_PATH + " -> 403");
    refused(MACHINE_SLUG, StoryIdentities.CI, "GET " + StoryTarget.DEPLOYMENTS_PATH + " -> 403");
    ReportAssertions.assertEdgeCount(CATEGORY, MACHINE_SLUG, 2);
    nothingLeftThisProcess(MACHINE_SLUG);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, MACHINE_SLUG, List.of(StoryIdentities.CI));

    ReportAssertions.assertComplete(CATEGORY, PERSON_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, PERSON_SLUG, "intake-refused");
    ReportAssertions.assertStepId(CATEGORY, PERSON_SLUG, "catalogue-write-refused");
    refused(
        PERSON_SLUG,
        StoryIdentities.OPERATOR,
        "POST " + StoryTarget.SOFTWARE_RELEASED_PATH + " -> 403");
    refused(
        PERSON_SLUG,
        StoryIdentities.OPERATOR,
        "PUT " + StoryTarget.SERVICES_PATH + "/" + UNREGISTERED + " -> 403");
    ReportAssertions.assertEdgeCount(CATEGORY, PERSON_SLUG, 2);
    nothingLeftThisProcess(PERSON_SLUG);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, PERSON_SLUG, List.of(StoryIdentities.OPERATOR));

    ReportAssertions.assertComplete(CATEGORY, ANONYMOUS_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, ANONYMOUS_SLUG, "anonymous-refused");
    ReportAssertions.assertStepId(CATEGORY, ANONYMOUS_SLUG, "wrong-audience-refused");
    refused(
        ANONYMOUS_SLUG,
        StoryIdentities.IMPOSTOR,
        "POST " + StoryTarget.SOFTWARE_RELEASED_PATH + " -> 401");
    // ONE, for two credentials: the diagram says which dependencies exist and what answered, and
    // the two refusals are the same dependency answering the same way.
    ReportAssertions.assertEdgeCount(CATEGORY, ANONYMOUS_SLUG, 1);
    nothingLeftThisProcess(ANONYMOUS_SLUG);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, ANONYMOUS_SLUG, List.of(StoryIdentities.IMPOSTOR));

    for (String slug : List.of(MACHINE_SLUG, PERSON_SLUG, ANONYMOUS_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }

  /**
   * The claim every story in this class shares: a refused caller costs the platform nothing.
   *
   * <p>{@code assertNoEdgesFrom} rather than three {@code assertNoEdgesTo}s, because the statement
   * is not "the orchestrator was not asked" — it is that <b>this process initiated nothing at
   * all</b>. No class here declares an edge from the service for exactly that reason: a declared
   * store would make the claim unspellable.
   */
  private static void nothingLeftThisProcess(String slug) {
    ReportAssertions.assertNoEdgesFrom(CATEGORY, slug, StoryTarget.SERVICE);
    ReportAssertions.assertNoEdgesTo(CATEGORY, slug, StorySwarm.ORCHESTRATOR);
    ReportAssertions.assertNoEdgesTo(CATEGORY, slug, StoryPeers.GIT_HOST);
  }

  private static void refused(String slug, String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }
}
