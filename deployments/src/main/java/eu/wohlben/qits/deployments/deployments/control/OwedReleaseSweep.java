package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>Re-drives what an earlier process accepted and never deployed.</b> The other half of {@link
 * ReleaseAcceptance}, and the thing that turns "the event was claimed" into "the release was
 * deployed" across a cutover.
 *
 * <p>The claim ledger in qits-eventstream settles a {@code SoftwareRelease} the moment this
 * component's handler returns, and the handler returns as soon as the release is on the in-memory
 * {@code pd-deploy-worker} queue that {@code @PreDestroy} then throws away. Everything still on that
 * queue at a cutover was, before this class existed, lost silently and forever — the successor's
 * catch-up sweep found the claim and skipped the event. This is what reads those obligations back.
 *
 * <h2>What it re-drives, and what it refuses to</h2>
 *
 * <p>Only obligations {@link ReleaseAcceptance#orphaned() nobody is holding} — a row stamped with
 * this process's own instance id is on its worker queue and is left alone however long it sits
 * there. Each one is then put through the SAME three questions the live door answers, in the same
 * order, so a re-drive is an ordinary announcement and never a bypass:
 *
 * <ul>
 *   <li><b>Has this exact version already been requested?</b> Then the obligation was met by the
 *       process that died holding it — the request row was written, the container may even be live —
 *       and re-driving would mint a duplicate. Settled {@code ALREADY_REQUESTED}. <b>Unless every
 *       one of those requests ended in a deployment a restart interrupted</b> (qits-1803): the
 *       previous process wrote the request and died before deciding anything, so the release is
 *       still owed and goes on to the next question rather than being settled. See {@link
 *       #requested}.
 *   <li><b>Is it still the tip?</b> {@link ReleaseTips} answers, exactly as it does on the live
 *       path, against a floor read from the request rows because a freshly booted process remembers
 *       nothing. A release that a newer one has overtaken is settled {@code SUPERSEDED} rather than
 *       deployed: replaying it would be a rollback nobody asked for, which is the one thing a
 *       re-drive must never be able to cause. An interrupted release is asked through {@link
 *       ReleaseTips#reclaim} instead, because its own request IS the floor — equal passes there,
 *       newer still refuses.
 *   <li><b>Anything else</b> goes through {@link ReleaseAnnouncements#announceDurably}, which is the
 *       identical call the bus door makes — the identifier validation, the spec read at the released
 *       tag, derived registration, the request row, the queue, the health-gated cutover. Nothing
 *       here shortcuts any of it, so a release whose spec cannot be read is refused on the re-drive
 *       exactly as it was refused live.
 * </ul>
 *
 * <p>The two answers above are settled by <em>this</em> class rather than by the worker, because
 * they are decisions not to deploy: nothing would run, so nothing would discharge the row.
 *
 * <h2>When it runs</h2>
 *
 * <p><b>Once at startup, which is the cutover case exactly</b>, and then on a tick. The startup pass
 * is what recovers the previous container's queue; the tick is the belt for the two things the
 * startup pass cannot cover — a boot that ran while this component's own postgres was still coming
 * up, and an obligation this process took up and handed back after failing to discharge it.
 *
 * <p>It is a plain daemon thread rather than {@code quarkus-scheduler}, following {@link
 * DeployService}'s observation ticker: this component's whole ordering story is "one worker, in
 * queue order", and a scheduler extension would add a second thread pool and a second concurrency
 * model to it. Nothing is done ON this thread but the reading and the guards — every announcement
 * lands on the deploy worker, behind whatever is already queued.
 */
@ApplicationScoped
public class OwedReleaseSweep {

  private static final Logger LOG = Logger.getLogger(OwedReleaseSweep.class);

  @Inject ReleaseAcceptance acceptance;

  @Inject ReleaseAnnouncements announcements;

  @Inject ReleaseTips tips;

  @Inject PdDeploymentRequestRepository requests;

  /** How often owed work is looked for; {@code 0} switches the tick off and keeps the boot pass. */
  @ConfigProperty(name = "qits.deployments.owed-release-sweep-seconds")
  long sweepIntervalSeconds;

  private volatile Thread ticker;

  /**
   * The boot pass and the tick, on one thread — the first iteration runs immediately rather than
   * after a sleep, because a start is exactly when this component is behind.
   *
   * <p><b>It observes at a LOWER priority than {@link DeployService#onStart}, and that ordering is
   * a correctness requirement rather than tidiness.</b> That observer runs {@code sweepInFlight()}
   * synchronously — it reads every {@code QUEUED}/{@code STARTING} row and settles each one as
   * interrupted by the previous shutdown. A re-drive queues rows of exactly those statuses. Started
   * first, this sweep could put a fresh {@code QUEUED} row in front of that read and have the
   * predecessor's cleanup settle a deployment that is beginning rather than one that was
   * interrupted. CDI runs observers in ascending priority, so a higher number here means this
   * starts only after that sweep has returned.
   *
   * <p>Nothing here in test mode, for {@link DeployService#onStart}'s reason: a sweep landing behind
   * a test is the non-determinism a suite must not have, and the suite drives {@link #sweep()}
   * itself. Off its own thread for the reason the eventstream library's startup sweep is: boot must
   * not wait for a postgres that is coming up, and nothing about this application depends on the
   * sweep having finished.
   */
  void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 600) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Thread thread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  sweep();
                } catch (RuntimeException e) {
                  // A sweep that could not read its own table is a condition, not an event: the
                  // rows stayed owed and the next pass reads exactly the same ones.
                  LOG.warnf(e, "Could not sweep the accepted releases nobody is holding");
                }
                if (sweepIntervalSeconds <= 0) {
                  return;
                }
                try {
                  Thread.sleep(Duration.ofSeconds(sweepIntervalSeconds).toMillis());
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            },
            "pd-owed-release-sweep");
    thread.setDaemon(true);
    ticker = thread;
    thread.start();
    LOG.infof(
        "Re-driving accepted releases nobody is holding at startup and every %ds",
        sweepIntervalSeconds);
  }

  @PreDestroy
  void shutdown() {
    Thread thread = ticker;
    if (thread != null) {
      thread.interrupt();
    }
  }

  /**
   * One pass. Returns how many obligations it announced — the ones it settled without deploying are
   * not in the count, because nothing was put live for them.
   *
   * <p>Public so the suite drives it by hand, which is the only way it runs in test mode.
   */
  public int sweep() {
    List<ReleaseAcceptance.Owed> orphans = acceptance.orphaned();
    if (orphans.isEmpty()) {
      return 0;
    }
    LOG.infof(
        "%d accepted release(s) were left undeployed by an earlier process; re-driving them",
        orphans.size());
    int announced = 0;
    for (ReleaseAcceptance.Owed row : orphans) {
      if (redrive(row)) {
        announced++;
      }
    }
    return announced;
  }

  /** True when the release was handed to the worker; false when it was settled without deploying. */
  private boolean redrive(ReleaseAcceptance.Owed row) {
    ReleaseAcceptance.Accepted accepted = row.accepted();
    String application = accepted.applicationName();
    String version = accepted.version();

    if (row.attempts() >= ReleaseAcceptance.MAX_ATTEMPTS) {
      acceptance.reportExhausted(row);
      acceptance.settle(
          row.id(),
          ReleaseAcceptance.Outcome.EXHAUSTED,
          "taken up " + row.attempts() + " times without a deployment");
      return false;
    }
    Requested requested = requested(application, version);
    if (requested == Requested.DECIDED) {
      LOG.infof(
          "%s@%s was already requested, so the obligation the previous process left is discharged"
              + " rather than announced again",
          application, version);
      acceptance.settle(row.id(), ReleaseAcceptance.Outcome.ALREADY_REQUESTED, null);
      return false;
    }
    boolean tip =
        requested == Requested.INTERRUPTED
            ? tips.reclaim(application, version)
            : tips.claim(application, version);
    if (!tip) {
      LOG.infof(
          "%s@%s is not the newest release of %s any more; the obligation is settled rather than"
              + " deployed over the newer one",
          application, version, application);
      acceptance.settle(row.id(), ReleaseAcceptance.Outcome.SUPERSEDED, null);
      return false;
    }
    if (requested == Requested.INTERRUPTED) {
      LOG.infof(
          "%s@%s was requested and every attempt at it was interrupted by a restart; re-driving it"
              + " as the newest release of %s",
          application, version, application);
    }
    try {
      announcements.announceDurably(
          accepted.eventId(),
          accepted.runId(),
          accepted.repository(),
          application,
          version,
          accepted.packageName(),
          // Off the row, because there is nothing else left to read it from: the event was claimed
          // by a process that is gone. A field the ledger did not store is one a re-driven release
          // deploys without, which is why pd_owed_release carries this column at all.
          accepted.priority(),
          accepted.causationId());
      return true;
    } catch (BadRequestException e) {
      // The identifier validation refused it, exactly as it would have on the live path. Retrying
      // refuses it again, so it is settled and said out loud rather than re-driven forever.
      LOG.warnf(
          "The accepted release %s@%s was refused on re-drive: %s", application, version, e.getMessage());
      acceptance.settle(row.id(), ReleaseAcceptance.Outcome.EXHAUSTED, e.getMessage());
      return false;
    }
  }

  /**
   * What the request rows say about this exact {@code (application, version)} — the idempotence
   * guard, and since qits-1803 a three-way answer rather than a boolean.
   *
   * <p><b>Asked of the REQUESTS for {@link ReleaseTips}' reason</b>: a request is written the moment
   * a release is accepted for a place, before the gate, before the pull and before any terminal
   * word — so it records "this was already done here" even for a deployment that then failed.
   * Asking the deployments alone would re-drive a release whose container never came up, which is a
   * second deployment of something a human is already looking at.
   *
   * <p><b>But "requested" stopped being enough on its own</b>, because the worker writes the request
   * at the START of {@code deploy()}. A process replaced after that write — this component's own
   * self-update is the commonest way, its worker having moved on to the next queued release — leaves
   * a request whose deployment the successor's {@code sweepInFlight()} settles {@code FAILED} and
   * marks {@code interrupted}. Nothing decided it, nothing settled the obligation, and the old
   * boolean called it met: the release was never deployed and its release request's gate hung on a
   * {@code DeploymentActive} nothing would announce. So the rows are read for what they decided:
   *
   * <ul>
   *   <li>{@link Requested#NONE} — never asked for; the ordinary re-drive, through {@link
   *       ReleaseTips#claim}.
   *   <li>{@link Requested#DECIDED} — at least one request reached an outcome (a deployment that is
   *       not interrupted, or a refusal with no deployment at all). Settled {@code
   *       ALREADY_REQUESTED} exactly as before: a real {@code FAILED} is a person's to look at, not a
   *       sweep's to retry.
   *   <li>{@link Requested#INTERRUPTED} — asked for, and every attempt cut off by a restart. Owed
   *       still, and re-driven through {@link ReleaseTips#reclaim}, which lets this very version
   *       through and still refuses it under a newer one.
   * </ul>
   *
   * <p>A re-drive that is itself interrupted lands here again with one more interrupted row and one
   * more attempt on the obligation, so {@link ReleaseAcceptance#MAX_ATTEMPTS} still bounds it.
   */
  private Requested requested(String applicationName, String version) {
    return DbRetry.inNewTx(
        "The request lookup of " + applicationName + "@" + version,
        () -> {
          if (!requests.existsFor(applicationName, version)) {
            return Requested.NONE;
          }
          return requests.countDecidedFor(applicationName, version) > 0
              ? Requested.DECIDED
              : Requested.INTERRUPTED;
        },
        DeployService.CUTOVER_BUDGET);
  }

  /** What {@link #requested} found; see there. */
  private enum Requested {
    NONE,
    DECIDED,
    INTERRUPTED
  }
}
