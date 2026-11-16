package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.dto.SplitCheckDto;
import eu.wohlben.qits.deployments.deployments.entity.PdEdgeRouteTable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The split check (epic qits-2174, task qits-2215) against {@link FakeDeploymentDriver}'s labels and
 * attachments and {@link FakePublicEdgeProbe}: how the public edge's answer is classified, where the
 * probe is aimed, the cadence, and the door.
 */
@QuarkusTest
class PdSplitCheckTest {

  private static final Instant T1 = Instant.parse("2026-11-16T09:00:00Z");

  /** {@code qits.deployments.platform-domain} in the suite. */
  private static final String DOMAIN = "dev.localhost:8080";

  private static final String SPLIT_CHECK = "/deployments/api/split-check";

  @Inject SplitCheck check;
  @Inject EdgeRouteTables tables;
  @Inject FakeDeploymentDriver driver;
  @Inject FakePublicEdgeProbe probe;
  @Inject FakeEdgeRouteClient edges;
  @Inject DeployService deployService;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.deployments.split-check.every")
  Duration every;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    driver.reset();
    probe.reset();
    edges.reset();
    check.forget();
    QuarkusTransaction.requiringNew().run(() -> PdEdgeRouteTable.deleteAll());
  }

  private static DeploymentDriver.LabelledService service(
      String app, String exposure, String placement, String host) {
    Map<String, String> labels = new HashMap<>();
    labels.put(DeploymentDriver.APP_NAME_LABEL, app);
    labels.put(DeploymentDriver.EXPOSURE_LABEL, exposure);
    labels.put(DeploymentDriver.PLACEMENT_LABEL, placement);
    if (host != null) {
      labels.put(DeploymentDriver.ROUTES_LABEL, "/" + host);
      labels.put(DeploymentDriver.HOSTS_LABEL, host);
    }
    return new DeploymentDriver.LabelledService("dev-" + app, labels);
  }

  /** The estate the checks read: two private apps with a host, one public app, all in place. */
  private void aCleanEstate() {
    driver.scriptLabelledServices(
        List.of(
            service("qits-sc-stt", "private", "qits-private", "sc-stt"),
            service("qits-sc-docs", "private", "qits-private", "sc-docs"),
            service("qits-sc-web", "public", "qits-net", "sc-web")));
    driver.scriptAttachedNetworks("dev-qits-sc-stt", Set.of("qits-private"));
    driver.scriptAttachedNetworks("dev-qits-sc-docs", Set.of("qits-private"));
    driver.scriptAttachedNetworks("dev-qits-sc-web", Set.of("qits-net"));
    tables.rebuild(T1);
  }

  private static SplitCheckDto.PrivateApp app(SplitCheckDto result, String application) {
    return result.privateApps().stream()
        .filter(app -> application.equals(app.application()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + application + " in " + result.privateApps()));
  }

  @Test
  void everyPrivateAppIsProbedOnTheApexWithItsOwnNameAndPath() {
    aCleanEstate();

    SplitCheckDto result = check.run(T1).orElseThrow();

    assertTrue(result.ok(), result.toString());
    assertEquals(T1.toString(), result.checkedAt());
    assertEquals(
        List.of(
            new FakePublicEdgeProbe.Probe(
                "qits." + DOMAIN, "sc-docs.qits." + DOMAIN, "/sc-docs/q/health/ready"),
            new FakePublicEdgeProbe.Probe(
                "qits." + DOMAIN, "sc-stt.qits." + DOMAIN, "/sc-stt/q/health/ready")),
        probe.probes(),
        "the private table's apps only, never the public one");
    assertEquals(
        List.of(
            new SplitCheckDto.PrivateApp("qits-sc-docs", "sc-docs", 404, null),
            new SplitCheckDto.PrivateApp("qits-sc-stt", "sc-stt", 404, null)),
        result.privateApps());
  }

  @Test
  void fourHundredFourAndFourHundredTwentyOneAreRefusalsAndEverythingElseIsAnError() {
    aCleanEstate();
    probe.answer("sc-stt.qits." + DOMAIN, 421);
    assertTrue(check.run(T1).orElseThrow().ok(), "421 is the public edge refusing a private name");

    for (int served : List.of(200, 302, 401, 403, 502)) {
      probe.answer("sc-stt.qits." + DOMAIN, served);
      SplitCheckDto result = check.run(T1).orElseThrow();
      assertFalse(result.ok(), served + " must fail the check");
      assertEquals(served, app(result, "qits-sc-stt").status());
      assertEquals(404, app(result, "qits-sc-docs").status(), "each app is recorded on its own");
    }
  }

  @Test
  void aProbeThatGetsNoAnswerFailsTheCheckAndSaysWhy() {
    aCleanEstate();
    probe.failWith("sc-docs.qits." + DOMAIN, "Connection refused");

    SplitCheckDto result = check.run(T1).orElseThrow();

    assertFalse(result.ok());
    SplitCheckDto.PrivateApp docs = app(result, "qits-sc-docs");
    assertNull(docs.status());
    assertEquals("Connection refused", docs.error());
  }

  @Test
  void aBridgedOrMisplacedServiceFailsTheCheck() {
    aCleanEstate();
    driver.scriptAttachedNetworks("dev-qits-sc-web", Set.of("qits-net", "qits-private"));
    driver.scriptAttachedNetworks("dev-qits-sc-stt", Set.of("qits-net"));

    SplitCheckDto result = check.run(T1).orElseThrow();

    assertFalse(result.ok());
    assertEquals(
        List.of(new SplitCheckDto.Bridged("dev-qits-sc-web", List.of("qits-net", "qits-private"))),
        result.bridged());
    assertEquals(
        List.of("dev-qits-sc-stt", "dev-qits-sc-web"),
        result.misplaced().stream().map(SplitCheckDto.Misplaced::service).toList());
  }

  @Test
  void aRuntimeThatCannotBeReadChecksNothingAndKeepsTheLastResult() {
    aCleanEstate();
    SplitCheckDto first = check.run(T1).orElseThrow();
    driver.scriptLabelledServices(null);
    tables.rebuild(T1.plusSeconds(1));
    probe.reset();

    assertTrue(check.run(T1.plusSeconds(1)).isEmpty());
    assertEquals(first, check.last().orElseThrow());
    assertEquals(List.of(), probe.probes());
  }

  @Test
  void theObservationPassChecksAtMostOncePerWindow() {
    aCleanEstate();

    check.observe(T1);
    assertEquals(2, probe.probes().size(), "the first pass checks");
    check.observe(T1.plus(every).minusSeconds(1));
    assertEquals(2, probe.probes().size(), "inside the window: no check");
    check.observe(T1.plus(every));
    assertEquals(4, probe.probes().size(), "the window is over: a check");
  }

  @Test
  void aDeployOfEitherEdgeChecksAtOnceAndAnyOtherDeployDoesNot() {
    aCleanEstate();
    check.observe(T1);
    probe.reset();

    check.afterDeploy("qits-sc-web");
    assertEquals(List.of(), probe.probes(), "not an edge");
    check.afterDeploy(EdgeRouteTables.PRIVATE_EDGE_APPLICATION);
    assertEquals(2, probe.probes().size());
    check.afterDeploy(EdgeRouteTables.PUBLIC_EDGE_APPLICATION);
    assertEquals(4, probe.probes().size());
  }

  @Test
  void anObservationPassOfTheWorkerRunsTheCheck() throws Exception {
    driver.scriptLabelledServices(List.of(service("qits-sc-obs", "private", "qits-private", "sc-obs")));
    driver.scriptAttachedNetworks("dev-qits-sc-obs", Set.of("qits-private"));

    deployService.enqueueObservation();
    deployService.awaitIdle();

    SplitCheckDto result = check.last().orElseThrow();
    assertTrue(result.ok(), result.toString());
    assertEquals(List.of("qits-sc-obs"), result.privateApps().stream().map(SplitCheckDto.PrivateApp::application).toList());
  }

  // --- the door ----------------------------------------------------------------------------------

  @Test
  void theDoorAnswers503BeforeTheFirstCheck() {
    given()
        .header("Authorization", SuiteBearers.bearer("sc-agent", "qits:agent"))
        .when()
        .get(SPLIT_CHECK)
        .then()
        .statusCode(503);
  }

  @Test
  void theDoorAnswersTheLastResultWithoutRunningACheck() {
    aCleanEstate();
    driver.scriptAttachedNetworks("dev-qits-sc-web", Set.of("qits-net", "qits-private"));
    check.run(T1);
    probe.reset();

    given()
        .header("Authorization", SuiteBearers.bearer("sc-agent", "qits:agent"))
        .when()
        .get(SPLIT_CHECK)
        .then()
        .statusCode(200)
        .body("checkedAt", equalTo(T1.toString()))
        .body("ok", equalTo(false))
        .body("bridged", hasSize(1))
        .body("bridged[0].service", equalTo("dev-qits-sc-web"))
        .body("bridged[0].networks", equalTo(List.of("qits-net", "qits-private")))
        .body("misplaced[0].placement", equalTo("qits-net"))
        .body("privateApps", hasSize(2))
        .body("privateApps[0].application", equalTo("qits-sc-docs"))
        .body("privateApps[0].host", equalTo("sc-docs"))
        .body("privateApps[0].status", equalTo(404));
    assertEquals(List.of(), probe.probes(), "a read runs no check");
  }

  @Test
  void theDoorAdmitsTheReadRolesAndNoOther() {
    aCleanEstate();
    check.run(T1);
    for (String role :
        List.of("qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:agent")) {
      given()
          .header("Authorization", SuiteBearers.bearer("sc-" + role.replace(':', '-'), role))
          .when()
          .get(SPLIT_CHECK)
          .then()
          .statusCode(200);
    }
    given()
        .header("Authorization", SuiteBearers.bearer("sc-system", "qits:system"))
        .when()
        .get(SPLIT_CHECK)
        .then()
        .statusCode(403);
    given().when().get(SPLIT_CHECK).then().statusCode(401);
  }
}
