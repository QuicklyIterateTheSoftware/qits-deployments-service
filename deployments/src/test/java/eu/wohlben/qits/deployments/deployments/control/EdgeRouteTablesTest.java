package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.dto.EdgeRouteTableDto;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What goes into each edge's route table, off the services' labels (epic qits-2174), and the
 * version rule — without a boot. The storage half is {@code PdEdgeRouteTablesTest}'s.
 */
class EdgeRouteTablesTest {

  private static final String DEV_ID = "env-dev-id";
  private static final Map<String, String> NAMES = Map.of(DEV_ID, "dev");

  static DeploymentDriver.LabelledService service(
      String name, String app, String exposure, String routes, String hosts, Map<String, String> more) {
    Map<String, String> labels = new HashMap<>();
    labels.put(DeploymentDriver.APP_NAME_LABEL, app);
    labels.put(DeploymentDriver.ENVIRONMENT_LABEL, DEV_ID);
    if (exposure != null) {
      labels.put(DeploymentDriver.EXPOSURE_LABEL, exposure);
    }
    if (routes != null) {
      labels.put(DeploymentDriver.ROUTES_LABEL, routes);
    }
    if (hosts != null) {
      labels.put(DeploymentDriver.HOSTS_LABEL, hosts);
    }
    labels.putAll(more);
    return new DeploymentDriver.LabelledService(name, labels);
  }

  @Test
  void aPublicServiceWithRoutesIsInThePublicTableWithEverythingItsLabelsSay() {
    var ci =
        service(
            "dev-qits-ci",
            "qits-ci",
            "public",
            "/ci,/ci-hooks",
            "ci",
            Map.of(
                DeploymentDriver.UPSTREAM_PORT_LABEL, "8081",
                DeploymentDriver.API_DOCS_LABEL, "/ci/q/openapi",
                DeploymentDriver.NAVIGATION_LABEL, "platform.CI:30,project.detail.Runs:2=runs"));

    var tables = EdgeRouteTables.contentsOf(List.of(ci), NAMES);

    assertEquals(
        List.of(
            new EdgeRouteTableDto.Application(
                "dev",
                "qits-ci",
                "public",
                "ci",
                "/ci/q/openapi",
                List.of(
                    new EdgeRouteTableDto.Endpoint("/ci", "dev-qits-ci", 8081),
                    new EdgeRouteTableDto.Endpoint("/ci-hooks", "dev-qits-ci", 8081)),
                List.of(
                    new EdgeRouteTableDto.Navigation("platform", "CI", 30, null),
                    new EdgeRouteTableDto.Navigation("project.detail", "Runs", 2, "runs")))),
        tables.get(EdgeRouteTables.PUBLIC).applications());
    assertEquals(List.of(), tables.get(EdgeRouteTables.PUBLIC).withdrawn());
    assertEquals(List.of(), tables.get(EdgeRouteTables.PRIVATE).applications());
  }

  @Test
  void aPrivateServiceIsInThePrivateTableAndWithdrawnFromThePublicOne() {
    var stt = service("dev-qits-stt", "qits-stt", "private", "/stt", "stt", Map.of());

    var tables = EdgeRouteTables.contentsOf(List.of(stt), NAMES);

    assertEquals(
        List.of(
            new EdgeRouteTableDto.Application(
                "dev",
                "qits-stt",
                "private",
                "stt",
                null,
                List.of(new EdgeRouteTableDto.Endpoint("/stt", "dev-qits-stt", 8080)),
                List.of())),
        tables.get(EdgeRouteTables.PRIVATE).applications());
    assertEquals(List.of(), tables.get(EdgeRouteTables.PRIVATE).withdrawn());
    assertEquals(List.of(), tables.get(EdgeRouteTables.PUBLIC).applications());
    assertEquals(
        List.of(new EdgeRouteTableDto.Withdrawn("dev", "qits-stt", "stt", "private")),
        tables.get(EdgeRouteTables.PUBLIC).withdrawn());
  }

  @Test
  void theEdgesRoutelessServicesAndUnplacedServicesAreInNoTable() {
    var services =
        List.of(
            service("dev-qits-edge", "qits-edge", "public", "/edge", "edge", Map.of()),
            service("dev-qits-edge-private", "qits-edge-private", "private", "/x", null, Map.of()),
            service("dev-qits-edge-overlay", "qits-edge-overlay", "private", "/y", null, Map.of()),
            service("dev-qits-oci-postgresql", "qits-oci-postgresql", "public", "", "", Map.of()),
            service("dev-qits-daemon", "qits-daemon", "private", null, null, Map.of()),
            service("dev-qits-unplaced", "qits-unplaced", null, "/unplaced", null, Map.of()));

    var tables = EdgeRouteTables.contentsOf(services, NAMES);

    assertEquals(List.of(), tables.get(EdgeRouteTables.PUBLIC).applications());
    assertEquals(List.of(), tables.get(EdgeRouteTables.PUBLIC).withdrawn());
    assertEquals(List.of(), tables.get(EdgeRouteTables.PRIVATE).applications());
  }

  @Test
  void theUpstreamIsTheWireAliasAndAnUnknownEnvironmentIdFallsBackToTheServiceName() {
    var orphan =
        new DeploymentDriver.LabelledService(
            "prod-qits-docs",
            Map.of(
                DeploymentDriver.APP_NAME_LABEL, "qits-docs",
                DeploymentDriver.ENVIRONMENT_LABEL, "an-id-nobody-knows",
                DeploymentDriver.EXPOSURE_LABEL, "public",
                DeploymentDriver.ROUTES_LABEL, "/docs"));

    var application =
        EdgeRouteTables.contentsOf(List.of(orphan), NAMES)
            .get(EdgeRouteTables.PUBLIC)
            .applications()
            .get(0);

    assertEquals("prod", application.environment());
    assertEquals("prod-qits-docs", application.endpoints().get(0).upstreamHost());
    assertNull(application.browserHost());
  }

  @Test
  void twoServicesOfOneApplicationAreOneEntryTheWireAliasFirst() {
    // A seed-stack twin or a rename in flight: the edge refuses a table naming an application twice.
    var twin = service("qits-seed_dev-qits-ci", "qits-ci", "public", "/ci-old", "ci", Map.of());
    var alias = service("dev-qits-ci", "qits-ci", "public", "/ci", "ci", Map.of());

    var applications =
        EdgeRouteTables.contentsOf(List.of(twin, alias), NAMES)
            .get(EdgeRouteTables.PUBLIC)
            .applications();

    assertEquals(1, applications.size());
    assertEquals("/ci", applications.get(0).endpoints().get(0).path());
  }

  @Test
  void theTablesAreSortedSoTheHashDoesNotDependOnTheRuntimesOrder() {
    var a = service("dev-qits-a", "qits-a", "public", "/a", null, Map.of());
    var b = service("dev-qits-b", "qits-b", "public", "/b", null, Map.of());

    var forwards = EdgeRouteTables.contentsOf(List.of(a, b), NAMES).get(EdgeRouteTables.PUBLIC);
    var backwards = EdgeRouteTables.contentsOf(List.of(b, a), NAMES).get(EdgeRouteTables.PUBLIC);

    assertEquals(forwards, backwards);
    assertEquals(EdgeRouteTables.hash(forwards), EdgeRouteTables.hash(backwards));
    assertNotEquals(
        EdgeRouteTables.hash(forwards),
        EdgeRouteTables.hash(
            EdgeRouteTables.contentsOf(List.of(a), NAMES).get(EdgeRouteTables.PUBLIC)));
  }

  @Test
  void aChangedTableGoesOneAboveTheStoredVersionAndNeverBelowTheEpochMillis() {
    Instant now = Instant.parse("2026-11-13T09:20:00Z");
    long millis = now.toEpochMilli();

    // Nothing stored: the millis of now.
    assertEquals(millis, EdgeRouteTables.nextVersion(null, now));
    // Stored below now: the floor wins — a deployer that lost its table still answers above it.
    assertEquals(millis, EdgeRouteTables.nextVersion(5L, now));
    // Stored at or above now (two changes in one millisecond, a clock that stepped back): +1.
    assertEquals(millis + 1, EdgeRouteTables.nextVersion(millis, now));
    assertEquals(millis + 8, EdgeRouteTables.nextVersion(millis + 7, now));
    assertTrue(EdgeRouteTables.nextVersion(null, Instant.EPOCH) >= 1, "a version is positive");
  }

  @Test
  void anApplicationIsTheEdgeOfItsOwnDeployOnlyWhenItIsAnEdge() {
    assertEquals(EdgeRouteTables.PUBLIC, EdgeRouteTables.edgeOf("qits-edge"));
    assertEquals(EdgeRouteTables.PRIVATE, EdgeRouteTables.edgeOf("qits-edge-private"));
    assertEquals(EdgeRouteTables.OVERLAY, EdgeRouteTables.edgeOf("qits-edge-overlay"));
    assertNull(EdgeRouteTables.edgeOf("qits-ci"));
    assertTrue(EdgeRouteTables.isEdgeApplication("qits-edge-overlay"));
    assertFalse(EdgeRouteTables.isEdgeApplication("qits-ci"));
  }

  @Test
  void twoTablesAreStoredAndThreeTargetsCarryThem() {
    // qits-2304: the overlay edge is a push target, never a table of its own.
    assertEquals(List.of("public", "private"), EdgeRouteTables.TABLES);
    assertEquals(List.of("public", "private", "overlay"), EdgeRouteTables.TARGETS);
    assertEquals(EdgeRouteTables.PUBLIC, EdgeRouteTables.tableOf(EdgeRouteTables.PUBLIC));
    assertEquals(EdgeRouteTables.PRIVATE, EdgeRouteTables.tableOf(EdgeRouteTables.PRIVATE));
    assertEquals(EdgeRouteTables.PRIVATE, EdgeRouteTables.tableOf(EdgeRouteTables.OVERLAY));
    assertEquals(List.of("public"), EdgeRouteTables.targetsOf(EdgeRouteTables.PUBLIC));
    assertEquals(List.of("private", "overlay"), EdgeRouteTables.targetsOf(EdgeRouteTables.PRIVATE));
    assertThrows(IllegalArgumentException.class, () -> EdgeRouteTables.tableOf("nowhere"));
  }
}
