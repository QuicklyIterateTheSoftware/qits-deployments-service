package eu.wohlben.qits.deployments.deployments.control;

import java.util.OptionalLong;

/**
 * The seam to qits-egress's grant-table door (epic qits-2523): {@code PUT /egress/api/grant-table}
 * and {@code GET /egress/api/grant-table}, presented with this component's own {@code qits:system}
 * bearer. Its implementation is {@code egresshost/HttpEgressGrantClient}; the suite installs {@code
 * FakeEgressGrantClient}.
 *
 * <p><b>Pushed, never pulled</b>: qits-egress is a public service and this one is private, so
 * qits-egress cannot reach {@code GET /deployments/api/egress-grants}. This side carries the table
 * to it, the way {@link EdgeRouteClient} carries the route tables to the edges.
 *
 * <p>Both calls throw {@link EgressGrantException} when qits-egress could not be reached or
 * answered anything the door does not promise; {@link EgressGrantPusher} turns that into one WARN
 * per outage.
 */
public interface EgressGrantClient {

  /** What qits-egress made of a pushed table. */
  sealed interface Pushed {}

  /** {@code 204}: stored, or the version it already holds. */
  record Stored() implements Pushed {}

  /** {@code 409 {"error":…,"held":v}}: qits-egress holds {@code held}, which is newer. */
  record Newer(long held) implements Pushed {}

  /**
   * Push the table.
   *
   * @param tableJson exactly what {@code GET /deployments/api/egress-grants} answers, {@code
   *     {version, routes, refused}}, sent verbatim
   */
  Pushed push(String tableJson);

  /** The version qits-egress holds, or empty when it holds none yet ({@code 404}). */
  OptionalLong heldVersion();
}
