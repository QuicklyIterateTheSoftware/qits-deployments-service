package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * An agent reads what it reads today, under its own role.
 *
 * <p>Agents will stop inheriting their owner's roles and hold {@code qits:agent} instead. Every
 * read here accepts that role; no write does, the operator's levers included.
 *
 * <p>The identity is the forward-auth pair. With {@code X-Qits-User} present, the roles are exactly
 * {@code X-Qits-Roles}, so the {@code %test} dev user's roles do not leak in. Seeding sends no
 * header and so runs as that dev user, which holds {@code qits:system}.
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final String BASE = "/deployments/api";

  private static RequestSpecification asAgent() {
    return given()
        .header("X-Qits-User", "dyn-workspace-agent-reads")
        .header("X-Qits-Roles", "qits:agent");
  }

  private static String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + name + "\"}")
        .when()
        .post(BASE + "/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  @Test
  void anAgentReadsTheApplications() {
    asAgent().when().get(BASE + "/applications").then().statusCode(200);
  }

  @Test
  void anAgentReadsTheDeployments() {
    String environmentId = createEnvironment("agt-deployments");
    asAgent().when().get(BASE + "/deployments?environmentId=" + environmentId).then().statusCode(200);
  }

  @Test
  void anAgentReadsTheDeploymentRequests() {
    String environmentId = createEnvironment("agt-requests");
    asAgent()
        .when()
        .get(BASE + "/deployment-requests?environmentId=" + environmentId)
        .then()
        .statusCode(200);
  }

  @Test
  void anAgentReadsTheEnvironments() {
    String environmentId = createEnvironment("agt-environments");
    asAgent().when().get(BASE + "/environments").then().statusCode(200);
    asAgent().when().get(BASE + "/environments/" + environmentId).then().statusCode(200);
    asAgent().when().get(BASE + "/environments/" + environmentId + "/links").then().statusCode(200);
  }

  @Test
  void anAgentReadsTheServices() {
    asAgent().when().get(BASE + "/services").then().statusCode(200);
  }

  @Test
  void anAgentReadsThePins() {
    asAgent().when().get(BASE + "/pins").then().statusCode(200);
  }

  @Test
  void anAgentReadsTheIdpClientClaims() {
    asAgent().when().get(BASE + "/claims/idp-clients").then().statusCode(200);
  }

  @Test
  void anAgentCannotWrite() {
    String environmentId = createEnvironment("agt-refused");
    asAgent()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"agt-refused-too\"}")
        .when()
        .post(BASE + "/environments")
        .then()
        .statusCode(403);
    asAgent()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"agt-renamed\"}")
        .when()
        .patch(BASE + "/environments/" + environmentId)
        .then()
        .statusCode(403);
    asAgent().when().delete(BASE + "/environments/" + environmentId).then().statusCode(403);
    asAgent()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}")
        .when()
        .put(BASE + "/services/agt-refused")
        .then()
        .statusCode(403);
    asAgent().when().delete(BASE + "/services/agt-refused").then().statusCode(403);
    asAgent()
        .contentType(ContentType.JSON)
        .body("{\"repoId\":\"agt-refused\",\"version\":\"2026.912.1\"}")
        .when()
        .post(BASE + "/events/software-released")
        .then()
        .statusCode(403);
    asAgent()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(BASE + "/applications/agt-refused/scale")
        .then()
        .statusCode(403);
    asAgent().when().post(BASE + "/applications/agt-refused/restart").then().statusCode(403);
    asAgent().when().post(BASE + "/applications/agt-refused/decommission").then().statusCode(403);
  }

  private static RequestSpecification asAdminAgent() {
    return given()
        .header("X-Qits-User", "dyn-admin-workspace")
        .header("X-Qits-Roles", "qits:admin-agent");
  }

  /**
   * qits-628 follow-up: an ADMIN workspace's coding agent's credential, carrying {@code
   * qits:admin-agent} and NOT {@code qits:admin}, passes {@code PdApplicationController}'s
   * operator levers exactly as {@code qits:admin} does — they inherit the class-level role list,
   * which names no {@code qits:agent} at all, so the id names nobody deployed and the answer is the
   * door's own 404 rather than the role gate's 403. Plain {@code qits:agent}, as {@link
   * #anAgentCannotWrite} already proved on the same three routes, still gets 403.
   */
  @Test
  void anAdminAgentOperatesTheApplicationLeversAndPlainAgentStillCannot() {
    // A well-formed <environmentId>:<name> key that nothing was ever deployed under: past the
    // role gate, resolve() reads a real NotFoundException (404) rather than the malformed-id 400.
    String noSuchApplication = "no-such-env:admin-agent-refused";
    asAdminAgent()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(BASE + "/applications/" + noSuchApplication + "/scale")
        .then()
        .statusCode(404);
    asAdminAgent()
        .when()
        .post(BASE + "/applications/" + noSuchApplication + "/restart")
        .then()
        .statusCode(404);
    asAdminAgent()
        .when()
        .post(BASE + "/applications/" + noSuchApplication + "/decommission")
        .then()
        .statusCode(404);

    asAgent()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(BASE + "/applications/" + noSuchApplication + "/scale")
        .then()
        .statusCode(403);
  }
}
