package eu.wohlben.qits.deployments.deployments.control;

import static eu.wohlben.qits.deployments.deployments.control.DeployService.CUTOVER_BUDGET;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The second half of the eaa34fbc story. A deployment's status is written once, by the deployment
 * that earned it, and until this class existed it was never read back against the world: row
 * eaa34fbc ended {@code FAILED: [unexpected: JDBCConnectionException …]} because the post-gate
 * bookkeeping ran on connections its own cutover of qits-oci-postgresql had just killed, while the
 * container it started stayed {@code Up (healthy)} for hours holding the application's alias.
 * {@link DbRetry} fixed the CAUSE; a row that ended {@code FAILED} still stays {@code FAILED}
 * forever, and the mirror image — an {@code ACTIVE} row whose container died an hour after the gate
 * passed — was never noticed at all.
 *
 * <p>So this is a periodic observation, not a second deploy path. <b>It writes rows only.</b> It
 * reaps no container, starts no container and touches no network — the startup sweep's "deliberately
 * reaps no containers" stance, which applies here more strongly than there: the sweep at least runs
 * once, at a moment nothing else is happening, while this runs beside a live platform forever.
 *
 * <p><b>It settles the LATEST row per (application, tier) and nothing else.</b> Latest by {@code
 * seq}, the documented ordering — {@code createdAt} is not unique and the id is a random UUID.
 * History stays history: an older {@code FAILED} row describes an attempt that really did fail, and
 * a healthy container today says nothing about it. {@code QUEUED} and {@code STARTING} belong to the
 * worker's own state machine (the intake queued them, the sweep fails them after a crash) and
 * {@code DECOMMISSIONED} is a decision another deployment made; none of the three is observable from
 * a container's state.
 *
 * <p><b>Three transitions, and each is deliberately narrow.</b>
 *
 * <ul>
 *   <li>{@code FAILED}, {@code GONE} or {@code SCALED_TO_ZERO} → {@code ACTIVE} when the container
 *       <b>the row itself names</b> exists, runs
 *       and is healthy by {@link HealthGate#healthy} — the gate's own verdict, so a recovery cannot
 *       mean something a health gate would have refused. Only the row's own container counts: a
 *       healthy container of some other deployment must never resurrect a foreign row, which is why
 *       this asks docker about {@code containerName} rather than about the alias.
 *   <li>{@code ACTIVE} → {@code GONE} when the container is <b>absent or terminally exited</b> on
 *       {@value #STRIKES_TO_DEMOTE} consecutive passes. Patience is inherited from the health gate
 *       rather than reinvented: <b>restarting is not dead</b> and <b>running-but-unhealthy is not
 *       dead</b> — that is the postgres-alias boot race the gate already tolerates, and a container
 *       coming back from it must not be declared failed on the way. The second pass is the belt for
 *       a docker hiccup: one {@code inspect} that could not answer must never flip a deployment that
 *       is serving.
 *   <li>{@code ACTIVE} → {@code SCALED_TO_ZERO} when the place is empty and the orchestrator says it
 *       is <b>declared</b> empty — {@link DeploymentDriver#desiredReplicas}. This one takes no
 *       strikes and needs none: it is not a guess about a container that might come back, it is the
 *       intent read off the runtime that holds it.
 * </ul>
 *
 * <p><b>The third arm is what keeps this class from fighting an operator</b>, and it is the reason
 * it was added at all. An application scaled to zero — through {@code ApplicationScaling}, or by
 * hand with {@code docker service scale} — runs no task, so every reading here says "gone": without
 * the desired count this pass would demote the row two ticks after somebody deliberately stopped
 * the application, report an outage that is not one, and then keep saying so for as long as the
 * pause lasted. The count is asked <b>only of a candidate that already looks dead</b>, so a healthy
 * platform pays nothing for it.
 *
 * <p><b>A recovery also retires the predecessors it never got to retire.</b> The bookkeeping that
 * died in eaa34fbc was one bracket doing two things — decommission the prior {@code ACTIVE} rows of
 * this (application, tier) and mark this one {@code ACTIVE} — so a row recovered here may well have
 * an older row still claiming to serve. Leaving it would break the invariant {@code
 * PdDeploymentRepository#listActiveByApplication} is written around (at most one ACTIVE per place),
 * and the rollback pins read off it. Their containers are NOT removed, exactly as the sweep does not
 * remove them: whatever still holds the alias is absorbed as a predecessor by the next deployment.
 *
 * <p>The pass runs <b>on the deploy worker</b> ({@code pd-deploy-worker}), enqueued by {@link
 * DeployService}, which is the whole reason it can read these rows at all without racing a cutover —
 * see that class for the collapse rule. Its own shape follows the worker's: read the candidates in
 * one transaction, copy them out as plain values, ask docker outside any transaction, then write each
 * settled row in its own bracket. Every one of those brackets is a {@link DbRetry#inNewTx} — the
 * retry owns the transaction, so it retries only attempts that certainly did not commit. Retried for
 * the same reason the cutover bookkeeping is: this is bookkeeping that runs <i>after</i> a container
 * is running, and one day it will run during a postgres self-cutover.
 */
@ApplicationScoped
public class DeploymentObserver {

  private static final Logger LOG = Logger.getLogger(DeploymentObserver.class);

  /**
   * How many consecutive passes must agree that an {@code ACTIVE} row's container is gone before the
   * row is demoted. Two, because one docker call that could not answer — a daemon reloading, an
   * {@code inspect} that timed out — must not take a serving deployment's row with it.
   */
  static final int STRIKES_TO_DEMOTE = 2;

  /** Docker container statuses that are not coming back. Everything else is patience. */
  private static final Set<String> TERMINAL_STATUSES = Set.of("exited", "dead");

  /**
   * The words a healthy container takes back. Every one of them is a statement this class or a
   * deployment made about a place being down, and a demotion that could not be undone is a status
   * this pass could write and never retract — which {@code GONE}'s own javadoc argues at length and
   * {@link PdDeploymentStatus#SCALED_TO_ZERO} inherits for the same reason: scaling back up is what
   * ends a pause, and nothing else in this component would notice that it had.
   */
  private static final Set<PdDeploymentStatus> RECOVERABLE =
      Set.of(
          PdDeploymentStatus.FAILED,
          PdDeploymentStatus.GONE,
          PdDeploymentStatus.SCALED_TO_ZERO);

  @Inject PdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;

  /** The announcement a recovered {@code FAILED} row owes — see {@link DeployService#announceRecovered}. */
  @Inject DeployService deployService;

  /** What one recovery bracket did: the rows it retired, and whether the row it flipped was FAILED. */
  private record Recovery(List<String> retired, boolean fromFailed) {}

  /**
   * Consecutive dead observations per deployment id. In memory on purpose: it is a debounce, not a
   * fact about the world, and a restart that loses it simply spends two more passes agreeing. Keyed
   * by deployment id and pruned to the candidates of the latest pass, so it cannot grow with the
   * history. A concurrent map rather than a plain one because the pass and a shutdown are different
   * threads, not because two passes ever overlap — they cannot, the worker is single-threaded.
   */
  private final Map<String, Integer> strikes = new ConcurrentHashMap<>();

  /** One row worth observing, as plain values — the {@code Plan} stance: never an entity. */
  private record Candidate(
      String deploymentId,
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      String detail) {}

  /**
   * One observation pass. Package-private so the suite drives it without the tick, exactly as {@link
   * DeployService#sweepInFlight()} is driven without a real StartupEvent.
   */
  void observeOnce() {
    List<Candidate> candidates =
        DbRetry.inNewTx(
            "The observation pass's candidate read", this::candidates, CUTOVER_BUDGET);
    Set<String> seen = new HashSet<>();
    for (Candidate candidate : candidates) {
      seen.add(candidate.deploymentId());
      // Outside every transaction: a docker call is a child process, and no bracket of this
      // component's own may span one.
      HealthGate.Poll observed = driver.observe(candidate.containerName());
      if (candidate.status() != PdDeploymentStatus.ACTIVE) {
        // FAILED, GONE or SCALED_TO_ZERO — every word this pass can write, it can take back.
        if (HealthGate.healthy(observed)) {
          recover(candidate, observed);
        }
        continue;
      }
      if (dead(observed)) {
        if (deliberatelyStopped(candidate)) {
          // Not a death: somebody scaled this application to zero, here or by hand on the host.
          // Recorded once and then left alone — the strike count is cleared, so a place that is
          // deliberately empty never accumulates toward a demotion it would deserve if it were not.
          pause(candidate);
          strikes.remove(candidate.deploymentId());
          continue;
        }
        int strike = strikes.merge(candidate.deploymentId(), 1, Integer::sum);
        if (strike >= STRIKES_TO_DEMOTE) {
          demote(candidate, observed);
          strikes.remove(candidate.deploymentId());
        } else {
          LOG.debugf(
              "%s looks %s, and one pass is not a verdict — waiting for a second",
              candidate.containerName(), describe(observed));
        }
      } else {
        // Restarting, unhealthy, paused, created: all of them are the health gate's PENDING, and a
        // container that answered at all clears whatever the last pass thought.
        strikes.remove(candidate.deploymentId());
      }
    }
    strikes.keySet().retainAll(seen);
  }

  /**
   * The latest row of each (application, tier) that a container's state can say anything about: an
   * {@code ACTIVE} one that should still be serving, or a demoted one — {@code FAILED} or {@code
   * GONE} — that named a container.
   *
   * <p>The whole history is read and reduced here rather than asked of SQL, which is the same trade
   * {@code RollbackPins} makes: one ordered scan of a table with one row per deployment ever, and
   * the (application, tier) pair — whose tier half is null on the platform plane — is grouped in
   * Java where a null is an ordinary value rather than something {@code =} silently drops.
   */
  private List<Candidate> candidates() {
    List<Candidate> candidates = new ArrayList<>();
    Set<String> latestSeen = new HashSet<>();
    for (PdDeployment row : deployments.listAllNewestFirst()) {
      // Environment ids are UUIDs, so the empty string cannot collide with one.
      if (!latestSeen.add(
          row.applicationName + " " + (row.environmentId == null ? "" : row.environmentId))) {
        continue; // not the latest for its place — history, and history stays history
      }
      if (row.containerName == null || row.containerName.isBlank()) {
        continue; // nothing to observe: this row never got as far as a `docker run`
      }
      if (row.status == PdDeploymentStatus.ACTIVE
          || row.status == PdDeploymentStatus.FAILED
          || row.status == PdDeploymentStatus.GONE
          // A stopped place is watched too, and only so it can come BACK: scaling up is the one
          // event that ends this state, and nothing else in this component would notice it.
          || row.status == PdDeploymentStatus.SCALED_TO_ZERO) {
        candidates.add(
            new Candidate(
                row.id, row.applicationName, row.environmentId, row.status, row.containerName,
                row.detail));
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * A demoted row — {@code FAILED} or {@code GONE} — whose container is in fact serving. The
   * original failure text is <b>appended, never erased</b> — it is the diagnosis of what went wrong
   * at deploy time, and eaa34fbc is exactly the row where that text is the whole reason anybody
   * found the bug.
   *
   * <p>Every word this class can write recovers, and that is the rule rather than a convenience:
   * {@code GONE} and {@code SCALED_TO_ZERO} are both its own, so a container that comes back has to
   * be able to undo either. A recovery arm that healed only {@code FAILED} would leave the
   * observation able to write a status it could never put back — and for a paused application the
   * scale back up is the ONLY thing that ever ends the state.
   */
  private void recover(Candidate candidate, HealthGate.Poll observed) {
    Instant at = Instant.now();
    Recovery recovery =
        DbRetry.inNewTx(
            "The observed recovery of deployment " + candidate.deploymentId(),
            () -> {
              PdDeployment row = deployments.findById(candidate.deploymentId());
              if (row == null || !RECOVERABLE.contains(row.status)) {
                // deleted, or already settled by a deployment
                return new Recovery(List.of(), false);
              }
              boolean fromFailed = row.status == PdDeploymentStatus.FAILED;
              List<String> stale = new ArrayList<>();
              for (PdDeployment previous :
                  deployments.listActiveByApplication(
                      candidate.applicationName(), candidate.environmentId())) {
                if (previous.id.equals(row.id)) {
                  continue;
                }
                previous.status = PdDeploymentStatus.DECOMMISSIONED;
                previous.finishedAt = at;
                stale.add(previous.id);
              }
              row.status = PdDeploymentStatus.ACTIVE;
              row.detail = recoveryDetail(candidate, observed, at);
              row.finishedAt = at;
              // Flushed rather than left to the commit: an ORM flushes at commit by default, which
              // would put these statements on the far side of the one round trip nothing can place.
              // Flushed, a lost connection is a body failure — certainly not committed, so safe to
              // run again.
              deployments.flush();
              return new Recovery(List.copyOf(stale), fromFailed);
            },
            CUTOVER_BUDGET);
    List<String> retired = recovery.retired();
    LOG.infof(
        "Recovered deployment %s by observation: %s is %s, so the row that said %s was wrong%s",
        candidate.deploymentId(),
        candidate.containerName(),
        describe(observed),
        retired.isEmpty() ? "" : " (retired " + retired.size() + " row(s) it never decommissioned)");
    if (recovery.fromFailed()) {
      // A FAILED deployment never reached its cutover's DeploymentActive, so nobody has said it is
      // live — and a release request's deployment gate waits on exactly that (qits-2234). Only the
      // bracket that flipped the row gets here, so it is said once. GONE and SCALED_TO_ZERO rows
      // were ACTIVE, and announced, before.
      deployService.announceRecovered(candidate.deploymentId(), at);
    }
  }

  /**
   * Whether the place is empty <b>because somebody asked for it to be</b> — the orchestrator's own
   * desired task count, read only for a candidate that already looks dead.
   *
   * <p>It is asked here rather than for every candidate on every pass because it is the expensive
   * half of the question and the rare one: an application that is up answers {@link #dead} false and
   * never reaches this line, so a healthy platform costs exactly what it costed before.
   *
   * <p><b>Only a confident zero counts.</b> A runtime that cannot answer — no such service, a daemon
   * that timed out, a template that printed something unparseable — is not a deliberate stop, and
   * treating it as one would silence the demotion this class exists to make: an outage would read as
   * a pause forever, which is the exact failure in the opposite direction.
   */
  private boolean deliberatelyStopped(Candidate candidate) {
    OptionalInt declared = driver.desiredReplicas(candidate.containerName());
    return declared.isPresent() && declared.getAsInt() == 0;
  }

  /**
   * An {@code ACTIVE} row whose place the orchestrator is declared to keep empty.
   *
   * <p>Written <b>once</b>: the row is already {@code SCALED_TO_ZERO} on every later pass and is
   * then a recovery candidate rather than a demotion one, so a stopped application does not re-write
   * its row every thirty seconds. It is the mirror of {@link #demote} and reaches the same place by
   * a different word, which is the whole point — {@code GONE} asks for a person and this asks for
   * nothing.
   */
  private void pause(Candidate candidate) {
    Instant at = Instant.now();
    DbRetry.runInNewTx(
        "The observed pause of deployment " + candidate.deploymentId(),
        () -> {
          PdDeployment row = deployments.findById(candidate.deploymentId());
          if (row == null || row.status != PdDeploymentStatus.ACTIVE) {
            return; // deleted, or settled by a deployment while this pass ran
          }
          row.status = PdDeploymentStatus.SCALED_TO_ZERO;
          row.detail = pauseDetail(candidate, at);
          deployments.flush(); // statement phase, so a lost connection is retriable — see recover()
        },
        CUTOVER_BUDGET);
    LOG.infof(
        "Deployment %s was ACTIVE and %s is declared to run 0 tasks — recorded SCALED_TO_ZERO."
            + " Nothing was touched: scaling it back up recovers the row on the next pass.",
        candidate.deploymentId(), candidate.containerName());
  }

  /** The pause stamp, with whatever the row already said kept under it. */
  private static String pauseDetail(Candidate candidate, Instant at) {
    String stamp =
        "[scaled to 0 as observed at "
            + at
            + ": "
            + candidate.containerName()
            + " is declared to run no tasks, so this place is stopped rather than gone]";
    return candidate.detail() == null || candidate.detail().isBlank()
        ? stamp
        : stamp + "\n" + candidate.detail();
  }

  /**
   * An {@code ACTIVE} row whose container two passes agree is gone.
   *
   * <p><b>It writes {@code GONE} rather than {@code FAILED}</b>, and the distinction is the whole
   * reason the word exists: this deployment succeeded — it passed its gate and served — and what
   * failed is the place, minutes or hours later. Spelled {@code FAILED} it read as an indictment of
   * a build that was fine, beside rows where the build really was the problem.
   */
  private void demote(Candidate candidate, HealthGate.Poll observed) {
    Instant at = Instant.now();
    DbRetry.runInNewTx(
        "The observed failure of deployment " + candidate.deploymentId(),
        () -> {
          PdDeployment row = deployments.findById(candidate.deploymentId());
          if (row == null || row.status != PdDeploymentStatus.ACTIVE) {
            return; // deleted, or replaced by a deployment while this pass ran
          }
          row.status = PdDeploymentStatus.GONE;
          row.detail = failureDetail(candidate, observed, at);
          row.finishedAt = at;
          deployments.flush(); // statement phase, so a lost connection is retriable — see recover()
        },
        CUTOVER_BUDGET);
    LOG.warnf(
        "Deployment %s was ACTIVE, but %s is %s on %d consecutive observations — recorded GONE."
            + " No container was touched: whatever still holds the alias is the next deployment's"
            + " predecessor.",
        candidate.deploymentId(), candidate.containerName(), describe(observed), STRIKES_TO_DEMOTE);
  }

  /** The recovery stamp, with the original failure text kept under it. */
  private static String recoveryDetail(Candidate candidate, HealthGate.Poll observed, Instant at) {
    String stamp =
        "[recovered by observation at "
            + at
            + ": container "
            + candidate.containerName()
            + " is "
            + describe(observed)
            + ", so this deployment is serving]";
    return candidate.detail() == null || candidate.detail().isBlank()
        ? stamp
        : stamp + "\n" + candidate.detail();
  }

  private static String failureDetail(Candidate candidate, HealthGate.Poll observed, Instant at) {
    return "[gone by observation at "
        + at
        + ": container "
        + candidate.containerName()
        + " is "
        + describe(observed)
        + " on "
        + STRIKES_TO_DEMOTE
        + " consecutive observations]";
  }

  /**
   * Whether the container is not coming back. Absent is one answer (docker cannot inspect it at all
   * — the gate's own "gone"), and a terminal status is the other. Everything docker still has a
   * state for and that is not terminal is PENDING, which is the health gate's rule and the reason a
   * restart loop recovering from the postgres-alias race is left alone.
   */
  private static boolean dead(HealthGate.Poll observed) {
    if (observed == null) {
      return false; // a driver that answered nothing has said nothing
    }
    if (observed.gone() != null) {
      return true;
    }
    return TERMINAL_STATUSES.contains(status(observed.state()));
  }

  /** Docker prints {@code <status>/<health>}; the status is what says whether it is running. */
  private static String status(String state) {
    if (state == null) {
      return "";
    }
    int slash = state.indexOf('/');
    return (slash < 0 ? state : state.substring(0, slash)).strip().toLowerCase(Locale.ROOT);
  }

  /** How an observation reads on a row and in a log line. */
  private static String describe(HealthGate.Poll observed) {
    if (observed == null) {
      return "unobservable";
    }
    if (observed.gone() != null) {
      return observed.gone().isBlank() ? "gone" : "gone (" + firstLine(observed.gone()) + ")";
    }
    return observed.state() == null || observed.state().isBlank() ? "stateless" : observed.state();
  }

  private static String firstLine(String output) {
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }
}
