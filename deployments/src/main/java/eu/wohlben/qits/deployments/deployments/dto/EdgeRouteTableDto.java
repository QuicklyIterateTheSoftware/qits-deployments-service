package eu.wohlben.qits.deployments.deployments.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.entity.PdEdgeRouteTable;
import java.util.List;

/**
 * One edge's route table (epic qits-2174): what {@code PUT /edge-route-table} carries to the edge
 * and {@code GET /deployments/api/edge-routes/{edge}} answers. The field names are the ones
 * qits-edge's {@code RouteTables} parses, and {@code endpoints}/{@code navigation} are spelled like
 * {@code DeploymentActive}'s.
 *
 * @param edge {@code public} or {@code private}
 * @param version only ever goes up; the edge ignores a table whose version is not above its own
 * @param builtAt when this version was built, ISO-8601
 * @param applications every routed application this edge serves
 * @param withdrawn hosts this edge must not serve: on the public table, every private application
 *     with routes, for {@code /main-navigation}; always empty on the private one
 */
public record EdgeRouteTableDto(
    String edge,
    long version,
    String builtAt,
    List<Application> applications,
    List<Withdrawn> withdrawn) {

  public EdgeRouteTableDto {
    applications = applications == null ? List.of() : List.copyOf(applications);
    withdrawn = withdrawn == null ? List.of() : List.copyOf(withdrawn);
  }

  /**
   * One routed application.
   *
   * @param browserHost the DNS label it is served at, or null for a path-only application
   * @param apiDocsPath where its browsable API document lives, or null
   * @param endpoints one per route prefix, primary first; the upstream is the wire alias
   * @param navigation where it asks to appear, possibly empty
   */
  public record Application(
      String environment,
      String application,
      String exposure,
      String browserHost,
      String apiDocsPath,
      List<Endpoint> endpoints,
      List<Navigation> navigation) {

    public Application {
      endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
      navigation = navigation == null ? List.of() : List.copyOf(navigation);
    }
  }

  /** One route prefix and the upstream behind it — {@code DeploymentEndpoint}'s fields. */
  public record Endpoint(String path, String upstreamHost, int upstreamPort) {}

  /** One navigation placement — {@code NavigationEntry}'s fields; {@code subpath} may be null. */
  public record Navigation(String slot, String label, int position, String subpath) {}

  /** One host withdrawn from this edge; {@code host} is null for a path-only application. */
  public record Withdrawn(String environment, String application, String host, String exposure) {}

  /** The stored table, read back from the body it was pushed as. */
  public static EdgeRouteTableDto of(PdEdgeRouteTable row, ObjectMapper json) {
    try {
      return json.readValue(row.body, EdgeRouteTableDto.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "the stored route table of the " + row.edge + " edge is unreadable: " + e.getMessage(), e);
    }
  }
}
