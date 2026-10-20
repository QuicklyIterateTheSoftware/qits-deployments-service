package eu.wohlben.qits.deployments.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.FakeDeclarationSeed;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeResourceProvisioner;
import eu.wohlben.qits.deployments.deployments.control.FakeIdpClientProvisioner;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.events.NavigationEntry;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A real deployment, through the real intake and the real worker, ending as rows in the real
 * outbox. The producing half of this component's bus wiring, asserted end to end: what
 * {@code DeployService} announces at each of the four lifecycle points, with the payload a consumer
 * will read and the causation edge back to the build that caused it.
 *
 * <p><b>The bus is aimed at a CLOSED PORT, deliberately, and that is what makes this assertable.</b>
 * {@code QitsEventBus.publish} attempts the idempotent PUT inline and hands anything that does not
 * land to the outbox — so against a port nothing answers, every published event becomes exactly one
 * row, whole, with the canonical payload it would have been sent with. A stub qits-events would
 * prove the same thing and add a second moving part; a row IS the publish, from this side of the
 * bus.
 *
 * <p>The scheduler is off in this profile, so no sweeper retries a row out from under an assertion,
 * and the catch-up sweep does not run at startup for the same reason.
 *
 * <p>A class of its own rather than more cases in {@link PdCausationTest}, because a bus that is on
 * is a different process configuration and {@code @TestProfile} is per class.
 */
@QuarkusTest
@TestProfile(PdDeployPublishTest.BusEnabled.class)
public class PdDeployPublishTest {

  private static final String VERSION = "2026.903.193059";

  public static class BusEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.eventstream.enabled", "true",
          // Nothing answers here. A refused connection is immediate, so the inline attempt costs
          // no wall time and the event lands in the outbox rather than on a socket.
          "qits.events.url", "http://localhost:1",
          "quarkus.scheduler.enabled", "false",
          "qits.eventstream.catchup-at-startup", "false");
    }
  }

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject FakeDeclarationSeed seeds;
  @Inject DeployService deployService;

  /**
   * The outbox is read through its OWN persistence unit's EntityManager rather than through
   * Panache's static methods: {@code OutboxEvent} arrives from the qits-eventstream jar, and a
   * Panache static on an entity this application did not compile is not enhanced here — it throws
   * "did you forget to annotate your entity with @Entity?", which names the wrong problem.
   */
  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
    seeds.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  @Test
  public void aGreenBuildAnnouncesQueuedStartedAndActiveInThatOrder() {
    String environmentId = createEnvironment("pub-green");
    String cause = UUID.randomUUID().toString();

    postRelease("run-pub-green", "repo-pub-green", cause);
    awaitSettled(environmentId, 1);

    OutboxEvent queued = only("DeploymentQueued");
    OutboxEvent started = only("DeploymentStarted");
    // Exactly one, and that is a claim: a file that declares no `renamed_from` retires nobody.
    OutboxEvent active = only("DeploymentActive");

    // One deployment, one id, three statements about it.
    String deploymentId = deploymentIdOf(queued);
    assertTrue(started.payload.contains("\"deploymentId\":\"" + deploymentId + "\""),
        started.payload);
    assertTrue(active.payload.contains("\"deploymentId\":\"" + deploymentId + "\""), active.payload);

    for (OutboxEvent event : List.of(queued, started, active)) {
      assertTrue(event.payload.contains("\"applicationName\":\"repo-pub-green\""), event.payload);
      assertTrue(event.payload.contains("\"environmentId\":\"" + environmentId + "\""),
          event.payload);
      assertTrue(event.payload.contains("\"environmentName\":\"pub-green\""), event.payload);
      assertTrue(event.payload.contains("\"version\":\"" + VERSION + "\""), event.payload);
      assertTrue(event.payload.contains("\"runId\":\"run-pub-green\""), event.payload);
      assertNotNull(event.occurredAt);
      assertEquals(cause, event.parentId, "every event of one deployment names the same cause");
    }

    // The name is the one fact only the deployer holds: under swarm it is the wire alias, so the
    // event names the address peers dial rather than something only this host could resolve.
    String containerName = driver.applied().get(0).wireAlias();
    assertTrue(active.payload.contains("\"containerName\":\"" + containerName + "\""),
        active.payload);
    assertFalse(queued.payload.contains("containerName"), queued.payload);

    // The lifecycle in the log's own order: queued, then started, then live.
    assertTrue(queued.occurredAt.compareTo(started.occurredAt) <= 0, "queued before started");
    assertTrue(started.occurredAt.compareTo(active.occurredAt) <= 0, "started before active");
    assertNull(only("DeploymentFailed", 0), "a green deployment announces no failure");
  }

  @Test
  public void aDeploymentWithNoImageAnnouncesItsTerminalStatusAndDetail() {
    // IMAGE_MISSING rather than FAILED, carried as data: to a consumer both mean "not live", and to
    // a person they are different things — nothing publishes this application yet.
    String environmentId = createEnvironment("pub-noimage");
    driver.scriptPull(
        new DeploymentDriver.PullResult(
            DeploymentDriver.PullOutcome.IMAGE_MISSING, "manifest unknown"));

    postRelease("run-pub-noimage", "repo-pub-noimage", null);
    awaitSettled(environmentId, 1);

    OutboxEvent failed = only("DeploymentFailed");
    assertTrue(failed.payload.contains("\"status\":\"IMAGE_MISSING\""), failed.payload);
    assertTrue(failed.payload.contains("manifest unknown"), failed.payload);
    assertTrue(failed.payload.contains("\"applicationName\":\"repo-pub-noimage\""), failed.payload);
    assertTrue(failed.payload.contains("\"environmentName\":\"pub-noimage\""), failed.payload);
    assertNull(failed.parentId, "a hand-made POST with no header is a rootless deployment");

    // The two points it did reach are still announced; the one it never got to is not.
    assertNotNull(only("DeploymentQueued"));
    assertNotNull(only("DeploymentStarted"));
    assertNull(only("DeploymentActive", 0), "nothing was cut over");
  }

  @Test
  public void theActiveEventPublishesTheResolvedRoutesHostAndNavigation() {
    String environmentId = createEnvironment("pub-routes");
    specs.script(
        "repo-pub-routes",
        spec(
            List.of("/refinement", "/refinement/api"),
            8181,
            "refinement",
            List.of(
                new NavigationEntry("services.details", "Refinement", 9),
                new NavigationEntry("platform", "Refinement", 1))));

    postRelease("run-pub-routes", "repo-pub-routes", null);
    awaitSettled(environmentId, 1);

    String payload = only("DeploymentActive").payload;
    // The resolver publishes the runtime address rather than requiring the edge to reproduce the
    // environment-qualified wire-alias convention.
    assertTrue(payload.contains("\"path\":\"/refinement\""), payload);
    assertTrue(payload.contains("\"upstreamHost\":\"pub-routes-repo-pub-routes\""), payload);
    assertTrue(payload.contains("\"upstreamPort\":8181"), payload);
    assertTrue(payload.contains("\"path\":\"/refinement/api\""), payload);
    // The host is the application's, not a route's, and so is every placement.
    assertTrue(payload.contains("\"browserHost\":\"refinement\""), payload);
    assertTrue(
        payload.contains("{\"label\":\"Refinement\",\"position\":9,\"slot\":\"services.details\"}"),
        payload);
    assertTrue(
        payload.contains("{\"label\":\"Refinement\",\"position\":1,\"slot\":\"platform\"}"),
        payload);
  }

  @Test
  public void aHostTheFileDoesNotStateIsDerivedFromTheApplicationName() {
    // The derivation the parser cannot do, because it never knows which application it read for:
    // the name without its platform prefix. It happens at registration, where the name is in hand.
    String environmentId = createEnvironment("pub-derived");
    specs.script(
        "qits-platform-pub-derived",
        spec(
            List.of("/pub-derived"),
            8080,
            null,
            List.of(new NavigationEntry("platform", "Derived", 4))));

    postRelease("run-pub-derived", "qits-platform-pub-derived", null);
    awaitSettled(environmentId, 1);

    assertTrue(
        only("DeploymentActive").payload.contains("\"browserHost\":\"pub-derived\""),
        only("DeploymentActive").payload);
  }

  @Test
  public void anApplicationThatAsksForNoHostIsAnnouncedWithout() {
    // Every file that still carries the retired `navigation` key is this case, and it must keep
    // being reached under its path prefix alone until it is rewritten.
    String environmentId = createEnvironment("pub-nohost");
    specs.script(
        "repo-pub-nohost",
        new DeploymentSpec(
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of("/nohost"),
            8080,
            null,
            false,
            List.of(new NavigationEntry("system", "No host", 1)),
            null,
            null,
            null));

    postRelease("run-pub-nohost", "repo-pub-nohost", null);
    awaitSettled(environmentId, 1);

    String payload = only("DeploymentActive").payload;
    assertFalse(payload.contains("browserHost"), payload);
    assertTrue(payload.contains("\"label\":\"No host\""), payload);
  }

  @Test
  public void aRenamedApplicationRetiresItsPredecessorsRoutesBeforeAnnouncingItsOwn() {
    // Nothing else would ever replace the predecessor's snapshot, so the successor withdraws it —
    // and FIRST, because until it is gone the successor's own paths are a conflict at the edge.
    String environmentId = createEnvironment("pub-rename");
    String cause = UUID.randomUUID().toString();
    specs.script(
        "repo-pub-rename",
        new DeploymentSpec(
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of("/rename"),
            8080,
            "rename",
            true,
            List.of(new NavigationEntry("platform", "Rename", 1)),
            null,
            null,
            "qits-platform-pub-rename"));

    postRelease("run-pub-rename", "repo-pub-rename", cause);
    awaitSettled(environmentId, 1);

    List<OutboxEvent> actives =
        rows().stream()
            .filter(row -> "DeploymentActive".equals(row.name))
            .sorted(Comparator.comparing(row -> row.createdAt))
            .toList();
    assertEquals(2, actives.size(), "the predecessor's retirement, then the successor's own");
    OutboxEvent retired = actives.get(0);
    OutboxEvent active = actives.get(1);
    assertTrue(retired.createdAt.isBefore(active.createdAt), "the retirement is published first");

    // An explicit empty snapshot is removal at the edge; a host with no routes would be refused,
    // and a version would have qits-projects finalize a release nobody deployed.
    assertTrue(
        retired.payload.contains("\"applicationName\":\"qits-platform-pub-rename\""),
        retired.payload);
    assertTrue(retired.payload.contains("\"environmentId\":\"" + environmentId + "\""),
        retired.payload);
    assertTrue(retired.payload.contains("\"endpoints\":[]"), retired.payload);
    assertTrue(retired.payload.contains("\"navigation\":[]"), retired.payload);
    assertFalse(retired.payload.contains("browserHost"), retired.payload);
    assertFalse(retired.payload.contains("apiDocsPath"), retired.payload);
    assertFalse(retired.payload.contains("\"version\""), retired.payload);
    assertFalse(retired.payload.contains("containerName"), retired.payload);
    // The successor's deployment, for traceability, and the successor's cause.
    assertEquals(deploymentIdOf(active), deploymentIdOf(retired));
    assertEquals(cause, retired.parentId, "the retirement is caused by what caused the successor");
    assertEquals(cause, active.parentId);

    assertTrue(active.payload.contains("\"applicationName\":\"repo-pub-rename\""), active.payload);
    assertTrue(active.payload.contains("\"path\":\"/rename\""), active.payload);
    assertTrue(active.payload.contains("\"browserHost\":\"rename\""), active.payload);
    assertTrue(active.payload.contains("\"version\":\"" + VERSION + "\""), active.payload);
  }

  /** A spec that declares a public surface: routes, the host it asks for, and its placements. */
  private static DeploymentSpec spec(
      List<String> routes, int upstreamPort, String host, List<NavigationEntry> navigationEntries) {
    return new DeploymentSpec(
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        routes,
        upstreamPort,
        host,
        true,
        navigationEntries,
        null,
        null,
        null);
  }

  @Test
  public void aDeploymentThatNeverConvergesAnnouncesTheOrchestratorsOwnWords() {
    String environmentId = createEnvironment("pub-sick");
    driver.scriptConvergence(
        DeploymentDriver.Convergence.failed(
            "service pub-sick-repo-pub-sick was still updating after 60s"));

    postRelease("run-pub-sick", "repo-pub-sick", null);
    awaitSettled(environmentId, 1);

    OutboxEvent failed = only("DeploymentFailed");
    assertTrue(failed.payload.contains("\"status\":\"FAILED\""), failed.payload);
    assertTrue(failed.payload.contains("was still updating"), failed.payload);
    assertNull(only("DeploymentActive", 0), "a failed convergence leaves the predecessor serving");
  }

  @Test
  public void aRefusedDeclarationAnnouncesItsOwnWordThroughTheSameFourEvents() {
    // NO FIFTH EVENT, and that is the claim. A refused declaration is one more terminal outcome, so
    // it rides the record's existing `status` STRING — which is exactly what a string on the wire
    // was for, and is the same decision ROLLED_BACK and IMAGE_MISSING already took. To a consumer
    // it means what they mean: it did not go live.
    String environmentId = createEnvironment("pub-declaration");
    specs.scriptDeclaration("repo-pub-declaration", "defaults:\n  FLAGS: on\n");
    seeds.refuseBroken(
        "repo-pub-declaration",
        "qits-configuration refused the declaration of repo-pub-declaration"
            + " (.config/qits/configuration.yml): 422 — line 3: mapping values are not allowed"
            + " here. The file at the released tag is broken; fix it and cut a new release.");

    postRelease("run-pub-declaration", "repo-pub-declaration", null);
    awaitSettled(environmentId, 1);

    OutboxEvent failed = only("DeploymentFailed");
    assertTrue(failed.payload.contains("\"status\":\"DECLARATION_REFUSED\""), failed.payload);
    assertTrue(failed.payload.contains("cut a new release"), failed.payload);
    assertTrue(
        failed.payload.contains("\"applicationName\":\"repo-pub-declaration\""), failed.payload);

    // Queued is announced because a row really was written — the refusal comes after it, which is
    // what makes it readable on the tier's listing. Nothing after that: the deployment never
    // started, so there is no Started and certainly no Active.
    assertNotNull(only("DeploymentQueued"));
    assertNull(only("DeploymentStarted", 0), "the deployment was refused before it started");
    assertNull(only("DeploymentActive", 0), "nothing was cut over");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String deploymentIdOf(OutboxEvent event) {
    String key = "\"deploymentId\":\"";
    int start = event.payload.indexOf(key) + key.length();
    return event.payload.substring(start, event.payload.indexOf('"', start));
  }

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        // The entry tier: a release lands in the designated platform environment.
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postRelease(String runId, String repoId, String cause) {
    var request =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("runId", runId, "repoId", repoId, "version", VERSION));
    if (cause != null) {
      request = request.header(CausationHeader.NAME, cause);
    }
    request
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  /**
   * Wait for the tier's deployments to reach a terminal status, then for the worker to drain. The
   * second half is what this suite needs and the flow tests do not: an announcement is published
   * <b>after</b> the row a poll can see, so a test that stopped at the row would race the publish.
   */
  private void awaitSettled(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows =
          given()
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      boolean settled =
          rows.size() == count
              && rows.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        try {
          deployService.awaitIdle();
        } catch (Exception e) {
          throw new IllegalStateException("the deploy worker did not drain", e);
        }
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    fail("the deployments of " + environmentId + " did not settle");
  }

  private List<OutboxEvent> rows() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList());
  }

  /** The one outbox row of that event type, failing the test if there is not exactly one. */
  private OutboxEvent only(String name) {
    OutboxEvent row = only(name, 1);
    assertNotNull(row, "expected one " + name + " row");
    return row;
  }

  /** The single row of that type, or null when {@code expected} is 0 and there are none. */
  private OutboxEvent only(String name, int expected) {
    List<OutboxEvent> matching = rows().stream().filter(row -> name.equals(row.name)).toList();
    assertEquals(
        expected, matching.size(), () -> "expected " + expected + " " + name + " row(s)");
    return matching.isEmpty() ? null : matching.get(0);
  }
}
