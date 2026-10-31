package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog.LinkedService;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.events.DeploymentActive;
import eu.wohlben.qits.deployments.events.DeploymentEndpoint;
import eu.wohlben.qits.deployments.events.DeploymentFailed;
import eu.wohlben.qits.deployments.events.DeploymentQueued;
import eu.wohlben.qits.deployments.events.DeploymentStarted;
import eu.wohlben.qits.deployments.events.NavigationEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The deployment orchestrator: a build-succeeded event → the repository's deployment spec at that
 * commit → the environments that spec addresses → one recorded deployment each, driven pull → run →
 * join → health gate → cutover on a single-threaded daemon worker (the intake returns immediately;
 * deployments across all environments are serialized — parallelism is an explicit follow-up, and
 * serial is what makes "the previous ACTIVE deployment" an uncontended read).
 *
 * <p><b>Registration is derived, and it is a local write.</b> Nothing declares an application over
 * the API. A release carries this component to {@code .config/qits/deployments.yml} in the
 * repository at that tag, and the service row is created or brought up to date from it: the service
 * is linked into the entry tier, and that link is the whole of what says where it runs. A repository
 * with no such file gets the defaults and behaves exactly as it did before the file existed.
 *
 * <p><b>There is ONE plane and one shape of deployment.</b> A release lands in {@code
 * pd_environment.designated}, the designated entry environment, and everything about the deployment
 * names it: the row, the labels, {@code QITS_ENVIRONMENT}, the wire alias {@code <env>-<app>}, the
 * container name and all four events. The platform plane — one instance for the whole platform,
 * carrying no link, reached under its bare name from every tier — is deleted, and with it the two
 * register arms, the conversion between them and everything that asked which plane a row was on.
 *
 * <p><b>This is what the merge bought.</b> Registration and resolution used to be HTTP calls onto
 * qits-serviceregistry: a port, a {@code java.net.http} implementation, a stub server in the suite,
 * a bearer to mint, and a whole recorded-FAILED posture for the case where the peer was down —
 * because a deployer that cannot ask where a build belongs must never guess a topology. All of it
 * is gone. Registration writes rows in the same transaction as everything else here, resolution is
 * a repository query with an index behind it, and there is no outage to have a posture about. The
 * one remote call left is the spec read, and its posture is the one thing about this that changed:
 * a read that never saw the file no longer ends the release, it holds it — see {@link
 * #retrySpecReads()}.
 *
 * <p><b>The worker carries two things that are not events</b>, both enqueued by one ticker here and
 * both running in queue order like everything else: the periodic observation pass ({@link
 * DeploymentObserver}), and the re-read of every release held on a spec the git host would not
 * serve ({@link #retrySpecReads()}). That is the whole reason either may touch deployment rows at
 * all — see {@link #enqueueObservation()}.
 *
 * <p>Each DB transition sits in its own {@link QuarkusTransaction#requiringNew()} bracket so the
 * slow docker work never holds a transaction, and everything the docker calls need is copied into a
 * plain {@link Plan} first — the worker thread has no request context and no open session.
 *
 * <p><b>Three of those brackets are wrapped in {@link DbRetry}</b> — the platform's, from
 * qits-db-core — because this component deploys the postgres its own registry lives in and a cutover
 * of qits-oci-postgresql kills the connections of the very deployment performing it: the catalogue
 * read an event opens with, the post-gate cutover bookkeeping, and {@link #finish}. Connection-class
 * failures only, and {@link #CUTOVER_BUDGET} only — the rest of the brackets are deliberately left
 * alone, and {@link #queue} says why.
 *
 * <p><b>Which of the two spellings each one takes is decided by who owns the transaction.</b> The
 * catalogue read is a {@link DbRetry#call} around a read that brackets itself; the two writes are
 * {@link DbRetry#inNewTx}, which <i>is</i> the {@code requiringNew} — the retry owning the boundary
 * is what lets it retry only attempts that certainly did not commit, and leaves the one undecidable
 * round trip (the commit acknowledgement) reported rather than repeated.
 *
 * <p><b>The cutover is the DRIVER's, and that is the shape this class settled into.</b> What is
 * left here is one path with no branches in it: resolve the target, provision what the repository
 * declared, pull so a missing image is its own outcome, {@link DeploymentDriver#apply} the spec,
 * {@link DeploymentDriver#awaitConverged wait for the verdict}, record it. A predecessor search, a
 * stop-before-start and a hand-rolled rollback used to sit in the middle of that, and each was a
 * statement about how one orchestrator replaces a container rather than about deploying. Nothing
 * about the sequence above is orchestrator-shaped, which is the test that it is in the right place.
 *
 * <p><b>What stayed is the bookkeeping</b>: the row per place, the four announcements, the cutover
 * bracket that decommissions the prior {@code ACTIVE} rows of an (application, tier) and marks this
 * one live, and the reap that follows it — rows first, services after, so a bracket that has to
 * retry for thirty seconds has not already removed the predecessor.
 *
 * <p><b>One outcome is neither success nor failure and is worth knowing about here</b>: a
 * deployment that replaces THIS process comes back {@link DeploymentDriver.ApplyOutcome#HANDED_OFF
 * HANDED_OFF}, and the row is deliberately left {@code STARTING}. Neither instance can arbitrate
 * its own succession, so the outcome is recorded by whichever survives: the next boot's {@link
 * #sweepInFlight() sweep} settles the row from the image the service is running.
 */
@ApplicationScoped
public class DeployService implements ReleaseAnnouncements {

  private static final Logger LOG = Logger.getLogger(DeployService.class);

  /** Every platform repository carries it, and no path segment does. */
  private static final String NAME_PREFIX = "qits-";

  /** The longer prefix a platform-tier repository carries; stripped before {@link #NAME_PREFIX}. */
  private static final String PLATFORM_NAME_PREFIX = "qits-platform-";

  /** What an in-flight row says when nothing is running under its name — see {@link #sweepInFlight()}. */
  private static final String INTERRUPTED = "[interrupted by a qits-platform-deployments restart]";

  /**
   * How long a self-inflicted blip may last before it is a failure worth recording — longer than
   * {@link DbRetry#DEFAULT_DEADLINE}, and stated at every call site here rather than taken from the
   * library, because this worker's outage is one it caused itself: it deploys the postgres its own
   * registry lives in, and the container has to come all the way back before the bracket can
   * succeed. Safe to sleep for, because the worker is single-threaded — nothing is queued behind a
   * lock and the next event simply waits. Do not lift it onto a request thread.
   *
   * <p>Package-private because {@link DeploymentObserver} runs on the same worker and wraps its
   * brackets for the same reason, and one budget spelled twice would drift.
   */
  static final Duration CUTOVER_BUDGET = Duration.ofSeconds(30);

  /**
   * How hard the startup sweep tries to read a spec it should not have needed — see {@link
   * #legacyEndpoints}. Deliberately a constant and not a config key: it applies to rows queued
   * before V3 and there will never be another one, so a knob would outlive everything it tunes.
   */
  private static final int ADOPTION_SPEC_ATTEMPTS = 3;

  private static final Duration ADOPTION_SPEC_RETRY = Duration.ofSeconds(2);

  /**
   * How long a release whose spec read failed retryably keeps being re-read before the attempt is
   * given up on and recorded {@code FAILED}.
   *
   * <p><b>The ordinary exit is supersession, not this.</b> A newer version of the same application
   * replaces the pending entry and the stale rows become history, which is what ends nearly every
   * one of these. The deadline is the belt for the application nothing releases again: a git host
   * that has been refusing for an hour is not having a moment, and a row that says {@code
   * SPEC_UNREADABLE} forever would be the stranding this whole arm exists to remove, wearing a
   * different word.
   *
   * <p>A constant rather than a config key, for {@link #ADOPTION_SPEC_ATTEMPTS}' reason: the number
   * that matters operationally is the retry CADENCE, and that is already {@code
   * observe-interval-seconds}.
   */
  private static final Duration SPEC_RETRY_DEADLINE = Duration.ofMinutes(60);

  /**
   * How many SETTLED requests a project's listing carries — see {@link
   * #projectDeploymentRequests(String)}, which caps nothing else.
   *
   * <p>A constant rather than a config key, and rather than a client-supplied {@code ?limit=}: it is
   * the shape of one screen and not an operational tuning, and a knob would invite a caller to ask
   * for the year the cap exists to refuse.
   */
  private static final int PROJECT_COMPLETED_LIMIT = 10;

  @Inject PdDeploymentRepository deployments;
  @Inject PdDeploymentRequestRepository requests;
  @Inject DeploymentDriver driver;
  @Inject SpecSource specs;

  @Inject ServiceCatalog catalog;
  @Inject EnvironmentService environments;
  @Inject ResourceProvisioning resourceProvisioning;
  @Inject DeploymentObserver observer;

  /** The settings store a rename cutover copies onto its successor and retires for its predecessor. */
  @Inject Settings settings;

  /**
   * The acceptance ledger the durable door writes through — see {@link #announceDurably}. Nothing
   * on the manual path touches it.
   */
  @Inject ReleaseAcceptance acceptance;

  /**
   * Where this component's own events leave it ({@link DeployAnnouncer}). An {@code Instance}
   * because zero implementations is a supported configuration: a build without the bus deploys
   * exactly as before and says nothing to anybody.
   */
  @Inject Instance<DeployAnnouncer> announcers;

  /** The OCI registry host images are pulled and pushed through — derived, never configured. */
  @Inject RegistryHost registryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String imageRepository;

  /**
   * The last resort at deploy time, for a service row that carries no path. Registration writes
   * {@link #conventionHealthPath} now, so this only reaches rows nothing has registered since.
   */
  @ConfigProperty(name = "qits.deployments.default-health-path")
  String defaultHealthPath;

  @ConfigProperty(name = "qits.deployments.health-timeout-seconds")
  long healthTimeoutSeconds;

  /** How often the observation pass is enqueued; {@code 0} switches the observer off entirely. */
  @ConfigProperty(name = "qits.deployments.observe-interval-seconds")
  long observeIntervalSeconds;

  /**
   * The network every fresh container additionally joins while the platform still holds direct
   * cross-application URLs. Emptying it is the enforcement flip: from then on an application can
   * only be reached through the gateway route or a hub join, and a URL nobody migrated fails loudly
   * instead of resolving on a flat network.
   *
   * <p>{@code Optional} because SmallRye reads an empty value as ABSENT, not as an empty string —
   * so the flip's own spelling ({@code QITS_PLATFORM_DEPLOYMENTS_LEGACY_NETWORK=}) would fail this
   * bean's injection if the field were a plain String.
   */
  @ConfigProperty(name = "qits.deployments.legacy-network")
  Optional<String> legacyNetwork;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "pd-deploy-worker");
            t.setDaemon(true);
            return t;
          });

  /**
   * At most one observation pass is pending behind the deploy queue at a time. A tick that fires
   * while one is already waiting collapses into it: an observation is a statement about NOW, so
   * stacking ten of them behind a long deploy queue would only re-answer a question the first one is
   * about to answer.
   */
  private final AtomicBoolean observationPending = new AtomicBoolean();

  /**
   * The releases whose spec could not be READ for a reason that may pass, by the name the
   * announcement carried — one entry per application, because that is what supersession means here.
   *
   * <p><b>In memory, on purpose, and it is the {@code DeploymentObserver#strikes} trade.</b> What
   * is remembered is an intention to try again, not a fact about the world: the facts are on the
   * rows, which say {@code SPEC_UNREADABLE} and survive anything. A restart loses the intention and
   * costs the release its automatic recovery — the same manual replay it needed before this
   * existed, so nothing is worse than it was — while persisting it would mean a second table and a
   * background job that outlives every process that queued it.
   *
   * <p>Concurrent because the intake thread writes it and the worker reads it, not because two
   * retries ever overlap: they cannot, the worker is single-threaded.
   */
  private final Map<String, SpecRetry> specRetries = new ConcurrentHashMap<>();

  /**
   * The rename retirements that stopped at a failed step, keyed by tier and predecessor — what
   * {@link #retryRetirements()} finishes on the observation's cadence. Empty in the steady state, so
   * a pass with nothing owed costs one {@code isEmpty}.
   *
   * <p><b>In memory, the {@link #specRetries} trade.</b> What is remembered is an intention; the
   * facts are on the rows. A restart loses the intention and the successor's next deployment picks
   * it back up — {@link #retireAfterCutover} reads what is still owed from the tables, not from
   * here.
   */
  private final Map<String, Retirement> owedRetirements = new ConcurrentHashMap<>();

  /**
   * The observation tick — a bare daemon thread, the worker's own shape, rather than the
   * quarkus-scheduler extension. It has one job (submit a runnable every n seconds), it must not run
   * the pass itself, and a scheduler extension would add a managed thread pool and a second
   * concurrency model to a component whose whole ordering story is "one worker, in queue order".
   */
  private volatile Thread observerTicker;

  @PreDestroy
  void shutdown() {
    Thread ticker = observerTicker;
    if (ticker != null) {
      ticker.interrupt();
    }
    worker.shutdownNow();
  }

  /**
   * A deployment left {@code QUEUED} or {@code STARTING} by a crash can never make progress — the
   * worker queue does not survive the JVM — so it would show as forever-deploying. Settle those
   * once at startup, from what the runtime says: see {@link #sweepInFlight()}. The
   * containers are deliberately NOT reaped: a deployed application outlives its deployer, and
   * whatever was {@code ACTIVE} before the restart is still serving.
   *
   * <p><b>{@code SPEC_UNREADABLE} is the same stranding one word further on</b>, and it needed its
   * own pass: those rows are held by {@link #specRetries}, which is memory, so a process that dies
   * mid-hold leaves them in flight with nobody left to re-read them. {@link #sweepHeldSpecReads()}
   * is that pass, and it runs <b>after</b> the in-flight one for the ordering reason {@link
   * OwedReleaseSweep} states one step further out: each of these reads a set of rows and settles
   * what a previous process left, so they run to completion before anything queues a fresh row.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      sweepInFlight();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted deployments at startup");
    }
    try {
      sweepHeldSpecReads();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not settle the spec retries a previous process was holding");
    }
    startObserving();
  }

  /**
   * Start the periodic tick that keeps deployment rows honest ({@link DeploymentObserver}).
   *
   * <p>Nothing here in test mode, for the same reason the sweep is skipped: {@code onStart} returns
   * before this on a {@code LaunchMode.TEST} boot, so no {@code @QuarkusTest} has a ticker running
   * behind it and the suite's observation tests drive {@link #enqueueObservation()} themselves. That
   * is also why the interval keeps its shipped default in the suite — a test-resource override would
   * be re-declaring an app-level setting to disable something that never starts.
   */
  private void startObserving() {
    if (observeIntervalSeconds <= 0) {
      LOG.info(
          "Deployment observation is off (qits.deployments.observe-interval-seconds=0):"
              + " a row's status will be whatever the deployment that wrote it said, and a release"
              + " held on an unreadable spec is never re-read");
      return;
    }
    Thread ticker =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(Duration.ofSeconds(observeIntervalSeconds).toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                enqueueObservation();
              }
            },
            "pd-observation-ticker");
    ticker.setDaemon(true);
    observerTicker = ticker;
    ticker.start();
    LOG.infof("Observing deployment rows every %ds on the deploy worker", observeIntervalSeconds);
  }

  /**
   * Queue the periodic work <b>on the deploy worker</b> — {@link #retrySpecReads()} and then one
   * {@link DeploymentObserver#observeOnce() observation pass}. Package-private so the suite drives
   * a tick without waiting for one.
   *
   * <p><b>Two passes on one tick rather than a second ticker</b>, because they answer the same
   * question at the same cadence: what does the world say now that a row was written minutes ago.
   * One re-asks the runtime about containers, the other re-asks the git host about a file neither
   * of them could read.
   *
   * <p>The placement is the concurrency contract, not a convenience. Serial execution is what makes
   * "the previous ACTIVE deployment" an uncontended read during a cutover, and an observer thread
   * reading and writing those same rows while a cutover runs would take that invariant away — it
   * would see the half-written state between a cutover's own brackets and could demote a row a
   * deployment is in the middle of promoting. On the worker there is no middle: the pass runs between
   * events, never inside one.
   */
  void enqueueObservation() {
    if (!observationPending.compareAndSet(false, true)) {
      LOG.debugf("An observation pass is already queued; this tick collapses into it");
      return;
    }
    worker.submit(
        () -> {
          // Cleared as the pass BEGINS, not when it ends: a tick arriving during a long pass may
          // queue the next one, so the queue holds at most one pending pass plus the running one.
          observationPending.set(false);
          // The held releases first, and the order is deliberate: a recovered spec read queues a
          // real deployment, and observing the rows AFTER it has run means the pass sees the world
          // this tick actually left behind rather than the one it found.
          try {
            retrySpecReads();
          } catch (RuntimeException e) {
            LOG.warnf(e, "The held spec-read pass failed; the next tick tries again");
          }
          try {
            observer.observeOnce();
          } catch (RuntimeException e) {
            LOG.warnf(e, "The deployment observation pass failed; the next tick tries again");
          }
          // Last: a rename retirement a failed step left owed. Nothing to do on nearly every tick.
          try {
            retryRetirements();
          } catch (RuntimeException e) {
            LOG.warnf(e, "The owed rename retirements failed to run; the next tick tries again");
          }
        });
  }

  /**
   * Queue an <b>operator's</b> action on the deploy worker — a scale, a bounce — behind whatever the
   * queue already holds. Package-private: {@link ApplicationScaling} is the only caller, and it is
   * in this package for exactly that reason.
   *
   * <p><b>The placement is a correctness requirement, not tidiness.</b> A scale and a bounce are
   * both {@code docker service update} on a service a deployment may be cutting over this second,
   * and two concurrent updates of one service is the least of it: swarm's {@code UpdateStatus} holds
   * the most recent update, so an operator's restart issued from a request thread mid-cutover would
   * be the status {@link DeploymentDriver#awaitConverged} reads as that deployment's verdict. On the
   * worker there is no mid-cutover — the action runs between events, never inside one, which is the
   * same guarantee {@link #enqueueObservation()} takes.
   *
   * <p>Unlike an observation, two of these never collapse: they are two things a person asked for.
   *
   * @param description what a failure names in the log — the action, not the outcome
   */
  void enqueueOperation(String description, Runnable action) {
    worker.submit(
        () -> {
          try {
            action.run();
          } catch (RuntimeException e) {
            LOG.warnf(e, "%s failed", description);
          }
        });
  }

  /**
   * Settle every row a previous process left in flight, from what the runtime is running now.
   * Package-private so the suite drives the sweep without a real StartupEvent.
   *
   * <p><b>This is the self-update bookkeeping, and it is the only part of it left.</b> An instance
   * deploying itself hands the succession to the orchestrator and dies with the row {@code
   * STARTING}; whichever instance boots next has to say what became of it. The question is asked of
   * the deployment rather than of the deployer — {@link DeploymentDriver#runningImage} on the name
   * the row itself carries — which is why it is one arm for both orchestrators and for every
   * application, not a special case for this one.
   *
   * <table>
   *   <caption>What settles a row, and on what evidence</caption>
   *   <tr><th>row</th><th>the runtime says</th><th>verdict</th></tr>
   *   <tr><td>{@code QUEUED}, or {@code STARTING} with no name</td><td>nothing was asked</td>
   *       <td>{@code FAILED}: nothing ever ran, and the queue did not survive</td></tr>
   *   <tr><td>{@code STARTING}</td><td>no such service</td>
   *       <td>{@code FAILED}: it was interrupted, or the orchestrator took it away</td></tr>
   *   <tr><td>{@code STARTING}</td><td>an image carrying this row's sha</td>
   *       <td>{@code ACTIVE}, prior actives of the place decommissioned</td></tr>
   *   <tr><td>{@code STARTING}</td><td>an image carrying another sha</td>
   *       <td>{@code SUPERSEDED}: a rollback, or a later deployment, took the place</td></tr>
   * </table>
   *
   * <p><b>The last two rows used to be one word, and telling them apart is the point.</b> An
   * interrupted row with no evidence leaves nobody known to be serving, which is {@code FAILED};
   * a row whose place a newer sha is already serving is {@code SUPERSEDED}, and nothing is owed
   * about it. The sweep is the only writer of that word.
   *
   * <p><b>An adopted row is not a claim that the gate passed</b>, and it does not need to be: on
   * what carries the row's image is what is serving under the row's name, and a
   * container that is in fact dying is demoted by {@link DeploymentObserver} on the next two
   * passes. The prior actives are matched with an explicit null test for the platform plane
   * ({@code environment_id = ?} would silently match nothing, and a self-updated instance would
   * come back having failed its own deployment).
   *
   * <p><b>An adoption ANNOUNCES, and it is the only correction here that does.</b> {@code
   * DeploymentActive} carries the routing snapshot the platform edge projects its route table from,
   * so an adopted deployment that said nothing is an application the edge never learns a route for —
   * and the one application that reaches {@code ACTIVE} through this sweep every single time is
   * <b>this one</b>, whose self-update is handed to the orchestrator and never announces from {@link
   * #execute}. That cost it its own route and its own navigation entry: the deployer's UI was
   * unreachable and absent from the menu on every platform, while every other application announced
   * normally. {@link DeploymentObserver}'s later corrections still announce nothing — a demotion and
   * a recovery restate an outcome hours later and a consumer would have to know the second statement
   * supersedes the first — but an adoption is not a correction: it is the FIRST statement anybody
   * makes about a deployment that went live, made by the instance that lived to make it.
   *
   * <p><b>It announces from the ROW and reaches no peer</b> — V3's routing columns, written at queue
   * time — because this runs at boot and the boot in question is the one right after a cutover. See
   * {@link #announceAdopted}; and a snapshot that cannot be established announces <b>nothing</b>
   * rather than an empty one, because consumers replace rather than merge and an empty snapshot
   * deletes the routes it could not describe.
   *
   * <p>The shape is the observation pass's: read the rows in one transaction, ask the runtime
   * outside every one of them — a driver call is a child process, and no bracket of this
   * component's own may span one — then write each row in a bracket of its own. The announcement
   * follows that write, the way every other announcement here follows the transaction that made it
   * true.
   */
  void sweepInFlight() {
    List<InFlight> rows = QuarkusTransaction.requiringNew().call(this::inFlight);
    int adopted = 0;
    int settled = 0;
    for (InFlight row : rows) {
      Verdict verdict = verdictOn(row);
      Instant recordedAt = QuarkusTransaction.requiringNew().call(() -> record(row, verdict));
      if (verdict.adopt()) {
        adopted++;
        LOG.infof(
            "Adopted deployment %s at startup: %s is running its image",
            row.deploymentId(), row.containerName());
        if (recordedAt != null) {
          announceAdopted(row, recordedAt);
        }
      } else {
        settled++;
      }
    }
    if (settled > 0) {
      LOG.infof(
          "Settled %d deployment(s) left in flight by a previous shutdown — SUPERSEDED where a"
              + " newer sha is serving the place, FAILED where nothing is",
          settled);
    }
    if (adopted > 0) {
      LOG.infof("Adopted %d deployment(s) the previous instance could not record", adopted);
    }
  }

  /**
   * One in-flight row as plain values — nothing carries an entity across a driver call.
   *
   * <p>Everything from {@code runId} down is carried for the adoption's announcement alone: it is a
   * {@code DeploymentActive} like any other and owes the same pointer back to the build, the same
   * trace edge and the same routing snapshot. They are read here rather than re-read later for the
   * reason everything else here is — the announcement happens outside every transaction this sweep
   * opens, and {@link #announceAdopted} must not need one.
   *
   * <p>{@code upstreamPort} is the snapshot's sentinel: null is a row queued before V3 added the
   * columns, not a row without a port. See {@link #adoptedSnapshot}.
   *
   * <p><b>It carried the row's PLANE and does not any more.</b> The alias this sweep rebuilds for an
   * adopted deployment's routes used to be bare on the platform plane and qualified in a tier; it is
   * qualified always, so the tier the row already carries is the whole of what the rebuild needs.
   *
   * <p>The last two are V3's retired pair, carried for one reason: a row queued before V4 has a
   * label and no entries, and the sweep announces it as {@code system.<label>} rather than as
   * nothing. Nothing writes them.
   */
  private record InFlight(
      String deploymentId,
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      String imageTag,
      String commitSha,
      String runId,
      UUID causationId,
      String routes,
      Integer upstreamPort,
      String browserHost,
      String navigationEntries,
      String apiDocs,
      String navigationLabel,
      Integer navigationPosition) {}

  /**
   * What settles this row: the status to write, and the detail that argues it. It carries the word
   * rather than a boolean because the sweep has three answers now — adopt, superseded, failed — and
   * a boolean could only spell two of them.
   */
  private record Verdict(PdDeploymentStatus status, String detail) {

    /** Adoption is the one verdict with bookkeeping of its own: the prior actives of the place. */
    boolean adopt() {
      return status == PdDeploymentStatus.ACTIVE;
    }
  }

  private List<InFlight> inFlight() {
    List<PdDeployment> rows = new ArrayList<>(deployments.listByStatus(PdDeploymentStatus.QUEUED));
    rows.addAll(deployments.listByStatus(PdDeploymentStatus.STARTING));
    return rows.stream()
        .map(
            row ->
                new InFlight(
                    row.id,
                    row.applicationName,
                    row.environmentId,
                    row.status,
                    row.containerName,
                    row.imageTag(),
                    row.commitSha,
                    row.runId,
                    row.causationId,
                    row.routes,
                    row.upstreamPort,
                    row.browserHost,
                    row.navigationEntries,
                    row.apiDocs,
                    row.navigationLabel,
                    row.navigationPosition))
        .toList();
  }

  /** The decision table above, asked of the runtime. */
  private Verdict verdictOn(InFlight row) {
    if (row.status() != PdDeploymentStatus.STARTING || row.containerName() == null) {
      return new Verdict(PdDeploymentStatus.FAILED, INTERRUPTED);
    }
    DeploymentDriver.RunningImage running = driver.runningImage(row.containerName()).orElse(null);
    if (running == null) {
      return new Verdict(PdDeploymentStatus.FAILED, INTERRUPTED);
    }
    // The VERSION, through the row's own coalesce: a row written before V7 is tagged with a sha
    // and still has to adopt. Getting this wrong makes a self-updated instance come back SUPERSEDED
    // by its own successor.
    if (ImageRefs.carries(running.imageRef(), row.imageTag())) {
      return new Verdict(
          PdDeploymentStatus.ACTIVE,
          "[adopted at startup: " + row.containerName() + " is running this deployment]");
    }
    return new Verdict(
        PdDeploymentStatus.SUPERSEDED,
        "[superseded: "
            + row.containerName()
            + " runs "
            + running.imageRef()
            + ", which is another deployment"
            + (running.detail() == null || running.detail().isBlank()
                ? ""
                : "; " + firstLine(running.detail()))
            + "]");
  }

  /**
   * @return the {@code finished_at} this wrote, which an adoption announces as its {@code
   *     occurredAt}, or null when there was no row left to write — the same "no row, no event" rule
   *     {@link #finish} states.
   */
  private Instant record(InFlight row, Verdict verdict) {
    PdDeployment deployment = deployments.findById(row.deploymentId());
    if (deployment == null) {
      return null; // the environment was torn down between the read and this write
    }
    if (verdict.adopt()) {
      for (PdDeployment previous :
          deployments.listActiveByApplication(row.applicationName(), row.environmentId())) {
        previous.status = PdDeploymentStatus.DECOMMISSIONED;
        previous.finishedAt = Instant.now();
      }
    }
    deployment.status = verdict.status();
    deployment.detail = verdict.detail();
    deployment.finishedAt = Instant.now();
    return deployment.finishedAt;
  }

  /**
   * The adopted deployment's {@code DeploymentActive}, made by the instance that survived the
   * succession — see {@link #sweepInFlight()} for why this one correction speaks.
   *
   * <p><b>The routing snapshot comes off the ROW</b> (V3's columns, written at queue time from the
   * same {@code Target} the deployment was performed with), so this reaches no peer at all. That is
   * the whole point of storing it: this runs at BOOT, and the boot in question is the one right
   * after a cutover — asking the git host to re-read a file here would make the deployer's own route
   * depend on another service answering during the seconds it is coming up, with nothing to retry,
   * because the row settles {@code ACTIVE} and no later sweep asks again. The only local read left
   * is the tier's name, which is this component's own database.
   *
   * <p><b>What cannot be rebuilt announces NOTHING</b>, and that asymmetry is the whole care in this
   * method. A consumer replaces its snapshot rather than merging, so an event carrying an empty
   * endpoint list DELETES an application's routes — which is a strictly worse answer than silence
   * when the truth is "the tier is gone" or "this row never recorded its routing". A repository that
   * genuinely declares no routes is a different thing and does announce its empty snapshot.
   *
   * <p><b>An adoption is not a claim that the health gate passed</b> — {@link #sweepInFlight()} says
   * so about the row, and the event inherits it. It costs nothing here: an endpoint's host is the
   * wire ALIAS, which is the service's own address and is the same whichever task is answering it,
   * so a route announced for a deployment that turns out to be sick points where a route for it
   * would have pointed anyway. {@link DeploymentObserver} demotes the row two passes later, and the
   * routes are the next deployment's to replace.
   */
  private void announceAdopted(InFlight row, Instant finishedAt) {
    String environmentName;
    try {
      environmentName =
          row.environmentId() == null ? null : environments.require(row.environmentId()).name;
    } catch (RuntimeException gone) {
      LOG.warnf(
          "Adopted deployment %s names environment %s, which is no longer there; its routes are"
              + " announced by nobody",
          row.deploymentId(), row.environmentId());
      return;
    }
    Snapshot snapshot = adoptedSnapshot(row, environmentName);
    if (snapshot == null) {
      return; // said why at the point it decided
    }
    announce(
        row.deploymentId(),
        announcer ->
            announcer.onActive(
                new DeploymentActive(
                    row.deploymentId(),
                    row.applicationName(),
                    row.environmentId(),
                    environmentName,
                    row.imageTag(),
                    row.commitSha(),
                    row.runId(),
                    row.containerName(),
                    finishedAt,
                    snapshot.browserHost(),
                    snapshot.apiDocsPath(),
                    snapshot.navigation(),
                    snapshot.endpoints()),
                row.causationId()));
  }

  /**
   * Everything {@code DeploymentActive} says about where an application is reached: the routes, the
   * host they are also served at, and where it asks to appear. One value because the three are one
   * statement — a consumer replaces them together or not at all.
   */
  private record Snapshot(
      List<DeploymentEndpoint> endpoints,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation) {}

  /**
   * {@link Plan#endpoints()} for a row this process did not deploy: the same three rules — the host
   * is the wire alias, one endpoint per declared route, navigation on the primary one — over the
   * snapshot the row carries instead of over a live {@code Target}.
   *
   * <p><b>A null return is "announce nothing"</b>, and it has exactly one cause: a row queued before
   * V3 added the columns, which cannot say what it routed. See {@link #legacySnapshot} for what is
   * done about those and why it is bounded. An empty LIST is the other answer entirely — an
   * application that declares no public routes — and it is announced.
   *
   * <p><b>A row queued between V3 and V4 carries a navigation LABEL and no entries</b>, and it is
   * announced as {@code system.<label>} with no host — the placement a flat menu's option always
   * had, and the statement that row was written to make. Nothing is derived for it: a host it never
   * asked for would put an application on a vhost the release it was queued by never promised.
   */
  private Snapshot adoptedSnapshot(InFlight row, String environmentName) {
    if (row.upstreamPort() == null) {
      return legacySnapshot(row, environmentName);
    }
    List<NavigationEntry> navigation = DeploymentSpecParser.parseEntries(row.navigationEntries());
    if (navigation.isEmpty() && row.navigationLabel() != null) {
      navigation =
          List.of(new NavigationEntry("system", row.navigationLabel(), row.navigationPosition()));
    }
    return new Snapshot(
        resolveEndpoints(
            splitRoutes(row.routes()),
            PdNetworks.alias(environmentName, row.applicationName()),
            row.upstreamPort()),
        row.browserHost(),
        row.apiDocs(),
        navigation);
  }

  /**
   * The one deployment that has no snapshot to read is the one that SHIPS the snapshot: the old
   * build queued its row, this build's successor swept it, and V3's columns were null the whole
   * time. So this reads the spec at the row's sha after all — the transitional path, and it exists
   * for exactly one deployment per platform, ever.
   *
   * <p><b>It is patient, because it is the one moment the network can cost the deployer its own
   * route.</b> The read is this component's single outbound call and it runs at boot, seconds after
   * a cutover; the git host is up on any ordinary platform and briefly unreachable is exactly the
   * case worth surviving, so it is attempted {@link #ADOPTION_SPEC_ATTEMPTS} times a couple of
   * seconds apart. The sleeps are safe here and nowhere else in this class: the sweep runs before
   * the worker is doing anything and nothing is queued behind it.
   *
   * <p>Exhausted, it announces nothing rather than an empty snapshot — deleting routes it could not
   * describe would be worse than leaving them — and the recovery is the application's next
   * deployment, which will carry the columns and never come back here.
   *
   * <p><b>It reads the spec and nothing else.</b> The sweep runs at BOOT, seconds after a cutover,
   * and its whole design is to reach no peer it does not have to. This one read is already the
   * exception, and it is bounded for that reason.
   */
  private Snapshot legacySnapshot(InFlight row, String environmentName) {
    for (int attempt = 1; attempt <= ADOPTION_SPEC_ATTEMPTS; attempt++) {
      try {
        // Id-addressed on purpose: a row carries the application NAME and no storage id, and this
        // path only ever runs for deployments queued before V3's routing columns existed — which
        // is to say before the identity rollback, when the id and the name were the same string.
        // A SHA rev, not a tag: this path runs only for rows queued before V3's routing columns
        // existed, which is to say long before a version was the coordinate.
        DeploymentSpec spec =
            specs.read(RepositoryRef.ofId(row.applicationName()), row.commitSha()).spec();
        LOG.infof(
            "Adopted deployment %s predates the routing columns; its snapshot was read from the"
                + " spec of %s@%s",
            row.deploymentId(), row.applicationName(), row.commitSha());
        return new Snapshot(
            resolveEndpoints(
                spec.routes(),
                PdNetworks.alias(environmentName, row.applicationName()),
                spec.upstreamPort()),
            browserHost(row.applicationName(), spec),
            spec.apiDocs(),
            spec.navigationEntries());
      } catch (RuntimeException unreadable) {
        LOG.warnf(
            "Could not read the deployment spec of %s@%s while adopting %s (attempt %d of %d): %s",
            row.applicationName(),
            row.commitSha(),
            row.deploymentId(),
            attempt,
            ADOPTION_SPEC_ATTEMPTS,
            unreadable.getMessage());
        if (attempt < ADOPTION_SPEC_ATTEMPTS && !pause(ADOPTION_SPEC_RETRY)) {
          break;
        }
      }
    }
    LOG.warnf(
        "The routing of adopted deployment %s could not be established, so it is announced by"
            + " nobody rather than as an empty snapshot; %s keeps whatever routes it has until its"
            + " next deployment",
        row.deploymentId(), row.applicationName());
    return null;
  }

  /**
   * Settle every {@code SPEC_UNREADABLE} row no in-memory hold is carrying — which at boot is all of
   * them. The in-flight sweep's twin, one word further on, and it exists for the same reason: a row
   * whose only means of progress died with a process is a deployment that will never move again, and
   * {@code SPEC_UNREADABLE} is on {@link RequestLifecycle}'s in-flight list, so the client draws it
   * as a pending deployment forever.
   *
   * <p><b>Measured, not feared.</b> On 2026-09-07 two rows — {@code qits-ci@2026.907.184918} and
   * {@code qits-deployments@2026.907.192519} — sat {@code SPEC_UNREADABLE} for hours across several
   * deployer restarts while both versions were serving, deployed for real by re-fired events. The
   * hold that would have settled them ({@link #retrySpecReads()}) belongs to a JVM that was gone.
   *
   * <p><b>Two arms, and the second one is the decision worth arguing.</b>
   *
   * <ul>
   *   <li><b>Something already serves the place → {@code SUPERSEDED}.</b> An {@code ACTIVE} row of
   *       the same (application, tier) that arrived after this one, or that is serving the same or a
   *       newer version, answers the question this row was waiting on: whatever the file said, the
   *       place is live. That is the sweep's own word for "this in-flight row's place was taken",
   *       and this is its second writer.
   *   <li><b>Nothing serves it → {@code FAILED}</b>, terminal, naming the death. <b>Re-entering the
   *       hold was the alternative and it cannot be built honestly.</b> A {@link SpecRetry} needs a
   *       {@link RepositoryRef}, a package name and a {@link Door}; {@code pd_deployment} records an
   *       application NAME and no repository identity at all, by V1's own header, so there is
   *       nothing on the row to rebuild one from — and an application name is not a repository name.
   *       <b>And the recovery already exists one layer out</b>: a release held on an unreadable spec
   *       is deliberately left owed in {@code pd_owed_release}, and {@link OwedReleaseSweep}
   *       re-drives it at boot through the ordinary door, with the identifiers, the tip collapse and
   *       the acceptance ledger all intact. A hold reconstructed here would be a second announcement
   *       path for the same release racing that one on the same worker — the two-doors hazard this
   *       component refuses everywhere else. The {@link Door#MANUAL} door writes no obligation on
   *       purpose, so for a hand-announced version {@code FAILED} is the whole answer: an operator
   *       named a version, the attempt is over, and re-announcing it would re-answer a question a
   *       person already answered.
   * </ul>
   *
   * <p><b>Neither arm announces</b>, and that is {@link #finishHeld}'s rule rather than an omission:
   * the row already announced {@code DeploymentFailed} when {@link #recordUnreadableSpec} wrote it
   * through {@link #finish}, so a second event would tell every consumer the deployment failed
   * twice. It is also what the sweep's other {@code SUPERSEDED} writer does — {@link #record} states
   * that word and says nothing — and what {@link DeploymentObserver}'s corrections do.
   *
   * <p>The shape is {@link #sweepInFlight()}'s: read and decide in one transaction, write each row
   * in a bracket of its own. There is no driver call at all here — every question this asks is this
   * component's own database — which is why the decision may sit inside the read.
   *
   * <p>Package-private so the suite drives a pass without a real StartupEvent, exactly as {@link
   * #sweepInFlight()} and {@link #retrySpecReads()} are driven.
   */
  void sweepHeldSpecReads() {
    List<Settlement> settlements = QuarkusTransaction.requiringNew().call(this::heldSpecReads);
    for (Settlement settlement : settlements) {
      finishHeld(settlement.deploymentId(), settlement.status(), settlement.detail());
    }
    if (!settlements.isEmpty()) {
      LOG.infof(
          "Settled %d deployment(s) whose spec retry a previous process was holding when it died —"
              + " SUPERSEDED where the application is already serving, FAILED where it is not",
          settlements.size());
    }
  }

  /** One held row and the word that ends it, decided before anything is written. */
  private record Settlement(String deploymentId, PdDeploymentStatus status, String detail) {}

  /**
   * The decision above, asked of the rows alone.
   *
   * <p><b>A row this process is holding is skipped</b>, which at boot is none of them and is the
   * contract rather than a precaution: the pass settles rows <i>nobody</i> is carrying, so driving
   * it on a live instance must not settle a release {@link #retrySpecReads()} is about to re-read.
   * The map is the deploy worker's and so is this pass, so the read is uncontended.
   */
  private List<Settlement> heldSpecReads() {
    Set<String> carried = new HashSet<>();
    for (SpecRetry held : specRetries.values()) {
      carried.addAll(held.rows());
    }
    List<Settlement> settlements = new ArrayList<>();
    for (PdDeployment row : deployments.listByStatus(PdDeploymentStatus.SPEC_UNREADABLE)) {
      if (carried.contains(row.id)) {
        continue;
      }
      PdDeployment serving = servingSuccessor(row);
      settlements.add(
          serving == null
              ? new Settlement(
                  row.id,
                  PdDeploymentStatus.FAILED,
                  "[the process holding this spec retry died; nothing serves "
                      + row.applicationName
                      + " here, so the attempt is over — settled by the boot sweep]\n[an accepted"
                      + " release is re-driven from its own obligation; a version announced by hand"
                      + " is announced again by hand]")
              : new Settlement(
                  row.id,
                  PdDeploymentStatus.SUPERSEDED,
                  "[the process holding this spec retry died; "
                      + row.applicationName
                      + " already serves "
                      + serving.imageTag()
                      + " — settled by the boot sweep]"));
    }
    return settlements;
  }

  /**
   * The {@code ACTIVE} deployment that answers a held row's question, or null when the place is not
   * being served by anything this row can be superseded by.
   *
   * <p>The place is the sweep's own spelling — {@link
   * PdDeploymentRepository#listActiveByApplication} on the row's application and tier, the same call
   * {@link #record} decommissions a predecessor with — so the null tier of a pre-V8 row is tested as
   * a value rather than compared.
   *
   * <p><b>Two ways to answer, and each is deliberately narrow.</b> A later {@code seq} is the
   * decidable one: V1's identity column is what every listing here means by "newer", so a row
   * created after this one and still {@code ACTIVE} is a deployment that happened afterwards and
   * won. A same-or-newer {@code version} is the other, and it is asked <b>only when both rows carry
   * one</b> — that is what covers a redeploy of the very version this row was held on, which is
   * exactly the measured case, without letting a pre-V7 row tagged with a sha answer a question
   * about a CalVer stamp. {@link PdDeployment#imageTag()} stays the single reader of the tag.
   */
  private PdDeployment servingSuccessor(PdDeployment held) {
    for (PdDeployment active :
        deployments.listActiveByApplication(held.applicationName, held.environmentId)) {
      if (held.seq != null && active.seq != null && active.seq > held.seq) {
        return active;
      }
      if (held.version != null
          && active.version != null
          && Versions.compare(active.version, held.version) >= 0) {
        return active;
      }
    }
    return null;
  }

  /** One endpoint per route, the host resolved once. */
  private static List<DeploymentEndpoint> resolveEndpoints(
      List<String> routes, String upstreamHost, int upstreamPort) {
    List<DeploymentEndpoint> endpoints = new ArrayList<>();
    for (String route : routes) {
      endpoints.add(new DeploymentEndpoint(route, upstreamHost, upstreamPort));
    }
    return List.copyOf(endpoints);
  }

  /**
   * The DNS label an application is served at, resolved <b>here</b> because this is the first place
   * that holds both the spec and the application's name — the {@code ResourceProvisioning.resolve}
   * arrangement, applied to the other value the parser cannot derive.
   *
   * <p>Null is the answer for every application that asked for no host of its own, and that is the
   * common case: a file carrying the retired {@code navigation} key alone keeps being reached under
   * its path prefix, exactly as it was before hosts existed. A file that named {@code host} or
   * {@code navigation-entries} gets one — its own label where it stated one, and otherwise the
   * name without its platform prefix ({@code qits-ci} → {@code ci}, {@code qits-platform-events} →
   * {@code events}).
   */
  static String browserHost(String applicationName, DeploymentSpec spec) {
    if (!spec.browserHostDeclared()) {
      return null;
    }
    if (spec.host() != null) {
      return spec.host();
    }
    String name = applicationName == null ? "" : applicationName;
    if (name.startsWith(PLATFORM_NAME_PREFIX)) {
      return name.substring(PLATFORM_NAME_PREFIX.length());
    }
    return name.startsWith(NAME_PREFIX) ? name.substring(NAME_PREFIX.length()) : name;
  }

  /**
   * The stored spelling back into the list it was joined from. Blank is no routes — the answer most
   * applications give — and never a one-element list containing nothing, which would be a route.
   */
  private static List<String> splitRoutes(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    return Arrays.stream(stored.split(","))
        .map(String::strip)
        .filter(route -> !route.isEmpty())
        .toList();
  }

  /** @return false when the wait was interrupted, which ends the retry rather than ignoring it. */
  private static boolean pause(Duration duration) {
    try {
      Thread.sleep(duration);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * The async entry every announcement door calls ({@link ReleaseAnnouncements}). It validates,
   * hands the release to the worker and returns — the sender is fire-and-forget and has nothing to
   * do with the answer.
   *
   * <p><b>The whole event runs on the worker, registration included</b>, and that placement is the
   * concurrency contract rather than a detail. Derived registration is a read-then-write — "what
   * does the catalogue hold for this service, and what should it hold now" — and two green builds
   * of one repository arriving together would each read the old state and each write a link set
   * computed from it. The worker is single-threaded, so putting the read and the write on it is
   * what makes the pair atomic against every other event — the same reason the cutover lives there,
   * applied to the rows instead of the containers. ({@code ServiceCatalog.upsert} is synchronized
   * as the belt for every other caller.)
   *
   * <p>{@code runId} is optional and is recorded on every row this queues, verbatim: it is the only
   * pointer from a deployment back to the run that produced it, and it is resolved against nothing
   * — a reader takes it to qits-ci. A {@code SoftwareRelease} carries none, so the bus door passes
   * null and the row honestly names no build. The pair that actually drives the deployment is
   * (application, version).
   *
   * <p><b>The application name arrives by VALUE and is not derived from the repository here.</b> A
   * release names a package ({@code qits/qits-ci}) and no repository name at all, so the caller has
   * already taken the image path out of it ({@link PackageNames}). Everything below takes that
   * string: the catalogue key, the {@link Target}, the health path, the provisioned database and
   * role, the wire alias, the container name and the image reference. The {@link RepositoryRef} is
   * carried alongside for one purpose only — the spec read, which addresses the repository rather
   * than the application. Passing a repository id where an application name is meant is the
   * regression to watch for: it would tag images {@code qits/<uuid>:<version>} while every pipeline
   * publishes {@code qits/<name>}.
   *
   * <p><b>The application name can still be OVERRIDDEN one step later</b>, in {@link #deploy}, by
   * {@code application:} in the file this has not read yet. Absent — every file today — the
   * released package's name is the whole answer and this paragraph describes both.
   *
   * <p><b>{@code causationId} is carried by value from here on, and that is the whole reason it is
   * a parameter.</b> {@code CausationScope} is a ThreadLocal: the door's scope — the frame's for the
   * bus, the request filter's restored one for the HTTP intake — stands on THIS thread and is gone
   * the instant the lambda below runs somewhere else. So the cause travels the way {@code runId}
   * always has, as data, and the rows this event writes set it explicitly rather than hoping a
   * listener finds a scope that is not there. It is validated by nothing: an announcement is never
   * refused over a trace column.
   */
  @Override
  public void announce(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      String priority,
      UUID causationId,
      Door door) {
    DeploymentIdentifiers.requireRunId(runId);
    RepositoryRef repo = repository.validated();
    String released = DeploymentIdentifiers.requireApplicationName(applicationName);
    DeploymentIdentifiers.requireVersion(version);
    // Made recordable rather than validated, and that asymmetry is deliberate: the priority is an
    // advisory badge that reaches no argv and no URL, so it is dropped with a WARN when it will not
    // fit rather than refusing a release somebody is waiting for. See ReleasePriorities.
    String recorded = ReleasePriorities.recorded(priority, released + "@" + version);
    submit(null, runId, repo, released, version, packageName, recorded, causationId, door);
  }

  /**
   * The durable door — see {@link ReleaseAnnouncements#announceDurably} for the whole argument.
   *
   * <p><b>The order of the three steps here is the guarantee.</b> The identifiers are validated
   * first, so a release this component refuses never becomes owed work and the refusal reaches the
   * caller as it always did. The obligation is written second, in a transaction of its own that
   * commits before anything is queued. Only then is the release handed to the worker.
   *
   * <p><b>The obligation cannot commit with the bus's claim and does not try.</b> {@code
   * consumed_event} is in the {@code eventstream} database and this row is in {@code
   * platformdeployments}; both datasources are non-XA and Narayana refuses two of them in one
   * transaction — the note {@link ReleaseTips} already carries for its floor read. So the ORDER is
   * what is chosen: obligation first. What that leaves is a duplicate (obligation written, claim
   * rolled back, event offered again) and never a loss, and a duplicate is what {@code
   * OwedReleaseSweep}'s guards collapse to a no-op.
   */
  @Override
  public void announceDurably(
      String eventId,
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      String priority,
      UUID causationId) {
    DeploymentIdentifiers.requireRunId(runId);
    RepositoryRef repo = repository.validated();
    String released = DeploymentIdentifiers.requireApplicationName(applicationName);
    DeploymentIdentifiers.requireVersion(version);
    // Made recordable BEFORE the obligation is written, so the ledger holds a value the column can
    // take and a re-drive hands back exactly what the live path recorded.
    String recorded = ReleasePriorities.recorded(priority, released + "@" + version);
    String owedId =
        acceptance.accept(
            new ReleaseAcceptance.Accepted(
                eventId, runId, repo, released, version, packageName, recorded, causationId));
    submit(
        owedId, runId, repo, released, version, packageName, recorded, causationId,
        Door.RELEASE_EVENT);
  }

  /**
   * Hand one validated release to the worker, and — when it came through the durable door — settle
   * its obligation with whatever the worker made of it.
   *
   * <p><b>Three outcomes, and the two that are not a discharge are the point.</b> A {@code deploy}
   * that returns having HELD the release on an unreadable spec has not finished with it: the hold
   * lives in a map that dies with this JVM, so the obligation stays owed and the next process
   * re-drives it. A {@code deploy} that threw has not finished with it either, and hands the row
   * back to nobody — so <em>this</em> process's next sweep retries it, without waiting for a
   * restart. Everything else is a discharge: the spec was read, the request row was written, the
   * container was cut over, or the release was recorded and refused. All of those are decisions,
   * and a decision is not owed work.
   */
  private void submit(
      String owedId,
      String runId,
      RepositoryRef repo,
      String released,
      String version,
      String packageName,
      String priority,
      UUID causationId,
      Door door) {
    worker.submit(
        () -> {
          try {
            deploy(runId, repo, released, version, packageName, priority, causationId, door);
            if (owedId != null && !isHeldOnSpec(released)) {
              acceptance.settle(owedId, ReleaseAcceptance.Outcome.DISCHARGED, null);
            }
          } catch (RuntimeException e) {
            LOG.errorf(
                e,
                "The software-release event for %s@%s could not be handled",
                released,
                version);
            if (owedId != null) {
              acceptance.release(owedId, "[" + e + "]");
            }
          }
        });
  }

  /**
   * Whether this application's release is being held for a spec read that may yet answer — the one
   * state {@link #deploy} can return in without having finished.
   *
   * <p>Read off the map rather than returned from {@code deploy}, because both readers run on the
   * same single worker thread: {@link #deploy} clears any prior entry for the name before it does
   * anything and {@link #retrySpecReads()} runs between events, so nothing can put an entry there
   * between the two lines that ask.
   */
  private boolean isHeldOnSpec(String releasedName) {
    return specRetries.containsKey(releasedName);
  }

  /**
   * One place this build deploys to: one application in one tier. Resolved before anything is queued
   * and carried by value from there on — the docker work must not need a second query to know where
   * it is going.
   *
   * <p><b>It carried a {@code target} and does not any more.</b> The field said which plane, and the
   * plane decided three things — the bare wire alias, a membership in every tier's networks, and a
   * {@code platform:<name>} read-surface key. All three are gone, so a place is a tier.
   *
   * <p>{@code healthCmd}, {@code resources}, routing and the orchestrator options are the
   * spec's, and are <b>the only fields here no row holds</b>. They need none: the spec is read
   * fresh from the repository before every deployment, and the one path that resolves targets from
   * the catalogue instead ({@link #alreadyRegistered}) records a failure and deploys nothing. Null
   * is the HTTP probe over {@code healthPath}; an empty resource list is every application that
   * stores nothing; the default order is {@code start-first} and the default publish mode is
   * {@code host}.
   *
   * <p>{@code renamedFrom} is the newest of those and the narrowest: the application name this one
   * used to carry, read straight off the file, threaded here so {@code ResourceProvisioning} can
   * tell a rename from a collision and so a successful cutover can retire the predecessor's edge
   * routes ({@link #announceRetired}). Null is every file that exists today. It is carried
   * per-place rather than once per event for the reason every other spec value is — both uses are
   * made per target, and a {@link Plan} is plain values.
   *
   * <p>{@code pinnedVolumes} follows from it: the resolved names of the private volumes that mount
   * the PREDECESSOR's storage because the file declares a rename (qits-1772, see {@code
   * VolumeSpec.source(String, String)}). Empty for every application that was not renamed or keeps
   * no private data. It is computed beside {@code volumes}, off the declared scopes, rather than
   * re-derived later from the resolved names — a name's prefix cannot tell a pinned private volume
   * from a shared one that happens to share it. The cutover reads it for one decision: two
   * applications on one private volume is two writers, so a rename that pins one is always swapped
   * stop-old.
   */
  record Target(
      String applicationName,
      String environmentId,
      String environmentName,
      String bundleNetwork,
      boolean availableOnEnv,
      String healthPath,
      String healthCmd,
      List<ResourceProvisioning.Resolved> resources,
      List<DeploymentDriver.VolumeMount> volumes,
      DeploymentDriver.UpdateOrder updateOrder,
      DeploymentDriver.PublishMode publishMode,
      List<String> routes,
      int upstreamPort,
      String browserHost,
      List<NavigationEntry> navigation,
      String apiDocs,
      String renamedFrom,
      List<String> pinnedVolumes,
      Duration stopGracePeriod,
      DeclaredShape shape) {}

  /**
   * One software-release event, start to finish, on the worker thread: read what the repository
   * declares at the released tag, bring the catalogue up to date with it, record what each place
   * was asked for, and deploy it.
   *
   * <p>The spec read comes first because it decides <b>which places exist</b> — there is nothing to
   * request or queue until it has answered. A read that fails does not guess: the places this
   * repository is already registered in each get a recorded deployment naming the cause, and a
   * repository with nothing registered gets nothing, exactly as an unknown repository always has.
   *
   * <p><b>What that recorded word is depends on WHY the read failed</b>, and the difference was
   * bought with three stranded releases. A file that does not parse is the repository's problem and
   * ends {@code FAILED}, terminally, as it always did. A read that never saw the file — the git
   * host refused it, 500'd, or did not answer — ends {@code SPEC_UNREADABLE} and the release is
   * <b>held</b>: {@link #retrySpecReads()} reads it again on the observation cadence and deploys it
   * for real the moment it answers. See {@link #recordUnreadableSpec} and {@link SpecException}.
   *
   * <p><b>The spec read is also where the application name can stop being the repository's</b>, and
   * this is the only place that substitution happens. {@code application:} in the file names what
   * this repository deploys AS, and from the line below every derivation takes that string: the
   * catalogue key, the {@link Target}, the wire alias, the container name, the image reference, the
   * provisioned database and role, the derived host, the extras family and every event this
   * deployment publishes. Absent — which is every file that exists today — the name is the
   * announcement's own and nothing about this method changed. The key exists so a repository can be
   * RENAMED without moving anything that runs: {@code qits-ci-service} declaring {@code
   * application: qits-ci} deploys exactly what {@code qits-ci} deployed, down to the image tag its
   * pipeline still pushes.
   *
   * <p><b>An unreadable spec keeps the repository's own name, and it has to.</b> The override lives
   * in the file that could not be read, so the failure rows go to the places registered under the
   * announcement's name — which for a repository that has been renamed AND overrides is no place at
   * all. That is the honest answer rather than a gap: nothing here knows which application a file it
   * could not read was speaking for, and guessing would record a failure against somebody else's.
   */
  private void deploy(
      String runId,
      RepositoryRef repository,
      String releasedName,
      String version,
      String packageName,
      String priority,
      UUID causationId,
      Door door) {
    // Whatever this application was waiting to re-read, this release replaces: a newer version is
    // the supersession that ends a pending retry, and a re-announcement of the same one is a
    // fresh attempt rather than a second entry beside the first.
    specRetries.remove(releasedName);
    SpecSource.SpecRead read;
    try {
      // The reference, not the application: the git host serves this blob under the repository's
      // own address. The REV is the released TAG, fully qualified — a bare version would let a
      // branch of the same name win, and the file that decides where this container runs has to be
      // the file the version was cut from. The answer carries the commit the tag resolved to, which
      // is the only place a release deployment can learn one.
      read = specs.read(repository, SpecSource.tagRev(version));
    } catch (RuntimeException e) {
      recordUnreadableSpec(
          runId, repository, releasedName, version, packageName, priority, causationId, door, e);
      return;
    }
    deployReadSpec(
        runId,
        repository,
        releasedName,
        version,
        packageName,
        priority,
        causationId,
        door,
        read.spec(),
        read.commitSha(),
        read.declared(),
        List.of());
  }

  /**
   * The rest of a deployment, once the repository's file has been read — everything that was in
   * {@link #deploy} before a failed read stopped being final.
   *
   * <p>It is a method rather than the tail of one because there are two ways to arrive here now:
   * the release event itself, and {@link #retrySpecReads()} minutes later with the same release and
   * a file that has started answering. Both deploy identically from that point on, which is the
   * property worth having — a recovered read is not a second code path.
   *
   * <p><b>And this is where a release that never asked to be deployed stops.</b> {@code declared}
   * is the git host's own answer — the repository carries {@link SpecSource#SPEC_PATH} at the
   * released tag, or it does not — and on the {@link Door#RELEASE_EVENT} door a release that
   * carries no file is recorded and goes no further. See {@link #refuseUndeclaredSpec}.
   *
   */
  private void deployReadSpec(
      String runId,
      RepositoryRef repository,
      String releasedName,
      String version,
      String packageName,
      String priority,
      UUID causationId,
      Door door,
      DeploymentSpec spec,
      String commitSha,
      boolean declared,
      List<String> heldRows) {
    if (!declared && door == Door.RELEASE_EVENT) {
      refuseUndeclaredSpec(
          new Release(
              runId,
              repository,
              releasedName,
              version,
              commitSha,
              packageName,
              priority,
              causationId),
          heldRows);
      return;
    }
    String applicationName = applicationName(releasedName, spec);
    String failure = null;

    List<Target> targets;
    try {
      targets = register(applicationName, spec, causationId);
    } catch (RuntimeException e) {
      // Registration is a local transaction, so this is a bug rather than an outage — and a bug
      // here is exactly the shape that once cost an hour of silence: a fire-and-forget sender,
      // no row, no signal. It is recorded where an operator looks, in the tiers this application
      // is already registered in.
      LOG.errorf(e, "Registration of %s@%s failed", applicationName, version);
      failure = "[registration failed: " + e.getMessage() + "]";
      targets = alreadyRegistered(applicationName);
    }

    List<Queued> queued =
        queue(
            new Release(
                runId,
                repository,
                applicationName,
                version,
                commitSha,
                packageName,
                priority,
                causationId),
            targets);
    if (failure != null) {
      for (Queued row : queued) {
        finish(row.deploymentId(), row.target(), PdDeploymentStatus.FAILED, failure);
      }
      return;
    }
    for (Queued row : queued) {
      try {
        execute(row.deploymentId(), row.target(), version, commitSha);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Deployment %s failed unexpectedly", row.deploymentId());
        finish(
            row.deploymentId(), row.target(), PdDeploymentStatus.FAILED, "[unexpected: " + e + "]");
      }
    }
  }

  /**
   * A release that carries no {@link SpecSource#SPEC_PATH} at its tag: <b>recorded, and not
   * deployed</b>.
   *
   * <p><b>Why this exists at all.</b> A docker package is not a service. The release door hears one
   * event per published image, chosen by nobody, and three of this platform's repositories publish
   * workspace CONTAINER images — the image a workspace RUNS AS and the two it is built from, with
   * no port, no health surface and nothing this component can put live. On 2026-09-04 all three
   * were announced here, read no file, took {@link DeploymentSpec#DEFAULTS}, and were launched as
   * the swarm services {@code dev-workspace}, {@code dev-workspace-editor} and {@code
   * dev-workspace-base}, which sat created until the 120s health gate failed them. The defaults
   * were never wrong; the question they answer was. They mean "deploy it the ordinary way", and the
   * old build door only ever asked it of repositories the platform already deployed.
   *
   * <p><b>So the missing file is read as the answer it is</b>: a repository that declares nothing
   * has not asked to be deployed. This is deliberately not a parse failure and not a refusal of the
   * release — the version is real, the image is published, and a repository is free to publish one
   * and deploy nothing. What it is is a decision, so it is written down where decisions are: a
   * {@link PdDeploymentRequest} with {@link PdQualityGate#UNMET} and a {@code gate_detail} saying
   * why, pointing at no deployment. That is the shape the request row's own javadoc describes and
   * the first thing to ever write it.
   *
   * <p><b>Before registration, and that is half the fix.</b> Registering first would create the
   * catalogue row, the wire alias and the tier link for an application that is not one — which is
   * exactly what left {@code workspace}, {@code workspace-editor} and {@code workspace-base} in the
   * catalogue. Nothing is registered, so nothing has to be retired afterwards.
   *
   * <p><b>Only on the {@link Door#RELEASE_EVENT} door.</b> The manual door is somebody naming an
   * application and a version, and one of the things it is for is deploying a tag cut before this
   * file existed anywhere; refusing that would be refusing the choice. {@link Door} argues it.
   *
   * @param heldRows the {@code SPEC_UNREADABLE} deployments a retryable read already wrote for this
   *     release, if it went that way round — a git host that refused the blob and then answered 404
   *     has told us the file is absent, so those rows are settled rather than left waiting for an
   *     answer that has arrived. Empty on the ordinary path, where the first read said so.
   */
  private void refuseUndeclaredSpec(Release release, List<String> heldRows) {
    String detail =
        "The release declares no "
            + SpecSource.SPEC_PATH
            + " at "
            + SpecSource.tagRev(release.version())
            + ", so it publishes an image and asks for no deployment. Recorded and not deployed;"
            + " add the file to deploy it, or announce it through the manual door.";
    LOG.infof(
        "%s@%s carries no %s at its tag, so it is recorded and not deployed: a published image is"
            + " not necessarily a service",
        release.applicationName(), release.version(), SpecSource.SPEC_PATH);
    List<PdEnvironment> entry = entryTiers();
    // Where it WOULD have gone, so the refusal sits on the same tier's page as the deployments it
    // is standing in for. Null when nothing is designated yet — pd_deployment_request's own null,
    // and the honest answer when there is no tier to name.
    String environmentId = entry.isEmpty() ? null : entry.get(0).id;
    // Not retried, for queue()'s reason: it INSERTs, and a commit whose outcome the connection died
    // before reporting would be duplicated by a second attempt. Nothing docker-side has happened,
    // so losing the row drops the event with nothing half-done.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeploymentRequest request =
                  persistRequest(release, release.applicationName(), environmentId);
              request.qualityGate = PdQualityGate.UNMET;
              request.gateDetail = detail;
              request.gateSettledAt = Instant.now();
            });
    for (String deploymentId : heldRows) {
      finishHeld(deploymentId, PdDeploymentStatus.FAILED, "[" + detail + "]");
    }
  }

  /**
   * A spec read that did not answer, recorded on the places this repository is already registered
   * in — and, when the failure is one a second read may survive, remembered so that second read
   * happens.
   *
   * <p><b>The application name is the announcement's own here, and it has to be.</b> The {@code
   * application:} override lives in the file that could not be read, so the rows go to the places
   * registered under the released name — which for a repository that has been renamed AND overrides
   * is no place at all. That is the honest answer rather than a gap: nothing here knows which
   * application a file it could not read was speaking for, and guessing would record a failure
   * against somebody else's. It is also why a retry is worth having at all — an unregistered
   * repository gets no row, so without one its very first release would strand invisibly.
   */
  private void recordUnreadableSpec(
      String runId,
      RepositoryRef repository,
      String releasedName,
      String version,
      String packageName,
      String priority,
      UUID causationId,
      Door door,
      RuntimeException cause) {
    boolean retryable = SpecException.isRetryable(cause);
    String failure = "[deployment spec unreadable: " + cause.getMessage() + "]";
    LOG.warnf(
        "Could not read the deployment spec of %s@%s: %s", releasedName, version, cause.getMessage());

    List<Queued> queued =
        queue(
            new Release(
                runId,
                repository,
                releasedName,
                version,
                null,
                packageName,
                priority,
                causationId),
            alreadyRegistered(releasedName));
    List<String> rows = new ArrayList<>();
    for (Queued row : queued) {
      finish(
          row.deploymentId(),
          row.target(),
          retryable ? PdDeploymentStatus.SPEC_UNREADABLE : PdDeploymentStatus.FAILED,
          failure);
      rows.add(row.deploymentId());
    }
    if (!retryable) {
      return;
    }
    specRetries.put(
        releasedName,
        new SpecRetry(
            runId,
            repository,
            releasedName,
            version,
            packageName,
            priority,
            causationId,
            door,
            Instant.now(),
            1,
            List.copyOf(rows)));
    LOG.infof(
        "The deployment spec of %s@%s did not answer for a reason that may pass, so the release is"
            + " held rather than failed: it is read again every %ds until it answers, a newer"
            + " version supersedes it, or %d minutes pass.",
        releasedName, version, observeIntervalSeconds, SPEC_RETRY_DEADLINE.toMinutes());
  }

  /**
   * One release whose spec read has not answered yet — everything {@link #deploy} was given, plus
   * what the retry needs to know when to stop.
   *
   * <p>{@code rows} are the {@code SPEC_UNREADABLE} deployments this release already wrote. They
   * are held so that giving up can settle exactly those rows and nothing else; a recovery leaves
   * them alone, because a row that says the file could not be read at 13:17 is true and becomes
   * history the moment the retry queues a real deployment beside it.
   */
  private record SpecRetry(
      String runId,
      RepositoryRef repository,
      String releasedName,
      String version,
      String packageName,
      String priority,
      UUID causationId,
      Door door,
      Instant since,
      int attempts,
      List<String> rows) {

    SpecRetry next() {
      return new SpecRetry(
          runId, repository, releasedName, version, packageName, priority, causationId, door, since,
          attempts + 1, rows);
    }
  }

  /**
   * Read the spec of every held release again — <b>on the deploy worker</b>, beside the observation
   * pass and for the same reason it is there: this can queue a deployment, and a deployment that
   * ran off a ticker thread would race the cutover invariants the whole class is written around.
   *
   * <p><b>Why this is not {@code DeploymentObserver}'s job.</b> That class observes CONTAINERS and
   * writes rows about what they are doing; it explicitly touches nothing that never reached a
   * {@code docker run}, which is every row here. What has to be re-asked for a held release is the
   * git host, and the answer to it is a whole deployment rather than a status. Two questions, two
   * passes, one worker.
   *
   * <p><b>Three outcomes and no fourth.</b> The file answers and the release deploys for real, at
   * which point the held rows are simply history behind the new ones. It fails again the same way
   * and the entry ages one attempt. It fails permanently, or the deadline passes, and the rows this
   * release wrote are settled {@code FAILED} — the terminal word, said once, with what it was
   * waiting for on it.
   *
   * <p>Package-private so the suite drives a pass without waiting on a tick, exactly as {@link
   * DeploymentObserver#observeOnce()} and {@link #sweepInFlight()} are driven.
   */
  void retrySpecReads() {
    for (SpecRetry held : List.copyOf(specRetries.values())) {
      // Re-read under the key rather than trusting the copy: a release that arrived while this
      // loop ran has already replaced the entry, and re-deploying the superseded version would
      // undo exactly the collapse ReleaseTips performs.
      if (specRetries.get(held.releasedName()) != held) {
        continue;
      }
      SpecSource.SpecRead read;
      try {
        read = specs.read(held.repository(), SpecSource.tagRev(held.version()));
      } catch (RuntimeException e) {
        if (SpecException.isRetryable(e) && !expired(held)) {
          specRetries.replace(held.releasedName(), held, held.next());
          LOG.debugf(
              "The deployment spec of %s@%s still does not answer (attempt %d): %s",
              held.releasedName(), held.version(), held.attempts() + 1, e.getMessage());
          continue;
        }
        giveUpOnSpec(held, e);
        continue;
      }
      if (!specRetries.remove(held.releasedName(), held)) {
        continue; // superseded between the read and here; the newer release owns the place now
      }
      LOG.infof(
          "The deployment spec of %s@%s answered after %d attempt(s) — deploying the release that"
              + " was held on it",
          held.releasedName(), held.version(), held.attempts() + 1);
      deployReadSpec(
          held.runId(),
          held.repository(),
          held.releasedName(),
          held.version(),
          held.packageName(),
          held.priority(),
          held.causationId(),
          held.door(),
          read.spec(),
          read.commitSha(),
          read.declared(),
          held.rows());
    }
  }

  /** Whether this release has been held longer than a git host having a moment could explain. */
  private static boolean expired(SpecRetry held) {
    return Duration.between(held.since(), Instant.now()).compareTo(SPEC_RETRY_DEADLINE) > 0;
  }

  /**
   * Stop holding a release, and say so on the rows it wrote: {@code SPEC_UNREADABLE} becomes {@code
   * FAILED}, which is the word for "this attempt is over and nothing is known to serve".
   *
   * <p>The rows are settled by ID and are the ones this release wrote, never "the latest of the
   * place" — anything newer got there by being a newer deployment, and this must not overwrite it.
   * A row that is already something else (a newer deployment decommissioned it, an operator scaled
   * it) is left alone by {@link #finishHeld}'s status check.
   */
  private void giveUpOnSpec(SpecRetry held, RuntimeException cause) {
    specRetries.remove(held.releasedName(), held);
    String detail =
        "[deployment spec unreadable: "
            + cause.getMessage()
            + "]\n[given up after "
            + (held.attempts() + 1)
            + " attempt(s) over "
            + Duration.between(held.since(), Instant.now()).toMinutes()
            + " minute(s)"
            + (SpecException.isRetryable(cause)
                ? "; the git host never answered"
                : "; the last read failed permanently")
            + "]";
    LOG.warnf(
        "Giving up on the deployment spec of %s@%s after %d attempt(s): %s",
        held.releasedName(), held.version(), held.attempts() + 1, cause.getMessage());
    for (String deploymentId : held.rows()) {
      finishHeld(deploymentId, PdDeploymentStatus.FAILED, detail);
    }
  }

  /**
   * Settle one held row. Its own bracket, and <b>no announcement</b>: the row was already announced
   * as a failure when it was written — {@link #recordUnreadableSpec} writes it through {@link
   * #finish}, which announces every non-{@code ACTIVE} word — and a second {@code DeploymentFailed}
   * for the same deployment would tell every consumer the deployment failed twice.
   *
   * <p><b>The status is the caller's</b> because there are two ways a held row ends now: {@code
   * FAILED} when a retry gave up or the file turned out never to have existed, and {@code
   * SUPERSEDED} when {@link #sweepHeldSpecReads()} finds the place already being served. Both take
   * the same bracket, the same silence and the same guard below — only the word differs.
   *
   * <p>The guard is what makes every caller safe: a row that is already something else (a newer
   * deployment decommissioned it, an operator retired the application) is left alone.
   */
  private void finishHeld(String deploymentId, PdDeploymentStatus status, String detail) {
    DbRetry.runInNewTx(
        "Settling held deployment " + deploymentId + " as " + status,
        () -> {
          PdDeployment row = deployments.findById(deploymentId);
          if (row == null || row.status != PdDeploymentStatus.SPEC_UNREADABLE) {
            return; // torn down, or settled by something with a better claim on the row
          }
          row.status = status;
          row.detail = detail;
          row.finishedAt = Instant.now();
          deployments.flush(); // statement phase, so a lost connection is retriable
        },
        CUTOVER_BUDGET);
  }

  /**
   * One accepted release, as the values every row it writes needs. It exists so the request and the
   * deployment are written from one description rather than from eight positional arguments — the
   * {@link Plan} stance, applied one step earlier.
   */
  private record Release(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String commitSha,
      String packageName,
      String priority,
      UUID causationId) {}

  /**
   * The name this release deploys under: the released package's, unless the file the repository
   * carries says otherwise.
   *
   * <p>One line, and it is the whole of the {@code application:} key. It sits here rather than in
   * the parser because the parser never knows which repository it is reading for — the {@code
   * ResourceProvisioning.resolve} and {@link #browserHost} arrangement, applied to the value those
   * two are themselves derived from.
   *
   * <p><b>Two repositories declaring one application name is last-wins and nothing here can refuse
   * it</b>: this schema records an application NAME and no repository identity, by V1's own rule, so
   * there is nothing to compare a second claimant against. It is not a hazard the key introduces —
   * two repositories in two projects may already carry one name and already collapse the same way —
   * so what the key adds is the log line below, which puts the claim on the record of every
   * deployment that makes it.
   */
  static String applicationName(String releasedName, DeploymentSpec spec) {
    if (spec == null || spec.application() == null || spec.application().equals(releasedName)) {
      return releasedName;
    }
    LOG.infof(
        "The release of %s declares `application: %s`, so it deploys as %s",
        releasedName, spec.application(), spec.application());
    return spec.application();
  }

  /**
   * The catalogue read every event starts with — and the first database access a build-succeeded
   * event makes, which is why it is retried.
   *
   * <p>An event arriving while this component's own postgres is cutting over used to die here and
   * be swallowed: the intake is fire-and-forget, the worker logs the exception, and the build that
   * caused it is simply never deployed. There is no row to look at afterwards, because a row is
   * what the read was on the way to creating.
   */
  private Optional<LinkedService> findService(String applicationName) {
    return DbRetry.call(
        "The catalogue lookup of " + applicationName,
        () -> catalog.find(applicationName),
        CUTOVER_BUDGET);
  }

  /**
   * <b>Where a release enters the platform</b> — the topology half of the same read, and the whole
   * of what replaced branch matching.
   *
   * <p>A build used to name a branch, and the tiers listening to that branch were where it went. A
   * release names no branch — it names a tag — so the tier a version lands in is a property of the
   * PLATFORM rather than of the thing being deployed. The designated platform environment
   * ({@code pd_environment.designated}, of which there is exactly one) is that tier: it is already
   * the row that says which tier this install deploys from, and there is nothing else in this
   * schema that could answer the question.
   *
   * <p><b>Empty is a real answer</b>, not only a fresh-database one: an install can be mid-bootstrap
   * with no tier designated yet, and a release then registers nothing and deploys nothing rather
   * than picking a tier at random. That is the same answer both retired register arms gave.
   *
   * <p><b>It is the platform plane's tier too</b>, and that is what makes it one question rather
   * than two. A platform service is deployed INTO the environment a release enters at — same row,
   * same labels, same {@code QITS_ENVIRONMENT}, same event attribution — so both register arms take
   * their {@link Target} from exactly this answer and neither has an absence to interpret.
   *
   * <p><b>A promotion ladder is the follow-up this shape is waiting for</b>, and it is deliberately
   * not here: a version that entered dev is promoted to the next tier by a decision somebody makes,
   * which is a deployment REQUEST of its own against another environment — the row this component
   * now writes. What must not come back is a branch: {@code pd_environment.branch} is gone from the
   * schema (V8) and nothing anywhere reads one.
   */
  private List<PdEnvironment> entryTiers() {
    return DbRetry.call(
        "The entry-tier lookup",
        () -> environments.platformEnvironment().map(List::of).orElseGet(List::of),
        CUTOVER_BUDGET);
  }

  /**
   * Bring the catalogue up to date with what the repository declares, and answer where to deploy.
   * The whole of derived registration, and it is <b>one arm</b>.
   *
   * <p><b>It was two, and they are collapsed because the plane is deleted.</b> {@code
   * registerInEnvironments} linked a service into the entry tier; {@code registerPlatform} dropped
   * its links, moved its deployment history onto the plane and owed the stranded {@code <env>-<app>}
   * services a retirement. There is one kind of service now, so there is one arm, no conversion, and
   * nothing to route between — including the catalogue read that decided the routing, which was the
   * 2026-09-23 incident's fix and superseded by this change. {@code alreadyRegistered} keeps its own
   * catalogue read: that one answers where to record a failure, which is a different question.
   *
   * <p>The link set written is the <b>union</b> of what the catalogue already holds and the entry
   * tier this release lands in. A release entering dev says nothing about whether the service also
   * belongs in preprod, and the upsert replaces the whole set — so sending only the entry tier
   * would silently unlink every other tier a promotion has already reached. <b>It is also what
   * carries the nine that were the plane</b>: V13 linked each of them into the designated tier, so
   * the union reads that link back and states it again rather than emptying it.
   */
  private List<Target> register(
      String applicationName, DeploymentSpec spec, UUID causationId) {
    if (!isDeployableName(applicationName)) {
      // The application name is the image path segment and the network alias, so it has to be a
      // dns label. A repository whose name is not one cannot be deployed by convention at all, and
      // the intake is fire-and-forget — saying so in the log beats a 400 nobody reads.
      LOG.warnf("%s cannot be an application name, so nothing was registered", applicationName);
      return List.of();
    }
    List<PdEnvironment> matching = entryTiers();
    if (matching.isEmpty()) {
      // No tier is designated as the platform's entry tier — a mid-bootstrap install. Nothing to
      // link into, so nothing is written. That is the answer both register arms always gave.
      return List.of();
    }
    Optional<LinkedService> known = findService(applicationName);

    Set<String> links =
        new LinkedHashSet<>(known.map(LinkedService::environmentIds).orElse(List.of()));
    for (PdEnvironment environment : matching) {
      links.add(environment.id);
    }
    String healthPath = resolveHealthPath(applicationName, spec, known);
    // The spec's databases, with the convention filled in where the file left it out. Resolved
    // ONCE, here, because this is the first place that holds both the application name and the
    // spec — and before any Target is built, so a collision is one refusal rather than one per
    // tier. The name is the repository's, never its storage id: a database called after a UUID
    // would be a fresh, empty store on the first deployment after the identity rollback.
    List<ResourceProvisioning.Resolved> resources =
        ResourceProvisioning.resolve(applicationName, spec.resources());
    List<DeploymentDriver.VolumeMount> volumes = volumeMounts(applicationName, spec);
    List<String> pinnedVolumes = pinnedVolumes(applicationName, spec);
    catalog.upsert(
        new ServiceCatalog.Upsert(
            applicationName,
            null, // PdService.branch is vestigial; a release names a tag
            spec.availableOnEnv(),
            healthPath,
            List.copyOf(links)),
        causationId);

    List<Target> targets = new ArrayList<>();
    for (PdEnvironment environment : matching) {
      if (known.isEmpty() || !known.get().environmentIds().contains(environment.id)) {
        LOG.infof("Registered %s in environment %s", applicationName, environment.name);
      }
      targets.add(
          new Target(
              applicationName,
              environment.id,
              environment.name,
              environment.network,
              spec.availableOnEnv(),
              healthPath,
              spec.healthCmd(),
              resources,
              volumes,
              spec.updateOrder(),
              spec.publishMode(),
              spec.routes(),
              spec.upstreamPort(),
              browserHost(applicationName, spec),
              spec.navigationEntries(),
              spec.apiDocs(),
              spec.renamedFrom(),
              pinnedVolumes,
              spec.stopGracePeriod(),
              // binds/publishes/aliases as WRITTEN: the driver resolves ${QITS_ENVIRONMENT} in an
              // alias against the place it deploys into, which is where a refusal can end a row.
              spec.shape()));
    }
    return List.copyOf(targets);
  }

  /**
   * The volumes the repository declared, with every name resolved — which is this method's whole
   * job, and it is the {@code resources} arrangement exactly: the parser reads what the file says
   * and validates it, and the NAME is finished here, because this is the first place that knows
   * which application the file was read for. A private {@code volumes: private:data:/data} becomes
   * the volume {@code <application>-data}; a shared one was named in full and passes through.
   *
   * <p>A file that declares {@code renamed_from:} resolves its private volumes against the
   * predecessor instead — {@code <renamed_from>-data} — so the successor mounts the data the old
   * name wrote rather than a fresh, empty volume (qits-1772). The derivation is {@code
   * VolumeSpec.source(String, String)}'s, not this method's; see {@link #pinnedVolumes} for which
   * entries that touched.
   *
   * <p>It is also why a repository cannot reach another application's storage: the name it writes
   * is a segment, and the prefix is not its to choose. See {@code DeploymentSpec.VolumeSpec}.
   */
  private static List<DeploymentDriver.VolumeMount> volumeMounts(
      String applicationName, DeploymentSpec spec) {
    List<DeploymentDriver.VolumeMount> mounts = new ArrayList<>();
    for (DeploymentSpec.VolumeSpec volume : spec.volumes()) {
      mounts.add(
          new DeploymentDriver.VolumeMount(
              volume.source(applicationName, spec.renamedFrom()),
              volume.target(),
              volume.readOnly()));
    }
    return List.copyOf(mounts);
  }

  /**
   * The resolved names of the volumes {@link #volumeMounts} pinned to the predecessor — the private
   * ones, under a declared rename. Asked of the spec's scopes through {@code
   * VolumeSpec.pinnedToPredecessor} rather than inferred from a {@code <renamed_from>-} prefix on
   * the resolved names: a shared volume can carry that prefix without being anybody's private data.
   */
  private static List<String> pinnedVolumes(String applicationName, DeploymentSpec spec) {
    List<String> pinned = new ArrayList<>();
    for (DeploymentSpec.VolumeSpec volume : spec.volumes()) {
      if (volume.pinnedToPredecessor(applicationName, spec.renamedFrom())) {
        pinned.add(volume.source(applicationName, spec.renamedFrom()));
      }
    }
    return List.copyOf(pinned);
  }

  /**
   * Where a service's health path comes from, in this order: the repository's own {@code
   * health_path}, then the value the catalogue already holds, then the default derived from its
   * first declared {@code routes:} entry, then — for a spec declaring no route — the convention
   * derived from the application name. The resolved value is <b>written to the row</b> like every
   * other derived fact, so a fresh database gets working health gates with nothing to fill in by
   * hand.
   *
   * <p>The stored value sits above the two derived defaults on purpose. An operator who set a path
   * is fixing something a default got wrong, and a later green build must not undo the fix; a
   * repository that states its own path is the more specific statement and does.
   *
   * <p><b>The route is what the image actually serves; the application name is a deployed identity
   * that a rename can change underneath it.</b> qits-766's qits-stt → qits-speech rename is the
   * measured case: the renamed application derived {@code /speech/q/health/ready} from its new
   * name while the container itself went on answering {@code /stt} — the route a repository with
   * {@code routes: /stt} had declared all along and the application-name convention has no way to
   * read. {@link #routeHealthPath} is what reads it instead, and {@link #conventionHealthPath}
   * stays what a spec with no route falls back to — most of them, and every one of them before this
   * change.
   *
   * <p>What this replaces: registration once had no source for the path at all, so every row was
   * written null, every deployment fell back to {@code
   * qits.deployments.default-health-path} ({@code /q/health/ready}), and every service
   * mounted under its own prefix — all of them but the gateway — failed a health gate against a URL
   * that 404s while the container was fine.
   */
  static String resolveHealthPath(
      String applicationName, DeploymentSpec spec, Optional<LinkedService> known) {
    if (spec.healthPath() != null) {
      return spec.healthPath();
    }
    Optional<String> stored =
        known
            .map(linked -> linked.service().healthPath)
            .filter(path -> path != null && !path.isBlank());
    if (stored.isPresent()) {
      return stored.get();
    }
    return routeHealthPath(spec.routes()).orElseGet(() -> conventionHealthPath(applicationName));
  }

  /**
   * The default health path for a spec that declares at least one {@code routes:} entry: the
   * first one, with {@code /q/health/ready} appended. Navigation already treats the first route as
   * the one that matters ({@code DeploymentSpecParser}'s "navigation belongs to the first route"),
   * and the health gate follows the same rule — a repository naming several routes still serves one
   * container, and the first is the one it chose to lead with.
   *
   * <p>A first route of exactly {@code /} appends without a double slash, and a trailing slash on
   * any other route is stripped first for the same reason: the join is {@code <route>/q/health/ready},
   * never {@code <route>//q/health/ready}.
   *
   * <p>Empty is a real answer — a spec declaring no route falls all the way back to {@link
   * #conventionHealthPath}, which is every spec before qits-1773 and most of them since.
   */
  static Optional<String> routeHealthPath(List<String> routes) {
    if (routes == null || routes.isEmpty()) {
      return Optional.empty();
    }
    String route = routes.get(0);
    String trimmed = route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
    return Optional.of(trimmed + "/q/health/ready");
  }

  /**
   * The platform's path convention, and the fallback for a spec with no {@code routes:} entry: a
   * service serves everything under its own name without the {@code qits-} prefix, so
   * qits-observability answers on {@code /observability/q/health/ready} and this component — the
   * application {@code qits-deployments} — on {@code /deployments/q/health/ready}. A name that does
   * not carry the prefix keeps the whole name.
   *
   * <p>This component was the last exception to its own convention and is no longer one: it served
   * {@code /platform-deployments} while the plane that word named was already deleted. Both are the
   * same string now, and every source {@link #resolveHealthPath} asks agree on it — so its own
   * {@code .config/qits/deployments.yml} carries <b>no</b> {@code health_path:} key, and its declared
   * {@code routes: /deployments} derives the identical path through {@link #routeHealthPath} as
   * well. That override was load-bearing for exactly one release, while the stored {@code
   * pd_service.health_path} row still held the retired path and beat the convention; the row has
   * since been re-registered with the served path and the key was removed. Do not re-add one to
   * restate this method's answer: a spec that spells the path again is a second place for it to
   * drift from the route.
   *
   * <p><b>This is no longer the only derived default</b> — see {@link #routeHealthPath} for the one
   * {@link #resolveHealthPath} asks first, because the route is what a container actually serves and
   * the application name is a deployed identity a rename can change out from under it.
   */
  static String conventionHealthPath(String applicationName) {
    String segment =
        applicationName.startsWith(NAME_PREFIX)
            ? applicationName.substring(NAME_PREFIX.length())
            : applicationName;
    // A repository called exactly `qits-` would leave nothing to mount under; keep its whole name
    // rather than compose a path with an empty segment in it.
    return "/" + (segment.isBlank() ? applicationName : segment) + "/q/health/ready";
  }

  /**
   * A refused registration, written down where the operator will look for it: one {@code FAILED}
   * deployment on the platform plane. A log line alone would say the same thing to nobody — the
   * intake is fire-and-forget, so the row is the only surface a refusal can surface on.
   *
   * <p><b>It has NO CALLER LEFT</b>, and knowing why saves the next reader a search — the {@link
   * HealthGate#await} situation, and the same answer. Its one caller was the environment arm refusing
   * a repository the catalogue held as a platform service; that refusal went on 2026-09-23, and the
   * plane it was about went with this change. What is kept is the SHAPE of recording a refusal where
   * an operator looks — the intake is fire-and-forget, so a row is the only surface a registration
   * that refused to queue anything can surface on, and the next such refusal wants exactly this and
   * not a second spelling of it. The row names the entry tier — the same place the deployment being
   * refused would have gone; a mid-bootstrap install with no designated tier records it with none,
   * because a row with no tier and no successor is a worse answer than a refusal nobody can see, and
   * it is the one place a null environment survives.
   */
  @SuppressWarnings("unused")
  private void recordRejection(
      String applicationName, String runId, String version, String detail, UUID causationId) {
    String environmentId =
        entryTiers().stream().findFirst().map(environment -> environment.id).orElse(null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment rejected = new PdDeployment();
              rejected.id = UUID.randomUUID().toString();
              // See queue(): the stamp finds no scope on this thread, so the cause is set here.
              rejected.causationId = causationId;
              rejected.applicationName = applicationName;
              rejected.environmentId = environmentId;
              rejected.version = version;
              rejected.runId = runId;
              rejected.status = PdDeploymentStatus.FAILED;
              rejected.detail = detail;
              rejected.createdAt = Instant.now();
              rejected.finishedAt = Instant.now();
              deployments.persist(rejected);
            });
  }

  /**
   * What a failed spec read falls back to: where this application is already registered, read off
   * the catalogue. It answers where to record the failure — never where to deploy.
   *
   * <p>Which is why every target here carries a null {@code healthCmd} and needs nothing better:
   * no container starts off one of them.
   */
  private List<Target> alreadyRegistered(String applicationName) {
    Optional<LinkedService> known = findService(applicationName);
    if (known.isEmpty()) {
      return List.of();
    }
    LinkedService linked = known.get();
    List<Target> targets = new ArrayList<>();
    for (PdEnvironment environment : entryTiers()) {
      if (linked.environmentIds().contains(environment.id)) {
        targets.add(
            new Target(
                applicationName,
                environment.id,
                environment.name,
                environment.network,
                linked.service().availableOnEnv,
                linked.service().healthPath,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                DeploymentSpecParser.DEFAULT_UPSTREAM_PORT,
                null,
                null,
                null,
                // No predecessor either: nothing is provisioned off one of these targets, so there
                // is no claim for a declared rename to move.
                null,
                // ...and so no volume pinned to one.
                List.of(),
                // ...and no grace period: nothing is stopped off one of them either.
                null,
                // ...and no declared shape: nothing is created off one of them at all.
                null));
      }
    }
    return List.copyOf(targets);
  }

  /**
   * Write one deployment REQUEST per place this release addresses, answer the quality gate, and —
   * where it is met — write the {@code QUEUED} deployment row the request hands off to.
   *
   * <p><b>The request and the deployment are written in ONE transaction, and the gate is answered
   * between them.</b> That ordering is the model in miniature: the platform records what was asked
   * for, something decides, and only a {@link PdQualityGate#MET} answer produces an execution row —
   * which the request then points at. Today the decision is {@link #gate}, a placeholder that says
   * yes to everything, so the two rows always come in pairs; the day a real gate says no, the
   * request stands with no {@code deployment_id} and this loop simply queues nothing for it. One
   * transaction rather than two because a request with a settled gate and no deployment must mean
   * "refused", never "the process died in between".
   *
   * <p><b>Deliberately not retried</b>, and it is the same answer for {@link #recordRejection}, the
   * {@code STARTING} transition and the platform conversion. They INSERT or move rows, so a commit
   * whose outcome the connection died before reporting would be duplicated by a second attempt; and
   * they all run before anything docker-side has happened, so losing one drops the event with
   * nothing half-done and a resent event replays it. The retried brackets are the ones that come
   * AFTER a container is already running, where dropping the work leaves a live container with no
   * row that admits it.
   *
   * <p>Each created row is announced as {@code DeploymentQueued} <b>after the transaction
   * commits</b>, so a consumer that reads the deployment back finds it. One event per row: a
   * release addressing three tiers queues three deployments and says so three times.
   */
  private List<Queued> queue(Release release, List<Target> targets) {
    if (targets.isEmpty()) {
      return List.of();
    }
    List<Queued> rows =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Queued> queued = new ArrayList<>();
                  for (Target target : targets) {
                    PdDeploymentRequest request =
                        persistRequest(release, target.applicationName(), target.environmentId());
                    Gate verdict = gate(release, target);
                    request.qualityGate = verdict.state();
                    request.gateDetail = verdict.detail();
                    request.gateSettledAt = Instant.now();
                    if (verdict.state() != PdQualityGate.MET) {
                      LOG.warnf(
                          "The quality gate refused %s@%s for %s: %s",
                          release.applicationName(),
                          release.version(),
                          target.environmentName(),
                          verdict.detail());
                      continue;
                    }
                    Queued row = persistQueued(release, target);
                    request.deploymentId = row.deploymentId();
                    queued.add(row);
                  }
                  return queued;
                });
    for (Queued row : rows) {
      announceQueued(row, release);
    }
    return rows;
  }

  /**
   * What the quality gate said. A record rather than a boolean because the detail is the whole
   * value of a refusal — a request that says {@code UNMET} and nothing else tells an operator
   * nothing.
   */
  private record Gate(PdQualityGate state, String detail) {}

  /**
   * <b>The placeholder quality gate: everything passes, immediately.</b>
   *
   * <p>It is a method rather than an absence on purpose. A released version has already been
   * through qits-ci's pipeline and this component has nothing further to ask today, so the honest
   * implementation is one line — but the SHAPE has to exist before a real gate does, or the first
   * one arrives as a change to the transaction that queues deployments rather than as a change to
   * this method. Whatever asks a question later (a userflow suite against the tier, a manual
   * approval, a soak window on the version already there) answers here, and {@link #queue} already
   * does the right thing with a no.
   *
   * <p>It takes the release and the target because a real gate is about a (version, place) pair —
   * the same version may be waved into dev and held out of prod — and a signature that could not
   * express that would have to change on the day it mattered.
   */
  private Gate gate(Release release, Target target) {
    return new Gate(PdQualityGate.MET, null);
  }

  /** A queued row, and the facts its announcement needs, carried out of the transaction. */
  private record Queued(String deploymentId, Target target, Instant queuedAt) {}

  /**
   * One deployment request. Inside {@link #queue}'s transaction, never on its own — the gate's
   * answer and the deployment it hands off to are written in the same bracket. The one other caller
   * is {@link #refuseUndeclaredSpec}, which writes a request that will never have a deployment and
   * therefore has no {@link Target} to take the place from.
   *
   * <p>It takes the two fields off the target rather than the target itself for exactly that
   * reason: a refusal knows the application and the tier and nothing else, because there was never
   * a place computed for it.
   */
  private PdDeploymentRequest persistRequest(
      Release release, String applicationName, String environmentId) {
    PdDeploymentRequest request = new PdDeploymentRequest();
    request.id = UUID.randomUUID().toString();
    // Set EXPLICITLY, for the reason every row this component writes on the worker is: the
    // CausationStamp listener reads a ThreadLocal the queue hop already left behind.
    request.causationId = release.causationId();
    request.applicationName = applicationName;
    request.version = release.version();
    request.environmentId = environmentId;
    request.packageName = release.packageName();
    // Recorded and never read by anything that decides: the worker is FIFO, and this row is where a
    // person (and the queue-ordering feature after it) reads what the release request declared.
    request.priority = release.priority();
    request.repoId = release.repository() == null ? null : release.repository().repoId();
    request.projectId = release.repository() == null ? null : release.repository().projectId();
    request.qualityGate = PdQualityGate.UNMET;
    request.createdAt = Instant.now();
    requests.persist(request);
    return request;
  }

  /** One {@code QUEUED} row. Inside {@link #queue}'s transaction, never on its own. */
  private Queued persistQueued(Release release, Target target) {
    PdDeployment deployment = new PdDeployment();
    deployment.id = UUID.randomUUID().toString();
    // The generic causation column, set EXPLICITLY. This runs on pd-deploy-worker, behind the
    // queue hop the intake made: CausationScope does not follow work, so CausationStamp would read
    // null here and record a decision nobody made. An author-set value is what the stamp yields to.
    // Every row of one event shares it — one build going green is one cause, however many tiers it
    // fans out over.
    deployment.causationId = release.causationId();
    deployment.applicationName = target.applicationName();
    deployment.environmentId = target.environmentId();
    // The released coordinate — the tag the image carries and what everything below derives from
    // — and, beside it, the commit that tag resolved to. The second is null when the spec read
    // could not resolve one, which is a real answer and never a reason to refuse a deployment.
    deployment.version = release.version();
    deployment.commitSha = release.commitSha();
    deployment.runId = release.runId();
    deployment.status = PdDeploymentStatus.QUEUED;
    deployment.createdAt = Instant.now();
    // The routing snapshot, written where the deployment is written. It is the spec's, and V3's
    // header argues why a row holds a spec value: a SELF-UPDATE announces from the successor's
    // startup sweep, which has this row and no live process that read the file. Storing it is what
    // keeps that announcement off the network — see announceAdopted.
    deployment.routes = String.join(",", target.routes());
    deployment.upstreamPort = target.upstreamPort();
    deployment.browserHost = target.browserHost();
    deployment.navigationEntries = DeploymentSpecParser.joinEntries(target.navigation());
    deployment.apiDocs = target.apiDocs();
    deployments.persist(deployment);
    return new Queued(deployment.id, target, deployment.createdAt);
  }

  /**
   * Everything a deployment needs off the worker thread — plain values, never entities.
   *
   * <p><b>The last three are for the announcements and nothing else.</b> They are read off the row
   * inside the {@code STARTING} transaction because that is the one place a deployment has the row
   * open; re-reading it at each of the three later announcement points would be three queries for
   * values that cannot change. {@code startedAt} is a clock reading rather than a column — there is
   * no {@code started_at} — taken at the transition rather than at publish, which is the property an
   * event log actually needs.
   */
  private record Plan(
      String deploymentId,
      Target target,
      String version,
      String commitSha,
      String healthPath,
      String healthCmd,
      String runId,
      UUID cause,
      Instant startedAt) {

    String applicationName() {
      return target.applicationName();
    }

    String environmentId() {
      return target.environmentId();
    }

    String environmentName() {
      return target.environmentName();
    }

    String bundleNetwork() {
      return target.bundleNetwork();
    }

    boolean availableOnEnv() {
      return target.availableOnEnv();
    }

    List<ResourceProvisioning.Resolved> resources() {
      return target.resources();
    }

    /** The application this one used to be called, or null — see {@link Target}. */
    String renamedFrom() {
      return target.renamedFrom();
    }

    /** What the repository declared in {@code volumes:}, with every name already resolved. */
    List<DeploymentDriver.VolumeMount> volumes() {
      return target.volumes();
    }

    /** The private volumes resolved to the predecessor's under a rename — see {@link Target}. */
    List<String> pinnedVolumes() {
      return target.pinnedVolumes() == null ? List.of() : target.pinnedVolumes();
    }

    /** The one network {@code docker run} can take; every other membership is a join. */
    String primaryNetwork() {
      return PdNetworks.application(environmentName(), applicationName());
    }

    DeploymentDriver.UpdateOrder updateOrder() {
      return target.updateOrder();
    }

    DeploymentDriver.PublishMode publishMode() {
      return target.publishMode();
    }

    /** The repository's {@code stop_grace_period}, or null for a target no spec was read for. */
    Duration stopGracePeriod() {
      return target.stopGracePeriod();
    }

    /** The repository's {@code binds}/{@code publishes}/{@code aliases}, or null with no spec. */
    DeclaredShape shape() {
      return target.shape();
    }

    /**
     * The address peers dial this by, on every network it is on: {@code <environment>-<application>},
     * for every service there is. It is derived in one place because everything that has to agree on
     * it takes it from here — swarm's service NAME first of all, which is why a changed spelling is a
     * second service beside the one that was serving rather than a rename.
     *
     * <p><b>That is exactly what the plane's deletion cost once.</b> The nine services that were the
     * platform plane answered on their bare names, and this now derives a qualified one for them — so
     * the first deployment of each creates {@code <env>-<app>} and the bare-named service it replaces
     * is found by nothing here. See {@code PdNetworks.alias} and AGENTS.md, <i>Retiring the plane's
     * bare-named services</i>.
     */
    String wireAlias() {
      return PdNetworks.alias(environmentName(), applicationName());
    }

    /**
     * The event's complete route projection. The host is resolved only after the target is known,
     * from the same wire alias the runtime registered; a consumer must never recreate that naming
     * convention.
     */
    List<DeploymentEndpoint> endpoints() {
      // The same builder the startup sweep's adoption uses over the row's stored snapshot, so the
      // two ways this component can announce a deployment cannot describe one differently.
      return resolveEndpoints(target.routes(), wireAlias(), target.upstreamPort());
    }

    /** The DNS label this deployment is also served at, or null — resolved at registration. */
    String browserHost() {
      return target.browserHost();
    }

    /** Where this application asks to appear. Application-level, so it is the target's own. */
    List<NavigationEntry> navigation() {
      return target.navigation();
    }

    /** Where the application's browsable API document lives, or null — the spec's own value. */
    String apiDocsPath() {
      return target.apiDocs();
    }
  }

  /**
   * The synchronous deployment — package-private so tests drive it without the worker.
   */
  void execute(String deploymentId, Target target, String version, String commitSha) {
    Plan plan =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  PdDeployment deployment = deployments.findById(deploymentId);
                  if (deployment == null || deployment.status != PdDeploymentStatus.QUEUED) {
                    return null; // torn down or swept while queued — nothing to do
                  }
                  deployment.status = PdDeploymentStatus.STARTING;
                  return new Plan(
                      deploymentId,
                      target,
                      version,
                      commitSha,
                      target.healthPath() != null ? target.healthPath() : defaultHealthPath,
                      // No default to fall back on, and none to want: an image that named no
                      // command is one the HTTP probe describes.
                      target.healthCmd(),
                      deployment.runId,
                      deployment.causationId,
                      Instant.now());
                });
    if (plan == null) {
      return;
    }
    announceStarted(plan);

    // RENAMING THE DEPLOYER IS REFUSED, before anything moves. A rename cutover retires the
    // predecessor's service, and this process cannot retire the one it answers on — nor be the
    // successor whose gate decides it. Asked only of a spec that declares `renamed_from`, so every
    // other deployment spends nothing here.
    if (plan.renamedFrom() != null && driver.isOwnService(plan.wireAlias())) {
      finish(
          deploymentId,
          target,
          PdDeploymentStatus.FAILED,
          DeploymentDriver.selfRenameRefusal(plan.applicationName(), plan.renamedFrom()));
      return;
    }
    // The declared predecessor, read once before anything moves: null for every spec without
    // `renamed_from`, and for one whose predecessor is not deployed in this tier — see `Rename`.
    Rename rename = renameOf(plan);

    // PROVISIONING GOES HERE, between the row's STARTING transition and the pull, and the placement
    // is three decisions at once:
    //   * AFTER the transition, because the row is what a failure is recorded on — a resource that
    //     cannot be made to exist has to be readable as a failed deployment, not as a log line
    //     under a fire-and-forget intake;
    //   * BEFORE the pull, because nothing docker-side has happened yet: there is no fresh
    //     container to remove and no predecessor stopped to restart, so the failure path is a
    //     single `finish` and the previous deployment is untouched;
    //   * on plain values, because Plan is plain values — no transaction and no open session spans
    //     the DDL call to another server.
    List<DeploymentDriver.ResourceBinding> bindings;
    try {
      bindings =
          resourceProvisioning.ensureAll(
              plan.applicationName(),
              plan.environmentName(),
              // The declared predecessor, if the file named one. An application rename is a new
              // application to every keyed thing here, and the one thing that MOVES is the resource
              // claim; the only other use is the retirement announced after cutover.
              plan.renamedFrom(),
              plan.resources(),
              // A rename's FIRST deployment carries the predecessor's settings over, in the claim
              // transfer's own transaction. Not on a later one: a successor already deployed has
              // been configured since, and a key-wise copy would resurrect what was removed.
              rename != null && rename.firstDeploy()
                  ? () ->
                      settings.copyForRename(
                          plan.renamedFrom(), plan.applicationName(), plan.environmentName())
                  : null);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not provision the resources of %s: %s", plan.applicationName(), e.getMessage());
      finish(
          deploymentId,
          target,
          PdDeploymentStatus.FAILED,
          "[resource provisioning failed: " + e.getMessage() + "]");
      return;
    }

    // The registry host is DERIVED from the platform's one domain, never configured — a blank or
    // absent QITS_DOMAIN must fail this deployment loudly rather than build `registry.qits./...`.
    String resolvedRegistryHost;
    try {
      resolvedRegistryHost = registryHost.require();
    } catch (RuntimeException e) {
      finish(deploymentId, target, PdDeploymentStatus.FAILED, "[" + e.getMessage() + "]");
      return;
    }

    // The released version is the tag: `qits/<application>:<version>`, which is the coordinate the
    // release pipeline published under. Never the commit — an image tagged with a sha is what the
    // retired build trigger pulled.
    String imageRef =
        ImageRefs.imageRef(
            resolvedRegistryHost, imageRepository, plan.applicationName(), plan.version());

    // The registry having no image for a green build is an expected outcome (nothing may publish
    // this application yet) and gets its own state rather than a generic failure.
    DeploymentDriver.PullResult pulled = driver.pull(imageRef);
    switch (pulled.outcome()) {
      case IMAGE_MISSING -> {
        finish(
            deploymentId,
            target,
            PdDeploymentStatus.IMAGE_MISSING,
            "no image " + imageRef + "\n" + safe(pulled.detail()));
        return;
      }
      case AUTH_REFUSED -> {
        // A refusal is a FAILED deployment rather than IMAGE_MISSING, and the detail is why the
        // two are told apart at all: what has to be fixed is the credential the daemon reads, not
        // the pipeline that publishes the image.
        finish(
            deploymentId,
            target,
            PdDeploymentStatus.FAILED,
            "the registry refused the pull of "
                + imageRef
                + " — check the deployer's registry credential\n"
                + safe(pulled.detail()));
        return;
      }
      case ERROR -> {
        finish(deploymentId, target, PdDeploymentStatus.FAILED, safe(pulled.detail()));
        return;
      }
      case OK -> {
        /* fall through */
      }
    }

    // Named after the deployment, not the sha: re-deploying the same commit must never collide
    // with what it is about to replace. Whether that name is what the runtime uses is the driver's
    // answer, below: docker names one container per deployment, a swarm service's name IS its
    // address and a replace updates it in place.
    String deploymentName =
        ContainerNames.of(plan.environmentName(), plan.applicationName(), deploymentId);

    // Networks are re-ensured on every deployment rather than trusted from creation time — an
    // environment created while docker was down must heal, not stay broken.
    String primaryNetwork = plan.primaryNetwork();
    driver.ensureNetwork(primaryNetworkSpec(plan));
    if (plan.availableOnEnv()) {
      driver.ensureNetwork(
          new DeploymentDriver.Network(
              plan.bundleNetwork(),
              plan.environmentId(),
              DeploymentDriver.NetworkKind.BUNDLE,
              null));
    }
    // The FULL membership, primary first, handed over in one piece. It is a list rather than a
    // primary plus a join set because an orchestrator that cannot join after the fact has to
    // declare all of it at create time — every swarm `--network-add` recreates the task — and the
    // docker driver still starts on the first and joins the rest.
    List<String> networks = new ArrayList<>();
    networks.add(primaryNetwork);
    networks.addAll(desiredJoins(plan, primaryNetwork));

    DeploymentDriver.ServiceSpec spec =
        serviceSpec(
            plan,
            List.copyOf(networks),
            imageRef,
            deploymentName,
            bindings,
            rename != null && rename.live());
    // A SERVING predecessor the successor cannot start beside — its order is stop-first, it binds
    // a host port the predecessor holds, or it mounts the predecessor's private volume — is stopped
    // first. Decided here, once the spec is whole, because the published ports are the driver's
    // union of the declaration and config.
    //
    // The volume clause is qits-1772's. A pinned private volume is the SAME volume the predecessor
    // has open, and start-first would run the two side by side on it: two postgres processes on
    // one data directory, or two writers on any store that assumes it is alone. Brief downtime is
    // the price, and it is paid only by a rename of an application that keeps private data.
    boolean pinsPredecessorVolume = !plan.pinnedVolumes().isEmpty();
    boolean stopOld =
        rename != null
            && rename.live()
            && (plan.updateOrder() == DeploymentDriver.UpdateOrder.STOP_FIRST
                || pinsPredecessorVolume
                || driver.publishesPorts(spec));
    if (stopOld
        && pinsPredecessorVolume
        && plan.updateOrder() != DeploymentDriver.UpdateOrder.STOP_FIRST) {
      LOG.infof(
          "%s mounts %s pinned to its predecessor %s in %s: stop-old order forced so the two never"
              + " run on the same volume",
          plan.applicationName(),
          String.join(", ", plan.pinnedVolumes()),
          rename.predecessor(),
          plan.environmentName());
    }
    // What the runtime will call it, and therefore what the row records: asked BEFORE anything is
    // applied, so a crash in the middle leaves a STARTING row the startup sweep can still identify.
    String name = driver.nameOf(spec);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment != null) {
                deployment.containerName = name;
              }
            });

    // The replace, whole, in one call. What it costs — a predecessor stopped and restartable, a
    // hand-rolled rollback — is the docker driver's business; under swarm it is one `service
    // update` and the orchestrator's own update policy.
    if (stopOld) {
      // AFTER the pull and the argv's inputs, so everything that can fail without touching the
      // predecessor already has; BEFORE the apply, so the successor's create finds the ports free.
      stopPredecessor(plan, rename);
    }
    DeploymentDriver.ApplyResult applied = driver.apply(spec);
    switch (applied.outcome()) {
      case REFUSED -> {
        finish(
            deploymentId,
            target,
            PdDeploymentStatus.FAILED,
            stopOld
                ? restorePredecessor(plan, rename, bindings, name, safe(applied.detail()))
                : safe(applied.detail()));
        return;
      }
      case HANDED_OFF -> {
        // Deploying this component itself. The row stays STARTING on purpose: whichever instance
        // survives the succession records the outcome — the successor's sweep adopts it, a
        // rolled-back predecessor's sweep fails it.
        LOG.infof(
            "Self-update handed over: %s succeeds this instance, and the row stays STARTING until"
                + " whoever survives records it (%s)",
            name, safe(applied.detail()));
        return;
      }
      case APPLIED -> {
        /* fall through to the verdict */
      }
    }

    DeploymentDriver.Convergence converged =
        driver.awaitConverged(name, convergenceTimeout(plan));
    if (!converged.converged()) {
      // Nothing runs that did not run before: the orchestrator reverted the spec itself, and the
      // predecessor is serving again by the time this returns.
      //
      // ROLLED_BACK is that verdict kept rather than flattened. The orchestrator already answered
      // the question a reader has ("is anything serving this place?") and FAILED threw the answer
      // away — a rolled-back deployment and a refused apply asked for very different reactions and
      // read identically on the row. A convergence that failed WITHOUT a rollback keeps FAILED,
      // because there nothing is known to serve.
      if (stopOld) {
        // The predecessor was stopped for this successor, so nothing serves the place: put it back.
        finish(
            deploymentId,
            target,
            PdDeploymentStatus.FAILED,
            restorePredecessor(plan, rename, bindings, name, safe(converged.detail())));
        return;
      }
      finish(
          deploymentId,
          target,
          converged.outcome() == DeploymentDriver.ConvergenceOutcome.ROLLED_BACK
              ? PdDeploymentStatus.ROLLED_BACK
              : PdDeploymentStatus.FAILED,
          safe(converged.detail()));
      return;
    }

    // Cutover: the new deployment is the application's ACTIVE one, whatever was ACTIVE before is
    // decommissioned — rows first, then whatever is left to reap (the rows' own names and the
    // predecessors the driver retired alike; a set, since the healthy path usually sees the same
    // ones from both angles).
    //
    // THE RETRY IS NOT DECORATION HERE. This component deploys the platform's postgres, and a
    // cutover of qits-oci-postgresql kills every connection this process holds — in the middle of
    // the deployment that just performed it. The container was healthy; only the bookkeeping died,
    // and it recorded the whole deployment FAILED for it. Re-running the bracket is safe because it
    // re-reads its entities and writes them the same values.
    //
    // `inNewTx` rather than `call` around a `requiringNew`: the retry owns the transaction, so an
    // attempt that fails is one it knows never committed. The two spelled separately would retry a
    // lost commit acknowledgement as well, which is the one round trip nothing can place.
    Cutover cutover =
        DbRetry.inNewTx(
            "The cutover bookkeeping of " + deploymentId,
            () -> {
              List<String> old = new ArrayList<>();
              for (PdDeployment previous :
                  deployments.listActiveByApplication(
                      plan.applicationName(), plan.environmentId())) {
                previous.status = PdDeploymentStatus.DECOMMISSIONED;
                previous.finishedAt = Instant.now();
                if (previous.containerName != null) {
                  old.add(previous.containerName);
                }
              }
              PdDeployment deployment = deployments.findById(deploymentId);
              deployment.status = PdDeploymentStatus.ACTIVE;
              deployment.finishedAt = Instant.now();
              // Flushed here rather than left to the commit, and that is what makes the retry
              // above worth having: an ORM flushes at commit by default, which would put these
              // statements on the far side of the one round trip nothing can place. Flushed, a
              // lost connection is a body failure — certainly not committed, so certainly safe to
              // run again.
              deployments.flush();
              // The timestamp comes back out rather than being taken again afterwards: the event
              // says when the cutover happened, and a retry that ran the body twice must announce
              // the value that is on the row.
              return new Cutover(old, deployment.finishedAt);
            },
            CUTOVER_BUDGET);
    Set<String> toRemove = new LinkedHashSet<>(cutover.oldContainers());
    toRemove.addAll(converged.retired());
    // Never the thing that just went live. Under swarm that removes the whole set by itself: a
    // replace is in place, so the predecessor row names the same service this deployment applied.
    toRemove.remove(name);
    driver.reap(List.copyOf(toRemove));
    LOG.infof(
        "Deployed %s@%s into %s (%s)",
        plan.applicationName(), plan.version(), plan.environmentName(), name);
    // Last, so an unreachable qits-events delays nothing this deployment still has to do. The
    // timestamp is the row's, so announcing late does not make the event late. A declared
    // predecessor is retired first — see announceRetired for why the order is the point.
    if (plan.renamedFrom() != null && !plan.renamedFrom().isBlank()) {
      announceRetired(plan, cutover.finishedAt());
    }
    announceActive(plan, name, cutover.finishedAt());
    // ...and then the predecessor itself, every step after the frames: see retireAfterCutover.
    retireAfterCutover(plan, rename, name);
  }

  /** What the cutover bracket carries out: the containers to reap, and when it happened. */
  private record Cutover(List<String> oldContainers, Instant finishedAt) {}

  /**
   * A rename cutover (qits-1399): this deployment's spec declares {@code renamed_from: P} and P's
   * newest row in this tier says it is deployed — {@code ACTIVE} or {@code SCALED_TO_ZERO}. Null
   * for every other deployment, which then runs exactly the path it always ran.
   *
   * @param live P's newest row is {@code ACTIVE}: its service has a task serving, so the order the
   *     successor takes over in matters (start beside it, or stop it first). {@code SCALED_TO_ZERO}
   *     is a shell — the six {@code qits-platform-*} predecessors on the estate are — and the
   *     successor deploys as an ordinary deployment with the retirement after it
   * @param predecessorDeploymentId P's newest row: the one a retirement settles and, when live, the
   *     last {@code ACTIVE} deployment a failed stop-first swap restores
   * @param predecessorService the name the runtime holds P under — its row's own, the reaper's rule
   * @param firstDeploy this successor has no earlier row in this tier, so the predecessor's
   *     settings are copied onto it; a successor deployed before has been configured since
   */
  private record Rename(
      String predecessor,
      boolean live,
      String predecessorDeploymentId,
      String predecessorVersion,
      String predecessorService,
      boolean firstDeploy) {}

  /**
   * What {@link #retire} works through for one retired application, as plain values.
   *
   * @param predecessor the application being retired
   * @param successor the rename's successor — or null for an operator's retirement with none
   *     ({@link #retireApplication}), which is the only thing that changes the stamp, the log lines
   *     and whether the empty snapshot is part of the steps
   * @param successorService the service the successor runs under, never removed; null without one
   * @param retiredDeploymentId the row an operator's retirement was resolved to, for its "a later
   *     deployment took the place" guard in {@link #retirementOwed}; null for a rename
   * @param actor who asked for an operator's retirement; null for a rename, and "an operator" when
   *     an operator's caller carried no name
   */
  private record Retirement(
      String predecessor,
      String successor,
      String successorService,
      String environmentId,
      String environmentName,
      String retiredDeploymentId,
      String actor) {

    String key() {
      return environmentId + " " + predecessor;
    }

    boolean renamed() {
      return successor != null;
    }
  }

  /** See {@link Rename}: the predecessor's newest row in this tier, read in one bracket. */
  private Rename renameOf(Plan plan) {
    String predecessor = plan.renamedFrom();
    if (predecessor == null
        || predecessor.isBlank()
        || predecessor.equals(plan.applicationName())) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment newest =
                  deployments.newestForPlace(predecessor, plan.environmentId()).orElse(null);
              if (newest == null
                  || (newest.status != PdDeploymentStatus.ACTIVE
                      && newest.status != PdDeploymentStatus.SCALED_TO_ZERO)) {
                return null;
              }
              boolean firstDeploy =
                  deployments
                      .listForPlaceNewestFirst(plan.applicationName(), plan.environmentId())
                      .stream()
                      .allMatch(row -> row.id.equals(plan.deploymentId()));
              String service =
                  newest.containerName == null || newest.containerName.isBlank()
                      ? PdNetworks.alias(plan.environmentName(), predecessor)
                      : newest.containerName;
              return new Rename(
                  predecessor,
                  newest.status == PdDeploymentStatus.ACTIVE,
                  newest.id,
                  newest.imageTag(),
                  service,
                  firstDeploy);
            });
  }

  /**
   * The stop-old half of a live swap: the predecessor's service goes before the successor's is
   * created, because the successor binds what the predecessor holds (a published port) or asked to
   * never run beside its old self ({@code update_order: stop-first}). The image was pulled already,
   * so the gap is the successor's start and nothing else.
   */
  private void stopPredecessor(Plan plan, Rename rename) {
    if (rename.predecessorService().equals(plan.wireAlias())) {
      return; // never the successor's own service, whatever a row says
    }
    LOG.infof(
        "Rename cutover %s -> %s in %s: stopping %s first (%s), then creating the successor",
        rename.predecessor(),
        plan.applicationName(),
        plan.environmentName(),
        rename.predecessorService(),
        plan.updateOrder() == DeploymentDriver.UpdateOrder.STOP_FIRST
            ? "update_order: stop-first"
            : "it publishes a host port");
    try {
      driver.removeService(rename.predecessorService());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not remove %s for its successor", rename.predecessorService());
    }
  }

  /**
   * A stop-old swap whose successor did not go live: nothing serves the place, so the predecessor is
   * created again from its last {@code ACTIVE} row — the image that row ran, which is still on the
   * host (no pull), under its own name, address and network. Returns the sentence the successor's
   * row is failed with.
   *
   * <p><b>What the row cannot say is taken from the successor's spec</b>, which is the same
   * repository one release later: the health command, the order, the publish mode, the grace and the
   * declared shape. The health path and the public-node flag are the predecessor's catalogue row's.
   * The resource bindings are the predecessor's own rows, plus the successor's for a database whose
   * claim moved to it — the same role and the same password, so the predecessor reconnects exactly
   * as it ran. A private volume is renamed back to the predecessor's ({@code <application>-<name>}).
   *
   * <p>A successor this deployment CREATED (its first) is removed first: it holds the ports and the
   * old address the predecessor needs back. One that existed before is swarm's own rollback's.
   */
  private String restorePredecessor(
      Plan plan,
      Rename rename,
      List<DeploymentDriver.ResourceBinding> successorBindings,
      String successorService,
      String failure) {
    String predecessor = rename.predecessor();
    String row = rename.predecessorDeploymentId() + " (" + rename.predecessorVersion() + ")";
    if (rename.firstDeploy()) {
      try {
        driver.removeService(successorService);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not remove the failed successor %s", successorService);
      }
    }
    DeploymentDriver.ApplyResult restored;
    try {
      DeploymentDriver.ServiceSpec spec = predecessorSpec(plan, rename, successorBindings);
      driver.ensureNetwork(
          new DeploymentDriver.Network(
              PdNetworks.application(plan.environmentName(), predecessor),
              plan.environmentId(),
              DeploymentDriver.NetworkKind.APPLICATION,
              predecessor));
      restored = driver.apply(spec);
    } catch (RuntimeException e) {
      restored =
          new DeploymentDriver.ApplyResult(DeploymentDriver.ApplyOutcome.REFUSED, e.toString());
    }
    if (restored.outcome() == DeploymentDriver.ApplyOutcome.REFUSED) {
      LOG.errorf(
          "Rename cutover %s -> %s failed and %s could NOT be restored: %s",
          predecessor, plan.applicationName(), predecessor, restored.detail());
      return "the successor did not go live after its predecessor "
          + predecessor
          + " was stopped for it, and restoring "
          + predecessor
          + " from its last ACTIVE deployment "
          + row
          + " was refused — "
          + predecessor
          + " is NOT serving: "
          + safe(restored.detail())
          + "\n"
          + failure;
    }
    LOG.warnf(
        "Rename cutover %s -> %s failed; %s is restored from %s",
        predecessor, plan.applicationName(), predecessor, row);
    return "the successor did not go live after its predecessor "
        + predecessor
        + " was stopped for it, so "
        + predecessor
        + " was restored from its last ACTIVE deployment "
        + row
        + " and serves under its own name again; the rename is not cut over\n"
        + failure;
  }

  /** The predecessor's service as its last ACTIVE row ran it — see {@link #restorePredecessor}. */
  private DeploymentDriver.ServiceSpec predecessorSpec(
      Plan plan, Rename rename, List<DeploymentDriver.ResourceBinding> successorBindings) {
    String predecessor = rename.predecessor();
    Optional<LinkedService> known = catalog.find(predecessor);
    boolean availableOnEnv =
        known.map(linked -> linked.service().availableOnEnv).orElse(plan.availableOnEnv());
    String healthPath =
        known
            .map(linked -> linked.service().healthPath)
            .filter(path -> path != null && !path.isBlank())
            .orElse(plan.healthPath());

    Map<String, DeploymentDriver.ResourceBinding> bindings = new java.util.LinkedHashMap<>();
    for (DeploymentDriver.ResourceBinding own :
        resourceProvisioning.recordedBindings(predecessor, plan.environmentName())) {
      bindings.put(own.name(), own);
    }
    for (DeploymentDriver.ResourceBinding moved : successorBindings) {
      bindings.putIfAbsent(moved.name(), moved);
    }

    // Since qits-1772 a private volume under a rename already resolves to the predecessor's own
    // name, so it passes through below unchanged. Checking the predecessor's prefix first is what
    // keeps a successor whose name is a prefix of the predecessor's (qits renamed from
    // qits-x) from rewriting an already-pinned name a second time.
    List<DeploymentDriver.VolumeMount> volumes = new ArrayList<>();
    String successorPrefix = plan.applicationName() + "-";
    String predecessorPrefix = predecessor + "-";
    for (DeploymentDriver.VolumeMount volume : plan.volumes()) {
      String source =
          !volume.source().startsWith(predecessorPrefix)
                  && volume.source().startsWith(successorPrefix)
              ? predecessor + "-" + volume.source().substring(successorPrefix.length())
              : volume.source();
      volumes.add(new DeploymentDriver.VolumeMount(source, volume.target(), volume.readOnly()));
    }

    String primary = PdNetworks.application(plan.environmentName(), predecessor);
    List<String> networks = new ArrayList<>();
    networks.add(primary);
    for (String join : desiredJoins(plan, plan.primaryNetwork())) {
      if (!join.equals(primary)) {
        networks.add(join);
      }
    }
    return new DeploymentDriver.ServiceSpec(
        plan.environmentId(),
        plan.environmentName(),
        ApplicationKeys.of(plan.environmentId(), predecessor),
        predecessor,
        rename.predecessorDeploymentId(),
        rename.predecessorVersion(),
        rename.predecessorVersion(),
        ContainerNames.of(plan.environmentName(), predecessor, rename.predecessorDeploymentId()),
        PdNetworks.alias(plan.environmentName(), predecessor),
        networks,
        ImageRefs.imageRef(
            registryHost.require(), imageRepository, predecessor, rename.predecessorVersion()),
        healthPath,
        plan.healthCmd(),
        availableOnEnv,
        plan.updateOrder(),
        plan.publishMode(),
        List.copyOf(bindings.values()),
        volumes,
        plan.stopGracePeriod(),
        plan.shape(),
        null,
        false);
  }

  /**
   * The predecessor's retirement, after the successor's gate passed and both frames went out — the
   * empty snapshot that withdraws its routes, then the successor's own ({@link #announceRetired}).
   * In order, each step idempotent:
   *
   * <ol>
   *   <li>its newest row and every {@code SPEC_UNREADABLE} one become {@code DECOMMISSIONED} — the
   *       {@link ApplicationRetirement} rule, stamped {@code rename to <successor>};
   *   <li>its service is removed if it still exists (a scaled-down shell still has one), and never
   *       when the name is the successor's own;
   *   <li>its {@code pd_service} row goes, by {@link ServiceCatalog#delete} — the operator door's
   *       own path;
   *   <li>its settings in this tier ({@link Settings#retire});
   *   <li>its idp client ({@link ResourceProvisioning#retireIdpClient}) — {@code <env>-<P>}, which
   *       is never the successor's {@code <env>-<T>}.
   * </ol>
   *
   * <p><b>A failed step never fails the successor</b>, which is live and announced by now: it is a
   * WARN, the rest waits, and {@link #retryRetirements()} runs the whole list again on the next
   * observation. A restart loses that intention, and the successor's next deployment reads it back
   * off the tables — so it runs not only on a cutover but whenever the predecessor's newest row is
   * already {@code DECOMMISSIONED} and something of it is still left. Once nothing is, nothing runs.
   */
  private void retireAfterCutover(Plan plan, Rename rename, String successorService) {
    String predecessor = plan.renamedFrom();
    if (predecessor == null || predecessor.isBlank() || predecessor.equals(plan.applicationName())) {
      return;
    }
    Retirement retirement =
        new Retirement(
            predecessor,
            plan.applicationName(),
            successorService,
            plan.environmentId(),
            plan.environmentName(),
            null,
            null);
    if (rename == null && !retirementOwed(retirement)) {
      return;
    }
    if (retire(retirement)) {
      owedRetirements.remove(retirement.key());
    } else {
      owedRetirements.put(retirement.key(), retirement);
    }
  }

  /** The observation's half of {@link #retireAfterCutover}: finish what a failed step left owed. */
  void retryRetirements() {
    if (owedRetirements.isEmpty()) {
      return;
    }
    for (Retirement retirement : List.copyOf(owedRetirements.values())) {
      if (!retirementOwed(retirement) || retire(retirement)) {
        owedRetirements.remove(retirement.key());
      }
    }
  }

  /**
   * An operator's retirement of an application that has <b>no successor</b> (qits-765, the {@code
   * POST /applications/{applicationId}/retire} door) — the rename cutover's retirement with the
   * successor left out, run on this worker so it can never interleave with a cutover of the same
   * service. Package-private: {@link ApplicationRetirement} is the only caller, and it refuses the
   * deployer's own service and a place the worker still owns before it queues this.
   *
   * <p>The steps are {@link #retire}'s, in its order, plus one the rename sends from its own
   * cutover: the empty {@code DeploymentActive} that makes the edge withdraw the application's routes
   * and navigation entries ({@link #announceRetired}). So: rows {@code DECOMMISSIONED} (stamped
   * {@code [decommissioned by <actor> …]}), routes withdrawn, swarm service removed, catalogue row
   * deleted, settings retired, idp client retired unless a setting still names it.
   *
   * <p><b>The databases are kept, on purpose.</b> A rename hands its predecessor's postgres claims to
   * the successor; here there is nobody to hand them to, and the claim rows ({@code pd_resource},
   * type postgres) are deliberately left where they are. That keeps each database claimed by the
   * retired name — no other application can declare it and be handed somebody else's data — and
   * nothing in this component ever drops a database, so the data outlives the application until a
   * person decides otherwise. Only the idp client row goes, because a client is a credential and
   * not data.
   *
   * <p><b>A failed step</b> is the rename's answer: a WARN, the retirement remembered in {@link
   * #owedRetirements} and finished by {@link #retryRetirements} on the next observation — unless
   * what stopped it was a deployment of that place in flight, which {@link #retirementOwed} does
   * not count, so the operator's request is dropped rather than retried into a deployment somebody
   * queued after it. Calling the door again is the other retry, and every step is idempotent.
   */
  void retireApplication(
      String applicationName,
      String environmentId,
      String environmentName,
      String deploymentId,
      String actor) {
    Retirement retirement =
        new Retirement(
            applicationName,
            null,
            null,
            environmentId,
            environmentName,
            deploymentId,
            actor == null || actor.isBlank() ? "an operator" : actor);
    if (retire(retirement)) {
      owedRetirements.remove(retirement.key());
    } else if (retirementOwed(retirement)) {
      owedRetirements.put(retirement.key(), retirement);
    } else {
      owedRetirements.remove(retirement.key());
    }
  }

  /**
   * Whether anything of a renamed predecessor is still left in this tier — the cheap check, all of
   * it reads. A predecessor still deployed ({@code ACTIVE} or {@code SCALED_TO_ZERO}) is owed by
   * definition; one whose newest row is {@code DECOMMISSIONED} is owed while a {@code
   * SPEC_UNREADABLE} row, its catalogue row, a setting or an idp client of it remains. Anything else
   * — nothing deployed here, or a newest row in any other word — is not a rename's to retire.
   */
  private boolean retirementOwed(Retirement retirement) {
    String predecessor = retirement.predecessor();
    Boolean rows =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<PdDeployment> place =
                      deployments.listForPlaceNewestFirst(
                          predecessor, retirement.environmentId());
                  if (place.isEmpty()) {
                    return false;
                  }
                  PdDeploymentStatus newest = place.get(0).status;
                  // An operator's retirement is of the row it was resolved to. A newer deployment
                  // that is not settled DECOMMISSIONED took the place after the request, and is
                  // somebody's later intention rather than this retirement's leftover.
                  if (retirement.retiredDeploymentId() != null
                      && newest != PdDeploymentStatus.DECOMMISSIONED
                      && !place.get(0).id.equals(retirement.retiredDeploymentId())) {
                    return null;
                  }
                  if (newest == PdDeploymentStatus.ACTIVE
                      || newest == PdDeploymentStatus.SCALED_TO_ZERO) {
                    return true;
                  }
                  // ...and that row unsettled in any word the worker does not own (a FAILED or GONE
                  // row the door was asked about) is owed too: the settle step itself failed.
                  if (retirement.retiredDeploymentId() != null
                      && newest != PdDeploymentStatus.DECOMMISSIONED
                      && newest != PdDeploymentStatus.QUEUED
                      && newest != PdDeploymentStatus.STARTING) {
                    return true;
                  }
                  if (newest != PdDeploymentStatus.DECOMMISSIONED) {
                    return null;
                  }
                  return place.stream()
                      .anyMatch(row -> row.status == PdDeploymentStatus.SPEC_UNREADABLE);
                });
    if (rows == null) {
      return false;
    }
    if (rows) {
      return true;
    }
    if (catalog.find(predecessor).isPresent()) {
      return true;
    }
    if (!settings.rowsOf(predecessor, retirement.environmentName()).isEmpty()) {
      return true;
    }
    return idpClientOwed(retirement);
  }

  /**
   * Whether the predecessor holds an idp client the retirement may still delete: one that no
   * setting in the tier names. A client something still names is deliberately left — see {@link
   * #retireIdpClientUnlessReferenced} — and counting it as owed would rerun the retirement on every
   * observation and every deployment for a step that is decided, not failed. Asked fresh each time,
   * so the day the referencing setting is fixed, the successor's next deployment retires it.
   */
  private boolean idpClientOwed(Retirement retirement) {
    for (String clientId :
        resourceProvisioning.idpClientIds(retirement.predecessor(), retirement.environmentName())) {
      if (clientId.isEmpty()
          || settings.referencesTo(retirement.environmentName(), clientId).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * The retirement's last step, guarded: a predecessor's idp client that any non-secret setting in
   * the tier still names is NOT deleted, and its {@code pd_resource} row stays. Deleting it would turn
   * that application's next token request into a 401 — measured shape: qits-orchestrator's settings
   * carried its predecessor's client id, copied by hand. The step counts as done (the guard is a
   * decision), so nothing retries it; the WARN names the client, the applications and the keys, never
   * a value.
   */
  private void retireIdpClientUnlessReferenced(Retirement retirement) {
    String predecessor = retirement.predecessor();
    String environment = retirement.environmentName();
    for (String clientId : resourceProvisioning.idpClientIds(predecessor, environment)) {
      List<String> references =
          clientId.isEmpty() ? List.of() : settings.referencesTo(environment, clientId);
      if (!references.isEmpty()) {
        LOG.warnf(
            "Not retiring the idp client %s of %s in %s: the settings %s still name it, and"
                + " deleting it would refuse their next token. Point them at their own client and"
                + " %s",
            clientId,
            predecessor,
            environment,
            references,
            retirement.renamed()
                ? "the successor's next deployment retires it"
                : "retire the application again to delete it");
        return;
      }
    }
    resourceProvisioning.retireIdpClient(predecessor, environment);
  }

  /**
   * One run of the retirement's steps; true when every one of them is done. Shared by the rename
   * cutover and the operator's successor-less retirement ({@link #retireApplication}); the only step
   * that differs is the empty snapshot, which a rename sends from its cutover (before the
   * successor's own frame) and a successor-less retirement sends here, once its rows are settled and
   * before its service goes — so the edge stops routing to it before there is nothing to route to.
   * Re-sending it on a retried run is harmless: removing routes that are gone is a no-op.
   *
   * <p>No step touches the application's postgres claims or its databases, in either case: a
   * rename has already moved them to the successor, and a successor-less retirement keeps them on
   * purpose (see {@link #retireApplication}).
   */
  private boolean retire(Retirement retirement) {
    String predecessor = retirement.predecessor();
    String step = "settling its deployment rows";
    try {
      Settled settled =
          DbRetry.inNewTx(
              "The retirement of " + predecessor,
              () -> settlePredecessorRows(retirement),
              CUTOVER_BUDGET);
      if (!retirement.renamed() && settled != null) {
        step = "withdrawing its routes";
        announceRetired(
            settled.deploymentId(),
            predecessor,
            retirement.environmentId(),
            retirement.environmentName(),
            Instant.now(),
            null);
      }
      String service = settled == null ? null : settled.service();
      if (service != null && !service.equals(retirement.successorService())) {
        step = "removing its service " + service;
        driver.removeService(service);
      }
      step = "deleting its catalogue row";
      if (catalog.find(predecessor).isPresent()) {
        catalog.delete(predecessor);
      }
      step = "retiring its settings";
      settings.retire(predecessor, retirement.environmentName());
      step = "retiring its idp client";
      retireIdpClientUnlessReferenced(retirement);
    } catch (RuntimeException e) {
      if (retirement.renamed()) {
        LOG.warnf(
            "The retirement of %s (renamed to %s) in %s stopped at %s, and the next observation"
                + " tries again — %s stays live: %s",
            predecessor,
            retirement.successor(),
            retirement.environmentName(),
            step,
            retirement.successor(),
            e.getMessage());
      } else {
        LOG.warnf(
            "The retirement of %s in %s, asked for by %s, stopped at %s; the next observation"
                + " tries again unless a deployment of it is in flight: %s",
            predecessor,
            retirement.environmentName(),
            retirement.actor(),
            step,
            e.getMessage());
      }
      return false;
    }
    if (retirement.renamed()) {
      LOG.infof(
          "Retired %s in %s: it is %s now", predecessor, retirement.environmentName(),
          retirement.successor());
    } else {
      LOG.infof(
          "Retired %s in %s, asked for by %s: nothing deploys under this name any more, and its"
              + " databases are kept",
          predecessor, retirement.environmentName(), retirement.actor());
    }
    return true;
  }

  /**
   * What step one leaves the rest of a retirement: the service the runtime holds the application
   * under, and the newest row's id — the deployment a successor-less retirement's empty snapshot
   * names for traceability.
   */
  private record Settled(String service, String deploymentId) {}

  /**
   * Step one, in one transaction: the predecessor's newest row and every {@code SPEC_UNREADABLE}
   * row become {@code DECOMMISSIONED}. Answers the service name the runtime holds it under, or null
   * when it never ran here. A row the worker still owns ({@code QUEUED}/{@code STARTING}) is refused
   * — on a rename it cannot happen on this single worker; on an operator's retirement it is a
   * deployment queued after the request, and settling one would be overwritten a second later.
   */
  private Settled settlePredecessorRows(Retirement retirement) {
    List<PdDeployment> place =
        deployments.listForPlaceNewestFirst(
            retirement.predecessor(), retirement.environmentId());
    if (place.isEmpty()) {
      return null;
    }
    PdDeployment newest = place.get(0);
    if (newest.status == PdDeploymentStatus.QUEUED
        || newest.status == PdDeploymentStatus.STARTING) {
      throw new IllegalStateException(
          "the newest deployment of " + retirement.predecessor() + " is " + newest.status);
    }
    String stamp =
        retirement.renamed()
            ? "[decommissioned by rename to "
                + retirement.successor()
                + " at "
                + Instant.now()
                + ": "
                + retirement.predecessor()
                + " is "
                + retirement.successor()
                + " now — nothing deploys under this name any more, and the rows below are what"
                + " it did while it existed]"
            : "[decommissioned by "
                + retirement.actor()
                + " at "
                + Instant.now()
                + ": "
                + retirement.predecessor()
                + " is retired with no successor — its service, routes, catalogue row, settings"
                + " and idp client are removed, its databases are kept, and the rows below are"
                + " what it did while it existed]";
    for (PdDeployment row : place) {
      boolean settle =
          (row == newest && row.status != PdDeploymentStatus.DECOMMISSIONED)
              || row.status == PdDeploymentStatus.SPEC_UNREADABLE;
      if (settle) {
        row.status = PdDeploymentStatus.DECOMMISSIONED;
        row.detail = ApplicationScaling.stamped(stamp, row.detail);
        if (row.finishedAt == null) {
          row.finishedAt = Instant.now();
        }
      }
    }
    deployments.flush();
    String service =
        newest.containerName == null || newest.containerName.isBlank()
            ? PdNetworks.alias(retirement.environmentName(), retirement.predecessor())
            : newest.containerName;
    return new Settled(service, newest.id);
  }

  // `retireConvertedTierServices` lived here, and it went with the plane conversion that owed it.
  // A repository flipping to `deployment_target: platform` had its `<env>-<app>` services left
  // running, holding their aliases, ports and volumes with no row managing them — the 2026-09-07
  // `dev-qits-configuration` incident — and this removed them once the first platform deployment
  // reported healthy. There is no conversion to owe a retirement any more.
  //
  // WHAT THE SAME SHAPE WOULD HAVE ANSWERED, AND DELIBERATELY DOES NOT. Deleting the plane renames
  // nine services the other way (`qits-ci` -> `dev-qits-ci`), so each of the nine leaves a
  // bare-named predecessor behind exactly as a conversion did. It is NOT retired from here, and the
  // reason is that nothing here can name it: the owed set was read off `container_name` on rows this
  // component had itself written under the old address, and the address that changes here is the one
  // every one of those rows already records — so a retirement driven from them would remove the
  // service this deployment just created. The nine are an operator's hand step instead; AGENTS.md,
  // "Retiring the plane's bare-named services", has the commands and the order.

  private DeploymentDriver.Network primaryNetworkSpec(Plan plan) {
    return new DeploymentDriver.Network(
        plan.primaryNetwork(),
        plan.environmentId(),
        DeploymentDriver.NetworkKind.APPLICATION,
        plan.applicationName());
  }

  /**
   * Everything the driver needs, as plain values — the {@link Plan} stance carried one step
   * further, since a driver may run the spec on a thread and in a process this one does not own.
   */
  private DeploymentDriver.ServiceSpec serviceSpec(
      Plan plan,
      List<String> networks,
      String imageRef,
      String deploymentName,
      List<DeploymentDriver.ResourceBinding> bindings,
      boolean takesOverPredecessor) {
    return new DeploymentDriver.ServiceSpec(
        plan.environmentId(),
        plan.environmentName(),
        ApplicationKeys.of(plan.environmentId(), plan.applicationName()),
        plan.applicationName(),
        plan.deploymentId(),
        // TWO SLOTS, ONE VALUE TODAY: the identity the container is stamped with (`service.version`,
        // which has been the released version since the coordinate changed and whose field kept its
        // older name), and the released coordinate itself.
        plan.version(),
        plan.version(),
        deploymentName,
        plan.wireAlias(),
        networks,
        imageRef,
        plan.healthPath(),
        plan.healthCmd(),
        plan.availableOnEnv(),
        plan.updateOrder(),
        plan.publishMode(),
        bindings,
        // The storage the repository declared, names already resolved at registration. It rides
        // beside the resource bindings because it is the same kind of fact — what this application
        // needs to exist before it can serve — and, unlike the extras, it is the application's own
        // statement rather than the platform's.
        plan.volumes(),
        plan.stopGracePeriod(),
        // ...and the host-facing half the repository declared beside them. The driver unions it
        // with deployment config's mounts, publishes and aliases while both sources are live.
        plan.shape(),
        // The declared predecessor, from which the driver derives the old wire address as an
        // alias; null for every spec without `renamed_from`, which renders exactly what it did.
        plan.renamedFrom(),
        takesOverPredecessor);
  }

  /**
   * How long {@code awaitConverged} may wait for this deployment's verdict: {@code
   * qits.deployments.health-timeout-seconds}, plus whatever the update order makes the orchestrator
   * spend waiting out a stopping task before the health gate even begins.
   *
   * <p><b>Under {@code start-first} that is nothing</b>, however long the grace. Swarm's updater
   * starts the successor, waits for it to be RUNNING (which, with a healthcheck, means healthy),
   * and then only marks the predecessor's desired state SHUTDOWN and moves on — it never waits for
   * the old task to exit. The {@code --update-monitor} window that follows watches the NEW task,
   * and {@code completed} is written when it ends. So the predecessor drains its transfers for as
   * long as the grace allows while the update is already {@code completed} and the row already
   * {@code ACTIVE}. A rollback under {@code start-first} is the same shape in reverse.
   *
   * <p><b>Under {@code stop-first} it is the grace, twice.</b> The updater stops the predecessor and
   * does not start the successor until that task has actually exited — up to the whole grace for a
   * task still draining a download — and a successor that then fails its gate is itself stopped,
   * under the same order and with the same grace, before the predecessor's spec is started again.
   * Neither wait is a symptom; both are swarm doing exactly what it was told. A deadline that did not
   * count them would turn swarm's verdict into this process's timeout and throw the diagnosis away,
   * which is the failure {@code health-timeout-seconds}'s own comment describes.
   */
  private Duration convergenceTimeout(Plan plan) {
    Duration timeout = Duration.ofSeconds(healthTimeoutSeconds);
    if (plan.updateOrder() == DeploymentDriver.UpdateOrder.STOP_FIRST
        && plan.stopGracePeriod() != null) {
      timeout = timeout.plus(plan.stopGracePeriod().multipliedBy(2));
    }
    return timeout;
  }

  /**
   * Every network this deployment belongs on beyond its primary one — a membership the driver
   * declares at create time or joins after the start, whichever its orchestrator can do.
   *
   * <ul>
   *   <li>the legacy network, while {@code qits.deployments.legacy-network} names one —
   *       the transition membership that keeps today's direct cross-application URLs resolving;
   *   <li>a public node ({@code availableOnEnv}) additionally joins its environment's bundle and
   *       <b>every</b> per-application network of that environment: that is the hub, and it is how
   *       an application reaches the gateway and how the gateway proxies every application.
   * </ul>
   *
   * <p><b>A third bullet went with the platform plane</b>: a platform service joined every
   * per-application network of every environment, which was the membership spelling of "locally
   * reachable everywhere". Nothing is reachable everywhere by construction now; a service another
   * tier needs is reached through the gateway like any other.
   */
  private List<String> desiredJoins(Plan plan, String primaryNetwork) {
    Set<String> joins = new LinkedHashSet<>();
    legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).ifPresent(joins::add);
    if (plan.availableOnEnv()) {
      joins.add(plan.bundleNetwork());
      for (DeploymentDriver.Network network : driver.networks()) {
        if (network.kind() == DeploymentDriver.NetworkKind.APPLICATION
            && plan.environmentId().equals(network.environmentId())) {
          joins.add(network.name());
        }
      }
    }
    joins.remove(primaryNetwork);
    return List.copyOf(joins);
  }

  /**
   * Record the outcome. Retried on a connection-class failure for the same reason the cutover
   * bookkeeping is: this is the last write of a deployment that may have just replaced the database
   * it writes to, and an outcome nobody could record is a row that says {@code STARTING} forever.
   *
   * <p><b>The one funnel every recorded failure goes through, which is why the announcement lives
   * here</b> rather than at the nine call sites that reach it. It announces only when the written
   * status is not {@code ACTIVE} — the same test the WARN already made, and nothing calls this with
   * {@code ACTIVE} today; the happy path announces from the cutover, which knows things this does
   * not.
   *
   * <p><b>The word travels.</b> {@code DeploymentFailed} already carries the status as a string, so
   * a {@code ROLLED_BACK} outcome announces that word and consumers that only care "this did not go
   * live" are unaffected — which is why refining the vocabulary needed no fifth event.
   *
   * <p><b>No row, no event.</b> A deployment whose environment was torn down mid-deploy has nothing
   * to record and nothing to announce: the bracket returns null and this returns without publishing.
   *
   * <p>{@code target} is the caller's, and it is the only thing here the row cannot supply — {@code
   * pd_deployment} holds the environment's ID and never its NAME. Every call site already has one.
   */
  private void finish(
      String deploymentId, Target target, PdDeploymentStatus status, String detail) {
    Finished finished =
        DbRetry.inNewTx(
            "Recording deployment " + deploymentId + " as " + status,
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment == null) {
                return null; // environment torn down mid-deploy
              }
              deployment.status = status;
              deployment.detail = detail;
              deployment.finishedAt = Instant.now();
              // statement phase, so a lost connection is retriable — see the cutover bracket
              deployments.flush();
              return new Finished(
                  deployment.imageTag(),
                  deployment.commitSha,
                  deployment.runId,
                  deployment.causationId,
                  deployment.finishedAt);
            },
            CUTOVER_BUDGET);
    if (status != PdDeploymentStatus.ACTIVE) {
      LOG.warnf("Deployment %s ended %s: %s", deploymentId, status, firstLine(detail));
      if (finished != null) {
        announceFailed(deploymentId, target, status, detail, finished);
      }
    }
  }

  /** What the outcome bracket carries out — the row's own values, for the announcement. */
  private record Finished(
      String version, String commitSha, String runId, UUID cause, Instant finishedAt) {}

  /**
   * The four announcements, each called after the transaction that made its statement true.
   *
   * <p><b>They are wrapped, every one of them, and that is the rule rather than caution.</b> An
   * announcement is a statement about a deployment, never part of it: a bus that is unreachable, a
   * serializer that refuses a value, an implementation with a bug must all cost the log line below
   * and nothing else. The port already says an implementation must not throw; this is what makes a
   * broken one survivable.
   *
   * <p>Every announcer is offered the event — {@code Instance} is a set, usually of one and validly
   * of none — and one that throws does not stop the next.
   */
  private void announceQueued(Queued row, Release release) {
    announce(
        row.deploymentId(),
        announcer ->
            announcer.onQueued(
                new DeploymentQueued(
                    row.deploymentId(),
                    row.target().applicationName(),
                    row.target().environmentId(),
                    row.target().environmentName(),
                    release.version(),
                    release.commitSha(),
                    release.runId(),
                    row.queuedAt()),
                release.causationId()));
  }

  private void announceStarted(Plan plan) {
    announce(
        plan.deploymentId(),
        announcer ->
            announcer.onStarted(
                new DeploymentStarted(
                    plan.deploymentId(),
                    plan.applicationName(),
                    plan.environmentId(),
                    plan.environmentName(),
                    plan.version(),
                    plan.commitSha(),
                    plan.runId(),
                    plan.startedAt()),
                plan.cause()));
  }

  private void announceActive(Plan plan, String containerName, Instant finishedAt) {
    announce(
        plan.deploymentId(),
        announcer ->
            announcer.onActive(
                new DeploymentActive(
                    plan.deploymentId(),
                    plan.applicationName(),
                    plan.environmentId(),
                    plan.environmentName(),
                    plan.version(),
                    plan.commitSha(),
                    plan.runId(),
                    containerName,
                    finishedAt,
                    plan.browserHost(),
                    plan.apiDocsPath(),
                    plan.navigation(),
                    plan.endpoints()),
                plan.cause()));
  }

  /**
   * The {@code renamed_from} predecessor's routes, withdrawn: a {@code DeploymentActive} in the
   * predecessor's name carrying an explicitly EMPTY endpoint snapshot, which the edge reads as
   * removal.
   *
   * <p><b>Nothing else would ever say it.</b> A rename is a new application to every keyed thing
   * here, so the predecessor never deploys again and its last snapshot is never replaced — and the
   * edge rebuilds its table by replaying the stream from epoch, so a route cleaned up by hand comes
   * straight back on the next restart. Left alone, the edge keeps serving the predecessor's paths
   * and refuses the successor's own frame as a conflict with them, for good.
   *
   * <p><b>It precedes the successor's frame, and the order is the fix.</b> The successor declares
   * the paths and host the predecessor held, so until the predecessor is retired those are a
   * conflict; and log order is exactly what a from-epoch replay walks, so the retirement has to sit
   * before the frame it unblocks every time the log is read, not just this once.
   *
   * <p><b>Every field that could claim something is empty or null, each for a reason.</b> No
   * {@code version}: qits-projects skips a versionless {@code DeploymentActive}, so no release is
   * finalized for an application that was not deployed. No {@code browserHost} and no {@code
   * apiDocsPath}: a host with no endpoints is refused by the edge, and a refused frame would poison
   * the very removal it carries. No commit, run or container, because none of them is the
   * predecessor's. The {@code deploymentId} is the successor's, for traceability only, and so is
   * the cause — the retirement is a consequence of this deployment and says so.
   *
   * <p><b>It is sent on every cutover, not once.</b> It is idempotent — removing routes that are
   * already gone is a no-op — so repeating it costs a frame, and it heals the rare case where an
   * outbox fallback published it after the successor's frame: the next cutover puts it back in
   * front. {@link #announceAdopted} deliberately does not send it: an adoption re-announces the
   * snapshot a row recorded and claims nothing that row's own deployment did not, and the next
   * cutover sends the retirement anyway.
   */
  private void announceRetired(Plan plan, Instant finishedAt) {
    announceRetired(
        plan.deploymentId(),
        plan.renamedFrom(),
        plan.environmentId(),
        plan.environmentName(),
        finishedAt,
        plan.cause());
  }

  /**
   * The empty snapshot itself, for any retired application — the rename's predecessor above, and
   * an operator's successor-less retirement ({@link #retire}), which names the retired
   * application's own newest row and no cause: nothing on the bus asked for it, a person did.
   */
  private void announceRetired(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      Instant finishedAt,
      UUID cause) {
    announce(
        deploymentId,
        announcer ->
            announcer.onActive(
                new DeploymentActive(
                    deploymentId,
                    applicationName,
                    environmentId,
                    environmentName,
                    null,
                    null,
                    null,
                    null,
                    finishedAt,
                    null,
                    null,
                    List.of(),
                    List.of()),
                cause));
  }

  private void announceFailed(
      String deploymentId,
      Target target,
      PdDeploymentStatus status,
      String detail,
      Finished finished) {
    announce(
        deploymentId,
        announcer ->
            announcer.onFailed(
                new DeploymentFailed(
                    deploymentId,
                    target.applicationName(),
                    target.environmentId(),
                    target.environmentName(),
                    finished.version(),
                    finished.commitSha(),
                    finished.runId(),
                    status.name(),
                    detail,
                    finished.finishedAt()),
                finished.cause()));
  }

  /** See {@link #announceQueued} for why every one of these is wrapped. */
  private void announce(String deploymentId, Consumer<DeployAnnouncer> announcement) {
    for (DeployAnnouncer announcer : announcers) {
      try {
        announcement.accept(announcer);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing deployment %s failed", deploymentId);
      }
    }
  }

  /** An environment's deployments across all its applications, newest-first. */
  public List<PdDeployment> deploymentsFor(String environmentId) {
    return deployments.listByEnvironmentNewestFirst(environmentId);
  }

  /**
   * A deployment request together with the deployment it handed off to, or {@code null} where there
   * is none.
   *
   * <p><b>Null is a real answer and is the whole reason this pair exists as a type.</b> A request
   * the gate refused queued nothing, so it points at no row; so does one whose deployment an
   * environment teardown has since forgotten. Both are "asked for, and nothing ran", which is
   * exactly what the request table exists to be able to say — and neither is an error the reader has
   * to be protected from.
   */
  public record RequestWithDeployment(PdDeploymentRequest request, PdDeployment deployment) {}

  /**
   * An environment's deployment REQUESTS, newest-first — what was asked for here, whatever the gate
   * said and whatever became of it.
   *
   * <p>It is the listing beside {@link #deploymentsFor}, and it is a separate question rather than a
   * richer answer to that one: a request the gate refused produced no deployment at all, so it is
   * invisible in the deployment listing by construction. {@code applicationName} narrows it to one
   * service and is optional — a null asks about the whole tier.
   */
  public List<RequestWithDeployment> deploymentRequestsFor(
      String environmentId, String applicationName) {
    return withDeployments(
        applicationName == null || applicationName.isBlank()
            ? requests.listByEnvironmentNewestFirst(environmentId)
            : requests.listByEnvironmentAndApplicationNewestFirst(environmentId, applicationName));
  }

  /**
   * One PROJECT's deployment requests: <b>every one still moving, and the ten most recent that are
   * not</b>, merged back into one newest-first list.
   *
   * <p>The cap is here and not on the client, and that is the decision worth carrying. A project
   * that has been releasing for a year has thousands of settled requests and a reader has never once
   * wanted them; what a project screen is for is "what is happening right now, and what happened
   * last". So the pending half is complete — a release the platform has not finished with must never
   * be the one the cap dropped — and the completed half is the ten newest. A client asked to do this
   * would be one that downloads the year and throws it away.
   *
   * <p>{@link RequestLifecycle} is what decides which half a row is in, and the deployments it needs
   * are batch-loaded rather than fetched per row.
   *
   * <p>An unknown project answers with an empty list. This component holds no project rows and
   * resolves none — see {@code PdDeploymentRequestRepository.listByProjectNewestFirst}.
   */
  public List<RequestWithDeployment> projectDeploymentRequests(String projectId) {
    List<RequestWithDeployment> joined =
        withDeployments(requests.listByProjectNewestFirst(projectId));
    List<RequestWithDeployment> answer = new ArrayList<>();
    int completed = 0;
    for (RequestWithDeployment entry : joined) {
      if (RequestLifecycle.isPending(entry.request(), entry.deployment())) {
        answer.add(entry);
      } else if (completed < PROJECT_COMPLETED_LIMIT) {
        completed++;
        answer.add(entry);
      }
    }
    // The source list is already `seq desc` and both halves are taken from it in order, so appending
    // preserves that ordering — no re-sort, and no second definition of "newest".
    return List.copyOf(answer);
  }

  /**
   * Every request written for one released version of one repository, newest-first.
   *
   * <p>It is the edge a release page follows the other way: qits-projects holds a released version
   * and a repository and wants to know what this platform did with it. The pair is {@code (repoId,
   * version)} because that is what the far side has — the application name may be the repository's
   * or the spec's {@code application:} override, and only this table knows which.
   *
   * <p>Empty is a real and common answer: a library or an SPA releases a version that deploys
   * nothing at all, and there is no request row for it anywhere.
   */
  public List<RequestWithDeployment> deploymentRequestsByRelease(String repoId, String version) {
    return withDeployments(requests.listByRepoAndVersionNewestFirst(repoId, version));
  }

  /** One request by its own id, with the deployment it produced. Empty for an id nothing wrote. */
  public Optional<RequestWithDeployment> deploymentRequestById(String id) {
    return requests
        .findByIdOptional(id)
        .map(request -> withDeployments(List.of(request)).get(0));
  }

  /**
   * The request → deployment join, in one extra query for the whole listing.
   *
   * <p>Plain request-thread Panache, like every other read on this surface: no worker hop and no
   * transaction bracket. Those exist for the reads the deploy worker makes, where there is no
   * request context to open a session in; a REST read already has one, and the patience it needs is
   * {@code PdReadPatience}'s, one layer up in the controller.
   */
  private List<RequestWithDeployment> withDeployments(List<PdDeploymentRequest> rows) {
    Set<String> ids = new HashSet<>();
    for (PdDeploymentRequest request : rows) {
      if (request.deploymentId != null) {
        ids.add(request.deploymentId);
      }
    }
    Map<String, PdDeployment> byId = new HashMap<>();
    for (PdDeployment deployment : deployments.listByIds(ids)) {
      byId.put(deployment.id, deployment);
    }
    return rows.stream()
        .map(
            request ->
                new RequestWithDeployment(
                    request, request.deploymentId == null ? null : byId.get(request.deploymentId)))
        .toList();
  }

  /** Drop this environment's recorded deployments — the first step of a teardown. */
  public void forgetEnvironment(String environmentId) {
    QuarkusTransaction.requiringNew()
        .run(() -> deployments.delete("environmentId = ?1", environmentId));
  }

  private static boolean isDeployableName(String applicationName) {
    try {
      PdIdentifiers.requireName(applicationName, "application name");
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String safe(String detail) {
    return detail == null ? "" : detail;
  }

  private static String firstLine(String output) {
    if (output == null || output.isBlank()) {
      return "(no detail)";
    }
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }

  /**
   * Test hook: waits for the work queued at this moment to drain. Public because the whole of an
   * event runs on the worker — registration included — so a suite that asserts "nothing was
   * registered" has to be able to wait for the worker rather than for a row that never appears.
   */
  public void awaitIdle() throws Exception {
    worker.submit(() -> {}).get();
  }
}
