package eu.wohlben.qits.deployments.deployments.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressGrantTable;
import java.util.List;

/**
 * The egress grant table in force (epic qits-2523): what {@code GET /deployments/api/egress-grants}
 * answers and qits-egress polls. Each route is a grant {@code (application, route, methods)},
 * taken from the {@code .config/qits/egress.yml} of each application's {@code ACTIVE} deployment.
 *
 * @param version only ever goes up, and moves only when the content does
 * @param routes every granted route, by environment, application and name
 * @param refused every {@code ACTIVE} deployment whose file was refused — that version carries no
 *     routes — by application and version
 */
public record EgressGrantTableDto(long version, List<Route> routes, List<Refused> refused) {

  public EgressGrantTableDto {
    routes = routes == null ? List.of() : List.copyOf(routes);
    refused = refused == null ? List.of() : List.copyOf(refused);
  }

  /**
   * One granted route.
   *
   * @param environment the name of the tier the deployment runs in
   * @param deployedVersion the released version whose file granted it
   * @param name the first path segment at the gateway, unique per application
   * @param target {@code https://…} or {@code platform:<label>}
   * @param secret the settings key whose value the gateway injects; null for a platform target
   * @param inject {@code bearer} or {@code header:<Header-Name>}; null for a platform target
   * @param methods sorted
   */
  public record Route(
      String application,
      String environment,
      String deployedVersion,
      String name,
      String target,
      String secret,
      String inject,
      List<String> methods) {

    public Route {
      methods = methods == null ? List.of() : List.copyOf(methods);
    }
  }

  /** An {@code ACTIVE} deployment whose egress declaration was refused, and why. */
  public record Refused(String application, String deployedVersion, String reason) {}

  /** The stored table, read back from the body it was stored as. */
  public static EgressGrantTableDto of(PdEgressGrantTable row, ObjectMapper json) {
    try {
      return json.readValue(row.body, EgressGrantTableDto.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "the stored egress grant table is unreadable: " + e.getMessage(), e);
    }
  }
}
