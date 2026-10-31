package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fourth lever (qits-765): {@code POST /applications/{applicationId}/retire} retires an
 * application that has no successor, completely — the rename cutover's retirement with nobody taking
 * the place — and keeps its databases.
 *
 * <p>Every application here is deployed through the real intake first, so its rows, its catalogue
 * row, its idp client and its database claim are the ones a real deployment writes. The bus is on
 * and aimed at a closed port ({@link PdSwarmDeployFlowTest}'s arrangement), so a published frame is
 * an outbox row. The door queues on the deploy worker, so every assertion follows {@code
 * awaitIdle()}.
 */
@QuarkusTest
@TestProfile(PdSwarmDeployFlowTest.SwarmOrchestrator.class)
public class PdApplicationRetireTest {

  private static final String VERSION = "2026.1031.120000";

  private static final ResourceSpec IDP = new ResourceSpec("idp", null, ResourceSpec.Type.IDP_CLIENT);

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver fake;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject PdDeploymentRepository deployments;
  @Inject PdResourceRepository resources;
  @Inject ServiceCatalog catalog;
  @Inject Settings settings;
  @Inject ResourceProvisioning resourceProvisioning;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
    fake.reset();
    clearOutbox();
  }

  @Test
  public void anActiveApplicationIsRetiredCompletelyAndItsDatabasesAreKept() {
    String env = "rt-active";
    String environmentId = createEnvironment(env);
    String app = "rt-active-app";
    deploy(environmentId, app, List.of(new ResourceSpec("db", "qits_rt_active_db"), IDP));
    settings.put(app, env, "GREETING", "hello", false, "an operator");
    String deploymentId = newest(app, environmentId).id;
    fake.reset();
    clearOutbox();

    retire(environmentId + ":" + app)
        .statusCode(202)
        .body("applicationId", org.hamcrest.Matchers.equalTo(environmentId + ":" + app))
        .body("applicationName", org.hamcrest.Matchers.equalTo(app))
        .body("environmentId", org.hamcrest.Matchers.equalTo(environmentId))
        .body("serviceName", org.hamcrest.Matchers.equalTo(env + "-" + app))
        .body("deploymentId", org.hamcrest.Matchers.equalTo(deploymentId))
        .body("previousStatus", org.hamcrest.Matchers.equalTo("ACTIVE"));
    awaitIdle();

    // a. the row says who retired it, and its history stays under the stamp.
    PdDeployment retired = newest(app, environmentId);
    assertEquals(deploymentId, retired.id, "no row is created and none deleted");
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, retired.status);
    assertTrue(retired.detail.startsWith("[decommissioned by rt-operator at "), retired.detail);
    assertTrue(retired.detail.contains("retired with no successor"), retired.detail);
    // b. the swarm service is removed, exactly once.
    assertEquals(List.of("removeService:" + env + "-" + app), removals(), fake.calls().toString());
    // c. the edge is told to withdraw the routes and navigation entries: an EMPTY snapshot.
    List<OutboxEvent> actives = actives();
    assertEquals(1, actives.size(), actives.toString());
    String frame = actives.get(0).payload;
    assertTrue(frame.contains("\"applicationName\":\"" + app + "\""), frame);
    assertTrue(frame.contains("\"endpoints\":[]"), frame);
    assertTrue(frame.contains("\"navigation\":[]"), frame);
    assertFalse(frame.contains("\"version\""), "a versionless frame finalizes no release: " + frame);
    // d. the catalogue row and its links are gone.
    assertTrue(catalog.find(app).isEmpty(), "the catalogue row is gone");
    // e. the settings are gone.
    assertTrue(settings.list(app, env).isEmpty(), "the settings are retired");
    // f. the idp client is retired, at qits-idp and in the registry.
    assertEquals(List.of(env + "-" + app), idpProvisioner.deleteCalls());
    assertFalse(resourceProvisioning.holdsIdpClient(app, env));
    // g. the postgres claim is KEPT, so the database stays claimed and nothing drops it.
    List<PdResource> kept = resourcesOf(app, env);
    assertEquals(1, kept.size(), kept.toString());
    assertEquals("qits_rt_active_db", kept.get(0).databaseName);
  }

  @Test
  public void aScaledToZeroShellIsRetiredTheSameWay() {
    String env = "rt-shell";
    String environmentId = createEnvironment(env);
    String app = "rt-shell-app";
    deploy(environmentId, app, List.of(new ResourceSpec("db", "qits_rt_shell_db")));
    setStatus(app, environmentId, PdDeploymentStatus.SCALED_TO_ZERO);
    fake.reset();

    retire(environmentId + ":" + app)
        .statusCode(202)
        .body("previousStatus", org.hamcrest.Matchers.equalTo("SCALED_TO_ZERO"));
    awaitIdle();

    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(app, environmentId).status);
    assertEquals(List.of("removeService:" + env + "-" + app), removals());
    assertTrue(catalog.find(app).isEmpty());
    assertEquals(1, resourcesOf(app, env).size(), "the database claim is kept");
  }

  @Test
  public void theDeployersOwnServiceIsRefusedBeforeAnythingMoves() {
    String env = "rt-self";
    String environmentId = createEnvironment(env);
    String app = "rt-self-app";
    deploy(environmentId, app, List.of(IDP));
    settings.put(app, env, "GREETING", "hello", false, "an operator");
    fake.reset();
    clearOutbox();
    fake.scriptOwnService(env + "-" + app);

    retire(environmentId + ":" + app).statusCode(409);
    awaitIdle();

    assertEquals(PdDeploymentStatus.ACTIVE, newest(app, environmentId).status);
    assertEquals(List.of(), removals());
    assertTrue(catalog.find(app).isPresent());
    assertEquals(1, settings.list(app, env).size());
    assertEquals(List.of(), idpProvisioner.deleteCalls());
    assertEquals(List.of(), actives());
  }

  @Test
  public void aPlaceTheWorkerStillOwnsIsRefused() {
    String env = "rt-busy";
    String environmentId = createEnvironment(env);
    String app = "rt-busy-app";
    deploy(environmentId, app, List.of());
    setStatus(app, environmentId, PdDeploymentStatus.STARTING);
    fake.reset();
    try {
      retire(environmentId + ":" + app).statusCode(409);
      awaitIdle();
      assertEquals(PdDeploymentStatus.STARTING, newest(app, environmentId).status);
      assertEquals(List.of(), removals());
      assertTrue(catalog.find(app).isPresent());
    } finally {
      setStatus(app, environmentId, PdDeploymentStatus.ACTIVE);
    }
  }

  @Test
  public void anUnknownOrMalformedIdIsAnAnswerNotALogLine() {
    retire("no-such-env:rt-nobody").statusCode(404);
    retire("not-an-id").statusCode(400);
  }

  @Test
  public void aFailedStepIsFinishedByTheNextObservationAndASecondCallIsHarmless() {
    String env = "rt-retry";
    String environmentId = createEnvironment(env);
    String app = "rt-retry-app";
    deploy(environmentId, app, List.of(new ResourceSpec("db", "qits_rt_retry_db"), IDP));
    settings.put(app, env, "GREETING", "hello", false, "an operator");
    fake.reset();
    fake.scriptRemoveServiceFailure(new IllegalStateException("the docker daemon is not answering"));

    retire(environmentId + ":" + app).statusCode(202);
    awaitIdle();

    // Stopped at the service: the rows are settled, everything after the failed step waits.
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(app, environmentId).status);
    assertTrue(catalog.find(app).isPresent(), "the steps after the failure have not run");
    assertEquals(1, settings.list(app, env).size());
    assertTrue(resourceProvisioning.holdsIdpClient(app, env));

    fake.scriptRemoveServiceFailure(null);
    deployService.enqueueObservation();
    awaitIdle();

    assertTrue(catalog.find(app).isEmpty(), "finished on the next observation");
    assertTrue(settings.list(app, env).isEmpty());
    assertFalse(resourceProvisioning.holdsIdpClient(app, env));
    assertEquals(1, resourcesOf(app, env).size(), "the database claim is still kept");
    String detail = newest(app, environmentId).detail;
    assertEquals(
        1,
        detail.split("\\[decommissioned by ", -1).length - 1,
        "a retried settle does not stamp twice: " + detail);

    // Fully retired: the next pass has nothing owed, and calling the door again changes nothing
    // that matters — the steps are idempotent.
    int removalsSoFar = removals().size();
    deployService.enqueueObservation();
    awaitIdle();
    assertEquals(removalsSoFar, removals().size(), "nothing owed, nothing run");
    retire(environmentId + ":" + app)
        .statusCode(202)
        .body("previousStatus", org.hamcrest.Matchers.equalTo("DECOMMISSIONED"));
    awaitIdle();
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, newest(app, environmentId).status);
    assertEquals(1, resourcesOf(app, env).size());
  }

  @Test
  public void decommissionKeepsItsOwnContract() {
    // The retire door is a separate door: decommission still refuses a deployed application and
    // still calls no orchestrator.
    String env = "rt-decom";
    String environmentId = createEnvironment(env);
    String app = "rt-decom-app";
    deploy(environmentId, app, List.of());
    fake.reset();

    given()
        .header("X-Qits-User", "rt-operator")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .post("/deployments/api/applications/" + environmentId + ":" + app + "/decommission")
        .then()
        .statusCode(409);
    assertEquals(PdDeploymentStatus.ACTIVE, newest(app, environmentId).status);
    assertEquals(List.of(), removals());
    assertTrue(catalog.find(app).isPresent());
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static ValidatableResponse retire(String applicationId) {
    return given()
        .header("X-Qits-User", "rt-operator")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .post("/deployments/api/applications/" + applicationId + "/retire")
        .then();
  }

  private static DeploymentSpec spec(List<ResourceSpec> resources) {
    return new DeploymentSpec(
        false,
        null,
        null,
        null,
        resources,
        List.of(),
        null,
        null,
        List.of(),
        8080,
        null,
        false,
        List.of(),
        null,
        null,
        null,
        null,
        null);
  }

  private void deploy(String environmentId, String name, List<ResourceSpec> declared) {
    specs.script(name, spec(declared));
    postRelease("run-" + name, name);
    awaitSettled(environmentId, name);
    assertEquals(PdDeploymentStatus.ACTIVE, newest(name, environmentId).status);
  }

  private List<String> removals() {
    return fake.calls().stream().filter(c -> c.startsWith("removeService:")).toList();
  }

  private void setStatus(String name, String environmentId, PdDeploymentStatus status) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                deployments.newestForPlace(name, environmentId).orElseThrow().status = status);
  }

  private List<PdResource> resourcesOf(String name, String environmentName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                resources.listByApplication(name, environmentName).stream()
                    .filter(row -> ResourceProvisioning.RESOURCE_TYPE.equals(row.resourceType))
                    .peek(row -> resources.getEntityManager().detach(row))
                    .toList());
  }

  private PdDeployment newest(String name, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.newestForPlace(name, environmentId).orElseThrow();
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              return copy;
            });
  }

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postRelease(String runId, String repoId) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", runId, "repoId", repoId, "version", VERSION))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private void awaitSettled(String environmentId, String name) {
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
          rows.stream().anyMatch(row -> name.equals(row.get("applicationName")))
              && rows.stream()
                  .noneMatch(
                      row ->
                          "QUEUED".equals(row.get("status")) || "STARTING".equals(row.get("status")));
      if (settled) {
        awaitIdle();
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    fail("the deployment of " + name + " in " + environmentId + " did not settle");
  }

  private void awaitIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private void clearOutbox() {
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  private List<OutboxEvent> actives() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList()
                    .stream()
                    .filter(row -> "DeploymentActive".equals(row.name))
                    .sorted(Comparator.comparing(row -> row.createdAt))
                    .toList());
  }
}
