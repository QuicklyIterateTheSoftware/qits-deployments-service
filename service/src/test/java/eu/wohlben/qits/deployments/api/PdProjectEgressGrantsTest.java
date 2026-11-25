package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.EgressGrantPusher;
import eu.wohlben.qits.deployments.deployments.control.EgressGrantTables;
import eu.wohlben.qits.deployments.deployments.control.FakeEgressGrantClient;
import eu.wohlben.qits.deployments.deployments.control.FakeProjectDirectory;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.deployments.deployments.control.ProjectEgressGrants;
import eu.wohlben.qits.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressDeclaration;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressGrantTable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The projects' egress grants (qits-2659): each project's wrapper {@code .config/qits/egress.yml}
 * at {@code refs/heads/main}, read through qits-projects' listing and the git host's blob door, in
 * {@code GET /deployments/api/egress-grants} as {@code principal: "project:<projectId>"}.
 *
 * <p>Every test starts and ends with no project and no declaration, so the suite's other egress
 * tests never see a project route.
 */
@QuarkusTest
class PdProjectEgressGrantsTest {

  private static final String PATH = "/deployments/api/egress-grants";

  private static final String PROJECT = "7d0c8a4e-4b1f-4c3e-9a51-0e2f3c4d5e6f";
  private static final String WRAPPER_ID = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d";
  private static final String WRAPPER = "qits-qits";

  private static final String GRANTS =
      """
      version: 1
      routes:
        - name: github
          target: https://api.github.com
          secret: GITHUB_READ
          inject: bearer
          methods: [GET, HEAD, CONNECT]
        - name: portal
          target: https://portal.example.com
          secret: SESSION
          inject: cookie:sid
          methods: [GET]
      """;

  @Inject FakeProjectDirectory directory;
  @Inject FakeSpecSource specs;
  @Inject FakeEgressGrantClient egress;
  @Inject ProjectEgressGrants projects;
  @Inject EgressGrantTables tables;
  @Inject DeployService deployService;
  @Inject EgressGrantPusher pusher;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    clear();
  }

  @AfterEach
  void clearAfter() {
    clear();
  }

  private void clear() {
    directory.reset();
    specs.reset();
    egress.reset();
    projects.refresh();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdEgressDeclaration.deleteAll();
              PdEgressGrantTable.deleteAll();
            });
  }

  @Test
  void aWrappersFileGrantsItsProjectTheRoutesWithTheSecretsInTheProjectsNamespace() {
    directory.project(PROJECT, "qits", WRAPPER_ID, WRAPPER);
    specs.scriptText(WRAPPER, SpecSource.EGRESS_PATH, GRANTS);
    projects.refresh();

    JsonPath table = read();
    assertEquals(2, table.getList("routes").size(), table.prettify());
    Map<String, Object> github = new HashMap<>();
    github.put("principal", "project:" + PROJECT);
    github.put("application", null);
    github.put("environment", null);
    github.put("deployedVersion", FakeSpecSource.RESOLVED_COMMIT.substring(0, 12));
    github.put("name", "github");
    github.put("target", "https://api.github.com");
    github.put("secret", "PROJECT_QITS_GITHUB_READ");
    github.put("inject", "bearer");
    github.put("methods", List.of("CONNECT", "GET", "HEAD"));
    assertEquals(github, table.getMap("routes[0]"));
    assertEquals("portal", table.getString("routes[1].name"));
    assertEquals("cookie:sid", table.getString("routes[1].inject"));
    assertEquals("PROJECT_QITS_SESSION", table.getString("routes[1].secret"));
    assertEquals(List.of(), table.getList("refused"));
    // The read was the wrapper's main, name-addressed.
    FakeSpecSource.TextReadAt how = specs.textReadOf(WRAPPER, SpecSource.EGRESS_PATH);
    assertEquals("refs/heads/main", how.rev());
    assertEquals(new RepositoryRef(WRAPPER_ID, PROJECT, WRAPPER), how.repository());
  }

  @Test
  void theWrappersNameIsAskedForWhenTheListingDoesNotCarryIt() {
    directory.project(PROJECT, "qits", WRAPPER_ID, null);
    directory.repository(WRAPPER_ID, WRAPPER);
    specs.scriptText(WRAPPER, SpecSource.EGRESS_PATH, GRANTS);
    projects.refresh();

    assertEquals(2, read().getList("routes").size());
  }

  @Test
  void aWrapperWithNoFileGrantsNothingAndRefusesNothing() {
    directory.project(PROJECT, "qits", WRAPPER_ID, WRAPPER);
    projects.refresh();

    JsonPath table = read();
    assertEquals(List.of(), table.getList("routes"));
    assertEquals(List.of(), table.getList("refused"));
    assertEquals(1, specs.readsOf(WRAPPER, SpecSource.EGRESS_PATH), "the 404 was asked");
  }

  @Test
  void aBadFileIsRefusedWithItsPrincipalAndTheOtherProjectsKeepTheirRoutes() {
    directory.project(PROJECT, "qits", WRAPPER_ID, WRAPPER);
    specs.scriptText(WRAPPER, SpecSource.EGRESS_PATH, GRANTS);
    String other = "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b";
    directory.project(other, "telemetry", "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e", "telemetry-wrapper");
    specs.scriptText(
        "telemetry-wrapper",
        SpecSource.EGRESS_PATH,
        "version: 1\nroutes:\n  - name: p\n    target: platform:projects\n    methods: [GET]\n");
    projects.refresh();

    JsonPath table = read();
    assertEquals(2, table.getList("routes").size(), table.prettify());
    assertTrue(
        table.getList("routes.principal").stream().allMatch(("project:" + PROJECT)::equals),
        table.prettify());
    assertEquals(1, table.getList("refused").size(), table.prettify());
    assertEquals("project:" + other, table.getString("refused[0].principal"));
    assertEquals(null, table.getString("refused[0].application"));
    assertEquals(
        FakeSpecSource.RESOLVED_COMMIT.substring(0, 12), table.getString("refused[0].deployedVersion"));
    assertTrue(
        table.getString("refused[0].reason").contains("grants no `platform:` target"),
        table.prettify());
  }

  @Test
  void anOutageKeepsWhatWasGrantedAndAProjectNoLongerListedLosesIt() {
    directory.project(PROJECT, "qits", WRAPPER_ID, WRAPPER);
    specs.scriptText(WRAPPER, SpecSource.EGRESS_PATH, GRANTS);
    projects.refresh();
    assertEquals(2, read().getList("routes").size());

    directory.failListingWith("qits-projects answered 503");
    projects.refresh();
    assertEquals(2, read().getList("routes").size(), "a listing outage revokes nothing");
    directory.failListingWith(null);

    specs.scriptRetryableFailureAt(WRAPPER, SpecSource.EGRESS_PATH, "the git host answered 503");
    projects.refresh();
    assertEquals(2, read().getList("routes").size(), "an unreadable file revokes nothing");
    specs.recoverAt(WRAPPER, SpecSource.EGRESS_PATH);

    directory.reset();
    projects.refresh();
    assertEquals(List.of(), read().getList("routes"), "a project no longer listed grants nothing");
  }

  @Test
  void theVersionIsUnchangedWhenNothingChanged() {
    directory.project(PROJECT, "qits", WRAPPER_ID, WRAPPER);
    specs.scriptText(WRAPPER, SpecSource.EGRESS_PATH, GRANTS);
    Instant first = Instant.parse("2025-11-25T09:00:00Z");
    long empty = tables.current(first).version();

    projects.refresh();
    long granted = tables.current(first.plusSeconds(60)).version();
    assertTrue(granted > empty, granted + " after " + empty);

    projects.refresh();
    assertEquals(granted, tables.current(first.plusSeconds(120)).version(), "the same file re-read");
    assertEquals(granted, read().getLong("version"));
  }

  @Test
  void theReconcileReadsTheProjectsAndPushesTheirRoutes() {
    directory.project(PROJECT, "qits", WRAPPER_ID, WRAPPER);
    specs.scriptText(WRAPPER, SpecSource.EGRESS_PATH, GRANTS);
    projects.refresh();
    egress.reset();

    assertTrue(pusher.reconcile(), "qits-egress holds the table");
    String pushed = egress.pushes().get(egress.pushes().size() - 1);
    assertTrue(pushed.contains("\"principal\":\"project:" + PROJECT + "\""), pushed);
    assertTrue(pushed.contains("\"secret\":\"PROJECT_QITS_GITHUB_READ\""), pushed);
  }

  private static JsonPath read() {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }
}
