package eu.wohlben.qits.deployments.deployments.control;

import java.util.UUID;

/**
 * How a released version reaches the deploy orchestration. One method, implemented by {@link
 * DeployService}, called by whatever door the release came through.
 *
 * <p><b>This replaces {@code BuildAnnouncements}, and the replacement is the point of the
 * epic.</b> A green build is no longer a reason to deploy anything. What deploys is a RELEASE: a
 * version was minted, a tag was pushed, an image was published under that tag, and only then does
 * the platform put it live. The old seam took {@code (repository, branch, commitSha)} and derived
 * an image from the sha; this one takes {@code (repository, application, version)} and derives it
 * from the version. There is no branch anywhere on this path — a release is not on a branch, it is
 * a tag — and no commit sha either, because a release does not name one.
 *
 * <p>Two doors, and neither wins: both funnel into {@link #announce} and everything downstream of
 * it — the spec read, derived registration, the request row, the queue, the health-gated cutover —
 * cannot tell them apart.
 *
 * <ul>
 *   <li>The bus ({@code bus/PdSoftwareReleaseSubscriber}) — a durable consumer of qits-ci's {@code
 *       SoftwareRelease}, and the door a deployment follows from: the publisher retries it, the log
 *       replays it after a cutover, and the eventstream library hands it over exactly once per
 *       event whichever channel delivered it.
 *   <li>{@code POST /deployments/api/events/software-released} — the direct HTTP intake
 *       ({@code api/PdEventController}). It stays, and it stays the <b>manual and bootstrap</b>
 *       door: a bootstrap replays a lost release through it by hand, an operator redeploys or rolls
 *       back a version with it, and it is the door that still works before qits-events exists. It
 *       is fire-and-forget and nobody retries it.
 * </ul>
 *
 * <p><b>Idempotency was never this seam's job.</b> Two announcements of one version are two
 * deployments of one version: the container is named after the deployment rather than the version,
 * the predecessor search finds the first one and cuts it over. What the bus adds is a guarantee the
 * POST never had — an event is not lost when nobody was listening — and one obligation the POST
 * never had, which is ordering. A replayed event can name an <em>older</em> version than the one
 * already live, so the subscriber collapses to the tip ({@link ReleaseTips}) before it calls this.
 * That check belongs to the door, not here: the manual door is an operator choosing a version, and
 * guarding it would be refusing the choice — including the deliberate choice to go back one.
 *
 * <p><b>The application name arrives by value and is not derived from the repository.</b> A {@code
 * SoftwareRelease} carries no repository NAME at all — it carries the package it published ({@code
 * qits/qits-ci}), and the application is the image path in it. So the two coordinates travel side
 * by side: the {@link RepositoryRef} is what the spec is read by, the application name is what
 * everything else is derived from (the catalogue key, the wire alias, the container name, the image
 * tag, the provisioned database and role, the GC pin key). A repository's own {@code deployments.yml}
 * may still override the application name one step later, in {@code DeployService.deploy}, exactly
 * as it always could.
 *
 * <p><b>{@code causationId} is a parameter rather than something the far side reads off an ambient
 * scope</b>, because everything downstream runs on {@code pd-deploy-worker} and an executor hop is
 * where {@code CausationScope} — a plain ThreadLocal — dies. Each door knows the answer on its own
 * thread and states it.
 */
public interface ReleaseAnnouncements {

  /**
   * Which door an announcement came through — and the <b>one</b> thing that differs between them.
   *
   * <p>The paragraph above says neither door wins and nothing downstream can tell them apart. That
   * is still the rule for everything that decides HOW a release is deployed; this enum is the one
   * exception, and it is about WHETHER.
   *
   * <p>A {@code SoftwareRelease} is published for every docker package qits-ci pushes, service or
   * not, and nobody chose it: a workspace container image announces itself exactly as qits-ci
   * does. So on that door a repository that declares no {@code deployments.yml} at the released
   * tag is recorded and not deployed — it never asked to be. The manual door is somebody naming
   * an application and a version on purpose, including a rollback to a tag cut before the file
   * existed, and refusing that would be refusing the choice. See {@code DeployService}.
   */
  enum Door {

    /** {@code bus/PdSoftwareReleaseSubscriber}: every published image, chosen by nobody. */
    RELEASE_EVENT,

    /** {@code POST /events/software-released}: an operator or a bootstrap, naming this version. */
    MANUAL
  }

  /**
   * One released version of one application. Returns as soon as the release is accepted: the
   * deployment runs on this component's own worker, and the announcer — a fire-and-forget POST or a
   * bus subscriber — has nothing to do with the outcome. A bus subscriber in particular must return
   * from its handler rather than wait: it is holding the claim transaction open while it does.
   *
   * @param runId the qits-ci run that produced the release, optional and resolved against nothing.
   *     A {@code SoftwareRelease} does not carry one, so the bus door passes null and the row
   *     honestly names no build; the manual door may supply one.
   * @param repository which repository was released, in whatever coordinate the door had. A release
   *     event has carried the storage id, the project and the repository NAME since 2026-09-04, so
   *     the spec is read through the public {@code /git/<projectId>/<repoName>}; an event from
   *     before that — or one replayed from before it — carries no name, and the ref falls back to
   *     the id route it always used. Both doors state both coordinates and neither chooses; {@link
   *     RepositoryRef#nameAddressed()} is where the choice lives.
   * @param applicationName what this release deploys under, taken from the released package rather
   *     than from the repository. Never null and never a storage id.
   * @param version the released CalVer stamp — the git tag, and the image tag
   * @param packageName the package the release announced, verbatim and registry-unqualified, or
   *     null when the door named an application directly. Recorded on the request row so the
   *     derivation above is visible rather than re-performed.
   * @param priority what the release request declared, carried down the chain from qits-projects and
   *     <b>recorded, never acted on</b>: the deploy worker is FIFO and stays FIFO. A verbatim String
   *     rather than an enum — the vocabulary is qits-projects' — and nullable everywhere, because
   *     null is the honest answer for every release cut before the field existed. It is advisory, so
   *     it is never validated in a way that could refuse a release; see {@link ReleasePriorities}.
   * @param hold whether the release request asked to HOLD the deployment for a person's approval,
   *     and which request it was (qits-2268) — {@link DeployHold#NONE} for every release that did
   *     not say, and the quality gate's question rather than this seam's
   * @param causationId the event this announcement is the effect of, recorded on every row it
   *     produces. Null is a rootless announcement — a bootstrap's hand-made POST — and never a
   *     reason to refuse one: causation is advisory and a deployment must not fail over a column
   *     only the trace graph reads.
   * @param door which intake this came through, and the only thing the two doors do not share —
   *     see {@link Door}
   * @throws eu.wohlben.qits.deployments.environments.error.BadRequestException if any of
   *     the identifiers could escape an argv or overrun its column
   */
  void announce(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      String priority,
      DeployHold hold,
      UUID causationId,
      Door door);

  /**
   * {@link #announce(String, RepositoryRef, String, String, String, String, DeployHold, UUID,
   * Door)} for a release that asked for no hold ({@link DeployHold#NONE}) — every caller before
   * qits-2268.
   */
  default void announce(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      String priority,
      UUID causationId,
      Door door) {
    announce(
        runId,
        repository,
        applicationName,
        version,
        packageName,
        priority,
        DeployHold.NONE,
        causationId,
        door);
  }

  /**
   * The same announcement from the manual door. It is the default because that door is the one
   * whose behaviour did not change: everything that called this seam before {@link Door} existed
   * was an operator or a bootstrap naming a version, and this overload is what they keep meaning.
   */
  default void announce(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      String priority,
      UUID causationId) {
    announce(
        runId,
        repository,
        applicationName,
        version,
        packageName,
        priority,
        causationId,
        Door.MANUAL);
  }

  /**
   * The {@link Door#RELEASE_EVENT} announcement, <b>recorded as an obligation before anything is
   * queued</b> and discharged only once the worker has finished with it. What the bus door calls,
   * and what {@code OwedReleaseSweep} calls again for a release an earlier process never deployed.
   *
   * <h2>The gap it closes, which the event bus cannot close for itself</h2>
   *
   * <p>qits-eventstream is durable up to the point where the handler returns: a {@code
   * SoftwareRelease} broadcast while this component is down is read back out of the log by the
   * catch-up sweep and claimed in {@code consumed_event} in the same transaction as the handler.
   * And {@link #announce} returns as soon as the release is a {@code Runnable} on the in-memory
   * {@code pd-deploy-worker} queue. That queue is serialized platform-wide so it is legitimately an
   * hour deep, and {@code @PreDestroy} calls {@code shutdownNow()} on it.
   *
   * <p>So a cutover of this component — which it performs on itself, {@code stop-first} — dropped
   * every release in that queue while the bus's ledger said, correctly, that the event had been
   * handled: the successor's sweep found the claim and skipped the event, and no deployment request
   * was ever written. Seven applications' releases went that way on 2026-09-05 between 13:00 and
   * 17:15 UTC. {@code ReleaseAcceptance} is the ledger that survives that, and {@code
   * OwedReleaseSweep} is what reads it back.
   *
   * <h2>Why the durable door is a second method rather than the default</h2>
   *
   * <p>Because {@link Door#MANUAL} does not want it. That door is an operator naming a version on
   * purpose, including the deliberate choice to go back one, and an obligation ledger that
   * re-announced a rollback after a restart would be re-answering a question a person already
   * answered — and would then have it refused by the monotonic collapse for good measure. The bus is
   * the opposite: nobody is standing there, the sender is a log that has already recorded the event
   * as consumed, and a release that is not deployed is a defect. The two doors differ in exactly one
   * more thing than {@link Door} says, and this is it.
   *
   * <p><b>It implies {@link Door#RELEASE_EVENT} rather than taking a door</b>, for the same reason:
   * the obligation and the door are one decision, not two that a caller could combine wrongly.
   *
   * <p><b>The obligation carries the priority and the hold with everything else it carries</b>, and
   * it has to: {@code OwedReleaseSweep} rebuilds the announcement from the row alone, in another process, with
   * the event long since claimed and nothing left to ask. A field the ledger did not store is a
   * field a re-driven release loses.
   *
   * @param eventId the {@code SoftwareRelease} frame this announces — the obligation's natural key,
   *     so a re-drive re-takes the same row rather than opening a second one. Never null: a durable
   *     announcement that could not be de-duplicated across restarts is not one, and the door with
   *     no event id is {@link #announce}.
   * @throws eu.wohlben.qits.deployments.environments.error.BadRequestException if any of
   *     the identifiers could escape an argv or overrun its column — checked <b>before</b> the
   *     obligation is written, so a release this component refuses never becomes owed work
   */
  void announceDurably(
      String eventId,
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      String priority,
      DeployHold hold,
      UUID causationId);

  /** {@link #announceDurably} for a release that asked for no hold ({@link DeployHold#NONE}). */
  default void announceDurably(
      String eventId,
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      String priority,
      UUID causationId) {
    announceDurably(
        eventId,
        runId,
        repository,
        applicationName,
        version,
        packageName,
        priority,
        DeployHold.NONE,
        causationId);
  }
}
