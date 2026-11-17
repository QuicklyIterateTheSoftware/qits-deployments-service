package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.dto.EdgeRouteTableDto;
import eu.wohlben.qits.deployments.deployments.entity.PdEdgeRouteTable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The route tables stored and pushed (epic qits-2174, task qits-2198), off {@link
 * FakeDeploymentDriver}'s scripted labels and {@link FakeEdgeRouteClient}: the version rule against
 * the real table, the three push triggers, the "same version, no push" pass, and a push that fails
 * without failing anything.
 */
@QuarkusTest
class PdEdgeRouteTablesTest {

  private static final Instant T1 = Instant.parse("2026-11-13T09:20:00Z");

  @Inject EdgeRouteTables tables;
  @Inject EdgeRoutePusher pusher;
  @Inject FakeDeploymentDriver driver;
  @Inject FakeEdgeRouteClient edges;
  @Inject DeployService deployService;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    driver.reset();
    edges.reset();
    deleteTables();
  }

  private static void deleteTables() {
    QuarkusTransaction.requiringNew().run(() -> PdEdgeRouteTable.deleteAll());
  }

  private static DeploymentDriver.LabelledService routed(String app, String exposure, String host) {
    return new DeploymentDriver.LabelledService(
        "dev-" + app,
        Map.of(
            DeploymentDriver.APP_NAME_LABEL, app,
            DeploymentDriver.EXPOSURE_LABEL, exposure,
            DeploymentDriver.ROUTES_LABEL, "/" + app,
            DeploymentDriver.HOSTS_LABEL, host));
  }

  private long version(String edge) {
    return tables.storedRow(edge).orElseThrow().version;
  }

  private EdgeRouteTableDto table(String edge) {
    return EdgeRouteTableDto.of(tables.storedRow(edge).orElseThrow(), EdgeRouteTables.mapper());
  }

  @Test
  void aChangedTableGetsANewVersionAndAnUnchangedOneKeepsIt() {
    driver.scriptLabelledServices(List.of(routed("qits-rt-a", "public", "rta")));

    assertEquals(List.of("public", "private"), tables.rebuild(T1));
    assertEquals(T1.toEpochMilli(), version("public"));
    assertEquals(T1.toEpochMilli(), version("private"));
    assertEquals("qits-rt-a", table("public").applications().get(0).application());

    // Unchanged content: no new version, however much later it is rebuilt.
    assertEquals(List.of(), tables.rebuild(T1.plusSeconds(3600)));
    assertEquals(T1.toEpochMilli(), version("public"));

    // Changed content on a clock BEHIND the stored version: one above it, never below.
    driver.scriptLabelledServices(
        List.of(routed("qits-rt-a", "public", "rta"), routed("qits-rt-b", "public", "rtb")));
    assertEquals(List.of("public"), tables.rebuild(T1.minusSeconds(3600)));
    assertEquals(T1.toEpochMilli() + 1, version("public"));
    assertEquals(T1.toEpochMilli(), version("private"), "the private table did not change");
  }

  @Test
  void aLostTableRowStillNeverAnswersBelowWhatAnEdgeHolds() {
    driver.scriptLabelledServices(List.of(routed("qits-rt-c", "private", "rtc")));
    tables.rebuild(T1);
    long held = version("private");

    // The deployer's database is lost: the next build is floored at the epoch millis of now.
    deleteTables();
    Instant later = T1.plusSeconds(60);
    tables.rebuild(later);

    assertEquals(later.toEpochMilli(), version("private"));
    assertTrue(version("private") > held);
    assertEquals(
        List.of(new EdgeRouteTableDto.Withdrawn("dev", "qits-rt-c", "rtc", "private")),
        table("public").withdrawn());
  }

  @Test
  void aRuntimeThatCannotBeReadChangesNoTable() {
    driver.scriptLabelledServices(List.of(routed("qits-rt-d", "public", "rtd")));
    tables.rebuild(T1);
    driver.scriptLabelledServices(null);

    assertEquals(List.of(), tables.rebuild(T1.plusSeconds(1)));
    assertEquals(1, table("public").applications().size(), "the routes are not withdrawn");
  }

  @Test
  void aNewVersionIsPushedToItsEdgeAndAnUnchangedOneIsNot() {
    driver.scriptLabelledServices(List.of(routed("qits-rt-e", "public", "rte")));

    pusher.refresh();
    assertEquals(1, edges.pushesTo("public").size());
    assertEquals(1, edges.pushesTo("private").size());
    assertEquals(
        tables.storedRow("public").orElseThrow().body,
        edges.pushesTo("public").get(0).table(),
        "the push carries the stored table verbatim");

    pusher.refresh();
    assertEquals(1, edges.pushesTo("public").size(), "an unchanged table is not pushed again");
  }

  @Test
  void anEdgesOwnDeployPushesItsTableEvenWhenNothingChanged() {
    pusher.refresh();
    edges.reset();

    pusher.afterDeploy("qits-edge");
    assertEquals(1, edges.pushesTo("public").size());
    assertEquals(0, edges.pushesTo("private").size());

    pusher.afterDeploy("qits-edge-private");
    assertEquals(1, edges.pushesTo("private").size());
    assertEquals(0, edges.pushesTo("overlay").size(), "the tunnel edge's deploy is not the overlay's");

    pusher.afterDeploy("qits-edge-overlay");
    assertEquals(1, edges.pushesTo("overlay").size());
    assertEquals(1, edges.pushesTo("private").size(), "nor the overlay's the tunnel edge's");
    assertEquals(
        tables.storedRow("private").orElseThrow().body,
        edges.pushesTo("overlay").get(0).table(),
        "the overlay edge is sent the private table, verbatim");

    pusher.afterDeploy("qits-ci");
    assertEquals(3, edges.pushes().size(), "another application's deploy pushes nothing unchanged");
  }

  @Test
  void aNewPrivateVersionIsPushedToBothPrivateKindEdges() {
    // qits-2304: two tables, three targets — the private table goes to qits-edge-private and to
    // qits-edge-overlay, the same body, and no third row is stored for it.
    driver.scriptLabelledServices(List.of(routed("qits-rt-o", "private", "rto")));

    pusher.refresh();

    String body = tables.storedRow("private").orElseThrow().body;
    assertEquals(1, edges.pushesTo("private").size());
    assertEquals(1, edges.pushesTo("overlay").size());
    assertEquals(body, edges.pushesTo("private").get(0).table());
    assertEquals(body, edges.pushesTo("overlay").get(0).table());
    assertTrue(tables.storedRow("overlay").isEmpty(), "the overlay edge is a target, not a table");
    assertEquals(version("private"), edges.held("overlay").orElseThrow());
  }

  @Test
  void anOverlayEdgeNotDeployedYetFailsNothingAndIsCaughtUpOnceItAnswers() {
    driver.scriptLabelledServices(List.of(routed("qits-rt-p", "private", "rtp")));
    edges.failWith("overlay", "no such host: dev-qits-edge-overlay");

    assertDoesNotThrow(() -> pusher.refresh());
    assertEquals(1, edges.pushesTo("private").size(), "the tunnel edge is pushed regardless");
    assertEquals(version("private"), edges.held("private").orElseThrow());
    assertDoesNotThrow(() -> pusher.observe());
    assertDoesNotThrow(() -> pusher.afterDeploy("qits-ci"));

    // It is deployed: its own deploy pushes it, and so would the next pass.
    edges.clearFailure("overlay");
    pusher.observe();
    assertEquals(version("private"), edges.held("overlay").orElseThrow());
    assertEquals(1, edges.pushesTo("private").size(), "the private edge held it all along");
  }

  @Test
  void aPassPushesToAnEdgeThatReportsAnOlderVersionAndNotToOneThatReportsTheSame() {
    driver.scriptLabelledServices(List.of(routed("qits-rt-f", "public", "rtf")));
    pusher.refresh();
    long stored = version("public");
    edges.reset();

    // Every edge holds what was pushed: the pass reads each and pushes nothing.
    edges.reportHeld("public", stored);
    edges.reportHeld("private", version("private"));
    edges.reportHeld("overlay", version("private"));
    pusher.observe();
    assertEquals(1, edges.readsOf("public"));
    assertEquals(1, edges.readsOf("private"));
    assertEquals(1, edges.readsOf("overlay"), "the overlay edge is asked on its own");
    assertEquals(List.of(), edges.pushes());

    // The public edge was redeployed onto an empty cache, the private one onto an older table.
    edges.reportHeld("public", null);
    edges.reportHeld("private", version("private") - 1);
    pusher.observe();
    assertEquals(1, edges.pushesTo("public").size());
    assertEquals(1, edges.pushesTo("private").size());
    assertEquals(0, edges.pushesTo("overlay").size(), "the overlay edge still holds the version");
  }

  @Test
  void aFailedPushNeitherThrowsNorStopsTheOtherEdgeAndTheNextPassRetries() {
    driver.scriptLabelledServices(List.of(routed("qits-rt-g", "public", "rtg")));
    edges.failWith("public", "connection refused");

    assertDoesNotThrow(() -> pusher.refresh());
    assertEquals(1, edges.pushesTo("public").size());
    assertEquals(1, edges.pushesTo("private").size(), "the other edge is pushed regardless");
    assertDoesNotThrow(() -> pusher.observe());

    edges.clearFailure("public");
    pusher.observe();
    assertEquals(
        version("public"),
        edges.held("public").orElseThrow(),
        "the pass after the outage delivers the stored version");
  }

  @Test
  void aFailedPushDoesNotFailTheDeploymentThatTriggeredIt() throws Exception {
    String environmentId = createEnvironment("edge-rt-push-fails");
    driver.scriptLabelledServices(List.of(routed("qits-rt-h", "public", "rth")));
    edges.failWith("public", "connection refused");
    edges.failWith("private", "connection refused");

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-edge-rt", "repoId", "repo-edge-rt", "version", "2026.1115.1"))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    deployService.awaitIdle();

    List<Map<String, Object>> deployments =
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get("/deployments/api/deployments?environmentId=" + environmentId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("deployments");
    assertEquals(1, deployments.size());
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertTrue(driver.labelReads() >= 1, "the deployment rebuilt the route tables");
    assertTrue(edges.pushesTo("public").size() >= 1, "and tried to push the new version");
  }

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
}
