package eu.wohlben.qits.deployments.stories.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * The platform a story <b>walks up to</b> rather than builds — one designated tier —
 * and the patience a story spends waiting for the deploy worker.
 *
 * <h2>Setup is invisible to the tap, by construction</h2>
 *
 * <p>A story's diagram must show the walk somebody takes, not the fixture somebody built. The tap
 * that would see this class is the framework's RestAssured filter, which is JVM-global once
 * installed — so everything here drives the API with a plain {@link HttpClient} instead, a client no
 * filter is attached to, and not one fixture request becomes an arrow into the deployer.
 *
 * <p>The tier is created with a MACHINE bearer and read back with the PERSON's header pair, because
 * that is what each door takes: a topology write is {@code qits:system}'s and the
 * deployment listing is {@code qits:admin}'s. A fixture that presented one credential
 * everywhere would be a fixture that could not exist against the running service.
 *
 * <h2>Provisioned once, for whichever story class runs first</h2>
 *
 * <p>Every story class that needs it calls {@link #provision()}; the first one does the work and the
 * rest find it done. That is what makes each class runnable on its own ({@code
 * -Dit.test=AccessRefusalIT}) while a full run creates exactly one tier. It has to be called from
 * {@code @BeforeEach} rather than {@code @BeforeAll}: it builds a url out of {@code
 * RestAssured.port}, which the Quarkus integration-test extension sets in beforeEach and clears back
 * to {@code -1} in afterEach.
 *
 * <h2>Waiting is a poll of the read surface, never a sleep</h2>
 *
 * <p>A software-release event is handled whole on {@code pd-deploy-worker} — spec read, registration,
 * queueing, the orchestrator, the cutover — and the intake returns 202 the moment it is queued. So
 * "the deployment finished" is only observable through the rows, which is also how a caller
 * experiences this service. {@link #awaitSettled} polls until the row for one (application, version)
 * leaves {@code QUEUED}/{@code STARTING}, which is exactly when every edge that deployment produces
 * is on disk.
 */
public final class StoryPlatform {

  /** Who the fixture writes as, on the record. It reaches nothing a story asserts, but it is read. */
  public static final String FIXTURE_USER = "the-userflow-fixture";

  /** The statuses a deployment is still moving through. Everything else is settled. */
  private static final Set<String> IN_FLIGHT = Set.of("QUEUED", "STARTING");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The fixture's own client — <b>not</b> RestAssured, which is where the tap lives. One per JVM,
   * because a client per request would leave a connection pool behind for each.
   */
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** How long a deployment may take to settle. The worker is serial, so this is a queue budget. */
  private static final Duration SETTLE_PATIENCE = Duration.ofSeconds(60);

  private static final Object LOCK = new Object();

  private static String tierId;

  private StoryPlatform() {}

  /**
   * Make sure {@link StoryTarget#TIER} exists and answer its id, once per JVM.
   *
   * <p>A 409 is not a failure: another story class provisioned it, or a previous class in this run
   * did. The id is then read back out of the listing, because the id is what a deployment listing is
   * scoped by and it is generated rather than named.
   */
  public static String provision() {
    synchronized (LOCK) {
      if (tierId != null) {
        return tierId;
      }
      HttpResponse<String> created =
          send(
              machineRequest(StoryTarget.ENVIRONMENTS_PATH)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"name\":\""
                              + StoryTarget.TIER
                              + "\",\"platform\":true}",
                          StandardCharsets.UTF_8))
                  .build(),
              201,
              409);
      tierId =
          created.statusCode() == 201
              ? json(created.body()).path("environment").path("id").asText()
              : findTierId();
      return tierId;
    }
  }

  /** The tier's id, provisioning it if this is the first story class to ask. */
  public static String tierId() {
    return provision();
  }

  /**
   * The settled deployment row for one (application, commit), or a failure naming what it saw.
   *
   * <p>Terminal is anything that is not {@code QUEUED} or {@code STARTING}: a story about a refused
   * deployment wants its {@code FAILED} row exactly as much as a story about a green one wants its
   * {@code ACTIVE}, so this waits for an ANSWER rather than for success.
   */
  public static JsonNode awaitSettled(String applicationName, String version) {
    long deadline = System.nanoTime() + SETTLE_PATIENCE.toNanos();
    JsonNode last = null;
    while (true) {
      last = deploymentOf(applicationName, version);
      if (last != null && !IN_FLIGHT.contains(last.path("status").asText())) {
        return last;
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "the deployment of "
                + applicationName
                + "@"
                + version
                + " never settled; the row is "
                + (last == null ? "not there at all" : last.toString()));
      }
      sleep();
    }
  }

  /** One recorded deployment of an application at a released version, or null while there is none. */
  public static JsonNode deploymentOf(String applicationName, String version) {
    JsonNode listed = get(StoryTarget.DEPLOYMENTS_PATH + "?environmentId=" + tierId());
    for (JsonNode row : listed.path("deployments")) {
      if (applicationName.equals(row.path("applicationName").asText())
          && version.equals(row.path("version").asText())) {
        return row;
      }
    }
    return null;
  }

  // --- the tap-invisible client -------------------------------------------------------------------

  private static String findTierId() {
    for (JsonNode row : get(StoryTarget.ENVIRONMENTS_PATH).path("environments")) {
      if (StoryTarget.TIER.equals(row.path("name").asText())) {
        return row.path("id").asText();
      }
    }
    throw new IllegalStateException(StoryTarget.TIER + " was refused as a duplicate and is not there");
  }

  private static JsonNode get(String path) {
    return json(send(personRequest(path).GET().build(), 200).body());
  }

  private static JsonNode json(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new UncheckedIOException("the fixture read answered unparseable JSON", e);
    }
  }

  /** A request builder for one path, carrying a signed-in admin's bearer. */
  private static HttpRequest.Builder personRequest(String path) {
    return request(path)
        .header("Authorization", "Bearer " + StoryIdentities.adminRoleToken(FIXTURE_USER));
  }

  /** A request builder carrying a machine peer's bearer — what every topology write takes. */
  private static HttpRequest.Builder machineRequest(String path) {
    return request(path)
        .header(
            "Authorization", "Bearer " + StoryIdentities.machineToken("the-userflow-fixture"));
  }

  private static HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + path))
        .timeout(REQUEST_TIMEOUT);
  }

  private static HttpResponse<String> send(HttpRequest request, int... accepted) {
    HttpResponse<String> response;
    try {
      response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(
          "fixture " + request.method() + " " + request.uri() + " failed", e);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "fixture " + request.method() + " " + request.uri() + " was interrupted");
    }
    for (int status : accepted) {
      if (response.statusCode() == status) {
        return response;
      }
    }
    throw new IllegalStateException(
        "fixture "
            + request.method()
            + " "
            + request.uri()
            + " answered "
            + response.statusCode()
            + ": "
            + response.body());
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
