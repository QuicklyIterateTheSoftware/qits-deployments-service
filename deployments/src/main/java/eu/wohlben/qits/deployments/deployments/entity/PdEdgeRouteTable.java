package eu.wohlben.qits.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnTransformer;

/**
 * The newest route table built for one edge (epic qits-2174) — see {@code V23__edge_route_table.sql}
 * and {@code EdgeRouteTables}. {@link #body} is the table exactly as it is pushed to the edge and
 * answered by {@code GET /deployments/api/edge-routes/{edge}}.
 *
 * <p><b>{@code @Uncaused} by decision</b>, {@link PdResource}'s reason: the row is a converging
 * projection of the runtime's labels, rewritten in place by every rebuild — on the worker after a
 * deployment, and on the observation ticker with no event behind it — so a cause column would
 * record whichever pass happened to change it last.
 */
@Uncaused
@Entity
@Table(name = "pd_edge_route_table")
public class PdEdgeRouteTable extends PanacheEntityBase {

  /** {@code public} or {@code private}. */
  @Id public String edge;

  /** Only ever goes up: see {@code EdgeRouteTables#nextVersion}. */
  @Column(nullable = false)
  public long version;

  /** The sha-256 of the canonical JSON of the table's applications and withdrawn hosts. */
  @Column(name = "content_hash", nullable = false, columnDefinition = "text")
  public String contentHash;

  /** The whole table as JSON text, the V17 jsonb shape. */
  @Column(nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  public String body;

  @Column(name = "built_at", nullable = false)
  public Instant builtAt;
}
