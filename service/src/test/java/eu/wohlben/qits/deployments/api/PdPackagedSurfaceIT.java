package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The whole service as it is <b>packaged</b> — the fast-jar under {@code mvn verify
 * -DskipITs=false}, the GraalVM binary under {@code mvn verify -Dnative}. The assertions are chosen
 * for what a native build can silently lose rather than for API coverage (that is the
 * {@code @QuarkusTest} suite's job):
 *
 * <ul>
 *   <li>the routes are where the config says — {@code quarkus.rest.path} and {@code
 *       quarkus.http.non-application-root-path} are build-time settings baked into the artifact;
 *   <li>the shipped datasource <b>expression</b> resolves and connects, and {@code
 *       db/platformdeployments/migration/} survived as a resource — migrations are loaded by
 *       scanning a classpath location, exactly the shape native-image drops, and the claim reaches
 *       every table the component has, since one request writes the topology and another writes a
 *       deployment row;
 *   <li>both domains round-trip through Hibernate/Panache in the packaged process, in one
 *       transaction each. That is a claim the ancestors could not make: a deployment row and the
 *       topology it names lived in two databases behind an HTTP call.
 * </ul>
 *
 * <p>It is also <b>the only test here that ever sees the client</b>. Quinoa is disabled by default
 * in test mode, so no {@code @QuarkusTest} in this repo has a client in it at all — a unit test
 * asserting something about what is served would pass against a process serving nothing. What the
 * SPA is actually served as is proven here or nowhere.
 *
 * <p><b>The client is served at the root</b> since this service got a host of its own
 * ({@code deployments.<env>.<domain>}). The segment survives only as the wire prefix, so
 * {@code /deployments/} is a 404 rather than a second door into the client, and an old bookmark is
 * the edge's problem, answered there with a redirect.
 *
 * <p><b>The segment moved to {@code /deployments} on 2026-09-26, and the retired one is now answered
 * by a 404.</b> {@code /platform-deployments} was rewritten onto the live prefix for exactly one
 * release by an {@code api/LegacyPrefixReroute} filter; that class and its declaration in {@code
 * routes:} are gone, and the test that probed the rewrite went with them. Its entry in {@code
 * quarkus.quinoa.ignored-path-prefixes} did NOT go, and {@link
 * #aPathUnderTheRetiredPrefixIsNeverTheClient} is why: with nothing rewriting the prefix, every path
 * under it names no route, and Quinoa's catch-all answers a path naming no route with {@code
 * index.html} at 200. So there are three claims here only a packaged process can make — the live
 * prefix answering, a path under it naming no route being a 404 rather than the client, and the same
 * of the retired prefix. {@code quarkus.rest.path} and {@code
 * quarkus.http.non-application-root-path} are build-time settings baked into the artifact, so
 * nothing else here can check any of them.
 *
 * <p>No deployment is driven here: that needs a swarm, and the packaged process carries the real
 * {@link eu.wohlben.qits.deployments.swarmhost.SwarmDeploymentDriver}. The container
 * runtime is pointed at a binary that does not exist, which exercises the best-effort seam (an
 * environment must exist even when docker is unreachable) and keeps this IT free of host side
 * effects.
 */
@QuarkusIntegrationTest
@TestProfile(PdPackagedSurfaceIT.PackagedUnderTarget.class)
public class PdPackagedSurfaceIT {

  private static final String SEGMENT = "/deployments";

  /**
   * The retired spelling. It is here because this service must answer a 404 under it rather than the
   * client — see {@link #aPathUnderTheRetiredPrefixIsNeverTheClient} — and NOT because anything
   * serves it. Deleting this constant is deleting that claim.
   */
  private static final String LEGACY_SEGMENT = "/platform-deployments";

  /** What the client's index.html spells now that it is mounted at the root of its own host. */
  private static final String BASE_HREF = "<base href=\"/\">";

  /**
   * Hands the launched artifact its databases the way a deployment does — as the generic resource
   * triples, not as the datasource keys. The environments jar ships {@code
   * jdbc.url=${QITS_RESOURCE_DB_URL}} and its two siblings and the qits-eventstream jar ships the
   * same three over {@code QITS_RESOURCE_EVENTSTREAM_*}, so supplying the variables leaves the
   * <b>shipped</b> expressions themselves under test (the AUTO_SERVER lesson from qits-ci, applied
   * to what replaced that URL). Expression expansion reads the whole config, and these overrides
   * reach the launched process as system properties, so the same six names resolve.
   *
   * <p><b>Both are mandatory, which is itself the claim.</b> Neither jar's expressions have a
   * default behind them, so a packaged process missing either triple dies at Flyway naming what is
   * absent rather than opening a store nobody meant — and this IT is the only place that boots the
   * shipped artifact and would find out.
   *
   * <p>The databases are an embedded postgres this JVM starts. <b>Their urls travel through system
   * properties rather than static fields</b>: a test profile is instantiated in more than one
   * classloader, so a field written by one copy is not the field the other reads, while the process
   * has exactly one property table.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    /** Where the urls are parked for whichever copy of this class is asked second. */
    private static final String URL_PROPERTY = "qits.test.packaged-it.db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.packaged-it.eventstream-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(URL_PROPERTY, "pd_packaged_it"),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          // The bus client's store, and this IT is where its shipped expressions are exercised
          // too. It is not optional: the jar's three keys have no defaults, so a packaged process
          // started without this triple dies at Flyway naming what is missing — which is the
          // refuse-to-boot stance, and the reason .config/qits/deployments.yml declares the
          // resource. Dark or not, the datasource opens and migrates.
          "QITS_RESOURCE_EVENTSTREAM_URL",
              databaseUrl(EVENTSTREAM_URL_PROPERTY, "pd_eventstream_packaged_it"),
          "QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD,
          // No docker on purpose: every driver call must degrade to a warning, never a failure.
          "qits.deployments.container-runtime", "docker-absent-for-this-it",
          // The packaged process runs the PROD profile, where the machine-token gate now ships ON
          // (the live value, qits-765). This IT's posture is gate-off — forward-auth headers only,
          // no issuer to reach — so it is stated here rather than inherited. StoryProfile below is
          // the gate-on twin and sets the key the other way.
          "qits.auth.machine.required", "false");
    }

    /**
     * The parking trick itself, {@code protected} so a subclass in another package can reuse it
     * rather than copy it.
     *
     * <p>{@code stories.support.StoryProfile} needs databases of its OWN — the story catalogue
     * writes tiers, services and deployment rows, and sharing a database with this IT would make
     * each suite's assertions depend on whether the other ran. What it must not have of its own is a
     * second copy of the two-classloader workaround, which is the thing that is easy to get subtly
     * wrong; so the names are the subclass's and the mechanism stays here.
     */
    protected static synchronized String databaseUrl(String property, String database) {
      String recorded = System.getProperty(property);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(database);
      System.setProperty(property, url);
      return url;
    }
  }

  @Test
  public void theClientIsServedAtTheRoot() {
    given().when().get("/").then().statusCode(200).contentType(ContentType.HTML);
  }

  /**
   * The one spelling no build here can check any other way: the client's own baseHref, set in
   * qits-spa-deployments' angular.json. The client is mounted at the root of this service's host, so
   * the value is {@code /} — and a page served with anything else loads and then fetches its own
   * JavaScript from somewhere nothing serves: green build, blank screen.
   */
  @Test
  public void theClientAsksForItsAssetsAtTheRoot() {
    String index = given().when().get("/").then().statusCode(200).extract().asString();
    assertTrue(index.contains(BASE_HREF), "index.html does not carry " + BASE_HREF);
  }

  @Test
  public void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    given().when().get("/some/route").then().statusCode(200).contentType(ContentType.HTML);

    // The project-scoped form of the one page. `/qits` is an address the client routes and the
    // server knows nothing about, and it has to survive a reload like any other.
    given().when().get("/qits").then().statusCode(200).contentType(ContentType.HTML);
  }

  @Test
  public void theWireSegmentIsNoDoorIntoTheClient() {
    // The whole /deployments prefix is in quarkus.quinoa.ignored-path-prefixes, so nothing under it
    // is rerouted to index.html.
    given().when().get(SEGMENT).then().statusCode(404);
    String body = given().when().get(SEGMENT + "/").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains(BASE_HREF), "the wire segment must not serve the client; got: " + body);
  }

  /**
   * A path under the RETIRED prefix is a 404 and never the client, which is the second entry in
   * {@code quarkus.quinoa.ignored-path-prefixes} doing the only job it has left.
   *
   * <p>This is the assertion that was missing when the reroute was deleted, and its absence shipped
   * the defect qits-380 was filed about. While {@code api/LegacyPrefixReroute} existed it rewrote
   * the prefix before Quinoa's catch-all could see it, so the entry only mattered for a legacy path
   * naming no route. With the reroute gone, EVERY path under the retired prefix names no route — so
   * the catch-all claims the whole prefix, and {@code /platform-deployments/api/environments} came
   * back {@code 200 text/html} carrying index.html on the deployed {@code 2026.926.170022}. A
   * machine parses that as data, and every machine caller of this service dials the wire alias
   * directly, so nothing in front of the process would catch it.
   *
   * <p>Both surfaces are probed because the one prefix covers both, and the assertion is "404, and
   * not the CLIENT" for the reason its live-prefix sibling gives: what answers is Vert.x' own stock
   * page, which is {@code text/html} and correct.
   */
  @Test
  public void aPathUnderTheRetiredPrefixIsNeverTheClient() {
    String index = given().when().get("/").then().statusCode(200).extract().asString();

    for (String path :
        List.of(
            LEGACY_SEGMENT + "/api/environments",
            LEGACY_SEGMENT + "/api/nope",
            LEGACY_SEGMENT + "/q/health/ready")) {
      String body = given().when().get(path).then().statusCode(404).extract().asString();
      assertFalse(
          body.equals(index),
          "the retired prefix must not be answered with the client at " + path + "; got: " + body);
      assertFalse(
          body.contains(BASE_HREF),
          "the retired prefix must not be answered with the client at " + path + "; got: " + body);
    }
  }

  @Test
  public void aMistypedMachinePathIsNeverTheClient() {
    // The whole reason quarkus.quinoa.ignored-path-prefixes is set: without the segment in that
    // list this answers 200 with index.html, and qits-ci's intake — which swallows delivery
    // failures at debug — would parse the client's not-found page as an accepted delivery.
    //
    // The assertion is "404, and not the CLIENT" rather than "404, never HTML", because what comes
    // back here is Vert.x' own stock `<h1>Resource not found</h1>` — text/html, and correct. Every
    // sibling answers a mistyped machine path the same way; asserting on the content type alone
    // would fail against the right behaviour while still passing against the wrong one.
    String index = given().when().get("/").then().statusCode(200).extract().asString();

    String body =
        given().when().get(SEGMENT + "/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.equals(index),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // /q is the other half of what the one prefix covers — this pins that the single absolute entry
    // did not lose it.
    String underQ =
        given().when().get(SEGMENT + "/q/health/nope").then().statusCode(404).extract().asString();
    assertFalse(
        underQ.equals(index),
        "a mistyped non-application path must not be answered with the client; got: " + underQ);

    // The edge path-routes verbatim by prefix, so there is no unprefixed form to fall back to — and
    // at the root an unprefixed /api/environments is the CLIENT's ground, which is why the check is
    // that it never answers as the API.
    given().when().get("/api/environments").then().statusCode(200).contentType(ContentType.HTML);
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    // The path this component's own health gate curls for a peer, at the address the deployment
    // convention assumes — under quarkus.http.non-application-root-path, not the rest path. It is
    // also the path the health-path convention derives for this very service's name, which is what
    // makes a self-deployment gate on something that exists.
    given()
        .when()
        .get(SEGMENT + "/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheGatewaySegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries the segment on its own; at / they would be unreachable through qits-gateway.
    given().when().get(SEGMENT + "/q/openapi").then().statusCode(200);
    given().when().get(SEGMENT + "/q/swagger-ui/").then().statusCode(200);
  }

  /**
   * The identity a call on this surface has to carry, and the reason it is a header pair rather
   * than a bearer.
   *
   * <p>Every endpoint here carries a {@code @RolesAllowed} and has done since the surface was
   * closed, so an anonymous request is a 401 whatever else is right about the artifact. This
   * profile pins the machine gate {@code false} (the prod default ships {@code true} since
   * qits-765, so this IT states the gate-off posture explicitly rather than inheriting it), which
   * is what keeps {@code quarkus.oidc.tenant-enabled} off and this IT free of an idp to reach — so
   * there is no tenant to validate a bearer with, and the forward-auth pair qits-auth-core reads is the only
   * identity a packaged process in this posture accepts. It is a real one: the platform edge
   * asserts it for a session, and the bootstrap asserts it on its own hop over qits-net.
   *
   * <p><b>Nothing here is a claim about authorization</b> — the three doors and the fact that the
   * two role sets do not overlap are {@code MachineGuardEnforcedTest}'s and {@code
   * stories.refusals.AccessRefusalIT}'s, both of which run with the gate ON and real tokens. What
   * this IT needs is to get past the annotation so it can assert what it is actually about: the
   * baked-in route prefixes, the shipped datasource expressions and the migrations. So each call
   * states the ONE role its own door names — never both at once, which would be a caller the
   * platform does not have.
   */
  private static final String USER_HEADER = "X-Qits-User";

  private static final String ROLES_HEADER = "X-Qits-Roles";

  /** {@code given()} carrying the machine role the writes and the intake name. */
  private static RequestSpecification machine() {
    return given().header(USER_HEADER, "packaged-surface-it").header(ROLES_HEADER, "qits:system");
  }

  /** …and the person's role every read of this surface names. */
  private static RequestSpecification person() {
    return given().header(USER_HEADER, "packaged-surface-it").header(ROLES_HEADER, "qits:admin");
  }

  @Test
  public void theReleaseIntakeIsAtTheAddressAReplayPostsTo() {
    // The manual door is fire-and-forget: a wrong path raises no error on either side and
    // deployments simply never happen, so the address is asserted from the artifact. An empty body
    // must reach @Valid — a 400 proves the resource, not the router's 404.
    machine()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(SEGMENT + "/api/events/software-released")
        .then()
        .statusCode(400);
  }

  @Test
  public void bothDomainsRoundTripAgainstTheShippedSchema() {
    // One request writes the topology (pd_environment, pd_service, pd_service_link) and the next
    // writes execution history (pd_deployment) — so a migration that did not make it into the
    // artifact shows up here, whichever table it was for.
    String environmentId =
        machine()
            .contentType(ContentType.JSON)
            // The entry tier: a release lands in the designated platform environment.
            .body(Map.of("name", "packaged-env", "platform", true))
            .when()
            .post(SEGMENT + "/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    person()
        .when()
        .get(SEGMENT + "/api/environments")
        .then()
        .statusCode(200)
        .body("environments.name", org.hamcrest.Matchers.hasItem("packaged-env"));

    // This process has no git host, so the spec read fails and resolution falls back to what the
    // catalogue already holds — the only path that reaches a deployment row here, and one that
    // needs the topology and the history in one transaction.
    machine()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "deploymentTarget", "ENVIRONMENT",
                "availableOnEnv", false,
                "environmentIds", List.of(environmentId)))
        .when()
        .put(SEGMENT + "/api/services/packaged-repo")
        .then()
        .statusCode(201);

    machine()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61",
                "repoId", "packaged-repo",
                "version", "2026.903.193059"))
        .when()
        .post(SEGMENT + "/api/events/software-released")
        .then()
        .statusCode(202);

    // The whole event runs on the worker, registration included, so the row appears a moment after
    // the 202 rather than during it.
    long deadline = System.currentTimeMillis() + 30_000;
    String runId = null;
    while (runId == null && System.currentTimeMillis() < deadline) {
      runId =
          person()
              .when()
              .get(SEGMENT + "/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .path("deployments[0].runId");
      if (runId == null) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    assertEquals("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61", runId);
  }
}
