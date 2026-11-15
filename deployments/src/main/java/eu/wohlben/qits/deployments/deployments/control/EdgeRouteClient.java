package eu.wohlben.qits.deployments.deployments.control;

import java.util.OptionalLong;

/**
 * The seam to the two edges' route-table door (epic qits-2174): {@code PUT /edge-route-table} and
 * {@code GET /edge-route-table}, presented with this component's own {@code qits:system} bearer.
 * Its implementation is {@code edgehost/HttpEdgeRouteClient}; the suite installs {@code
 * FakeEdgeRouteClient}.
 *
 * <p>Both calls throw {@link EdgeRouteException} when the edge could not be reached or answered
 * anything the door does not promise; {@link EdgeRoutePusher} turns that into one WARN per outage.
 */
public interface EdgeRouteClient {

  /** What the edge made of a pushed table. */
  sealed interface Pushed {}

  /** {@code 204}: stored, it is the edge's table now. */
  record Stored() implements Pushed {}

  /** {@code 200 {"held":v}}: the edge already holds {@code held}, which is not below the push. */
  record Held(long held) implements Pushed {}

  /** {@code 422 {"error":…}}: the edge refused the table whole and kept the one it held. */
  record Refused(String reason) implements Pushed {}

  /**
   * Push one table.
   *
   * @param edge {@code public} or {@code private}
   * @param table the table's JSON, sent verbatim
   */
  Pushed push(String edge, String table);

  /** The version the edge holds, or empty when it holds none yet. */
  OptionalLong held(String edge);
}
