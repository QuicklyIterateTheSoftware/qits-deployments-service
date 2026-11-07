package eu.wohlben.qits.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * One decommission run and what its purge of the application's published artifacts came to
 * (qits-1941, {@code V17__decommission.sql}). Written once, after the purge call, in its own
 * transaction — succeeded or failed — and never updated: a retry is a second run and a second row.
 *
 * <p><b>A {@link CausedRow}, stamped</b>: it is written on the request thread, where the REST
 * filter's restored scope holds the cause, the {@code PdEnvironment} case.
 *
 * <p>The two list columns are {@code jsonb} holding the JSON text written here; {@link
 * ColumnTransformer} casts the bound string, so no JSON type mapping is involved.
 */
@Entity
@Table(name = "pd_decommission")
@EntityListeners(CausationStamp.class)
public class PdDecommission extends PanacheEntityBase implements CausedRow {

  /** What the purge came to. {@code removed} is set exactly when it is {@code PURGED}. */
  public enum PurgeOutcome {
    PURGED,
    FAILED
  }

  @Id public UUID id;

  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /** The tier of the row the run settled. */
  @Column(name = "environment_id")
  public String environmentId;

  /** Who asked, or null for "an operator". */
  @Column(columnDefinition = "text")
  public String actor;

  @Column(name = "decommissioned_at", nullable = false)
  public Instant decommissionedAt;

  /** The repositories the purge named, as a JSON array of strings. */
  @Column(nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  public String repositories;

  @Enumerated(EnumType.STRING)
  @Column(name = "purge_outcome", nullable = false, length = 16)
  public PurgeOutcome purgeOutcome;

  /** qits-artifacts' {@code removed} list as JSON; null when the purge failed. */
  @Column(columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  public String removed;

  /** Why the purge failed; null when it did not. */
  @Column(name = "failure_detail", columnDefinition = "text")
  public String failureDetail;
}
