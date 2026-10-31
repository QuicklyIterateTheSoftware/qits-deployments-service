package eu.wohlben.qits.deployments.deployments.control;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The seam between this component's orchestration and whatever runs the containers — the {@code
 * CiStepRunner} arrangement: this module owns the interface and the state machine that calls it,
 * {@code service/} owns the implementations, and the suites install a scripted fake so a clone's
 * {@code mvn verify} needs no docker.
 *
 * <p><b>A second orchestrator is what reshaped this interface, and only one is left.</b> It used
 * to be docker's own vocabulary written out: {@code start} one container, {@code stop} and {@code
 * restart} the predecessors, {@code connect} each network after the fact, and a caller in {@link
 * DeployService} that sequenced all of it. Every one of those verbs is a statement about how one
 * orchestrator replaces a container rather than about deploying, so keeping them would have made
 * that model look like the contract. They went, the by-hand path went after them, and the shape
 * they left is the one worth keeping: a driver states outcomes, never mechanics.
 *
 * <p><b>So the deployment half of the seam is two verbs and the rest is bookkeeping</b>: {@link
 * #apply(ServiceSpec)} makes the described service exist at the described image, and {@link
 * #awaitConverged} says whether it took. How that happens — stop-then-start with a hand-rolled
 * rollback, or {@code service update --update-order start-first --update-failure-action rollback} —
 * is the implementation's business, and the whole of the difference between the two lives there.
 *
 * <p><b>Three verbs beside it are the OPERATOR's, and they are not the mechanics coming back.</b>
 * {@link #desiredReplicas}, {@link #scale} and {@link #restart} exist because a wedged application
 * had exactly one recovery on this platform — re-firing a same-sha push and waiting fifteen minutes
 * for a rebuild — and none of the three is a step of a cutover somebody else sequences. Each states
 * an outcome an operator asked for: how many of this application are declared to run, make that
 * number N, replace what is running under this name without changing what it runs. The verbs that
 * went ({@code start}, {@code stop}, the container-level {@code restart}, {@code connect}) were
 * different: {@link DeployService} called them in order to perform ONE replace, so the interface
 * described docker's way of replacing a container. Nothing calls these three in a sequence, and the
 * rule they were removed for is intact — <b>do not sequence them from a caller to build a
 * deployment</b>.
 *
 * <p>Everything crossing this seam is ids, names and references — never entities. The driver knows
 * nothing about environments or deployments; it applies a spec, watches it converge, and makes and
 * removes networks.
 *
 * <p><b>Docker is the membership bookkeeping.</b> Which service sits on which
 * network is never stored in this component's database — it is read back from the labels below.
 * One record of the truth, and it is the runtime's, so a row cannot describe a topology the
 * runtime does not have.
 *
 * <p><b>The labels are {@code qits.platform.deployments.*}, and every earlier spelling is a
 * legacy.</b> {@code qits.cd.*} came from the retired ancestor and {@code qits.pd.*} from this
 * component before the namespace was written out in full; containers and networks carrying either
 * are treated exactly like unlabelled ones — adoptable predecessors, never protected — because that
 * is what makes each cutover a deployment rather than a flag day. Nothing here reads a legacy
 * label, and nothing should start to: the absence of a {@code qits.platform.deployments.*} label is
 * already the whole statement.
 */
public interface DeploymentDriver {

  /**
   * The config key that has to say {@code swarm}. It picked between two implementations once and is
   * a guard now — see {@code orchestration/DeploymentDrivers}.
   */
  String ORCHESTRATOR_KEY = "qits.deployments.orchestrator";

  /**
   * The prefix of the per-application family that says what one application needs beyond its image
   * — mounts, published ports, extra groups, extra environment. {@link ServiceExtras} is the
   * grammar and the reader.
   *
   * <p><b>Deployment config is the ONLY source</b>, never the API and never the intake, which is
   * what keeps the trust domain the one that already holds the docker socket. It is spelled here,
   * on the seam, because both drivers read it and each renders it in its orchestrator's vocabulary
   * — and a second spelling of the key would let the two disagree about which application's
   * arguments they are.
   */
  String EXTRAS_PREFIX = "qits.deployments.extras.";

  /**
   * The prefix this family had while the namespace carried the retired {@code platform} qualifier.
   *
   * <p><b>Still read, and nothing produces it any more.</b> It was a migration window for two
   * sources this component did not release — qits-configuration's served entries and the extras
   * file on the config volume. Both are gone (qits-1393): the settings store renders {@link
   * #EXTRAS_PREFIX} only, and {@code ExtrasSnapshot} hides both prefixes in the boot layer, so
   * deleting this is a cleanup with no behaviour attached.
   */
  String LEGACY_EXTRAS_PREFIX = "qits.platform.deployments.extras.";

  /** The environment a service belongs to. Every service has one. */
  String ENVIRONMENT_LABEL = "qits.platform.deployments.environment";

  String APPLICATION_LABEL = "qits.platform.deployments.application";
  String DEPLOYMENT_LABEL = "qits.platform.deployments.deployment";

  /** {@code true} on an environment's public nodes — the other half of the reconciliation lookup. */
  String AVAILABLE_ON_ENV_LABEL = "qits.platform.deployments.available-on-env";

  /** On networks: {@code bundle} or {@code application}. */
  String NETWORK_LABEL = "qits.platform.deployments.network";

  /** On containers and on per-application networks: whose it is. */
  String APP_NAME_LABEL = "qits.platform.deployments.app-name";

  /** What a network this component made is for. */
  enum NetworkKind {
    /** An environment's public nodes ({@code availableOnEnv}). */
    BUNDLE,
    /** One application of one environment — its own containers and its joined hub. */
    APPLICATION
  }

  // PLATFORM was a third kind and went with the plane: it was the `qits-platform` overlay, the one
  // network that belonged to no environment. A live network still carrying `network=platform` reads
  // back as an unknown kind, which `networks()` already tolerates by answering null.

  /** A network this component made, as its labels describe it. */
  record Network(String name, String environmentId, NetworkKind kind, String applicationName) {}

  /**
   * Best-effort ensure the network exists, labelled — warn, never fail, when the runtime is absent.
   *
   * <p>Returns whether this call <b>created</b> it. The reconciliation deliberately does not hang
   * off that answer — a network outlives the deployment that made it, so who belongs on it is
   * recomputed every time rather than joined once. An already-existing network keeps whatever
   * labels it has: adopting an unlabelled network made outside this component (the platform's own
   * {@code qits-net}, or one a retired qits-cd labelled) stays supported, deliberately.
   *
   * <p>The driver decides the network's <b>driver</b>: an attachable overlay, because a bridge
   * cannot carry a service at all.
   */
  boolean ensureNetwork(Network spec);

  /** Best-effort remove the named network (it may still hold endpoints; the runtime refuses). */
  void removeNetwork(String network);

  /** Every network this component labelled — the membership bookkeeping, read back from the runtime. */
  List<Network> networks();

  /**
   * Release whatever still holds these networks, so a teardown can remove them.
   *
   * <p>The question it asks used to be the platform plane's — "these networks are about to go, and
   * the plane is on them without belonging to the tier that owns them" — and the plane is deleted.
   * What survives is the question a teardown still has: a network cannot be removed while anything
   * holds an endpoint on it. A swarm service declares its networks when it is created and a teardown
   * does not reshape one, so the honest answer today is to do nothing and let the removal's own retry
   * loop wait for the tasks to go. It stays a verb because the question outlives the answer.
   */
  void detachPlatformPlane(List<String> networks);

  /** Remove everything labelled as belonging to the environment. Returns how many there were. */
  int removeEnvironmentContainers(String environmentId);

  /**
   * Pull the reference so a missing image is its own recorded outcome.
   *
   * <p><b>It survives even though swarm pulls on its own</b>: the pull is not how the image gets
   * to the host, it is how "nothing published this application yet" is told apart from "the
   * deployment failed". A service create would report the same condition as a task that never
   * starts, minutes later, with the registry's words buried in a task error.
   */
  PullResult pull(String imageRef);

  /**
   * What this orchestrator calls the thing {@code spec} describes — and therefore what the
   * deployment row records, and what {@link #awaitConverged}, {@link #observe} and {@link #reap}
   * are asked about afterwards.
   *
   * <p><b>A swarm service's name IS the address</b> — {@code container_name} does not exist there
   * — so the name is the wire alias and a replace is an update of that one service. The by-hand
   * path named a fresh container per deployment instead ({@code qits-pd-<env>-<app>-<id8>}),
   * because a replace was two containers that must not collide; that name is still derived and
   * still on the spec, because it is what a person greps the host for.
   *
   * <p>It is asked <b>before</b> {@link #apply}, because the row has to name the thing before
   * anything starts: a crash between the two leaves a {@code STARTING} row the startup sweep can
   * still identify.
   */
  String nameOf(ServiceSpec spec);

  /**
   * Make the described service exist, at the described image, on the described networks — creating
   * it or updating it in place, and idempotent either way.
   *
   * <p><b>This is where the replace lives.</b> It is a single {@code service create}-or-{@code
   * update} carrying the full network list, because declaring membership at create time is the only
   * way to have it without a task restart. Anything an orchestrator does by hand instead — find
   * whoever holds the alias, stop it, join the successor to every network, put the loser back —
   * belongs behind this one call and never above it.
   *
   * <p>Returning is not success: {@link #awaitConverged} is where the outcome is. What returning
   * <i>does</i> settle is whether this deployment is still this process's to finish — see {@link
   * ApplyOutcome#HANDED_OFF}.
   */
  ApplyResult apply(ServiceSpec spec);

  /**
   * Park until the applied service is serving, is back on its predecessor, or the deadline passes.
   *
   * <p>The verdict is the orchestrator's own — swarm's {@code UpdateStatus}: {@code completed},
   * {@code rollback_completed}, {@code paused}, where the rollback already happened without
   * anybody asking.
   *
   * <p>A failed convergence leaves the world as it was: whatever was serving before is serving
   * again by the time this returns. The caller's remaining job is the row.
   */
  Convergence awaitConverged(String name, Duration timeout);

  /**
   * <b>One</b> observation of the named service — docker's {@code <status>/<health>} string, or the
   * statement that the runtime has no such thing. Deliberately the health gate's own type: {@link
   * DeploymentObserver} settles a row on {@link HealthGate#healthy}, so "healthy" means to an
   * observation exactly what it means to a gate, and "gone" is a structural fact rather than a
   * wording match.
   *
   * <p>It exists because a deployment's status used to be written once and never read back against
   * the world. Asking by the <b>name the row itself carries</b> is the point: only the service a
   * row named may settle that row, and a healthy service belonging to somebody else must not
   * resurrect it.
   */
  HealthGate.Poll observe(String name);

  /**
   * How many tasks the named service is <b>declared</b> to run — the desired count the orchestrator
   * holds, never how many happen to be up. Empty when the runtime has no such service, and empty
   * when it could not answer at all.
   *
   * <p><b>This is what tells a deliberate zero from a death</b>, and it is the whole reason the read
   * exists. {@link #observe} answers "is anything running under this name", and for a service an
   * operator scaled to 0 the honest answer is no — which read as a dead deployment and would have
   * had {@link DeploymentObserver} demote a row somebody had just deliberately stopped, then fight
   * every later observation about it. The desired count is the intent, held by the same runtime that
   * holds the membership: this component stores no replica column, exactly as it stores no network
   * row, so an operator scaling a service by hand is understood the same way as one going through
   * {@link #scale}.
   *
   * <p>It is asked <b>only when an observation looks dead</b> — one extra call on the rare pass
   * rather than a second call per application per tick.
   */
  OptionalInt desiredReplicas(String name);

  /**
   * Make the named service run exactly {@code replicas} tasks, and nothing else about it change.
   *
   * <p><b>Zero is the interesting value.</b> It stops the workload while keeping the service, its
   * spec, its networks, its mounts and its published ports — so scaling back up is the same image
   * and the same configuration coming back, not a deployment. That is what makes a restart an
   * operation rather than a release, and it is why the deployment row is left in place: a scale
   * changes what runs, never what was deployed.
   *
   * <p>An implementation must refuse to scale <b>this process's own service</b> to zero. There would
   * be nothing left to scale it back up, and the failure is silent — the API stops answering at the
   * moment the operator would need it.
   */
  ScaleResult scale(String name, int replicas);

  /**
   * Replace whatever is running under this name with a fresh copy of the same thing — the operator's
   * bounce, and the recovery for an application that is up, healthy to its probe and wedged behind
   * it.
   *
   * <p><b>It is not a deployment and must not become one.</b> Nothing about the service's spec
   * changes: same image, same environment, same networks, same ports. No row is created, no sha
   * moves, and the deployment that put this image here keeps its identity — see {@code
   * ApplicationScaling} for what it stamps instead.
   *
   * <p><b>It is not the {@code restart} that was removed either.</b> That one took a container name
   * and was one step of a hand-rolled cutover, called by {@link DeployService} between a stop and a
   * join; this one takes the service and is the whole of what its caller wants.
   */
  ScaleResult restart(String name);

  /** How a scale or a bounce ended. */
  enum ScaleOutcome {
    /** The runtime took it. */
    SCALED,
    /**
     * The service is <b>this very process</b>, and whatever happens next happens to the instance
     * that survives — {@link ApplyOutcome#HANDED_OFF} for the same reason and with the same
     * arbiter.
     */
    HANDED_OFF,
    /** Nothing changed, and {@code detail} says why. */
    REFUSED
  }

  record ScaleResult(ScaleOutcome outcome, String detail) {

    public boolean applied() {
      return outcome != ScaleOutcome.REFUSED;
    }
  }

  /**
   * Remove what settled deployments left behind — the containers of the rows a cutover just
   * decommissioned, plus whatever {@link Convergence#retired()} named.
   *
   * <p>Called <b>after</b> the rows say so, never before, which is why it is a call of its own
   * rather than the tail of {@link #awaitConverged}: a bookkeeping bracket that has to retry for
   * thirty seconds must not have removed the predecessor first.
   *
   * <p>Under swarm this has nothing to do, and the reason is worth stating rather than
   * discovering: a replace is in place, so the predecessor and the successor are one service and
   * removing "the old one" would remove the deployment that just went live. It stays a verb
   * because the rows still name what a cutover retired, and an orchestrator that replaced by
   * creating something new would have that list to act on.
   */
  void reap(List<String> names);

  /**
   * Make the named service not exist — an OUTCOME, stated once, about one service that has been
   * left behind.
   *
   * <p><b>It is not a cutover mechanic and must not become one.</b> Nothing sequences this to
   * perform a deployment: by the time it is called the deployment is applied, converged, recorded
   * and announced, and calling it or not calling it changes nothing about that.
   *
   * <p><b>It has no caller in this build, and it is kept for the thing it says.</b> Its caller was
   * the plane conversion, which moved an application's rows onto the platform plane and owed the
   * {@code <env>-<app>} services those rows named a retirement; the plane is deleted, so there is no
   * conversion left to owe one. What the verb states — "this one service, named by a row, has been
   * left behind and should not exist" — is exactly what the plane's own deletion leaves nine times
   * over on the live estate, and that is an operator's hand step rather than a deployment's (see
   * AGENTS.md, <i>Retiring the plane's bare-named services</i>): the bare-named predecessor is not
   * named by any row this build writes, so nothing here could address it.
   *
   * <p><b>Exactly the one named service, never a label sweep.</b> The name comes off the deployment
   * row that was decommissioned ({@code container_name}, which under swarm IS the service's name),
   * which is the same source {@link #observe}, {@code DeploymentObserver} and {@code
   * ApplicationScaling} take it from and the same rule they keep: only the service a row named may
   * be acted on for that row. A sweep by environment label is {@link #removeEnvironmentContainers},
   * it belongs to a teardown, and it would take a tier's whole fleet down for one application's
   * conversion.
   *
   * <p><b>Best-effort and idempotent</b>: a service that is already absent is a log line, and a
   * runtime that refuses is a WARN naming the by-hand remedy. <b>An implementation must not
   * throw</b> — the deployment this runs behind is live, so a retirement that failed is an operator's
   * one-line cleanup, while an exception here would be a healthy deployment recorded as broken.
   *
   * <p><b>The service OBJECT alone is retired.</b> Volumes, and therefore data, are untouched: the
   * application is serving from the plane out of the same stores, and a retirement that removed one
   * would be a data loss dressed as bookkeeping.
   */
  void removeService(String name);

  /**
   * What the named service runs <b>now</b>, and what the orchestrator says about the update that
   * put it there. Empty when the runtime has no such service at all.
   *
   * <p><b>The startup sweep is its only caller, and this is the half of a self-update no
   * orchestrator does for us.</b> The instance that issues the update on its own service dies
   * before it can record the outcome, so the row it left {@code STARTING} is settled by whichever
   * instance boots next — and the only honest evidence is what is running: an image carrying the
   * row's own sha says this deployment is serving, any other image says something else is.
   *
   * <p>It replaces the {@code isSelf} question, which was "am I the container this row names". That
   * one cannot tell a completed succession from a rolled-back one — under swarm the service keeps
   * its name across both — and it made the sweep a statement about the deployer rather than about
   * the deployment.
   *
   * <p><b>The image is the check; {@code detail} is only wording.</b> Swarm's {@code UpdateStatus}
   * holds the most recent update alone, so a later deployment overwrites the verdict of the one a
   * row is about.
   */
  Optional<RunningImage> runningImage(String name);

  /**
   * Whether {@code name} is the service this deployer itself runs as — the case {@link #apply}
   * answers {@link ApplyOutcome#HANDED_OFF} for. Asked by a rename cutover before anything moves,
   * because renaming the deployer is the one rename the deployer cannot perform on itself: it would
   * have to retire the service it answers on. False for a driver that cannot tell.
   */
  default boolean isOwnService(String name) {
    return false;
  }

  /**
   * The sentence a self-rename is refused with — one spelling for the deploy service, which refuses
   * before anything is provisioned, and for a driver's own belt in {@link #apply}.
   */
  static String selfRenameRefusal(String applicationName, String renamedFrom) {
    return applicationName
        + " is this deployer's own service and its spec declares renamed_from: "
        + renamedFrom
        + " — a rename cutover would have to retire the service the deployer answers on, so"
        + " renaming the deployer stays a manual operation; remove renamed_from and do it by hand";
  }

  /**
   * Whether the named volume exists on the runtime — {@link VolumePresence#present()}, {@link
   * VolumePresence#absent()}, or {@link VolumePresence#unknown(String)} when the runtime could not
   * be asked or did not answer. Read-only: it never creates the volume it asks about.
   *
   * <p><b>A rename's private volumes are its only caller</b> (qits-1772). A successor that declares
   * {@code renamed_from:} mounts the predecessor's {@code <renamed_from>-<name>} only while its own
   * {@code <application>-<name>} does not exist and the predecessor's does — so the deploy service
   * asks twice per private volume, and only for a spec that declares a rename. Every other
   * deployment spends nothing here.
   *
   * <p><b>Unknown is not absent.</b> A daemon that is down must never read as "no such volume":
   * that would pin a successor onto its predecessor's data, or move it off its own onto an empty
   * one, on a guess. The caller fails the deployment instead, naming the volume and {@code detail}.
   */
  VolumePresence volumeExists(String name);

  /** What {@link #volumeExists} found. {@code detail} is the runtime's own words when unknown. */
  record VolumePresence(VolumeState state, String detail) {

    public static VolumePresence present() {
      return new VolumePresence(VolumeState.PRESENT, null);
    }

    public static VolumePresence absent() {
      return new VolumePresence(VolumeState.ABSENT, null);
    }

    public static VolumePresence unknown(String detail) {
      return new VolumePresence(VolumeState.UNKNOWN, detail);
    }
  }

  /** The three answers {@link #volumeExists} can give. */
  enum VolumeState {
    PRESENT,
    ABSENT,
    /** The runtime could not be asked or did not answer: neither of the other two may be assumed. */
    UNKNOWN
  }

  /**
   * Whether the service {@code spec} describes publishes a host port — declared in the repository's
   * {@code publishes:} or stated by deployment config. A rename cutover asks it to choose its order:
   * a successor that publishes the predecessor's port cannot start beside it, so the predecessor has
   * to stop first. The default reads the declaration alone; a driver that also renders config's
   * publishes answers for the union it would render.
   */
  default boolean publishesPorts(ServiceSpec spec) {
    return !spec.shape().publishes().isEmpty();
  }

  /**
   * The environment a deployment of {@code spec} would state, as {@code KEY=VALUE} entries in argv
   * order — this component's own variables and the resource family, then, when {@code
   * includeExtras}, the deployment config's own {@code env} (the settings rows, through {@link
   * DeploymentExtrasSource}); where a key repeats the LAST entry wins, exactly as it does on the
   * argv.
   *
   * <p>Read-only: no orchestrator call, no write. The settings preview's "next" half, which asks
   * without the extras and overlays the settings rows itself. With {@code includeExtras} it reads
   * the extras the way a deployment does, so a source that cannot be read throws what the
   * deployment would have refused with.
   */
  List<String> stagedEnvironment(ServiceSpec spec, boolean includeExtras);

  /**
   * Whether an update keeps {@code key} on the live service even though its argv does not state it
   * — the exemption the update's {@code --env-rm} diff applies (this component's own identity
   * variables and the provisioned-resource family). The settings preview reads it so that it never
   * reports as removed a key the real update would leave in place.
   */
  boolean keptWhenUnstated(String key);

  /**
   * The environment the named live service carries now, as {@code KEY=VALUE} entries — or empty
   * when the orchestrator has no such service (or cannot say). The settings preview's "running"
   * half; a deployment never calls it.
   */
  Optional<List<String>> liveEnvironment(String name);

  /** What a service runs, as the sweep reads it. {@code detail} is the orchestrator's own words. */
  record RunningImage(String imageRef, String detail) {}

  /**
   * Everything one deployed service is described by. Plain values, resolved before anything
   * runtime-side happens.
   *
   * <p>{@code commitSha} is carried beside {@code imageRef} rather than parsed back out of it: it
   * is the deployment's own identity — the sha the row was created with and the image was addressed
   * by — and it becomes the service's {@code service.version} resource attribute.
   *
   * <p>{@code version} is the released CalVer coordinate — what the image is tagged with and the
   * identity variables are stamped with. It is a second field rather than a reuse of the one above
   * because they answer different questions — the coordinate the release published, and the commit
   * it resolved to, which may legitimately be null.
   *
   * <p>{@code networks} is the <b>full membership</b>, primary first, and declaring it whole is
   * what lets an orchestrator that cannot join afterwards do the job at all: every swarm {@code
   * --network-add} recreates the task, so a hub-and-spoke model built out of joins would turn one
   * deployment into a restart storm.
   *
   * <p>{@code deploymentName} and {@code wireAlias} are two different facts and only the second is
   * the name — see {@link #nameOf}. The alias is the address peers dial and is derived in one place
   * ({@code PdNetworks}) so nothing that has to agree about an address can disagree.
   *
   * <p>{@code environmentId} and {@code environmentName} are the tier this is deployed into, and
   * <b>every service has one</b>: it carries the environment label and boots with {@code
   * QITS_ENVIRONMENT}. There is no {@code target} beside them any more — an implementation that
   * needed to know which plane a service was on is asking a question with one answer. (An
   * environment teardown therefore reaps by the environment label alone: the second half of that
   * filter existed to keep the plane from going down with a tier it merely served.) Null is a
   * mid-bootstrap install with no tier designated.
   *
   * <p>{@code healthCmd} is the repository's own readiness probe and, when present, <b>replaces</b>
   * the health path rather than adding to it: an image with no HTTP surface has no path to fetch.
   * Null is every service that has one.
   *
   * <p>{@code updateOrder} is the repository's, and it reaches the orchestrator as {@code
   * --update-order}. See {@link UpdateOrder}.
   *
   * <p>{@code publishMode} is the repository's too, and it says where a published host port is
   * bound. See {@link PublishMode}. It decides nothing when the application publishes no port,
   * which is most of them.
   *
   * <p>{@code stopGracePeriod} is the repository's as well — {@code stop_grace_period}, how long a
   * stopped task is given between SIGTERM and SIGKILL — and it reaches the orchestrator as {@code
   * --stop-grace-period}. Null is {@link #DEFAULT_STOP_GRACE_PERIOD}, which is every application
   * that holds nothing longer than a request open.
   *
   * <p>{@code resources} is what {@code ResourceProvisioning} made exist a moment ago, one entry
   * per resource the repository declared. Empty for every application that stores nothing, which is
   * most of them.
   *
   * <p>{@code volumes} is what the repository declared in {@code volumes:}, with every name already
   * resolved — a private volume is {@code <application>-<name>} by the time it gets here. It sits
   * beside {@code resources} because it is the same kind of statement: the application says what
   * storage it needs, and the platform makes it exist. Empty for every application that keeps
   * nothing, which is most of them.
   *
   * <p>{@code shape} is what the repository declared in {@code binds:}, {@code publishes:} and
   * {@code aliases:} — {@link DeclaredShape}, aliases still carrying {@code ${QITS_ENVIRONMENT}}
   * for the driver to resolve against {@code environmentName}. A driver renders it together with
   * {@link ServiceExtras}' mounts, publishes and aliases, the declaration winning wherever the two
   * name one target, one port or one alias. {@link DeclaredShape#NONE} for every file that
   * declares none, which is every file today.
   *
   * <p><b>What is still NOT here: extra env.</b> That is {@link ServiceExtras}, read by the driver
   * from deployment config. The shape that DOES travel here was read at the released tag of the
   * repository's own file, through the same reviewed path as {@code volumes} and {@code resources}
   * — never from the HTTP intake, which carries a coordinate and no part of a spec.
   *
   * <p>{@code renamedFrom} is the repository's {@code renamed_from:} — the application this one used
   * to be called — or null, which is every file that does not use the key. A driver derives ONE
   * thing from it: the predecessor's wire address ({@code PdNetworks.alias(environment,
   * renamedFrom)}) as a network alias on this service, so a dialer still holding the old address
   * reaches the successor once the predecessor is gone. Derived from the spec and never written to
   * deployment config: the file said it, and the address follows from the name.
   *
   * <p>{@code takesOverPredecessor} is true only for the deployment that replaces a predecessor that
   * is SERVING (its newest row {@code ACTIVE}) — the one moment dialers of the old address exist.
   * It decides whether the derived alias is worth a recreate on a service that already exists
   * without it; see the swarm driver's {@code missingDeclaredAlias}. Everywhere else the alias rides
   * the next create and an existing service is updated in place.
   */
  record ServiceSpec(
      String environmentId,
      String environmentName,
      String applicationId,
      String applicationName,
      String deploymentId,
      String commitSha,
      String version,
      String deploymentName,
      String wireAlias,
      List<String> networks,
      String imageRef,
      String healthPath,
      String healthCmd,
      boolean availableOnEnv,
      UpdateOrder updateOrder,
      PublishMode publishMode,
      List<ResourceBinding> resources,
      List<VolumeMount> volumes,
      Duration stopGracePeriod,
      DeclaredShape shape,
      String renamedFrom,
      boolean takesOverPredecessor) {

    /** Null lists and empty ones are the same statement: this application declared none. */
    public ServiceSpec {
      shape = shape == null ? DeclaredShape.NONE : shape;
      networks = networks == null ? List.of() : List.copyOf(networks);
      resources = resources == null ? List.of() : List.copyOf(resources);
      volumes = volumes == null ? List.of() : List.copyOf(volumes);
      updateOrder = updateOrder == null ? UpdateOrder.START_FIRST : updateOrder;
      publishMode = publishMode == null ? PublishMode.HOST : publishMode;
      stopGracePeriod = stopGracePeriod == null ? DEFAULT_STOP_GRACE_PERIOD : stopGracePeriod;
    }

    /** The shape before {@code renamed_from} reached the driver: no predecessor to answer for. */
    public ServiceSpec(
        String environmentId,
        String environmentName,
        String applicationId,
        String applicationName,
        String deploymentId,
        String commitSha,
        String version,
        String deploymentName,
        String wireAlias,
        List<String> networks,
        String imageRef,
        String healthPath,
        String healthCmd,
        boolean availableOnEnv,
        UpdateOrder updateOrder,
        PublishMode publishMode,
        List<ResourceBinding> resources,
        List<VolumeMount> volumes,
        Duration stopGracePeriod,
        DeclaredShape shape) {
      this(
          environmentId,
          environmentName,
          applicationId,
          applicationName,
          deploymentId,
          commitSha,
          version,
          deploymentName,
          wireAlias,
          networks,
          imageRef,
          healthPath,
          healthCmd,
          availableOnEnv,
          updateOrder,
          publishMode,
          resources,
          volumes,
          stopGracePeriod,
          shape,
          null,
          false);
    }

    /** The shape before {@code binds}/{@code publishes}/{@code aliases}: it declares none. */
    public ServiceSpec(
        String environmentId,
        String environmentName,
        String applicationId,
        String applicationName,
        String deploymentId,
        String commitSha,
        String version,
        String deploymentName,
        String wireAlias,
        List<String> networks,
        String imageRef,
        String healthPath,
        String healthCmd,
        boolean availableOnEnv,
        UpdateOrder updateOrder,
        PublishMode publishMode,
        List<ResourceBinding> resources,
        List<VolumeMount> volumes,
        Duration stopGracePeriod) {
      this(
          environmentId,
          environmentName,
          applicationId,
          applicationName,
          deploymentId,
          commitSha,
          version,
          deploymentName,
          wireAlias,
          networks,
          imageRef,
          healthPath,
          healthCmd,
          availableOnEnv,
          updateOrder,
          publishMode,
          resources,
          volumes,
          stopGracePeriod,
          null);
    }

    /** The shape before {@code stop_grace_period}: a spec that names none takes the default. */
    public ServiceSpec(
        String environmentId,
        String environmentName,
        String applicationId,
        String applicationName,
        String deploymentId,
        String commitSha,
        String version,
        String deploymentName,
        String wireAlias,
        List<String> networks,
        String imageRef,
        String healthPath,
        String healthCmd,
        boolean availableOnEnv,
        UpdateOrder updateOrder,
        PublishMode publishMode,
        List<ResourceBinding> resources,
        List<VolumeMount> volumes) {
      this(
          environmentId,
          environmentName,
          applicationId,
          applicationName,
          deploymentId,
          commitSha,
          version,
          deploymentName,
          wireAlias,
          networks,
          imageRef,
          healthPath,
          healthCmd,
          availableOnEnv,
          updateOrder,
          publishMode,
          resources,
          volumes,
          null);
    }

    /** The one network {@code docker run} can take, and the first one a service declares. */
    public String primaryNetwork() {
      return networks.isEmpty() ? null : networks.get(0);
    }
  }

  /**
   * How long a stopped task is given between SIGTERM and SIGKILL when the repository names nothing
   * — {@code stop_grace_period} in its spec.
   *
   * <p>Thirty seconds because every application drains in-flight responses for up to 25s on
   * SIGTERM ({@code quarkus.shutdown.timeout=25s} in qits-mirror and qits-edge, qits-1117), and
   * docker's own default of 10s would SIGKILL them mid-drain. It was a fixed flag for every service
   * until qits-1170: a long download through qits-edge or qits-mirror outlives any uniform number,
   * and only the repository knows how long its longest transfer runs, so the default is now the
   * floor a repository may raise rather than the only answer.
   */
  Duration DEFAULT_STOP_GRACE_PERIOD = Duration.ofSeconds(30);

  /**
   * How a replacement overlaps its predecessor — {@code update_order} in the repository's spec.
   *
   * <p><b>{@code start-first} is what makes a rollback lossless</b>: the predecessor keeps serving
   * while the successor is starting, and an orchestrator that fails the successor reverts to a
   * container that never stopped. Measured on this platform's daemon: under {@code start-first} an
   * unhealthy successor sat in {@code Starting} for 33 seconds while the old task stayed {@code
   * Running}, and the update ended {@code rollback_completed} with the spec reverted.
   *
   * <p><b>{@code stop-first} is for anything that cannot be two processes at once</b> — one binder
   * per published host port, one writer per store, one holder of a config volume. It still rolls
   * back; it just has a gap in service, which is what those applications have today anyway. This
   * component's own deployment is one of them.
   */
  enum UpdateOrder {
    START_FIRST,
    STOP_FIRST;

    /** The spelling in {@code .config/qits/deployments.yml}: {@code start-first}/{@code stop-first}. */
    public String spelling() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
  }

  /**
   * Where a published host port is held — {@code publish_mode} in the repository's spec, and the
   * default is {@code host} because that is what every publishing service does today.
   *
   * <p><b>{@code host} binds the port on the node from inside the task</b>, like a plain {@code
   * docker run -p}. One binder per port, so a replacement cannot start while its predecessor still
   * holds it — which is why every host-publishing service also declares {@code update_order:
   * stop-first}.
   *
   * <p><b>{@code ingress} hands the port to swarm's routing mesh</b>, which holds it across the
   * replace. The successor can start while the predecessor is still serving, so an ingress-mode
   * service may keep {@code start-first} and its lossless rollback. That is what lets the front
   * door pull its own successor's image through the door that is still up.
   *
   * <p><b>Neither mode can name an address.</b> Swarm's publish syntax has no ip field in either
   * one, so a published port is on every interface — see {@link ServiceExtras.Publish}. The mode
   * changes who holds the port, not who can reach it.
   *
   * <p>The two keys are independent and this component never derives one from the other: {@code
   * update_order} stays the repository's own statement, and an ingress-mode service that declares
   * {@code stop-first} gets {@code stop-first}.
   */
  enum PublishMode {
    HOST,
    INGRESS;

    /** The spelling in {@code .config/qits/deployments.yml}, and docker's own: {@code host}/{@code ingress}. */
    public String spelling() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * One volume the application declared, resolved: {@code source} is the volume itself — a private
   * volume's {@code <application>-<name>} already derived, or one of the platform's shared volumes
   * — and {@code target} is where it is mounted inside the container.
   *
   * <p><b>There is no kind here, and that is the point rather than an omission.</b> Every mount
   * that can reach this record is a named volume: a repository's grammar has no host bind in it,
   * because a host path is a statement about the machine and belongs to deployment config. A
   * renderer therefore never has to ask which kind it is holding. See {@code
   * SpecSource.DeploymentSpec.VolumeSpec}.
   *
   * <p>{@code readOnly} is the one option the grammar carries, which is {@link ServiceExtras.Mount}'s
   * own.
   */
  record VolumeMount(String source, String target, boolean readOnly) {}

  /**
   * One provisioned resource, as the service is told about it: {@code
   * QITS_RESOURCE_<NAME>_<SUFFIX>} for each of {@code values}, in order, with {@code name}
   * uppercased and its dashes underscored.
   *
   * <p><b>The contract is generic on purpose, and it generalised the day a second resource type
   * arrived.</b> A postgres resource carries {@code URL}/{@code USERNAME}/{@code PASSWORD}; an
   * idp-client resource carries {@code URL}/{@code CLIENT_ID}/{@code CLIENT_SECRET} — two different
   * shapes an application maps in its own shipped configuration defaults, and this component names
   * no framework and no datasource key for either. {@link #postgres} and {@link #idp} are the two
   * shapes today; a third resource type adds a third factory rather than a new field.
   *
   * <p>Every value here is one this component generated or received from the resource's own server
   * and holds in its own registry. Nothing arriving over HTTP contributes it, and nothing writes it
   * to a log.
   */
  record ResourceBinding(String name, List<Value> values) {

    /** One {@code QITS_RESOURCE_<NAME>_<suffix>} entry — {@code suffix} is upper-cased already. */
    public record Value(String suffix, String value) {}

    /** The postgres shape: {@code URL}, {@code USERNAME}, {@code PASSWORD}, in that order. */
    public static ResourceBinding postgres(String name, String url, String username, String password) {
      return new ResourceBinding(
          name,
          List.of(new Value("URL", url), new Value("USERNAME", username), new Value("PASSWORD", password)));
    }

    /** The idp-client shape: {@code URL}, {@code CLIENT_ID}, {@code CLIENT_SECRET}, in that order. */
    public static ResourceBinding idp(String name, String url, String clientId, String clientSecret) {
      return new ResourceBinding(
          name,
          List.of(
              new Value("URL", url),
              new Value("CLIENT_ID", clientId),
              new Value("CLIENT_SECRET", clientSecret)));
    }

    /** The value carried for one suffix, or null when this binding carries none by that name. */
    public String value(String suffix) {
      for (Value entry : values) {
        if (entry.suffix().equals(suffix)) {
          return entry.value();
        }
      }
      return null;
    }
  }

  enum PullOutcome {
    OK,
    /** The registry answered and has no such image — the deployment's {@code IMAGE_MISSING}. */
    IMAGE_MISSING,
    /**
     * The registry <b>refused</b> the pull: no credential, a rejected one, or one without access to
     * this repository.
     *
     * <p>It is its own outcome because it is a different thing to fix. {@code IMAGE_MISSING} says
     * nothing published this build and points at the repository's own last pipeline step; this says
     * the image may well be there and the deployer was not allowed to see it, which points at the
     * credential the daemon reads. Reading one as the other sent an operator to a pipeline that had
     * published perfectly well.
     */
    AUTH_REFUSED,
    /** The runtime failed some other way (daemon absent, registry unreachable, ...). */
    ERROR
  }

  record PullResult(PullOutcome outcome, String detail) {}

  /** What {@link #apply} did, and therefore what the caller does next. */
  enum ApplyOutcome {
    /** The spec is in the runtime's hands; ask {@link #awaitConverged} what became of it. */
    APPLIED,
    /**
     * This deployment replaces <b>this very process</b>, and the outcome will be recorded by
     * whichever instance is alive to record it — the row stays {@code STARTING} on purpose.
     *
     * <p>Neither instance can arbitrate its own succession: the old is about to stop and the new
     * cannot boot until it has, so it takes a third party. Swarm has one — the manager lives in
     * the daemon rather than in a container this process owns — which is what makes a deployment
     * of this component possible at all. The caller returns without a verdict, and the next boot's
     * sweep settles the row from what is running ({@link #runningImage}).
     */
    HANDED_OFF,
    /** Nothing runs that did not run before, and {@code detail} says why. */
    REFUSED
  }

  record ApplyResult(ApplyOutcome outcome, String detail) {}

  /** How an applied deployment ended. */
  enum ConvergenceOutcome {
    /** It is serving. */
    CONVERGED,
    /** The predecessor is serving again — the orchestrator put it back. */
    ROLLED_BACK,
    /** Neither converged nor cleanly reverted; {@code detail} is the diagnosis. */
    FAILED
  }

  /**
   * The verdict, and what it left for the caller to clean up.
   *
   * <p>{@code retired} is what the driver stopped and is done with. It comes back as data rather
   * than being removed inside the driver so the order the component has always had survives:
   * <b>rows first, services after</b>. Empty on a failure (nothing was retired — it is serving
   * again) and empty under swarm, where a replace is in place.
   */
  record Convergence(ConvergenceOutcome outcome, String detail, List<String> retired) {

    public Convergence {
      retired = retired == null ? List.of() : List.copyOf(retired);
    }

    public static Convergence converged(List<String> retired) {
      return new Convergence(ConvergenceOutcome.CONVERGED, null, retired);
    }

    public static Convergence failed(String detail) {
      return new Convergence(ConvergenceOutcome.FAILED, detail, List.of());
    }

    public static Convergence rolledBack(String detail) {
      return new Convergence(ConvergenceOutcome.ROLLED_BACK, detail, List.of());
    }

    public boolean converged() {
      return outcome == ConvergenceOutcome.CONVERGED;
    }
  }
}
