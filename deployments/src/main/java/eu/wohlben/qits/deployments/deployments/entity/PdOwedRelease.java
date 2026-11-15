package eu.wohlben.qits.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One released version this component has <b>taken responsibility for</b> and has not finished with
 * yet — the acceptance ledger's row.
 *
 * <p><b>It is not a request and must not become one.</b> {@link PdDeploymentRequest} says "this
 * version was asked for HERE, and the gate said yes"; it is written per place, on the worker, after
 * the spec read has decided which places exist. This row is written <em>before</em> any of that, by
 * the door, and says only "an event arrived and nobody has discharged it yet". The gap between the
 * two is precisely where a cutover used to lose releases: the bus's own claim ledger commits when
 * the handler returns, and the handler returns as soon as the release is on an in-memory worker
 * queue that {@code @PreDestroy} then throws away. See {@code V10__owed_release.sql} for the
 * measurement.
 *
 * <p><b>{@link #settledAt} null is the whole of "still owed"</b>, exactly as {@code
 * CiReleaseAnnouncement#announcedAt} is in qits-ci. The row stays afterwards as the account of what
 * was accepted and what became of it, which is the only place a reader can see that a release was
 * superseded rather than merely never deployed.
 *
 * <p><b>{@link #acceptedBy} is the re-drive predicate and nothing else.</b> A row this process
 * holds is in its worker queue and must not be touched however long it sits there; a row held by a
 * process id that is not this one is orphaned by definition, because the queue that held it died
 * with its JVM. Null is "held by nobody" — the state the worker leaves a row it could not discharge
 * in, so this process's own sweep retries it without waiting for a restart.
 *
 * <p><b>Every foreign identity here is a plain string with no FK</b>, this schema's rule since V1:
 * the obligation outlives the catalogue row, the tier and the repository it names.
 *
 * <p><b>A {@link CausedRow} whose cause is set EXPLICITLY</b>, for {@link PdDeploymentRequest}'s
 * reason inverted: this row is written on the <em>door's</em> thread, where {@code CausationScope}
 * does hold the frame's id — but it is also written by the sweep, on a plain daemon thread hours
 * later, where nothing holds anything. One row cannot have its cause from an ambient scope half the
 * time, so the cause travels as data on both paths and {@link CausationStamp} finds it already set.
 */
@Entity
@Table(name = "pd_owed_release")
@EntityListeners(CausationStamp.class)
public class PdOwedRelease extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
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

  /**
   * The {@code SoftwareRelease} frame this obligation came from — the natural key, unique, and what
   * makes accepting idempotent: a re-drive re-accepts this row rather than opening a second one.
   */
  @Column(name = "event_id", nullable = false)
  public String eventId;

  /** What this release deploys under, already taken out of the released package by the door. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /** The released CalVer stamp — the git tag, and the image tag. */
  @Column(nullable = false, length = 64)
  public String version;

  /** The package the release announced, verbatim and registry-unqualified. */
  @Column(name = "package_name", length = 255)
  public String packageName;

  /**
   * What the release request declared, carried here because <b>the re-drive reconstructs the whole
   * announcement from this row</b> — in another process, with the event long since claimed and
   * nothing left to ask. A column the ledger lacked would be a field a re-driven release silently
   * deploys without.
   *
   * <p>Nullable, never backfilled, never validated: null is "the release stated none", which is
   * every release cut before qits-projects grew the field. See {@code ReleasePriorities}.
   */
  @Column(length = 32)
  public String priority;

  /**
   * Whether the release asked to be HELD for a person's approval (qits-2268), carried for {@link
   * #priority}'s reason: a re-drive that lost it would deploy a held release unapproved. False is
   * every release that did not say, and every row from before V22.
   */
  @Column(name = "deploy_hold", nullable = false)
  public boolean deployHold;

  /** The release request the release came from ({@code qits-ci-service-rr-12}), or null. */
  @Column(name = "release_request_qualified_id", columnDefinition = "text")
  public String releaseRequestQualifiedId;

  /** The repository's storage key — the coordinate the spec is read by. */
  @Column(name = "repo_id")
  public String repoId;

  /** The project that repository belongs to, when the release carried one. */
  @Column(name = "project_id")
  public String projectId;

  /** The repository's public name, when the door had one. Null on every bus acceptance today. */
  @Column(name = "repo_name")
  public String repoName;

  /** The qits-ci run, recorded on the rows this produces. Null on every bus acceptance. */
  @Column(name = "run_id")
  public String runId;

  /** Which process holds this obligation, or null for "held by nobody". See the class javadoc. */
  @Column(name = "accepted_by")
  public String acceptedBy;

  @Column(name = "accepted_at", nullable = false)
  public Instant acceptedAt;

  /** How many times this obligation has been taken up, the live acceptance included. */
  @Column(nullable = false)
  public int attempts;

  /** When it was discharged, or null while it is still owed. */
  @Column(name = "settled_at")
  public Instant settledAt;

  /** What became of it. Null while it is still owed. */
  @Column(length = 32)
  public String outcome;

  /** Why, in words, where the outcome alone would not say. */
  @Column(columnDefinition = "text")
  public String detail;

  /**
   * The sweep's order, assigned by the database (V10's identity column) and never written here — so
   * it reads null on a freshly persisted instance. {@link PdDeploymentRequest#seq}'s reasoning: the
   * obligations of one catch-up page land in one tick.
   */
  @Column(name = "seq", insertable = false, updatable = false)
  public Long seq;
}
