package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-2234: a {@code FAILED} deployment the observation pass finds serving its own image is
 * announced {@code DeploymentActive}, once — the cutover that would have said it never ran, and a
 * release request's deployment gate waits on exactly that event. Live on 2026-11-14:
 * dev-qits-maintenance ended FAILED after its database move, was recovered by observation in the
 * same second, and nothing ever told the release request it was live.
 *
 * <p>The bus is aimed at a closed port, the {@link PdSweepAdoptionPublishTest} profile: a refused
 * connection lands the event in the outbox whole, and a row IS the publish from this side.
 */
@QuarkusTest
@TestProfile(PdSweepAdoptionPublishTest.BusEnabled.class)
public class PdObservedRecoveryPublishTest {

  private static final String VERSION = "2026.1114.51114";

  @Inject DeploymentObserver observer;
  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject PdDeploymentRepository deployments;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  @Test
  public void aFailedDeploymentRecoveredByObservationIsAnnouncedOnce() {
    String environmentId = createEnvironment("obs-pub-recovered");
    UUID cause = UUID.randomUUID();
    String failed =
        deployment(
            "qits-obs-pub-recovered",
            environmentId,
            PdDeploymentStatus.FAILED,
            "obs-pub-recovered-svc",
            cause);
    driver.scriptObservation("obs-pub-recovered-svc", "running/healthy");
    driver.scriptRunningImage("obs-pub-recovered-svc", image(VERSION));

    observer.observeOnce();

    assertEquals("ACTIVE", statusOf(failed));
    List<OutboxEvent> active = activesOf(failed);
    assertEquals(1, active.size(), "one DeploymentActive for the recovery: " + active);
    String payload = active.get(0).payload;
    assertTrue(payload.contains("\"version\":\"" + VERSION + "\""), payload);
    assertTrue(payload.contains("\"containerName\":\"obs-pub-recovered-svc\""), payload);
    assertTrue(payload.contains("\"path\":\"/recovered\""), payload);
    assertEquals(cause.toString(), active.get(0).parentId, "the build's trace edge is kept");

    // The next pass finds the row ACTIVE and serving: nothing is said twice.
    observer.observeOnce();
    assertEquals(1, activesOf(failed).size(), "announced once per deployment");
  }

  @Test
  public void aRecoveredRowWhoseServiceRunsAnotherImageIsNotAnnounced() {
    // A swarm service keeps its name across deployments: a failed update can leave the predecessor
    // serving under it, and that is not this version live.
    String environmentId = createEnvironment("obs-pub-foreign");
    String failed =
        deployment(
            "qits-obs-pub-foreign",
            environmentId,
            PdDeploymentStatus.FAILED,
            "obs-pub-foreign-svc",
            UUID.randomUUID());
    driver.scriptObservation("obs-pub-foreign-svc", "running/healthy");
    driver.scriptRunningImage("obs-pub-foreign-svc", image("2026.1101.1"));

    observer.observeOnce();

    assertEquals(0, activesOf(failed).size());
  }

  @Test
  public void aGoneRowThatCameBackIsNotAnnouncedAgain() {
    // GONE was ACTIVE once, and announced at its cutover.
    String environmentId = createEnvironment("obs-pub-gone");
    String gone =
        deployment(
            "qits-obs-pub-gone",
            environmentId,
            PdDeploymentStatus.GONE,
            "obs-pub-gone-svc",
            UUID.randomUUID());
    driver.scriptObservation("obs-pub-gone-svc", "running/healthy");
    driver.scriptRunningImage("obs-pub-gone-svc", image(VERSION));

    observer.observeOnce();

    assertEquals("ACTIVE", statusOf(gone));
    assertEquals(0, activesOf(gone).size());
  }

  private static String image(String tag) {
    return "registry.qits.dev.localhost:8080/qits/qits-maintenance:" + tag;
  }

  private String createEnvironment(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", false))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      UUID cause) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.version = VERSION;
              row.commitSha = "c".repeat(40);
              row.runId = "run-" + applicationName;
              row.causationId = cause;
              row.status = status;
              row.containerName = containerName;
              row.detail = "service " + containerName + " was still not started yet";
              row.routes = "/recovered";
              row.upstreamPort = 8080;
              row.createdAt = Instant.now();
              row.finishedAt = Instant.now();
              deployments.persist(row);
            });
    return id;
  }

  private String statusOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).status.name());
  }

  private List<OutboxEvent> activesOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList())
        .stream()
        .filter(row -> "DeploymentActive".equals(row.name))
        .filter(row -> row.payload.contains("\"deploymentId\":\"" + deploymentId + "\""))
        .toList();
  }
}
