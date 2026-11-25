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
 * The egress grant table in force (epic qits-2523) — one row, see {@code V27__egress_declaration.sql}
 * and {@code EgressGrantTables}. {@link #body} is the table exactly as {@code GET
 * /deployments/api/egress-grants} answers it.
 *
 * <p><b>{@code @Uncaused} by decision</b>, {@link PdEdgeRouteTable}'s reason: a converging
 * projection of the deployment rows, rewritten in place whenever its content changes.
 */
@Uncaused
@Entity
@Table(name = "pd_egress_grant_table")
public class PdEgressGrantTable extends PanacheEntityBase {

  /** The one row's id. */
  public static final String ID = "egress";

  @Id public String id;

  /** Only ever goes up: see {@code EdgeRouteTables#nextVersion}. */
  @Column(nullable = false)
  public long version;

  /** The sha-256 of the canonical JSON of the table's routes and refusals. */
  @Column(name = "content_hash", nullable = false, columnDefinition = "text")
  public String contentHash;

  /** The whole table as JSON text, the V17 jsonb shape. */
  @Column(nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  public String body;

  @Column(name = "built_at", nullable = false)
  public Instant builtAt;
}
