package eu.wohlben.qits.deployments.deployments.entity;

/**
 * A deployment's lifecycle. {@code QUEUED} and {@code STARTING} are the only non-terminal states,
 * and neither survives a restart (the worker queue is in-memory; the startup sweep settles them).
 *
 * <p><b>{@code FAILED} used to be five different outcomes.</b> A row that said it could mean the
 * apply was refused, the successor never converged and the orchestrator put the predecessor back, a
 * restart interrupted the row and a newer deployment took its place, or a container that had been
 * serving for hours was found gone — four questions with four different answers ("is anything
 * serving?", "does somebody have to act?") flattened into one word. So three of them are their own
 * words now: {@link #ROLLED_BACK}, {@link #SUPERSEDED} and {@link #GONE}. Nothing was removed and no
 * row was relabelled — the column is a varchar with no check constraint precisely so a vocabulary
 * can grow, and every historical {@code FAILED} still says what it said. {@code FAILED} keeps the
 * remainder, narrowed to its honest meaning: the attempt ended and nothing is known to serve.
 *
 * <p><b>The terminal states are terminal but not final.</b> {@code ACTIVE}, {@code FAILED} and
 * {@code GONE} are the ones a container's own state can contradict, so the periodic observation
 * ({@code DeploymentObserver}) settles the disagreement on the LATEST row of each (application,
 * tier): a {@code FAILED} or {@code GONE} row whose own container is running and healthy becomes
 * {@code ACTIVE}, and an {@code ACTIVE} row whose container is absent or terminally exited on two
 * consecutive passes becomes {@code GONE}. The rest are nobody's to observe — {@code QUEUED} and
 * {@code STARTING} belong to the worker's state machine, {@code IMAGE_MISSING} is a statement about
 * a registry rather than a container, {@code SPEC_UNREADABLE} is a statement about the git host,
 * {@code SUPERSEDED} is a statement about a row a later deployment overtook, {@code
 * DECLARATION_REFUSED} is history (nothing writes it any more), and {@code DECOMMISSIONED} is a
 * decision another deployment made. A row that is not the latest for its place is history and is
 * never revisited.
 *
 * <p><b>One of them is settled by a retry rather than by an observation</b>: {@link
 * #SPEC_UNREADABLE} is re-attempted by {@code DeployService} on the same cadence, because what has
 * to be re-asked there is the file rather than a container.
 */
public enum PdDeploymentStatus {
  /** Recorded by the intake, waiting for the single-threaded deploy worker. */
  QUEUED,
  /** The worker is pulling the image, starting the container, or waiting on the health gate. */
  STARTING,
  /**
   * Passed the health gate; its container serves the application on its networks. Also what the
   * observation writes onto a {@code FAILED} or {@code GONE} row whose container turns out to be
   * running and healthy — the detail then carries the recovery stamp with the original failure text
   * under it.
   */
  ACTIVE,
  /**
   * The OCI registry has no image for this (application, sha) — the honest name for "CI went green
   * but no image arrived", which stays a distinct state because it indicts the publishing
   * convention rather than the build. Publishing is a repository's own last pipeline step, so this
   * state means that pipeline publishes nothing or its tag broke the convention.
   */
  IMAGE_MISSING,
  /**
   * The repository's {@code .config/qits/deployments.yml} could not be READ at the released tag,
   * for a reason that may pass — the git host refused the blob, answered a 5xx, or did not answer
   * at all. <b>Nothing about the repository is being claimed</b>: the file was never seen, so this
   * indicts the hop rather than the commit.
   *
   * <p><b>It is the one terminal-looking word that is not terminal</b>, and that is the whole
   * reason it exists. Every spec failure used to be {@code FAILED} — a row nothing revisits — so a
   * release whose single deploy attempt met an intermittent 403 from qits-githost stranded until
   * somebody replayed it by hand; three did, for 13 to 17 minutes each, on 2026-09-04. A row that
   * says this is re-read on the deployment observation's own cadence ({@code
   * qits.deployments.observe-interval-seconds}) until the file answers — at which point
   * the release deploys for real and this row becomes history — or until a newer version of the
   * same application supersedes it, which is the ordinary exit.
   *
   * <p><b>A spec problem that is the repository's own is NOT this.</b> A file that does not parse,
   * or a key the schema refuses, stays {@code FAILED}: it was read, and reading it again answers
   * the same thing. A repository carrying no file at all is neither — it gets every default and
   * deploys, exactly as it did before the file existed.
   *
   * <p>Nothing observes it: it names no container, so {@code DeploymentObserver} skips it like every
   * other row that never reached a {@code docker run}. The retry is {@code DeployService}'s, on the
   * deploy worker, beside the observation pass rather than inside it.
   */
  SPEC_UNREADABLE,
  /**
   * The attempt ended and <b>nothing is known to serve the place</b>. The apply was refused, the
   * convergence failed without the orchestrator reverting anything, or a restart interrupted the row
   * with no evidence of what took over.
   *
   * <p>It is the narrowed word, and the narrowing is what makes it worth paging on: the three
   * outcomes below all leave somebody serving or say who overtook the row, and each of them used to
   * arrive here.
   */
  FAILED,
  /**
   * The successor never converged and the orchestrator <b>put the predecessor back</b>, which is
   * still serving. Swarm's {@code rollback_completed} / {@code rollback_paused}, reaching this
   * component as {@code DeploymentDriver.ConvergenceOutcome#ROLLED_BACK}.
   *
   * <p>Distinct from {@code FAILED} because the platform is not down: the deployment did not land,
   * and the application it targeted is exactly as available as it was a minute earlier. What it asks
   * for is a fix to the commit, not a page.
   */
  ROLLED_BACK,
  /**
   * An in-flight row a restart interrupted, whose place a <b>newer deployment took</b>. The startup
   * sweep writes it when the service running under the row's name carries a different sha: this
   * attempt's outcome is unknowable and no longer interesting, because something later is serving.
   *
   * <p>An interrupted row with no such evidence stays {@code FAILED} — nothing is known to serve
   * there, which is the whole difference between the two words.
   */
  SUPERSEDED,
  /**
   * Was {@code ACTIVE}, and the observation found its container <b>absent or terminally exited</b>
   * on two consecutive passes. The deployment itself succeeded; the place died afterwards.
   *
   * <p>Distinct from {@code FAILED} because it indicts nothing about the build or the commit — the
   * gate passed, and this row is the only place a platform learns that what passed it is no longer
   * running. It is a demotion and it self-heals: a container that comes back healthy takes the row
   * back to {@code ACTIVE}, the same recovery a {@code FAILED} row gets.
   */
  GONE,
  /**
   * The workload is <b>deliberately stopped</b>: somebody scaled this application to zero, and the
   * deployment that put the image there is otherwise untouched. Written by {@code
   * ApplicationScaling} when an operator scales down, and by {@code DeploymentObserver} when it
   * finds a place empty that the orchestrator declares should be empty — which is how a scale
   * performed by hand on the host reads here too.
   *
   * <p><b>It is the word {@code GONE} would otherwise have been, and telling them apart is the whole
   * point.</b> {@code GONE} says a place died under a deployment that was serving and somebody
   * should look; this says a person stopped it and nothing is wrong. Answering the operator's own
   * action with a red row would train a reader to ignore the one status that means an outage.
   *
   * <p>It is <b>not</b> terminal in the sense the words above it are: scaling back up recovers it
   * through the same observation arm {@code FAILED} and {@code GONE} recover through, so a row that
   * says this becomes {@code ACTIVE} again as soon as the tasks are healthy — with its id, its sha
   * and its history intact, because a scale is not a deployment.
   */
  SCALED_TO_ZERO,
  /**
   * <b>HISTORY: nothing writes this any more</b> (qits-1393). It meant the release's {@code
   * .config/qits/configuration.yml} was not seeded into qits-configuration, so the deployment was
   * not run at all; that service and the seed are gone. The constant stays because rows written
   * before the switch carry it in {@code pd_deployment.status} and the enum is read back by name —
   * deleting it would make every such row unreadable.
   *
   * <p>It is terminal like {@link #FAILED} and absent from every positive status list ({@code
   * RequestLifecycle}'s in-flight set, the observer's candidate and recoverable sets, {@code
   * RollbackPins.SERVED}, the startup sweep's queries, the retirement door's refusal sets), so an old
   * row that says this is completed, pinned by nothing, observed by nothing and retirable.
   */
  DECLARATION_REFUSED,
  /** Was ACTIVE; replaced by a newer deployment that passed the health gate. */
  DECOMMISSIONED
}
