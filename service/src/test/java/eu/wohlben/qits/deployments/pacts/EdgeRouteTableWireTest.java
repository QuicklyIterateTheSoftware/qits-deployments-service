package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.EdgeRouteTables;
import eu.wohlben.qits.deployments.deployments.dto.EdgeRouteTableDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The table this component builds IS the table the edge recorded as accepted</b> (epic
 * qits-2174): the services' labels that describe qits-edge's own recording — {@code qits-ci} public
 * at {@code ci} in {@code prod}, {@code qits-stt} private at {@code stt} — built into the public
 * table and serialised the way the push sends it, compared with the body of the edge's {@code
 * no route table held / putEdgeRouteTable} golden master (answered 204). Field names, nesting,
 * types and nulls all have to agree, so a drift on either side is red here before the edge's
 * {@code RouteTables} ever refuses one.
 */
class EdgeRouteTableWireTest {

  private static final String PROD_ID = "prod-environment-id";

  private static DeploymentDriver.LabelledService labelled(String name, Map<String, String> labels) {
    return new DeploymentDriver.LabelledService(name, labels);
  }

  @Test
  void thePublicTableBuiltFromTheLabelsIsTheBodyTheEdgeAccepts() throws Exception {
    List<DeploymentDriver.LabelledService> services =
        List.of(
            labelled(
                "prod-qits-ci",
                Map.of(
                    DeploymentDriver.APP_NAME_LABEL, "qits-ci",
                    DeploymentDriver.ENVIRONMENT_LABEL, PROD_ID,
                    DeploymentDriver.EXPOSURE_LABEL, "public",
                    DeploymentDriver.HOSTS_LABEL, "ci",
                    DeploymentDriver.ROUTES_LABEL, "/ci",
                    DeploymentDriver.UPSTREAM_PORT_LABEL, "8080",
                    DeploymentDriver.API_DOCS_LABEL, "/ci/q/openapi",
                    DeploymentDriver.NAVIGATION_LABEL, "platform.CI:30")),
            labelled(
                "prod-qits-stt",
                Map.of(
                    DeploymentDriver.APP_NAME_LABEL, "qits-stt",
                    DeploymentDriver.ENVIRONMENT_LABEL, PROD_ID,
                    DeploymentDriver.EXPOSURE_LABEL, "private",
                    DeploymentDriver.HOSTS_LABEL, "stt",
                    DeploymentDriver.ROUTES_LABEL, "/stt",
                    DeploymentDriver.UPSTREAM_PORT_LABEL, "8080")));
    EdgeRouteTables.Content content =
        EdgeRouteTables.contentsOf(services, Map.of(PROD_ID, "prod")).get(EdgeRouteTables.PUBLIC);

    JsonNode accepted =
        Contracts.EDGE_MASTERS.operation(Contracts.NO_ROUTE_TABLE_HELD, "putEdgeRouteTable").body();
    String built =
        EdgeRouteTables.json(
            new EdgeRouteTableDto(
                EdgeRouteTables.PUBLIC,
                accepted.path("version").asLong(),
                accepted.path("builtAt").asText(),
                content.applications(),
                content.withdrawn()));

    assertEquals(accepted, EdgeRouteTables.mapper().readTree(built));
  }

  @Test
  void theRecordedBodyReadsIntoTheTableAndBackUnchanged() throws Exception {
    // The door answers what was stored, read back through the same record: nothing may be lost.
    JsonNode accepted =
        Contracts.EDGE_MASTERS.operation(Contracts.NO_ROUTE_TABLE_HELD, "putEdgeRouteTable").body();
    EdgeRouteTableDto table =
        EdgeRouteTables.mapper().treeToValue(accepted, EdgeRouteTableDto.class);

    assertEquals(accepted, EdgeRouteTables.mapper().readTree(EdgeRouteTables.json(table)));
  }
}
