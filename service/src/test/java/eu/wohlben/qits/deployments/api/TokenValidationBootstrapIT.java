package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.stories.support.StoryIdentities;
import eu.wohlben.qits.deployments.stories.support.StoryProfile;
import eu.wohlben.qits.deployments.stories.support.StoryTarget;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b> — like {@link PdPackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove. The shipped tenant is
 * {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}} and every suite in this
 * repository leaves that gate shut but one — and that one, {@link MachineGuardEnforcedTest}, opens
 * it by <b>inlining the verification key</b> and clearing {@code auth-server-url}, precisely so it
 * needs no idp. So the half of the shipped {@code quarkus.oidc.*} block that is about REACHING
 * qits-idp — the auth-server-url, the discovery document read from it and the JWKS that document
 * names, the boot-time fetch that {@code connection-delay} retries — is
 * exercised nowhere else at all. The far side here is {@link MockIdp}, whose recordings make the
 * interaction assertable on <b>both ends</b>.
 *
 * <p>It is also <b>the first class of this repository's userflow catalogue</b>, and the one that
 * owns the boot. The rest of the catalogue lives in {@code stories.*} and shares this class's
 * launched process, because they all name the same {@link StoryProfile}: one {@code @TestProfile} is
 * one launched artifact, so a second profile would be a second deployer with a second startup whose
 * traffic landed in whichever diagram happened to be open.
 *
 * <p>The diagram is <b>observed, never narrated</b> — {@code NetworkTaps.restAssured} (the framework
 * ships the filter this repo used to keep a copy of) taps what a story sends into this service,
 * {@link MockIdp}'s recordings supply what this service sent to the idp, and the framework drains
 * both at story end. A story method therefore asserts and notes; it draws nothing. The stories are
 * browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a cumulative
 * source is attributed by a cursor, so traffic that happened before any story ran — the startup JWKS
 * fetch, which is the whole subject of the first story — lands in whichever story drains
 * <i>first</i>. Pinning the order is what keeps that the story it belongs to, and this class sorting
 * first among the catalogue's packages ({@code …deployments.api} before {@code …deployments.stories})
 * is what keeps it the first story of the run.
 *
 * <p><b>The route both stories drive is {@code GET /deployments/api/pins}</b>, and it is
 * chosen because it is the one guarded read here whose caller is a MACHINE: {@link PdPinController}
 * takes {@code qits:system} rather than the {@code qits:admin} the environment
 * and deployment listings take, its one caller is qits-platform-artifacts' OCI garbage collector,
 * and it reads nothing but deployment rows — so what it answers is a fact about this instance's
 * own history and never about a peer this IT would then have to stand in for. A write would have
 * been the other candidate and is worse on every count: it has docker side effects.
 *
 * <p><b>ITs are skipped by default here and this one does NOT flip that</b>, unlike qits-githost's
 * namesake. {@code skipITs} is {@code true} in the root pom because {@link PdPackagedSurfaceIT} is
 * this module's other integration test and half of it is about the CLIENT — the base href, the deep
 * links, the fallback that must not swallow a machine path — which the userflow pipeline
 * deliberately does not build ({@code -Dquarkus.quinoa=false}, since the qits-spa-deployments
 * submodule arrives empty in a step container). A blanket {@code -DskipITs=false} would make that
 * run red on a test that is right. {@code .config/qits/ci-event-userflows.yml} names the story
 * classes instead, which is also what keeps the userflow pipeline about the stories and nothing
 * else.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-the-deployer-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-deployment-pin-ledger";

  private static final String PINS = StoryTarget.PINS_PATH;

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = StoryTarget.SERVICE;

  /** The pin ledger's one real caller: qits-platform-artifacts' OCI garbage collector. */
  static final String COLLECTOR = StoryIdentities.COLLECTOR;

  /** A person's session, which reaches this door only by a credential nobody should have minted. */
  static final String ADMIN_SESSION = "a person's session";

  private static final List<String> MINTED = new ArrayList<>();

  /**
   * Wires both halves of the network diagram, once, before either story runs.
   *
   * <p>The near side is the framework's own RestAssured tap: every request a story makes becomes
   * {@code <actor> -> qits-platform-deployments}, labelled with the method, the scrubbed path and the
   * status this service answered, and any path carrying a {@code /q/} segment is skipped — which is
   * right here, because {@code quarkus.http.non-application-root-path=/deployments/q}. This
   * repository kept a hand-written copy of that filter for one release; the framework ships it now
   * and the copy is gone.
   *
   * <p>The idp is the far side, registered as a <b>cumulative</b> source: the supplier hands over the
   * mock's whole request log every time it is asked and the framework remembers how much of it
   * earlier stories already consumed, so the startup fetch — recorded long before any story existed —
   * is attributed to the first story and to that one only. It is invoked lazily at story end, so
   * registering it here is safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  @UserStory(
      value = "On start, the deployer fetches the platform's signing keys",
      category = CATEGORY)
  @UserStoryDescription(
      """
      A freshly deployed qits-platform-deployments must validate service bearers before any
      caller arrives: at startup it reads qits-idp's discovery document and fetches the
      signing keys (JWKS) it names, so the very first machine request is
      accepted. qits-platform-artifacts' image collector reads the rollback pins with exactly
      this credential before it deletes anything.
      """)
  @Order(1)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-platform-deployments starts with the OIDC tenant on, beside a reachable"
            + " qits-platform-idp");
    given().get("/deployments/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. That is the claim the inlined-key suite cannot make, because it clears
    // auth-server-url precisely so that nothing is ever fetched.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), this service's side: those keys are what token validation now runs on. A platform
    // peer's bearer (aud = this service, roles in `groups`) opens the pin ledger — the read
    // qits-platform-artifacts plans its OCI sweep on, fail-closed, so an unreachable or refused
    // answer aborts the plan with nothing deleted.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `qits-platform-artifacts -> qits-platform-deployments`.
    NetworkCapture.actor(COLLECTOR);
    String collectorToken = StoryIdentities.machineToken(COLLECTOR);
    MINTED.add(collectorToken);
    StoryIdentities.bearer(given(), collectorToken)
        .get(PINS)
        .then()
        .statusCode(200)
        .body("pins", notNullValue());
    story
        .note(
            "the collector's bearer (aud=qits-platform,"
                + " groups=[qits:system]) opens the pin ledger")
        .as("pins-served");
  }

  @UserStory(
      value = "A stranger's token never opens the deployment pin ledger",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys. A token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks: both are 401 and not 403, because the credential never became an
      identity and there is no caller to have been forbidden. A token addressed here and signed
      correctly but carrying only a person's role gets the other answer, 403 — it authenticated
      and covers nothing. The pin ledger is a machine peer's read, and no browser session has
      business in it.
      """)
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // The first two credentials are an impostor's, so the actor is set once, up front; the admin
    // token below sets its own, because it is a person's session rather than a forgery.
    NetworkCapture.actor(StoryIdentities.IMPOSTOR);

    String strangersToken =
        idp.token()
            .audience(StoryIdentities.AUDIENCE)
            .groups(StoryIdentities.MACHINE_ROLE)
            .signedByUnknownKey()
            .mint();
    MINTED.add(strangersToken);
    StoryIdentities.bearer(given(), strangersToken).get(PINS).then().statusCode(401);
    // Both 401s are the same edge — same actor, same route, same status — so the diagram draws one
    // arrow and the notes are what keep the two credentials distinguishable. That is the right
    // division: the graph says who reached what and got what, the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
        .as("unknown-key-refused");

    String wrongAudienceToken = StoryIdentities.foreignAudienceToken("some-other-service");
    MINTED.add(wrongAudienceToken);
    StoryIdentities.bearer(given(), wrongAudienceToken).get(PINS).then().statusCode(401);
    story
        .note("a token minted for another service's audience is refused just the same")
        .as("wrong-audience-refused");

    // The third door, and the one that is this service's own shape rather than the fleet's: the
    // roles say who a caller is meant to be, and qits:admin is the PERSON's — it reaches
    // this service only as a forwarded X-Qits-Roles header from an admin session, never in a
    // token. Minted into one anyway it authenticates perfectly and still covers nothing here.
    //
    // A different initiator, so a different actor and therefore a different edge: this one is a
    // person's session reaching a machine peer's door, which is the whole of what the 403 says.
    NetworkCapture.actor(ADMIN_SESSION);
    String adminRoleToken = StoryIdentities.adminRoleToken("an-admin-session");
    MINTED.add(adminRoleToken);
    StoryIdentities.bearer(given(), adminRoleToken).get(PINS).then().statusCode(403);
    story
        .note("a token carrying only the person's role authenticates and still covers nothing here")
        .as("admin-role-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    // Observed on the near side, by the shipped tap, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", COLLECTOR, SERVICE, "GET " + PINS + " -> 200");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "pins-served");
    // TWO, and the readiness probe is deliberately not among them: the shipped tap skips any path
    // with a /q/ segment, and a diagram in which every node hangs off /q/health/ready documents
    // nothing. Nothing else left this process either — the bearer is judged on keys already held.
    ReportAssertions.assertEdgeCount(CATEGORY, ACCEPTED_SLUG, 2);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, ACCEPTED_SLUG, List.of(COLLECTOR, SERVICE));

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, "http", StoryIdentities.IMPOSTOR, SERVICE, "GET " + PINS + " -> 401");
    // The third door, and the only one of the three that answers 403 — a different initiator, so a
    // second arrow rather than a second label on the first.
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, "http", ADMIN_SESSION, SERVICE, "GET " + PINS + " -> 403");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "admin-role-refused");
    // Only the two refused callers initiate here. The service itself may or may not re-ask the idp
    // for its keys when a kid it has never seen arrives — which is quarkus-oidc's own patience and
    // not this component's decision — so the ACTOR set is the claim and the edge count is not.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, DENIED_SLUG, List.of(StoryIdentities.IMPOSTOR, ADMIN_SESSION, SERVICE));

    // No credential either story minted is anywhere in the bundle they publish.
    for (String slug : List.of(ACCEPTED_SLUG, DENIED_SLUG)) {
      for (String token : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, token);
      }
    }
  }
}
