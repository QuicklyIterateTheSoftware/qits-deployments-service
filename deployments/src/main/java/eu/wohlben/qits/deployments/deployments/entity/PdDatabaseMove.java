package eu.wohlben.qits.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnTransformer;

/**
 * One move of one postgres claim from a (server, database) to another (V20, qits-2175): the state it
 * reached, the per-table counts both sides answered, and when the copy left behind is dropped.
 *
 * <p><b>Durable because a move outlives the call that started it.</b> It stops an application and
 * closes its database; a deployer restart mid-move reads this row to undo or finish what it was doing
 * ({@code DatabaseMoves.settleInterrupted}) — never to forget it. See {@link PdDatabaseMoveState} for
 * the line between the two.
 *
 * <p><b>{@code @Uncaused} by decision.</b> Most moves are written on {@code pd-deploy-worker}, inside a
 * deployment that already records its cause, where no scope stands; an operator's is written on the
 * request thread and names its {@link #requestedBy} instead. The causation column would be null for
 * the one and a duplicate of the deployment row's for the other.
 *
 * <p>The counts are JSON text in a {@code jsonb} column, the V17 shape: {@code {"<schema>.<table>":
 * <rows>}}, written by {@code @ColumnTransformer} so no JSON type mapping is involved.
 */
@Entity
@Table(name = "pd_database_move")
@Uncaused
public class PdDatabaseMove extends PanacheEntityBase {

  /** Who asked: a deployment whose claim differed from its target, or an operator's door. */
  public enum Origin {
    DEPLOYMENT,
    OPERATOR
  }

  @Id public String id;

  /** The moves of one deployment share it: stopped once, switched together or not at all. */
  @Column(name = "batch_id", nullable = false, length = 36)
  public String batchId;

  /** The application whose claim moves — the successor's name on a rename. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  @Column(name = "environment_name", nullable = false, length = 64)
  public String environmentName;

  @Column(name = "resource_name", nullable = false, length = 64)
  public String resourceName;

  @Column(name = "source_server", nullable = false, length = 16)
  public String sourceServer;

  @Column(name = "source_database", nullable = false, length = 64)
  public String sourceDatabase;

  @Column(name = "target_server", nullable = false, length = 16)
  public String targetServer;

  @Column(name = "target_database", nullable = false, length = 64)
  public String targetDatabase;

  /** What the release's spec resolves to — an operator's move only; the next deployment's target. */
  @Column(name = "spec_server", length = 16)
  public String specServer;

  @Column(name = "spec_database", length = 64)
  public String specDatabase;

  /** What the source was renamed to at the switch: {@code <database>_moved_<yyyyMMddHHmm>}. */
  @Column(name = "kept_database", length = 64)
  public String keptDatabase;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, length = 16)
  public Origin origin;

  @Column(name = "requested_by", columnDefinition = "text")
  public String requestedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  public PdDatabaseMoveState state;

  /** The swarm service this move scaled to zero, or null when nothing was running. */
  @Column(name = "service_name", length = 255)
  public String serviceName;

  /** The replica count it had before, which an abort restores. */
  @Column(name = "service_replicas")
  public Integer serviceReplicas;

  @Column(name = "source_counts", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  public String sourceCounts;

  @Column(name = "target_counts", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  public String targetCounts;

  /** {@code pg_database_size} of the source, measured before anything stopped (V21, qits-2243). */
  @Column(name = "source_bytes")
  public Long sourceBytes;

  /** Free bytes of the target server's data-directory filesystem, measured with the source size. */
  @Column(name = "target_free_bytes")
  public Long targetFreeBytes;

  /** Free bytes of the filesystem the move's volume is on, measured with the source size. */
  @Column(name = "volume_free_bytes")
  public Long volumeFreeBytes;

  /** Why a move was refused or aborted, or what it finished with. Never a password. */
  @Column(name = "detail", columnDefinition = "text")
  public String detail;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "started_at")
  public Instant startedAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  /** When the kept copy of the source may be dropped — {@code finished_at} plus the retention. */
  @Column(name = "drop_after")
  public Instant dropAfter;

  /** When the retention sweep dropped it. */
  @Column(name = "dropped_at")
  public Instant droppedAt;
}
