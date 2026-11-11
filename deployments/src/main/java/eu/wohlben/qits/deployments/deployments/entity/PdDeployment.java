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

/**
 * One attempt to put one commit of one application live. Created {@code QUEUED} by the
 * build-succeeded intake, driven to a terminal state by the deploy worker; the previously {@code
 * ACTIVE} deployment of the same application becomes {@code DECOMMISSIONED} the moment its
 * replacement passes the health gate — never before.
 *
 * <p><b>It names its application and its tier by string, with no FK</b>, even though the topology
 * lives two tables away in the same database. Deployment history outlives the rows that described
 * it: a service removed from the catalogue, or a tier torn down, must not take its history with it,
 * and the rollback pins read off these rows must keep answering whatever the topology says today.
 *
 * <p><b>A {@link CausedRow}, and the one this component exists to trace.</b> A deployment happens
 * <em>because</em> a build went green, so {@link #causationId} names the {@code BuildSuccessful}
 * that caused it and a reader can walk from a container back into the event chain that started it.
 *
 * <p><b>The value is set EXPLICITLY, never left to the stamp</b>, and that is not a preference. The
 * intake hands the whole event to {@code pd-deploy-worker} and returns; an executor hop is exactly
 * where an ambient {@code CausationScope} dies, so the {@code CausationStamp} listener would read
 * null on every row this component writes. The cause therefore crosses the seam as data — a plain
 * {@code UUID} on {@code BuildAnnouncements.announce} — the same way {@link #runId} has always
 * crossed it. The listener stays attached because a value the author set is what it yields to, and
 * because a future writer standing in a scope should be stamped rather than silently rootless.
 *
 * <p>{@link #runId} stays what it is: the pointer into qits-ci's history, resolved against nothing.
 * The two answer different questions — "which pipeline produced the image" and "which event on the
 * bus caused this attempt" — and a manual replay through the HTTP intake can carry either without
 * the other.
 */
@Entity
@Table(name = "pd_deployment")
@EntityListeners(CausationStamp.class)
public class PdDeployment extends PanacheEntityBase implements CausedRow {

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

  /** The service this deployed, by name — the catalogue's own identity for it. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /**
   * The tier it was deployed into.
   *
   * <p><b>Every deployment has one</b>, and since V13 there is no second reading of its absence:
   * the platform plane is deleted, so null is only a row written on an install that had designated
   * no tier. It was once the spelling of "the platform plane", then a column beside it (V8), and now
   * nothing at all.
   */
  @Column(name = "environment_id")
  public String environmentId;

  /**
   * <b>The released coordinate — the CalVer stamp, and the tag the image carries.</b> {@code
   * qits/<application>:<version>} is what the deployment pulls, {@code refs/tags/<version>} is what
   * the spec is read at, and this is the string the startup sweep compares a running image against.
   *
   * <p>Null on every row written before V7 added the column, and only on those: a release names a
   * version or it is refused at the intake. Read it through {@link #imageTag()} rather than
   * directly, which is what makes a historical row keep answering.
   */
  @Column(length = 64)
  public String version;

  /**
   * The commit the released tag RESOLVED to, recorded rather than assumed: the spec read addresses
   * {@code refs/tags/<version>} and the git host answers with the commit in a header, so one
   * request gets both the file and the commit it came from. It is the edge from a container back to
   * a diff.
   *
   * <p><b>Nullable since V7, and the null is a real answer.</b> A repository that carries no
   * deployments.yml gets a 404 from the blob read, and a 404 says nothing about where the tag
   * points; so does a spec read that failed outright. On rows older than V7 it is the opposite —
   * the sha was the whole coordinate, which is why {@link #imageTag()} falls back to it.
   */
  @Column(name = "commit_sha", length = 64)
  public String commitSha;

  /**
   * What tag this deployment's image carries — the single spelling of that question, because three
   * readers have to agree on it: the pull, the startup sweep's adoption check, and the rollback
   * pins qits-artifacts' garbage collector keeps images by.
   *
   * <p>The version, and the commit sha behind it. The fallback is not defensive: a row written
   * before V7 describes a deployment whose image really is tagged with a sha, so answering the
   * version there (null) would make the pins claim nothing and the collector delete an image that
   * is live. V7 deliberately backfills nothing for exactly that reason.
   */
  public String imageTag() {
    return version != null ? version : commitSha;
  }

  /**
   * The qits-ci run whose green build caused this deployment, as the intake received it — the one
   * pointer back into the pipeline that produced the image, and nothing this component ever
   * resolves itself (no FK, the repo_id stance). Null on a sender that omits it, and on anything
   * queued while running an older build; a reader must render that absence rather than invent a
   * link.
   */
  @Column(name = "run_id")
  public String runId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public PdDeploymentStatus status;

  /**
   * The container this deployment started (named after the deployment, not the sha, so re-deploying
   * the same commit never collides). Null until the worker actually ran {@code docker run}, and on
   * every deployment that failed before one existed.
   */
  @Column(name = "container_name")
  public String containerName;

  /** What went wrong (docker's own output, bounded), or null on the happy path. */
  @Column(columnDefinition = "text")
  public String detail;

  /**
   * <b>True on a row a restart interrupted, and on no other</b> — set by {@code
   * DeployService.sweepInFlight} beside its {@code FAILED}, when the previous process left the row
   * {@code QUEUED}, or {@code STARTING} with nothing running under its name (V16).
   *
   * <p>It exists for one reader, {@code OwedReleaseSweep}: a release whose every deployment carries
   * it reached no outcome at all, so the obligation it was owed under is still owed, and re-driving
   * it is a first attempt rather than a duplicate. Every other row — an ordinary {@code FAILED}
   * included — is a decision, and a decided release is not re-driven.
   *
   * <p>A flag beside the status rather than a status of its own, so no read door, pact or client
   * learns a new word: the row did fail to deploy, and that is still what it says. And a column
   * rather than a match on {@link #detail}, which is prose for a person and has been reworded
   * before.
   */
  @Column(nullable = false)
  public boolean interrupted;

  /**
   * <b>The settings this deployment's {@code image_defaults:} retired</b> — the {@code pd_setting}
   * keys {@code DeployService.retireImageDefaults} deleted once the row was {@code ACTIVE},
   * comma-separated in the file's order (V19, qits-2048). Keys only, never a value: a secret row
   * may be among them. Null on every row that retired nothing, which is every deployment whose spec
   * declares no {@code image_defaults:}, every one that found no equal row, and every one that did
   * not converge.
   *
   * <p>It is the one durable trace of the retirement — a delete through the settings door records
   * no event and no audit row, and neither does this — so a person reading the deployment listing
   * can tell why a key the store held yesterday is gone. Written in its own transaction after the
   * rows are deleted; a failure there is a WARN and costs only this record.
   */
  @Column(name = "retired_settings", columnDefinition = "text")
  public String retiredSettings;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  /**
   * The public path prefixes this deployment was performed with, in the spec's own comma-separated
   * spelling ({@code /artifacts,/v2}) — see V3's header for why the row holds a spec value at all
   * when V1's says it does not.
   *
   * <p>Null or empty is an application that declares no public routes, which is most of them. The
   * column that says "this row predates the snapshot" is {@link #upstreamPort}, not this one.
   */
  @Column(name = "routes", columnDefinition = "text")
  public String routes;

  /**
   * The port the routes reach this deployment on, and <b>the sentinel for the whole snapshot</b>:
   * the spec always resolves a port, so a null here means the row was queued by a build that had no
   * routing columns to fill and never means "no port". {@code DeployService} reads exactly that.
   */
  @Column(name = "upstream_port")
  public Integer upstreamPort;

  /**
   * The one DNS label this deployment is also served at ({@code ci}, {@code registry}), or null for
   * an application reached under its path prefix alone — which is most of them. Never an authority:
   * the edge builds {@code <label>.<environment>.<domain>} around it.
   */
  @Column(name = "browser_host", length = 63)
  public String browserHost;

  /**
   * Where the application asks to appear, in the spec's own comma-separated spelling ({@code
   * services.details.CI:2,platform.Deployments:4}). Null or empty is an application that creates no
   * navigation option.
   */
  @Column(name = "navigation_entries", columnDefinition = "text")
  public String navigationEntries;

  /**
   * Where this application's browsable API document lives, under one of its published routes
   * ({@code /ci/q/swagger-ui}). Null is a real answer — a service that documents no HTTP surface —
   * and, like {@link #routes}, it is a spec value the row holds so the startup sweep's adoption can
   * announce a snapshot with no live process that read the file.
   */
  @Column(name = "api_docs", length = 255)
  public String apiDocs;

  /**
   * LEGACY, READ-ONLY: the navigation label the primary route carried before navigation became
   * application-level. Nothing writes it any more. A row that has it and no {@link
   * #navigationEntries} was queued before V4, and the startup sweep announces it as {@code
   * system.<label>} — see {@code DeployService.adoptedSnapshot}.
   */
  @Column(name = "navigation_label", length = 64)
  public String navigationLabel;

  /** LEGACY, READ-ONLY: where that label sat. Read with {@link #navigationLabel} and never alone. */
  @Column(name = "navigation_position")
  public Integer navigationPosition;

  /**
   * The listing tiebreak, assigned by the database (V1's identity column) and never written here —
   * which is why it reads null on a freshly persisted instance.
   *
   * <p>It exists because {@code createdAt} is not unique: two rows recorded in the same tick tied,
   * and the secondary sort was the random-UUID id, so a listing swapped them arbitrarily between
   * calls. This is monotonic, so "newest first" is one answer rather than a coin flip.
   */
  @Column(name = "seq", insertable = false, updatable = false)
  public Long seq;
}
