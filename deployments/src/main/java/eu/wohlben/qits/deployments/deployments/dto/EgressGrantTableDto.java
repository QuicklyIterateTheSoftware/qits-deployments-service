package eu.wohlben.qits.deployments.deployments.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressGrantTable;
import java.util.List;

/**
 * The egress grant table in force (epic qits-2523): what {@code GET /deployments/api/egress-grants}
 * answers and qits-egress is pushed. Each route is a grant {@code (principal, route, methods)},
 * taken from the {@code .config/qits/egress.yml} of each application's {@code ACTIVE} deployment
 * (a variant's own {@code egress.<variant>.yml}, qits-2784) ({@code application:<app>}) and, since qits-2659, of each project's wrapper at {@code main}
 * ({@code project:<projectId>}).
 *
 * @param version only ever goes up, and moves only when the content does
 * @param routes every granted route, by environment, application and name
 * @param refused every {@code ACTIVE} deployment, and every project, whose file was refused — that
 *     version carries no routes — by principal and version
 */
public record EgressGrantTableDto(long version, List<Route> routes, List<Refused> refused) {

  public EgressGrantTableDto {
    routes = routes == null ? List.of() : List.copyOf(routes);
    refused = refused == null ? List.of() : List.copyOf(refused);
  }

  /**
   * One granted route.
   *
   * @param principal who it is granted to: {@code application:<app>} or {@code
   *     project:<projectId>} (qits-2659)
   * @param application the application, for an application route; null for a project route
   * @param environment the name of the tier the deployment runs in; null for a project route
   * @param deployedVersion the released version whose file granted it; for a project route the
   *     wrapper's {@code main} commit, 12 characters
   * @param name the first path segment at the gateway, unique per principal
   * @param target {@code https://…} or {@code platform:<label>}
   * @param secret the settings key whose value the gateway injects ({@code
   *     QITS_EGRESS_SECRET_<secret>}); a project route's is resolved, {@code PROJECT_<SLUG>_<name>};
   *     null for a platform target
   * @param inject {@code bearer}, {@code header:<Header-Name>} or {@code cookie:<name>}; null for a
   *     platform target
   * @param methods sorted
   */
  public record Route(
      String principal,
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

  /**
   * An {@code ACTIVE} deployment, or a project's wrapper, whose egress declaration was refused, and
   * why.
   *
   * @param principal {@code application:<app>} or {@code project:<projectId>}
   * @param application null for a project
   * @param deployedVersion the released version; for a project the wrapper's {@code main} commit, 12
   *     characters, or null when the read never answered
   */
  public record Refused(
      String principal, String application, String deployedVersion, String reason) {}

  /** The principal of an application's routes. */
  public static String applicationPrincipal(String application) {
    return "application:" + application;
  }

  /** The principal of a project's routes. */
  public static String projectPrincipal(String projectId) {
    return "project:" + projectId;
  }

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
