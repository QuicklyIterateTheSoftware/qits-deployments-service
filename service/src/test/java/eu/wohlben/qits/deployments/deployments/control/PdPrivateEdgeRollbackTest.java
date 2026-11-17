package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The private edge's recreate rollback (epic qits-2174, task qits-2215), against {@link
 * FakeDeploymentDriver}: a {@code qits-edge-private} release that does not converge healthy, with
 * nothing swarm can revert to, has its last ACTIVE version re-applied by the deployer and is
 * recorded FAILED saying so; a healthy one is left alone; and nothing else is ever restored this
 * way — not another application, and not an update swarm already rolled back. The overlay edge
 * (qits-2304), which private services resolve through, is covered the same way.
 */
@QuarkusTest
class PdPrivateEdgeRollbackTest {

  private static final String EDGE = "qits-edge-private";

  private static final String V_OLD = "2026.1115.100000";
  private static final String V_NEW = "2026.1116.100000";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject FakeEdgeRouteClient edges;
  @Inject DeployService deployService;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    driver.reset();
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
    edges.reset();
  }

  @Test
  void aRecreateThatNeverGoesHealthyPutsThePreviousVersionBackAndFailsTheNewOne() {
    String environmentId = createEnvironment("pe-rollback");
    List<Map<String, Object>> first = deploy(environmentId, EDGE, V_OLD, 1);
    String previousId = (String) first.get(0).get("id");
    assertEquals("ACTIVE", first.get(0).get("status"));
    driver.reset();

    // The recreate's verdict: not converged and nothing reverted (swarm had no previous spec),
    // then the restore's: healthy.
    driver.scriptConvergences(
        DeploymentDriver.Convergence.failed("task exited: unhealthy (no route table)"),
        DeploymentDriver.Convergence.converged(List.of()));
    List<Map<String, Object>> rows = deploy(environmentId, EDGE, V_NEW, 2);

    Map<String, Object> failed = row(rows, V_NEW);
    Map<String, Object> previous = row(rows, V_OLD);
    assertEquals("FAILED", failed.get("status"));
    String detail = (String) failed.get("detail");
    assertTrue(detail.contains("rolled back"), detail);
    assertTrue(detail.contains(EDGE + " " + V_NEW + " failed its health check"), detail);
    assertTrue(detail.contains(previousId + " (" + V_OLD + ")"), detail);
    assertTrue(detail.contains("no route table"), "the cause is kept: " + detail);
    assertEquals("ACTIVE", previous.get("status"), "the previous deployment is still the live one");

    List<DeploymentDriver.ServiceSpec> applied = driver.applied();
    assertEquals(2, applied.size(), applied.toString());
    assertEquals(V_NEW, applied.get(0).version());
    DeploymentDriver.ServiceSpec restored = applied.get(1);
    assertEquals(V_OLD, restored.version());
    assertEquals(previousId, restored.deploymentId());
    assertTrue(restored.imageRef().endsWith(":" + V_OLD), restored.imageRef());
    assertEquals(applied.get(0).wireAlias(), restored.wireAlias(), "the same service, put back");
    assertEquals(2, driver.awaited().size(), "the restore waited for its own convergence");
  }

  @Test
  void theOverlayEdgesRecreateIsRolledBackTheSameWay() {
    // qits-2304: every overlay client asks qits-edge-overlay first, so a recreate of it that never
    // goes healthy is put back like the tunnel edge's.
    String overlay = "qits-edge-overlay";
    String environmentId = createEnvironment("pe-overlay-rollback");
    List<Map<String, Object>> first = deploy(environmentId, overlay, V_OLD, 1);
    String previousId = (String) first.get(0).get("id");
    driver.reset();
    driver.scriptConvergences(
        DeploymentDriver.Convergence.failed("task exited: unhealthy (no route table)"),
        DeploymentDriver.Convergence.converged(List.of()));

    List<Map<String, Object>> rows = deploy(environmentId, overlay, V_NEW, 2);

    Map<String, Object> failed = row(rows, V_NEW);
    assertEquals("FAILED", failed.get("status"));
    String detail = (String) failed.get("detail");
    assertTrue(detail.contains(overlay + " " + V_NEW + " failed its health check"), detail);
    assertTrue(detail.contains(previousId + " (" + V_OLD + ")"), detail);
    assertEquals("ACTIVE", row(rows, V_OLD).get("status"));
    assertEquals(2, driver.applied().size(), driver.applied().toString());
    assertEquals(V_OLD, driver.applied().get(1).version());
  }

  @Test
  void aHealthyRecreateRestoresNothing() {
    String environmentId = createEnvironment("pe-healthy");
    deploy(environmentId, EDGE, V_OLD, 1);
    driver.reset();

    List<Map<String, Object>> rows = deploy(environmentId, EDGE, V_NEW, 2);

    assertEquals("ACTIVE", row(rows, V_NEW).get("status"));
    assertEquals("DECOMMISSIONED", row(rows, V_OLD).get("status"));
    assertEquals(1, driver.applied().size(), "no second apply");
    assertEquals(V_NEW, driver.applied().get(0).version());
  }

  @Test
  void anUpdateSwarmRolledBackIsNotRestoredAgain() {
    String environmentId = createEnvironment("pe-swarm-rollback");
    deploy(environmentId, EDGE, V_OLD, 1);
    driver.reset();
    driver.scriptConvergence(DeploymentDriver.Convergence.rolledBack("update rolled back"));

    List<Map<String, Object>> rows = deploy(environmentId, EDGE, V_NEW, 2);

    assertEquals("ROLLED_BACK", row(rows, V_NEW).get("status"));
    assertEquals("ACTIVE", row(rows, V_OLD).get("status"));
    assertEquals(1, driver.applied().size(), "swarm reverted it; the deployer applies nothing more");
  }

  @Test
  void anotherApplicationThatNeverGoesHealthyIsNotRestored() {
    String environmentId = createEnvironment("pe-other");
    deploy(environmentId, "repo-pe-other", V_OLD, 1);
    driver.reset();
    driver.scriptConvergence(DeploymentDriver.Convergence.failed("unhealthy"));

    List<Map<String, Object>> rows = deploy(environmentId, "repo-pe-other", V_NEW, 2);

    Map<String, Object> failed = row(rows, V_NEW);
    assertEquals("FAILED", failed.get("status"));
    assertTrue(!((String) failed.get("detail")).contains("rolled back"), (String) failed.get("detail"));
    assertEquals(1, driver.applied().size(), "only the private edge is rolled back this way");
  }

  @Test
  void aFirstPrivateEdgeThatNeverGoesHealthyHasNothingToRollBackTo() {
    String environmentId = createEnvironment("pe-first");
    driver.scriptConvergence(DeploymentDriver.Convergence.failed("unhealthy"));

    List<Map<String, Object>> rows = deploy(environmentId, EDGE, V_NEW, 1);

    assertEquals("FAILED", rows.get(0).get("status"));
    String detail = (String) rows.get(0).get("detail");
    assertTrue(detail.contains("no ACTIVE version to roll back to"), detail);
    assertEquals(1, driver.applied().size());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static Map<String, Object> row(List<Map<String, Object>> rows, String version) {
    return rows.stream()
        .filter(row -> version.equals(row.get("version")))
        .max(Comparator.comparing(row -> String.valueOf(row.get("createdAt"))))
        .orElseThrow(() -> new AssertionError("no row of " + version + " in " + rows));
  }

  private List<Map<String, Object>> deploy(
      String environmentId, String repoId, String version, int rows) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-" + version, "repoId", repoId, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    return awaitSettled(environmentId, repoId, rows);
  }

  /**
   * Creating a platform environment moves the designation, so every test makes its own the tier
   * a release enters at.
   */
  private static String createEnvironment(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private List<Map<String, Object>> awaitSettled(String environmentId, String application, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows =
          given()
              .header("Authorization", SuiteBearers.operator())
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .<Map<String, Object>>getList("deployments")
              .stream()
              .filter(row -> application.equals(row.get("applicationName")))
              .toList();
      boolean settled =
          rows.size() == count
              && rows.stream()
                  .noneMatch(
                      row -> "QUEUED".equals(row.get("status")) || "STARTING".equals(row.get("status")));
      if (settled) {
        try {
          deployService.awaitIdle();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
        return rows;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return fail("the " + application + " deployments of " + environmentId + " did not settle to " + count);
  }
}
