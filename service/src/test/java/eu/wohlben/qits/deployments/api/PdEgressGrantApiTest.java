package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.DeploymentSpecParser;
import eu.wohlben.qits.deployments.deployments.control.EgressGrantTables;
import eu.wohlben.qits.deployments.deployments.control.FakeEgressGrantClient;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressDeclaration;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressGrantTable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /deployments/api/egress-grants} (epic qits-2523), driven through the release path: a
 * release whose {@code .config/qits/egress.yml} grants a route is in the table once it is ACTIVE; a
 * release whose file is malformed still DEPLOYS and is listed under {@code refused}; qits-egress
 * ({@code qits:system}), an operator and an agent read it, anybody else is refused; the version
 * moves only when the content does.
 *
 * <p>Every test clears the declarations first, so the table holds exactly what that test released —
 * the suite's other ACTIVE rows carry no declaration and grant nothing.
 */
@QuarkusTest
class PdEgressGrantApiTest {

  private static final String PATH = "/deployments/api/egress-grants";

  private static final String HETZNER =
      """
      version: 1
      routes:
        - name: hetzner                       # the first path segment at the gateway
          target: https://api.hetzner.cloud
          secret: HETZNER_DNS
          inject: bearer
          methods: [PUT, GET, DELETE, POST]
        - name: projects
          target: platform:projects
          methods:
            - GET
      """;

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject EgressGrantTables tables;
  @Inject FakeEgressGrantClient egress;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    driver.reset();
    specs.reset();
    egress.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdEgressDeclaration.deleteAll();
              PdEgressGrantTable.deleteAll();
            });
  }

  @Test
  void anActiveReleaseGrantsTheRoutesItsFileDeclaresInTheDossiersShape() {
    String token = token();
    String environment = "egress-" + token;
    String environmentId = createEnvironment(environment);
    String application = "egress-app-" + token;
    specs.scriptText(application, SpecSource.EGRESS_PATH, HETZNER);
    release(application, "2026.1201.101010");
    awaitStatus(environmentId, "ACTIVE");

    JsonPath table =
        given()
            .header("Authorization", SuiteBearers.platform())
            .when()
            .get(PATH)
            .then()
            .statusCode(200)
            .body("routes", hasSize(2))
            .body("refused", hasSize(0))
            .extract()
            .jsonPath();
    assertTrue(table.getLong("version") > 0, table.prettify());
    Map<String, Object> hetzner = table.getMap("routes[0]");
    assertEquals(
        Map.of(
            "principal", "application:" + application,
            "application", application,
            "environment", environment,
            "deployedVersion", "2026.1201.101010",
            "name", "hetzner",
            "target", "https://api.hetzner.cloud",
            "secret", "HETZNER_DNS",
            "inject", "bearer",
            "methods", List.of("DELETE", "GET", "POST", "PUT")),
        hetzner,
        "the dossier's route shape, methods sorted");
    // A platform route carries no secret and no injection: both keys are there, and null.
    assertEquals("platform:projects", table.getString("routes[1].target"));
    assertTrue(table.getMap("routes[1]").containsKey("secret"), table.prettify());
    assertNull(table.getString("routes[1].secret"));
    assertNull(table.getString("routes[1].inject"));
    assertEquals(List.of("GET"), table.getList("routes[1].methods"));
  }

  @Test
  void aVariantIsGrantedWhatItsOwnEgressFileDeclaresAndNotTheMainFilesRoutes() {
    // qits-2784: a variant is an application of its own with a principal of its own, so it reads
    // .config/qits/egress.<variant>.yml — and the main file's routes stay the main application's.
    String token = token();
    String environment = "egress-var-" + token;
    String environmentId = createEnvironment(environment);
    String application = "egress-var-" + token;
    String variantApplication = application + "-private";
    specs.script(
        application,
        DeploymentSpecParser.parse("variants: private, quiet\n", "deployments.yml of " + application));
    specs.scriptAt(
        application,
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parseVariant(
            "application: " + variantApplication + "\n", "deployments.private.yml"));
    specs.scriptAt(
        application,
        SpecSource.variantPath("quiet"),
        DeploymentSpecParser.parseVariant(
            "application: " + application + "-quiet\n", "deployments.quiet.yml"));
    specs.scriptText(
        application,
        SpecSource.EGRESS_PATH,
        "version: 1\nroutes:\n  - name: projects\n    target: platform:projects\n"
            + "    methods: [GET]\n");
    specs.scriptText(application, SpecSource.egressPath("private"), HETZNER);
    // `quiet` carries no egress.quiet.yml at all: no routes, and no refusal.
    release(application, "2026.1201.101014");
    awaitStatuses(environmentId, List.of("ACTIVE", "ACTIVE", "ACTIVE"));

    JsonPath table =
        given()
            .header("Authorization", SuiteBearers.platform())
            .when()
            .get(PATH)
            .then()
            .statusCode(200)
            .body("refused", hasSize(0))
            .extract()
            .jsonPath();
    List<String> grants =
        table.getList("routes", Map.class).stream()
            .map(route -> route.get("principal") + " " + route.get("name"))
            .sorted()
            .toList();
    assertEquals(
        List.of(
            "application:" + application + " projects",
            "application:" + variantApplication + " hetzner",
            "application:" + variantApplication + " projects"),
        grants,
        table.prettify());
    assertEquals(".config/qits/egress.private.yml", SpecSource.egressPath("private"));
    assertEquals(SpecSource.EGRESS_PATH, SpecSource.egressPath(null));
  }

  @Test
  void aReleaseWhoseEgressFileIsMalformedStillDeploysAndIsListedRefused() {
    String token = token();
    String environment = "egress-bad-" + token;
    String environmentId = createEnvironment(environment);
    String application = "egress-bad-" + token;
    specs.scriptText(
        application,
        SpecSource.EGRESS_PATH,
        "version: 1\nroutes:\n  - name: plain\n    target: http://example.com\n"
            + "    secret: X\n    inject: bearer\n    methods: [GET]\n");
    release(application, "2026.1201.101011");

    awaitStatus(environmentId, "ACTIVE");
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("routes", hasSize(0))
        .body("refused", hasSize(1))
        .body("refused[0].principal", equalTo("application:" + application))
        .body("refused[0].application", equalTo(application))
        .body("refused[0].deployedVersion", equalTo("2026.1201.101011"))
        .body(
            "refused[0].reason",
            org.hamcrest.Matchers.containsString("plain `http://` is refused"));
  }

  @Test
  void aReleaseWithNoEgressFileGrantsNothingAndRefusesNothing() {
    String token = token();
    String environment = "egress-none-" + token;
    String environmentId = createEnvironment(environment);
    release("egress-none-" + token, "2026.1201.101012");
    awaitStatus(environmentId, "ACTIVE");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("routes", hasSize(0))
        .body("refused", hasSize(0));
  }

  @Test
  void theVersionMovesOnlyWhenTheContentDoes() throws Exception {
    Instant first = Instant.parse("2025-11-25T09:00:00Z");
    long empty = tables.current(first).version();
    assertEquals(first.toEpochMilli(), empty);
    assertEquals(empty, tables.current(first.plusSeconds(60)).version(), "nothing changed");

    String token = token();
    String environment = "egress-ver-" + token;
    String environmentId = createEnvironment(environment);
    String application = "egress-ver-" + token;
    specs.scriptText(application, SpecSource.EGRESS_PATH, HETZNER);
    release(application, "2026.1201.101013");
    awaitStatus(environmentId, "ACTIVE");
    // The cutover's last steps build the table and push it to qits-egress (EgressGrantPusher), so
    // the new version is stored by the time the worker is idle, at the instant it was built.
    deployService.awaitIdle();

    long withRoutes = tables.current(first.plusSeconds(120)).version();
    assertTrue(withRoutes > empty, "the content changed: " + withRoutes + " after " + empty);
    assertEquals(withRoutes, tables.current(first.plusSeconds(180)).version());
    assertEquals(
        withRoutes,
        egress.pushedVersions().get(egress.pushedVersions().size() - 1),
        "the version qits-egress was pushed after the cutover");
    // ...and the door answers the stored table: the same version, a later instant notwithstanding.
    long served =
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get(PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("version");
    assertEquals(withRoutes, served);
  }

  @Test
  void qitsEgressAnOperatorAndAnAgentReadItAndNobodyElse() {
    for (String bearer :
        List.of(
            SuiteBearers.platform(),
            SuiteBearers.operator(),
            SuiteBearers.bearer("an-agent-reading-grants", "qits:agent"))) {
      given().header("Authorization", bearer).when().get(PATH).then().statusCode(200);
    }
    given().when().get(PATH).then().statusCode(401);
    given()
        .header("Authorization", SuiteBearers.bearer("a-desk", "qits:desk"))
        .when()
        .get(PATH)
        .then()
        .statusCode(403);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static String token() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  /** The designated tier a release enters at — creating one moves the designation. */
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

  private static void release(String repoId, String version) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-egress", "repoId", repoId, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private static void awaitStatus(String environmentId, String status) {
    awaitStatuses(environmentId, List.of(status));
  }

  private static void awaitStatuses(String environmentId, List<String> wanted) {
    long deadline = System.currentTimeMillis() + 15_000;
    List<String> statuses = List.of();
    while (System.currentTimeMillis() < deadline) {
      statuses =
          given()
              .header("Authorization", SuiteBearers.operator())
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments.status");
      if (statuses.equals(wanted)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    fail("the deployment into " + environmentId + " never reached " + wanted + ": " + statuses);
  }
}
