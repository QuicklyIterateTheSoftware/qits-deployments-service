package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.EdgeRouteTables;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeEdgeRouteClient;
import eu.wohlben.qits.deployments.deployments.entity.PdEdgeRouteTable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /deployments/api/edge-routes/{edge}} (epic qits-2174): the stored table to the edge
 * ({@code qits:system}), an operator and an agent; 404 for a name that is no edge, 503 while no
 * table has been built.
 */
@QuarkusTest
class PdEdgeRouteApiTest {

  private static final String PATH = "/deployments/api/edge-routes/";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeEdgeRouteClient edges;
  @Inject EdgeRouteTables tables;
  @Inject DeployService deployService;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    driver.reset();
    edges.reset();
    QuarkusTransaction.requiringNew().run(() -> PdEdgeRouteTable.deleteAll());
  }

  private void build() {
    driver.scriptLabelledServices(
        List.of(
            new DeploymentDriver.LabelledService(
                "dev-qits-door-app",
                Map.of(
                    DeploymentDriver.APP_NAME_LABEL, "qits-door-app",
                    DeploymentDriver.EXPOSURE_LABEL, "private",
                    DeploymentDriver.ROUTES_LABEL, "/door-app",
                    DeploymentDriver.HOSTS_LABEL, "door"))));
    tables.rebuild(Instant.parse("2025-11-13T09:20:00Z"));
  }

  @Test
  void theEdgeAnOperatorAndAnAgentReadTheStoredTable() {
    build();
    for (String bearer :
        List.of(
            SuiteBearers.platform(),
            SuiteBearers.operator(),
            SuiteBearers.bearer("an-agent-reading-routes", "qits:agent"))) {
      given()
          .header("Authorization", bearer)
          .when()
          .get(PATH + "private")
          .then()
          .statusCode(200)
          .body("edge", equalTo("private"))
          .body("version", equalTo(1763025600000L))
          .body("builtAt", equalTo("2025-11-13T09:20:00Z"))
          .body("applications", hasSize(1))
          .body("applications[0].application", equalTo("qits-door-app"))
          .body("applications[0].endpoints[0].upstreamHost", equalTo("dev-qits-door-app"))
          .body("withdrawn", hasSize(0));
    }
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(PATH + "public")
        .then()
        .statusCode(200)
        .body("applications", hasSize(0))
        .body("withdrawn[0].host", equalTo("door"));
  }

  @Test
  void anyOtherCallerIsRefused() {
    build();
    given().when().get(PATH + "private").then().statusCode(401);
    given()
        .header("Authorization", SuiteBearers.bearer("a-desk", "qits:desk"))
        .when()
        .get(PATH + "private")
        .then()
        .statusCode(403);
  }

  @Test
  void aNameThatIsNoEdgeIs404() {
    build();
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(PATH + "intranet")
        .then()
        .statusCode(404);
  }

  @Test
  void noTableBuiltYetIs503() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(PATH + "private")
        .then()
        .statusCode(503);
  }
}
