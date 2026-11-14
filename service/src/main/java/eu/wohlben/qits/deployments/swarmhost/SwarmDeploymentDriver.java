package eu.wohlben.qits.deployments.swarmhost;

import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.docker.SwarmArgv;
import eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Health;
import eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Mount;
import eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Publish;
import eu.wohlben.qits.containers.driver.swarm.ServiceSpec.UpdatePolicy;
import eu.wohlben.qits.containers.driver.swarm.ServiceUpdate;
import eu.wohlben.qits.deployments.deployments.control.DeclaredShape;
import eu.wohlben.qits.deployments.deployments.control.DeployedIdentity;
import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.DeploymentExtrasSource;
import eu.wohlben.qits.deployments.deployments.control.DeploymentIdentifiers;
import eu.wohlben.qits.deployments.deployments.control.DeploymentSpecParser;
import eu.wohlben.qits.deployments.deployments.control.ExtrasSnapshot;
import eu.wohlben.qits.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.deployments.deployments.control.DockerCredentialFile;
import eu.wohlben.qits.deployments.deployments.control.ResourceProvisioning;
import eu.wohlben.qits.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The swarm implementation of {@link DeploymentDriver}: a deployed application is a <b>service</b>,
 * and a replace is {@code docker service update --image} on it.
 *
 * <p><b>The whole cutover is flags.</b> {@code --update-order start-first} is the overlap, {@code
 * --update-monitor} is the gate window, {@code --update-failure-action rollback} is the rollback,
 * {@code --rollback-order} is the same overlap on the way back, {@code --stop-grace-period} is how
 * long a stopped task is given before SIGKILL (see {@link #updateFlags}), and a task does not
 * enter DNS until its healthcheck passes — measured on this host: while a task was {@code
 * Starting}, {@code getent hosts} answered nothing for its name while the VIP already existed. So there is no predecessor to find, nothing to stop, nothing to
 * restart and nobody to referee: this class issues one command and then reads a verdict.
 *
 * <p><b>The name is the address, and that is the one thing to keep in mind reading this.</b> {@code
 * container_name} does not exist in swarm — a task container is {@code
 * <service>.<slot>.<taskid>} — so the service NAME is what peers resolve, which makes it the wire
 * alias and makes a replace an update of the same service rather than a second container beside the
 * first. Every question this component asks afterwards ({@code awaitConverged}, {@code observe},
 * the environment teardown) is a service query, never a container name match.
 *
 * <p><b>The topology collapses to two overlays, and it is not a simplification for its own
 * sake.</b> {@code service update --network-add} recreates the task, so a hub-and-spoke topology —
 * one network per application, joined after the fact by every hub — would turn a single deployment
 * into a restart storm across the platform. So a service declares its whole membership at create
 * time, and the membership is ONE overlay: {@code qits.deployments.swarm.flat-network}
 * (attachable, so plain {@code docker run} containers — CI steps, workspaces, agents — keep working
 * on it). {@code qits-platform} was the second, and went with the plane whose services ran on it.
 * The per-application networks the caller asks for are dropped, deliberately and out loud.
 *
 * <p><b>What a service keeps across an update</b> is its mounts, its networks and its published
 * ports: {@link #buildUpdateArgv} changes the image, the identity labels, the environment and the
 * update policy, and nothing else. Changing the SHAPE of a service — another port, a new alias —
 * is therefore a {@code service rm} and a redeploy, which is the honest reading of it: a change of
 * shape is not a deployment.
 *
 * <p><b>A DECLARED volume is the one shape change a deployment performs for itself</b>, and the
 * distinction is what a repository said rather than what an operator did: {@code volumes:} in
 * {@code .config/qits/deployments.yml} is the application stating the storage it needs, and a
 * statement no deployment can ever honour would be worse than no key at all. So {@link #apply}
 * asks whether the live service has each declared volume, and a missing one makes this deployment
 * a {@code service rm} and a create — never an update with a mount bolted on, which swarm has no
 * flag for. The rule is one-directional and the asymmetry is load-bearing: see {@link
 * #missingDeclaredVolume}.
 *
 * <p><b>A declared network ALIAS is the second, and it arrived for the same reason</b>: an
 * attachment is restated whole or not at all, {@link #buildUpdateArgv} states no networks, and so
 * an alias reaches a LIVE service only on its next create. So {@link #apply} asks the same question
 * of the aliases, one-directionally and with the same refusal to act on an inspect that failed, and
 * each service recreates once, on its own next deployment, and matches for good afterwards. See
 * {@link #missingDeclaredAlias}. <b>The declarations are deployment config's alone now</b> — the
 * derived half was the environment-qualified alias a platform service was granted beside its bare
 * name, which was the staged half of the plane's own deletion and has nothing left to stage.
 *
 * <p><b>Two verbs here are not swarm-shaped at all</b>, and they are kept for what they answer:
 * {@code docker pull} classifies a missing image (swarm pulls on its own, but a task that never
 * starts is a much worse way to learn that nothing published this build), and {@code docker network
 * ls} reads the membership bookkeeping back whatever created the networks.
 *
 * <p><b>What a deployment adds beyond its image</b> — mounts, published ports, groups, environment
 * — is {@link ServiceExtras}, stated in deployment config and rendered here in swarm's own words.
 * Nothing translates a {@code docker run} argv any more: config states the intent, and the one
 * intent swarm cannot express — a publish bound to an ip — is refused rather than widened. The
 * repository's own {@code binds:}, {@code publishes:} and {@code aliases:} ({@link DeclaredShape})
 * are folded into the same statement first, the declaration winning wherever both name one target,
 * port or alias — see {@link #shapeOf}. They follow the extras' rules exactly: rendered by the
 * create only, and a declared alias missing from a live service forces the recreate.
 *
 * <p><b>There is no plane to read at all.</b> Every spec that reaches this class carries a tier, the
 * environment label and {@code QITS_ENVIRONMENT} are written for all of them, the wire alias is
 * {@code <env>-<app>} for all of them, and {@code qits.platform.deployments.target} is written by
 * nothing and read by nothing — the environment teardown reaps by the environment label alone again,
 * because the second filter existed only to keep a platform service from going down with a tier it
 * merely served. See {@link #removeEnvironmentContainers}.
 *
 * <p><b>One consequence of that rename is not handled here and cannot be.</b> A service is found by
 * NAME, the name is the wire alias, and the nine services that were the plane are running under
 * their old bare names — so the first deployment of each under this code finds nothing, CREATES
 * {@code <env>-<app>}, and leaves the bare-named service running. {@link #reap} is a no-op under
 * swarm (a replace is an update of the same service), so nothing retires it. That is an operator's
 * hand step, in a stated order, and AGENTS.md's <i>Retiring the plane's bare-named services</i> is
 * the whole of it.
 *
 * <p><b>The one piece of a self-update swarm does not do for us is the row.</b> The instance that
 * issues the update on its own service dies before the outcome exists, so the deployment stays
 * {@code STARTING} until an instance boots that can settle it — from {@link #runningImage}, the
 * image the service is running, which is the only reading that tells a completed succession from a
 * rolled-back one.
 */
@ApplicationScoped
public class SwarmDeploymentDriver implements DeploymentDriver {

  private static final Logger LOG = Logger.getLogger(SwarmDeploymentDriver.class);

  private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);

  /** How often {@link #awaitConverged} asks swarm where the update got to. */
  private static final Duration CONVERGE_POLL = Duration.ofSeconds(1);

  /**
   * How often a removed service's task containers are asked after (qits-2118). How LONG is the
   * spec's own: its {@code stop_grace_period} plus {@link DeploymentDriver#RECREATE_DRAIN_MARGIN} —
   * see {@link #drainDeadline}. The fixed ten one-second polls this replaced were shorter than the
   * thirty-second grace a postgres is given, so the give-up arm was the ordinary path for exactly
   * the service it existed to protect.
   */
  private static final Duration TWIN_DRAIN_WAIT = Duration.ofSeconds(1);

  /**
   * A network is removable roughly a second after the services on it go, not immediately —
   * measured. So a teardown retries rather than reporting a failure that is only a moment early.
   */
  private static final int NETWORK_REMOVE_ATTEMPTS = 5;

  private static final Duration NETWORK_REMOVE_WAIT = Duration.ofSeconds(1);

  /** Lines of service log kept as a failed convergence's diagnosis. */
  private static final int LOG_TAIL_LINES = 200;

  /**
   * Lines of ONE failed task's own log kept beside the service-wide tail — smaller, because there
   * are up to {@link #FAILED_TASKS_KEPT} of them and a task that died at boot says why in a few.
   */
  private static final int TASK_LOG_TAIL_LINES = 100;

  /** {@code docker service ps}'s filter for the current generation's tasks. */
  private static final String DESIRED_RUNNING = "desired-state=running";

  /** {@code docker service ps}'s template for a task's state phrase alone. */
  private static final String CURRENT_STATE_FORMAT = "{{.CurrentState}}";

  /** How many of the deployed image's failed tasks a failure detail names, newest first. */
  static final int FAILED_TASKS_KEPT = 3;

  /**
   * One task per line, for {@link #failedTasks}: id | image | desired state | current state |
   * error. The error is docker's free text and the one field that could carry a {@code |}, so it
   * is last and read as the remainder of the line.
   */
  static final String TASK_EVIDENCE_FORMAT =
      "{{.ID}}|{{.Image}}|{{.DesiredState}}|{{.CurrentState}}|{{.Error}}";

  /**
   * What swarm calls the update it is in the middle of, what it says about it, and <b>when it
   * started</b>. A service that has never been updated has no {@code UpdateStatus} at all, which is
   * why the format prints an empty state rather than failing — see {@link #awaitConverged}.
   *
   * <p><b>{@code StartedAt} sits in the middle on purpose.</b> The message is free text from the
   * daemon and is the one field that could contain a {@code |}, so it is read as the remainder of
   * the line; a timestamp behind it would be whatever the message left over.
   */
  static final String UPDATE_STATUS_FORMAT =
      "{{if .UpdateStatus}}{{.UpdateStatus.State}}|{{.UpdateStatus.StartedAt}}"
          + "|{{.UpdateStatus.Message}}{{else}}||{{end}}";

  /**
   * The startup sweep's evidence: what the service runs, then the same update status as wording.
   * One format because both fields sit on the object one {@code service inspect} returns.
   */
  static final String RUNNING_IMAGE_FORMAT =
      "{{.Spec.TaskTemplate.ContainerSpec.Image}}|" + UPDATE_STATUS_FORMAT;

  /**
   * The environment the live service carries, one {@code KEY=VALUE} per line — what an update
   * diffs against so it can state a REMOVAL as well as an addition. It reads the SPEC rather than a
   * running task: the spec is what the next task would inherit, and it is what an update rewrites.
   */
  static final String SPEC_ENV_FORMAT =
      "{{range .Spec.TaskTemplate.ContainerSpec.Env}}{{println .}}{{end}}";

  /**
   * The mounts the live service carries, one {@code <source>|<target>} per line — what a declared
   * volume is looked for in. It reads the SPEC rather than a running task for {@link
   * #SPEC_ENV_FORMAT}'s reason: the spec is what the next task would inherit.
   *
   * <p>A service with no mounts prints nothing, which is the honest answer and the one that makes a
   * declaration reach an application that never had a volume.
   */
  static final String SPEC_MOUNTS_FORMAT =
      "{{range .Spec.TaskTemplate.ContainerSpec.Mounts}}{{.Source}}|{{.Target}}{{println}}{{end}}";

  /**
   * Every network alias the live service carries, one per line, across all of its attachments —
   * what a declared alias is looked for in. It reads the SPEC for {@link #SPEC_ENV_FORMAT}'s
   * reason: the spec is what the next task inherits, and it is the half an operator's {@code
   * --network-rm}/{@code --network-add} rewrites.
   *
   * <p><b>The attachment's network is deliberately not printed, and not matched on.</b> The daemon
   * resolves a network NAME to its id when it stores the spec, so {@code .Target} here is an id
   * like {@code n0mb1...} and never {@code qits-net} — matching the shared overlay by name would
   * match nothing on every service alive, read as "no aliases at all", and recreate the estate. The
   * union over attachments is the safe reading and costs nothing in truth: this driver emits
   * aliases on the shared overlay and nowhere else, so the union IS that attachment's set.
   *
   * <p>A service with no aliases prints nothing, which is the honest answer and the one that makes
   * a declaration reach a service that never had one — every platform service today.
   */
  static final String SPEC_NETWORK_ALIASES_FORMAT =
      "{{range .Spec.TaskTemplate.Networks}}{{range .Aliases}}{{println .}}{{end}}{{end}}";

  /**
   * The networks a live service's spec attaches it to, one per line. <b>The daemon stores the
   * network's ID here, not its name</b> — the CLI resolves a name when it writes the spec — so
   * every reader matches a line against both a network's name and its ID ({@link #networkId}).
   */
  static final String SPEC_CAPABILITIES_FORMAT =
      "{{range .Spec.TaskTemplate.ContainerSpec.CapabilityAdd}}{{println .}}{{end}}";

  static final String SPEC_NETWORK_TARGETS_FORMAT =
      "{{range .Spec.TaskTemplate.Networks}}{{println .Target}}{{end}}";

  /**
   * The resolvers a live service's spec hands its tasks (qits-2117), as JSON — {@code
   * {"Nameservers":["10.231.0.5","1.1.1.1"]}}, or {@code {}}/{@code null} for a service created with
   * no {@code --dns}. Read for the order, which is the whole point: the private edge's VIP has to be
   * FIRST. See {@link #nameservers}.
   */
  static final String SPEC_DNS_FORMAT = "{{json .Spec.TaskTemplate.ContainerSpec.DNSConfig}}";

  /**
   * A service's virtual IPs, one {@code <NetworkID> <Addr>} line each — the runner's {@code
   * PrivateEdge.VIPS_FORMAT}, read by hand for the same reason: the native image is spared a JSON
   * binding for three fields.
   */
  static final String VIPS_FORMAT =
      "{{range .Endpoint.VirtualIPs}}{{.NetworkID}} {{.Addr}}{{\"\\n\"}}{{end}}";

  /** The comment line docker writes into a container's resolv.conf naming its upstream servers. */
  static final String EXT_SERVERS = "# ExtServers:";

  /**
   * The public edge's application name. With {@link #privateEdgeApplication} it is the pair that
   * never gets the private edge as resolver: the private edge would resolve through itself, and
   * the public one resolves the world for the world.
   */
  static final String PUBLIC_EDGE_APPLICATION = "qits-edge";

  /** A docker object id as {@code network inspect} and {@code service ls -q} print one. */
  private static final Pattern DOCKER_ID = Pattern.compile("[0-9a-z]{1,128}");

  /** The {@code host(x)} spelling docker uses for a server reached from the host's namespace. */
  private static final Pattern HOST_FORM = Pattern.compile("host\\((.*)\\)");

  /** A dotted-quad, before {@link InetAddress#ofLiteral} decides whether it is one. */
  private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

  /** Hex, colons and an optional embedded dotted-quad — never a zone or a name. */
  private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

  /** The quoted strings of the {@code Nameservers} array in {@link #SPEC_DNS_FORMAT}'s output. */
  private static final Pattern NAMESERVERS =
      Pattern.compile("\"Nameservers\"\\s*:\\s*\\[([^\\]]*)\\]");

  private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

  /**
   * The DESIRED task count the service spec holds. Guarded by {@code if}, because a global-mode
   * service has no {@code Replicated} block at all and the template would print {@code <no value>}
   * — an empty answer is "this service has no replica count", which {@link #parseReplicas} reads as
   * "cannot say" rather than as zero.
   */
  static final String REPLICAS_FORMAT =
      "{{if .Spec.Mode.Replicated}}{{.Spec.Mode.Replicated.Replicas}}{{end}}";

  /**
   * The live service's {@code stop_grace_period}, off its spec — swarm stores it as a plain
   * integer count of nanoseconds, or prints {@code <no value>} for a service created with none
   * stated. {@link #stopGracePeriod} is the one reader, for a database move's drain wait
   * (qits-2228).
   */
  static final String STOP_GRACE_PERIOD_FORMAT =
      "{{.Spec.TaskTemplate.ContainerSpec.StopGracePeriod}}";

  /**
   * How many tasks a service this component creates runs. One, everywhere: the applications on this
   * platform bind host ports from inside the task and write to stores with a single writer, so a
   * second task is a port collision or a corrupted volume rather than capacity.
   *
   * <p>It is stated once and used twice — the create declares it, and an update <b>restates</b> it.
   * See {@link #buildUpdateArgv} for why the update must.
   */
  static final int DEPLOYED_REPLICAS = 1;

  // The variables this driver writes for the deployer itself — QITS_ENVIRONMENT, QITS_APPLICATION,
  // QITS_VARIANT, QITS_DOMAIN and the telemetry four — and the QITS_RESOURCE_ prefix are spelled on
  // the seam (DeploymentDriver.DEPLOYER_OWN_VARIABLES and its neighbours) since qits-2048, because
  // the spec parser refuses an image_defaults: key of that family and must ask the same set this
  // driver's update diff exempts. They are inherited here under the names they always had.

  /**
   * How far an update's {@code StartedAt} may sit <i>before</i> the moment this process issued it
   * and still be believed to be that update.
   *
   * <p>Five seconds, and the asymmetry is the argument. In real time the daemon stamps {@code
   * StartedAt} <b>after</b> the CLI returned, so the only thing that can make it look earlier is
   * the two clocks disagreeing — an NTP-disciplined host is inside milliseconds and a daemon
   * reached over the network is inside a second or two. What this tolerance has to stay well under
   * is the distance to the update it must reject: the <i>previous</i> cutover of the same service,
   * which is another deployment and therefore minutes to months old. Five seconds is far above the
   * first number and far below the second, so no plausible skew makes a fresh status look stale and
   * no stale status can pass as fresh.
   */
  private static final Duration ISSUE_SKEW = Duration.ofSeconds(5);

  /**
   * How long an issued update is remembered when nobody ever came back for the verdict. Only a
   * deployment that never reached {@link #awaitConverged} can leave one behind (a crash between the
   * two calls), so this is a leak stop rather than a working value — an hour is far beyond any
   * health timeout, and pruning on write is what keeps the map bounded without a sweeper.
   */
  private static final Duration ISSUE_RETENTION = Duration.ofHours(1);

  /**
   * When this process issued the update swarm is now running, per service name — written by {@link
   * #apply}, read and cleared by {@link #awaitConverged}, which is the same "one orchestrator, one
   * seam" carry the retired docker driver used for its in-flight cutover state: both calls land on
   * this one {@code @ApplicationScoped} bean.
   *
   * <p>Concurrent because a bean is shared, not because the callers race: deployments run one at a
   * time on {@code pd-deploy-worker}.
   */
  private final Map<String, Instant> issuedUpdates = new ConcurrentHashMap<>();

  /**
   * The image this process last asked swarm to run, per service name — written by {@link #apply}
   * (update and create alike), read and cleared by {@link #awaitConverged}. It is what tells the
   * failed tasks of THIS deployment from the predecessor's in a failure detail; see {@link
   * #failedTasks}. Bounded by the number of services, since a name is overwritten, never added
   * twice.
   */
  private final Map<String, String> issuedImages = new ConcurrentHashMap<>();

  /**
   * The last spec this process applied, per service, for every {@code private} or {@code both}
   * service that takes the private edge as resolver (qits-2117) — what {@link
   * #reapplyBehindPrivateEdge} re-applies when the edge's VIP moves. Memory, on purpose and with the
   * {@code specRetries} trade: a deployer restart forgets it, and a service it forgot is put right
   * by its own next deployment, whose resolver drift recreates it. Bounded by the number of
   * services, since a name is overwritten, never added twice.
   */
  private final Map<String, ServiceSpec> resolverSpecs = new ConcurrentHashMap<>();

  /**
   * The private edge's VIP as it was before this process applied the edge, per edge service name —
   * written by {@link #apply}, read and cleared by {@link #awaitConverged}. Empty string for "there
   * was none". It is how a converged edge deployment tells a recreate (a new VIP) from an update.
   */
  private final Map<String, String> edgeVipsBefore = new ConcurrentHashMap<>();

  /** The wait between two polls of a stopping task. A seam so the suite's clock moves instead. */
  @FunctionalInterface
  interface Pause {
    void pause(Duration duration) throws InterruptedException;
  }

  /** {@link Thread#sleep} in production; the suite advances its own {@link #clock} instead. */
  Pause pause = duration -> Thread.sleep(duration.toMillis());

  /**
   * The clock the issue instant is read from. A field so the suite can pin it — a test that
   * compared a scripted {@code StartedAt} against the wall clock would be timing the build host.
   */
  Clock clock = Clock.systemUTC();

  /**
   * Go's {@code time.Time.String()}, which is what {@code docker service inspect --format} prints
   * for {@code .UpdateStatus.StartedAt} — measured on docker 29.7.2: {@code 2026-08-13
   * 10:21:12.655795838 +0000 UTC}. <b>The JSON body of the same inspect says RFC3339 instead</b>
   * ({@code 2026-08-13T10:21:12.655795838Z}), so the parser takes both and this is only the first
   * of the two. The trailing zone name — and Go's monotonic {@code m=+...} suffix, which a value
   * decoded from the API never carries — are matched and dropped.
   */
  private static final Pattern GO_TIMESTAMP =
      Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?) ([+-]\\d{2}:?\\d{2})(?:\\s.*)?$");

  /** The label swarm itself puts on a task container, naming the service it belongs to. */
  private static final String SWARM_SERVICE_LABEL = "com.docker.swarm.service.name";

  /**
   * The seed stack's namespace. The bootstrap deploys the seed as {@code docker stack deploy …
   * qits}, and a stack prefixes every service it creates — so the seed twin of {@code dev-qits-ci}
   * is {@code qits_dev-qits-ci}. Two things follow, and both live in {@link #apply}: the twin IS
   * this process when the deployer still runs as the seed (a self-update targets the stack-named
   * service), and for every other application the twin must be REMOVED at cutover — it holds the
   * wire alias and any host-mode ports, so a successor beside it schedules never (the port) or
   * serves half the traffic (the alias round-robins).
   */
  static final String SEED_STACK_PREFIX = "qits_";

  /** Task states that mean the task is not coming up. Everything else is patience. */
  private static final Set<String> TERMINAL_TASK_STATES =
      Set.of("failed", "rejected", "shutdown", "orphaned", "complete", "remove");

  /**
   * What docker says when the registry answered "no such image". Matched case-insensitively over
   * the pull's combined output — brittle by nature (docker's wording is not an API), so the match
   * errs toward {@code ERROR}: an unrecognized failure is a failed deployment, never a false
   * "nothing published an image".
   */
  private static final List<String> IMAGE_MISSING_MARKERS =
      List.of("manifest unknown", "not found", "name unknown", "repository does not exist");

  /**
   * What docker says when the registry <b>refused</b> the pull. Matched the same way and read
   * <b>before</b> {@link #IMAGE_MISSING_MARKERS}, which is the whole point of the order: docker's
   * own refusal reads {@code pull access denied for <image>, repository does not exist or may
   * require 'docker login'}, so it carries a missing-image marker inside it and a first-match-wins
   * list would keep calling a refusal a missing image.
   *
   * <p>Which is what it did. The platform's registry sits behind the edge proxy, and the day reads
   * there stop being anonymous every deployment would have been recorded as "nothing published this
   * build" — sending an operator to a pipeline that had published perfectly well, while the thing
   * to fix is the credential the daemon reads.
   *
   * <p>The narrowness rule is unchanged: anything neither list recognises is {@code ERROR}.
   */
  private static final List<String> AUTH_REFUSED_MARKERS =
      List.of(
          "pull access denied",
          "docker login",
          "authorization failed",
          "no basic auth credentials",
          "unauthorized",
          "authentication required");

  @ConfigProperty(name = "qits.deployments.container-runtime")
  String runtime;

  @ConfigProperty(name = "qits.deployments.pull-timeout-seconds")
  long pullTimeoutSeconds;

  @ConfigProperty(name = "qits.deployments.health-interval-seconds")
  long healthIntervalSeconds;

  @ConfigProperty(name = "qits.deployments.health-retries")
  int healthRetries;

  @ConfigProperty(name = "qits.deployments.health-start-period-seconds")
  long healthStartPeriodSeconds;

  @ConfigProperty(name = "qits.deployments.swarm.update-monitor-seconds")
  long updateMonitorSeconds;

  @ConfigProperty(name = "qits.deployments.swarm.flat-network")
  String flatNetwork;

  /**
   * The encrypted overlay a {@code private} or {@code both} service joins — {@code qits-private}
   * shipped. Created by {@link #ensureNetwork} the first time a service needs it, attachable so a
   * host container can join it later, and {@code --opt encrypted} from birth because a swarm
   * network cannot be switched to encrypted without detaching everything on it.
   */
  @ConfigProperty(name = "qits.deployments.swarm.private-network")
  String privateNetwork;

  /**
   * The private overlay's subnet, fixed rather than drawn from docker's pool, because the private
   * edge's allow-list names it. {@code 10.231.0.0/20} shipped.
   */
  @ConfigProperty(name = "qits.deployments.swarm.private-network.subnet")
  String privateNetworkSubnet;

  /**
   * The private edge's application name (qits-2117) — the swarm service labelled {@link
   * DeploymentDriver#APP_NAME_LABEL}{@code =<this>} whose VIP on {@link #privateNetwork} a {@code
   * private} or {@code both} service is handed as its first resolver. {@code qits-edge-private}
   * shipped, the name qits-containers' {@code PrivateEdgeResolver} and the runner's {@code
   * PrivateEdge} look for too.
   */
  @ConfigProperty(name = "qits.deployments.swarm.private-edge.application")
  String privateEdgeApplication;

  /**
   * Where this process's own resolvers are read from (qits-2117): the upstreams that follow the
   * private edge's VIP in a private service's {@code --dns} list. {@code /etc/resolv.conf} shipped;
   * another path is for a suite.
   */
  @ConfigProperty(name = "qits.deployments.swarm.private-edge.resolv-conf")
  String resolvConf;

  /**
   * The platform's one domain, handed to every container as {@link #DOMAIN_VARIABLE}. See that
   * constant for why it is one value rather than five composed spellings.
   *
   * <p>{@code Optional} because the key ships with an empty default and SmallRye reads an empty
   * value as absent — an installation that has not stated its domain must get no variable rather
   * than a wrong one, so that every consumer falls back to whatever default it ships. See {@link
   * #environment} for the guard.
   */
  @ConfigProperty(name = "qits.deployments.platform-domain")
  Optional<String> platformDomain;

  /**
   * Whether a service create and a service update carry {@code --with-registry-auth}. False
   * shipped, and then every argv here is what it was byte for byte.
   *
   * <p><b>What the flag does.</b> It serialises the credential the CLI holds for the registry into
   * the service spec, so the swarm agent authenticates the task's own pull. Without it only the
   * warm-up {@link #pull} is authenticated — that one runs as this process, with this
   * process's {@code DOCKER_CONFIG} — and the node-side pull the task then performs carries nothing
   * and is refused. The image being present in the local image store is not a substitute: swarm
   * re-pulls per node, and this platform being one node is a coincidence rather than a contract.
   *
   * <p><b>Why it is a key rather than always on.</b> The platform's registry reads are anonymous
   * today, so the flag has nothing to serialise and would only put an empty auth block on every
   * service spec. The key is what lets the deployer ship ahead of the flip and be turned on with
   * the deployment that mounts a {@code config.json} — one restart rather than a release.
   *
   * <p><b>It does not conflict with {@code --no-resolve-image}.</b> The two answer different
   * questions: no-resolve tells the CLI not to ask the registry to turn the tag into a digest (the
   * manager keeps the tag as written, which is what the seed's registry-less {@code qits/*} tags
   * need), and this one hands the agents a credential for the pull they perform later. Nothing
   * about carrying auth makes the manager resolve a digest again.
   */
  @ConfigProperty(name = "qits.deployments.registry-auth")
  boolean registryAuth;

  /**
   * The admin password the deployer provisions with on both postgres servers — and therefore the
   * superuser password the private one is initialised with, which this driver writes as {@link
   * DeploymentDriver#SERVER_PASSWORD_VARIABLE} for that one application (qits-2175). Absent, its
   * deployment has already failed naming the key in {@code ResourceProvisioning.ensureAll}.
   */
  @ConfigProperty(name = "qits.deployments.postgres.admin-password")
  Optional<String> postgresAdminPassword = Optional.empty();

  /**
   * The host HTTP port this installation was bootstrapped on — what {@code ${QITS_PORT}} in a
   * repository's {@code publishes:} becomes ({@link DeclaredShape#publishesFor}). 8080 shipped.
   */
  @ConfigProperty(name = "qits.deployments.qits-port")
  int qitsPort;

  @ConfigProperty(name = "qits.deployments.output-max-chars")
  int outputMaxChars;

  /**
   * Where this component's own docker credential is written when it was handed an idp client — see
   * {@code DockerCredentialFile}. Every CLI call here hands {@link ContainerProcess} the environment
   * {@link DockerCredentialFile#dockerEnvironment} derives from it — {@code DOCKER_CONFIG} set to it
   * only when a {@code config.json} is actually there, so the warm-up
   * pull and the {@code --with-registry-auth} the create and update serialise for the agents read
   * the same credential.
   */
  @ConfigProperty(name = "qits.deployments.docker-config-dir")
  Optional<String> dockerConfigDir;

  /**
   * Where the extras are read from, ONCE PER ARGV — see {@link DeploymentExtrasSource} and, for the
   * staleness a boot snapshot costs, {@link ExtrasSnapshot}. The settings store answers it in
   * production ({@code SettingsExtrasSource}); the suite states a snapshot in a lambda.
   */
  @Inject DeploymentExtrasSource extrasSource;

  /**
   * The docker socket THIS process has mounted, whose group a declared socket bind earns — see
   * {@link #socketGroup}. A field rather than a constant so the suite can point it at a file of its
   * own; package-private and set through {@link #scriptDockerSocket} for the proxy reason {@link
   * #scriptCli} gives.
   */
  private volatile Path dockerSocket = Path.of(DeclaredShape.DOCKER_SOCKET);

  void scriptDockerSocket(Path socket) {
    this.dockerSocket = socket;
  }

  /**
   * One docker CLI call. A seam so the suite can script the conversation: the argv IS the contract
   * with swarm, and asserting it — and the verdicts read back out of it — needs no daemon.
   */
  @FunctionalInterface
  interface Cli {
    ContainerProcess.Result run(List<String> argv, Duration timeout);
  }

  private volatile Cli cli;

  /**
   * Package-private, and a method rather than a field write, because an injected reference is a CDI
   * client proxy: a field set on the proxy would never reach the bean.
   */
  void scriptCli(Cli scripted) {
    this.cli = scripted;
  }

  private ContainerProcess.Result run(List<String> argv, Duration timeout) {
    Cli scripted = cli;
    return scripted != null
        ? scripted.run(argv, timeout)
        : ContainerProcess.run(
            null,
            argv,
            DockerCredentialFile.dockerEnvironment(dockerConfigDir),
            timeout,
            outputMaxChars);
  }

  /** A swarm service's name IS its address, so the wire alias is the name. */
  @Override
  public String nameOf(ServiceSpec spec) {
    return spec.wireAlias();
  }

  @Override
  public ApplyResult apply(ServiceSpec spec) {
    String name = spec.wireAlias();
    List<String> networks = collapse(spec);
    for (String network : networks) {
      ensureNetwork(
          new Network(
              network, null, NetworkKind.BUNDLE, null));
    }

    // Asked BEFORE the update, because after it this process may not exist to ask anything: the
    // manager stops this task the moment the new one is healthy. `own` is the service label on
    // this very container: when the deployer still runs as the SEED STACK's service, that label
    // is the stack-prefixed name, and the self-update must target that service — creating a
    // bare-named sibling instead would leave two deployers on one registry.
    String own = ownServiceName();
    boolean self = own.equals(name) || own.equals(SEED_STACK_PREFIX + name);
    String target = self ? own : name;
    if (self && spec.renamedFrom() != null) {
      // The deploy service refuses this before anything is provisioned; this is the belt. A rename
      // cutover retires the predecessor's service, and the deployer cannot retire the one it is.
      String refusal = DeploymentDriver.selfRenameRefusal(spec.applicationName(), spec.renamedFrom());
      LOG.warnf("Refusing to deploy %s: %s", name, refusal);
      return new ApplyResult(ApplyOutcome.REFUSED, refusal);
    }
    boolean exists = serviceExists(target);
    // A declared volume the live service does not have can only reach it through a CREATE — see
    // `missingDeclaredVolume`. Never for a self-update: removing the service this process answers
    // on would leave nothing to create the successor.
    String missingVolume = exists && !self ? missingDeclaredVolume(spec, target) : null;
    // The same sentence about aliases, and for the same reason — see `missingDeclaredAlias`. Asked
    // only when the volumes did not already decide it: the answer would change nothing, and a
    // recreate spends no second inspect to learn it is still a recreate. `!self` is what keeps the
    // deployer's own succession out of it, exactly as above.
    String missingAlias =
        exists && !self && missingVolume == null ? missingDeclaredAlias(spec, target, networks) : null;
    // ...and the networks themselves, for a service whose exposure class changed — see
    // `networkDrift`. Asked last, and never for a self-update, exactly as the two above.
    String drift =
        exists && !self && missingVolume == null && missingAlias == null
            ? networkDrift(target, networks)
            : null;
    // The private edge as resolver (qits-2117): asked once, for a private or both service that is
    // not an edge, and used three ways — the drift below, the create's --dns, and a self-update's
    // --dns-rm/--dns-add. Every other service spends no CLI call on it.
    Resolvers resolvers = privateEdgeResolvers(spec);
    // ...and the resolver list itself, last of the four, under the same rules: never for a
    // self-update, and only when the wanted list could be established at all — a missing edge must
    // not recreate every private service on the platform into a create with no --dns.
    String resolverDrift =
        exists
                && !self
                && missingVolume == null
                && missingAlias == null
                && drift == null
                && resolvers.found()
            ? resolverDrift(target, resolvers.servers())
            : null;
    boolean recreate =
        missingVolume != null || missingAlias != null || drift != null || resolverDrift != null;
    boolean updating = exists && !recreate;
    if (isPrivateEdge(spec) && !self) {
      // The VIP before, so the verdict can tell a recreate (a new VIP) from an update (the same).
      edgeVipsBefore.put(target, Optional.ofNullable(privateEdgeVip().vip()).orElse(""));
    }
    List<String> argv;
    try {
      if (!updating) {
        argv = buildCreateArgv(spec, target, networks, resolvers);
      } else if (self) {
        argv = buildSelfUpdateArgv(spec, target, networks, resolvers);
      } else {
        argv = buildUpdateArgv(spec, target);
      }
    } catch (ServiceExtras.Refused | IllegalArgumentException e) {
      // Deployment config said something swarm cannot express — or a value failed one of
      // qits-containers-driver's belts (SwarmIdentifiers), which is the same sentence one layer
      // down. Nothing was applied — the argv is built before the command runs — so this deployment
      // changed nothing.
      LOG.warnf("Refusing to deploy %s: %s", name, e.getMessage());
      return new ApplyResult(ApplyOutcome.REFUSED, e.getMessage());
    }
    if (recreate) {
      // Destructive, and announced before it happens: the writable layer of the running task goes
      // with the service. Everything an application is supposed to keep is on a volume — which is
      // exactly what this deployment is putting right — but the sentence is what a person reads
      // afterwards to know why a container they were looking at is gone. Removed after the argv is
      // built, like the seed twin below, so a REFUSED deployment changes nothing.
      if (drift != null) {
        LOG.warnf(
            "Recreating service %s: %s. A service update states no networks, so the service is"
                + " removed and created again on the networks its exposure names — its writable"
                + " layer goes with it. It recreates once: afterwards the networks match.",
            target, drift);
      } else if (resolverDrift != null) {
        // The CLI's --dns-add does not promise to keep the order, and the order is the point — the
        // private edge's VIP has to be asked first — so drift recreates rather than updating.
        LOG.warnf(
            "Recreating service %s: its resolvers are %s and should be %s",
            target, resolverDrift, resolvers.servers());
      } else if (missingVolume != null) {
        LOG.warnf(
            "Recreating service %s: it declares the volume %s and the live service does not have it."
                + " A service update cannot add a mount, so the service is removed and created"
                + " again — its writable layer goes with it.",
            target, missingVolume);
      } else {
        LOG.warnf(
            "Recreating service %s: it declares the network alias %s and the live service does not"
                + " answer to it. A service update states no networks and swarm has no"
                + " add-an-alias, so the service is removed and created again — its writable layer"
                + " goes with it. It recreates once: afterwards the alias is live and every later"
                + " deployment is an ordinary update.",
            target, missingAlias);
      }
      String stillStopping = removeForRecreate(spec, target);
      if (stillStopping != null) {
        // The service is gone and its task is not: creating now would be a second writer on the
        // volume. It stays absent, and the next deployment takes the create path.
        LOG.warnf("Refusing to deploy %s: %s", name, stillStopping);
        return new ApplyResult(ApplyOutcome.REFUSED, stillStopping);
      }
    }
    if (!self) {
      // The seed twin dies at cutover, and it dies FIRST. It holds the wire alias (DNS would
      // round-robin between seed and successor — measured: a step's ci-daemon registered with the
      // instance that had not launched it and exited 6) and any host-mode ports (the successor's
      // task then sits Pending on "port already in use" forever). Removed after the argv is built,
      // so a REFUSED deployment changes nothing; if the create still fails, the task the twin ran
      // is what the last boot's stack file restores.
      String twinStillStopping = reapSeedTwin(spec, name);
      if (twinStillStopping != null) {
        LOG.warnf("Refusing to deploy %s: %s", name, twinStillStopping);
        return new ApplyResult(ApplyOutcome.REFUSED, twinStillStopping);
      }
    }
    ContainerProcess.Result result = run(argv, APPLY_TIMEOUT);
    if (result.exitCode() != 0 || result.timedOut()) {
      LOG.warnf(
          "Could not %s service %s: %s", updating ? "update" : "create", name, result.output());
      return new ApplyResult(ApplyOutcome.REFUSED, result.output());
    }
    if (!self) {
      // WHICH image the verdict is about — the failure detail picks this image's tasks out of the
      // predecessor's. Never for a self-update: nothing here waits for its verdict.
      issuedImages.put(target, spec.imageRef());
      // What the private edge's next move re-applies — see `reapplyBehindPrivateEdge`.
      if (wantsPrivateEdgeResolvers(spec)) {
        resolverSpecs.put(target, spec);
      } else {
        resolverSpecs.remove(target);
      }
    }
    if (updating && !self) {
      // WHICH update the verdict is about, recorded the only place that knows. See
      // `awaitConverged`: a service that has been cut over before answers with the PREVIOUS
      // update's terminal state until the daemon has replaced it, and one poll of that is a
      // deployment declared live 43 milliseconds after it was issued.
      rememberIssued(target);
    } else if (!updating) {
      // A create has no update to wait for, and a service that was removed and made again must not
      // inherit the removed one's issue instant — its empty status would then never settle. That
      // second sentence is the recreate above, which is exactly such a service.
      issuedUpdates.remove(target);
    }
    if (self) {
      // The self-update, and the arbiter is what makes it possible at all: the manager lives in
      // the daemon rather than in a container this process owns, so it can stop this task, start the
      // successor and revert the spec if the successor never goes healthy. Nothing here waits for
      // that — this process is what is being replaced.
      LOG.infof(
          "Self-update issued on service %s: the swarm manager finishes it, and the row stays"
              + " STARTING until the instance that survives records it",
          target);
      if (updating && resolvers.found()) {
        checkSelfResolverOrder(target, resolvers);
      }
      return new ApplyResult(
          ApplyOutcome.HANDED_OFF, "the swarm manager arbitrates this service's own succession");
    }
    return new ApplyResult(ApplyOutcome.APPLIED, null);
  }

  /**
   * Swarm's own verdict on the update, polled from {@code .UpdateStatus.State}.
   *
   * <ul>
   *   <li>{@code completed} — the successor is running and healthy; DNS points at it.
   *   <li>{@code rollback_completed} — the successor never went healthy, swarm reverted the spec,
   *       and under {@code start-first} the predecessor never stopped serving. A failed deployment
   *       with nothing lost. The detail carries the evidence: swarm's own message, then {@code
   *       service ps} and the log tail.
   *   <li>{@code paused} / {@code rollback_paused} — swarm stopped trying and is waiting for a
   *       person. A failure, with its message as the diagnosis.
   *   <li>{@code updating} / {@code rollback_started} — keep waiting.
   * </ul>
   *
   * <p><b>A freshly created service has no {@code UpdateStatus} at all</b>, and that is the one
   * case this cannot read off a single field: the first deployment of an application is a {@code
   * service create}, and swarm records an update status only from the first {@code update} onward.
   * So an empty state falls through to the task itself — a task is {@code Running} only once its
   * healthcheck has passed, which is the same statement the field would have made.
   *
   * <p><b>The field says nothing about WHICH update it describes, and reading it as though it did
   * was a live defect.</b> {@code UpdateStatus} holds the most recent update of the service, and
   * {@code service update --detach} returns before the daemon has replaced it — so the first poll
   * after an update reads either the PREVIOUS cutover's terminal state or, in the window where
   * swarm has cleared it, nothing at all. Both were read as an answer: qits-docs was recorded
   * {@code ACTIVE} 43ms after its update was issued, off a {@code completed} left by an earlier
   * deployment, and its predecessor was decommissioned while swarm was still rolling the successor
   * back. The empty arm was as wrong for the same reason — under {@code start-first} the
   * predecessor's task is still {@code Running}, so the task fallback answered "converged" about
   * the deployment being replaced.
   *
   * <p>So an update this process ISSUED is matched by {@code StartedAt}: a status stamped before
   * the issue instant (less {@link #ISSUE_SKEW}) belongs to the update before this one and is
   * <b>pending</b>, an absent or unreadable stamp is <b>pending</b>, and the task fallback is
   * reached only where it is still true — a service this process has just created. Nothing here
   * waits forever: the caller's deadline ends it either way, and the timeout detail names what was
   * last seen, so an update whose status never arrives fails as an update rather than passing as
   * one.
   *
   * <p><b>Every failing arm carries its own evidence, because the row is all anybody gets.</b> The
   * detail is what {@code DeployService} writes onto the deployment, and by the time a person reads
   * it swarm has already reverted the spec — the failed task is gone, and its logs go with it. The
   * timeout arm always captured {@code service ps} and the log tail; {@code rollback_completed},
   * the arm a failed deployment ACTUALLY reaches, captured neither, and its one line ({@code
   * "update rolled back due to failure…"}) says that something failed without saying what. It
   * cost a whole investigation of the 2026-09-14 dev-qits-projects rollback, which could not
   * establish what state the task had been left in. So it captures the same two things, in the same
   * order — both bounded by {@code qits.deployments.output-max-chars}, which every {@link
   * #run} already truncates to, on top of the {@code --tail} the log capture asks for.
   *
   * <p><b>And the deployed image's failed tasks come first, each with its own log tail</b>, because
   * the service-wide tail is not the failed task's: it interleaves every task swarm remembers, and
   * the long-running predecessor fills it. A 2026-10-26 rollback of qits-containers stored a detail
   * whose tail was all predecessor while the task that exited 1 within half a second had none of
   * its output in it. So every failing arm reads: swarm's sentence, then {@link
   * #failedTaskEvidence}, then {@code service ps}, then (where it was captured before) the
   * service-wide tail last — anything that ever cuts the detail cuts that tail first.
   */
  @Override
  public Convergence awaitConverged(String name, Duration timeout) {
    Convergence verdict = awaitVerdict(name, timeout);
    // The private edge's own deployment, now that it is serving: if its VIP moved, every private
    // and both service this process deployed still asks the old one first (qits-2117).
    String vipBefore = edgeVipsBefore.remove(name);
    if (vipBefore != null && verdict.outcome() == ConvergenceOutcome.CONVERGED) {
      String vipAfter = privateEdgeVip().vip();
      if (vipAfter != null && !vipAfter.equals(vipBefore)) {
        reapplyBehindPrivateEdge(vipAfter);
      }
    }
    return verdict;
  }

  private Convergence awaitVerdict(String name, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    // Null when nothing issued an update for this service — the first deployment of an
    // application, or an instance that did not perform the update it is asking about.
    Instant issued = issuedUpdates.get(name);
    String last = "(never inspected)";
    try {
      while (true) {
        ContainerProcess.Result inspected =
            run(
                SwarmArgv.serviceInspect(runtime, name, UPDATE_STATUS_FORMAT),
                INSPECT_TIMEOUT);
        if (inspected.exitCode() != 0) {
          // Not a state at all: swarm has no such service. There is nothing to keep waiting for.
          return Convergence.failed("no service " + name + ": " + safe(inspected.output()));
        }
        String[] parts = safe(inspected.output()).strip().split("\\|", 3);
        String state = parts[0].strip().toLowerCase(Locale.ROOT);
        String startedAt = parts.length > 1 ? parts[1].strip() : "";
        String message = parts.length > 2 ? parts[2].strip() : "";
        String stale = issued == null ? null : notThisUpdate(state, startedAt, issued);
        if (stale != null) {
          // Somebody else's update, or not this one yet. Keep waiting, and remember the wording so
          // the timeout can say what it kept seeing.
          last = stale;
        } else {
          last = state.isEmpty() ? "created" : state;
          switch (state) {
            case "completed" -> {
              return Convergence.converged(List.of());
            }
            case "rollback_completed" -> {
              // The evidence, in the same shape the timeout below uses: swarm's sentence, then the
              // tasks, then the log tail. This is the arm a failed deployment actually lands in.
              return Convergence.rolledBack(
                  "swarm rolled "
                      + name
                      + " back to its predecessor: "
                      + (message.isBlank() ? "the successor never went healthy" : message)
                      + "\n"
                      + failedTaskEvidence(name)
                      + tasks(name)
                      + "\n"
                      + logs(name));
            }
            case "paused", "rollback_paused" -> {
              return Convergence.failed(
                  "swarm paused the update of "
                      + name
                      + ": "
                      + message
                      + "\n"
                      + failedTaskEvidence(name)
                      + tasks(name));
            }
            case "" -> {
              // A service nothing has updated yet — the first deployment of this application.
              Convergence fresh = freshCreateVerdict(name);
              if (fresh != null) {
                return fresh;
              }
            }
            default -> {
              /* updating, rollback_started: keep waiting */
            }
          }
        }
        if (System.nanoTime() >= deadline) {
          break;
        }
        try {
          Thread.sleep(CONVERGE_POLL.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return Convergence.failed("interrupted while waiting for " + name + " to converge");
        }
      }
      return Convergence.failed(
          "service "
              + name
              + " was still "
              + last
              + " after "
              + timeout.toSeconds()
              + "s\n"
              + failedTaskEvidence(name)
              + tasks(name)
              + "\n"
              + logs(name));
    } finally {
      // The verdict is reached, however it went: this update is nobody's in-flight state now.
      issuedUpdates.remove(name);
      issuedImages.remove(name);
    }
  }

  /**
   * Why this {@code UpdateStatus} is not the verdict of the update that was issued at {@code
   * issued} — or null when it is, and may be read.
   *
   * <p>Every arm here is a reason to keep waiting rather than to fail: the caller's deadline is
   * what ends the wait, and it carries the returned wording into the failure detail.
   */
  private static String notThisUpdate(String state, String startedAt, Instant issued) {
    if (state.isEmpty()) {
      // Swarm clears the field while it takes the update in. The task fallback is NOT reachable
      // here: under start-first the predecessor is still Running, so it would answer "converged"
      // about the deployment being replaced.
      return "not started yet (no update status)";
    }
    Instant stamped = parseStartedAt(startedAt);
    if (stamped == null) {
      // Never a crash and never a verdict: a stamp this cannot read is a stamp it cannot match, and
      // the raw text is what a person needs to see in the timeout.
      return state + " with an unreadable StartedAt '" + startedAt + "'";
    }
    if (stamped.isBefore(issued.minus(ISSUE_SKEW))) {
      return state + " from the earlier update started " + startedAt;
    }
    return null;
  }

  /**
   * Docker's two spellings of the same instant — Go's {@code time.Time.String()} from {@code
   * --format} and RFC3339 from the JSON body — or null for anything else, {@code <nil>} and {@code
   * <no value>} included.
   *
   * <p>Package-private for the parsing test.
   */
  static Instant parseStartedAt(String value) {
    String raw = safe(value).strip();
    if (raw.isEmpty()) {
      return null;
    }
    Matcher go = GO_TIMESTAMP.matcher(raw);
    if (go.matches()) {
      String offset = go.group(3);
      if (offset.length() == 5) {
        offset = offset.substring(0, 3) + ":" + offset.substring(3); // +0000 -> +00:00
      }
      raw = go.group(1) + "T" + go.group(2) + offset;
    }
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Record that this process just issued an update of {@code service}, pruning whatever an earlier
   * deployment left behind — see {@link #ISSUE_RETENTION}.
   */
  private void rememberIssued(String service) {
    Instant now = clock.instant();
    issuedUpdates.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(ISSUE_RETENTION)));
    issuedUpdates.put(service, now);
  }

  /**
   * The first deployment's verdict, read off the tasks: {@code Running} is healthy (swarm holds a
   * task in {@code Starting} until its healthcheck passes), a generation whose tasks have all ended
   * is a failure, and anything else is still pending.
   */
  private Convergence freshCreateVerdict(String name) {
    List<String> states = runningGenerationStates(name);
    if (states.isEmpty()) {
      return null;
    }
    if (states.stream().anyMatch("running"::equals)) {
      return Convergence.converged(List.of());
    }
    if (states.stream().allMatch(TERMINAL_TASK_STATES::contains)) {
      return Convergence.failed(
          "no task of "
              + name
              + " came up: "
              + String.join(", ", states)
              + "\n"
              + failedTaskEvidence(name)
              + tasks(name));
    }
    return null;
  }

  /**
   * One observation of the service, in the {@code <status>/<health>} spelling the gate and the
   * observer both read.
   *
   * <p>The mapping is swarm's task state, and it carries the health in it rather than beside it: a
   * task is {@code Starting} until its healthcheck passes and {@code Running} afterwards, so
   * {@code running/healthy} and {@code starting/unhealthy} are exact rather than approximate. A
   * service the daemon does not have is {@code gone}, which is a structural fact rather than a
   * wording match.
   */
  @Override
  public HealthGate.Poll observe(String name) {
    ContainerProcess.Result listed = observeTasks(name);
    if (listed.exitCode() != 0) {
      // The application may live under the seed stack's name — the deployer's own self-update
      // keeps its stack-named service. Asking only the bare alias flipped a healthy self-updated
      // deployer to FAILED: two observation passes read "no such service" while
      // qits_dev-qits-deployments served. The same fallback runningImage already has.
      listed = observeTasks(SEED_STACK_PREFIX + name);
    }
    if (listed.exitCode() != 0) {
      return HealthGate.Poll.gone(listed.output());
    }
    List<String> states = taskStates(listed.output());
    if (states.stream().anyMatch("running"::equals)) {
      return HealthGate.Poll.of("running/healthy");
    }
    if (states.stream().anyMatch(state -> !TERMINAL_TASK_STATES.contains(state))) {
      return HealthGate.Poll.of("starting/unhealthy");
    }
    return HealthGate.Poll.of("exited/unhealthy");
  }

  /**
   * The desired task count, off the service spec — asked under the bare alias first and under the
   * seed stack's name second, the fallback {@link #observe} and {@link #runningImage} both have.
   */
  @Override
  public OptionalInt desiredReplicas(String name) {
    ContainerProcess.Result inspected = inspectReplicas(name);
    if (inspected.exitCode() != 0) {
      inspected = inspectReplicas(SEED_STACK_PREFIX + name);
    }
    return inspected.exitCode() != 0 ? OptionalInt.empty() : parseReplicas(inspected.output());
  }

  private ContainerProcess.Result inspectReplicas(String service) {
    return run(
        SwarmArgv.serviceInspect(runtime, service, REPLICAS_FORMAT),
        INSPECT_TIMEOUT);
  }

  /**
   * Package-private for the parsing test. Anything that is not a whole number is <b>empty</b> and
   * never zero: {@code <no value>}, a blank line from a global-mode service and a daemon that
   * answered nonsense all mean "this cannot say what the count is", and reading any of them as a
   * deliberate scale-to-zero would silence the very demotion the observer exists to make.
   */
  static OptionalInt parseReplicas(String output) {
    String raw = safe(output).strip();
    try {
      return OptionalInt.of(Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /**
   * {@code service update --replicas <n>}, which is what {@code docker service scale} is underneath
   * — spelled as an update because every other command this class issues is one, and because {@code
   * scale} blocks on convergence by default while this component reads its own verdicts.
   *
   * <p><b>Scaling this process's own service to zero is refused</b>, and the refusal is the point:
   * it would stop the only thing that could ever scale it back up, and it would do so at the moment
   * the operator is holding the API that would have done it. Scaling it UP is allowed and is a
   * no-op — the count is already one.
   */
  @Override
  public ScaleResult scale(String name, int replicas) {
    if (replicas < 0) {
      return new ScaleResult(ScaleOutcome.REFUSED, "a replica count cannot be negative");
    }
    String target = resolveService(name);
    if (target == null) {
      return new ScaleResult(ScaleOutcome.REFUSED, "the orchestrator has no service " + name);
    }
    boolean self = isSelf(target);
    if (self && replicas == 0) {
      return new ScaleResult(
          ScaleOutcome.REFUSED,
          "refusing to scale "
              + target
              + " to 0: that is this deployer's own service, and nothing would be left to scale it"
              + " back up");
    }
    return issue(
        target,
        self,
        SwarmArgv.serviceScale(runtime, target, replicas),
        "scale " + name + " to " + replicas);
  }

  /**
   * {@code service update --force}, swarm's canonical bounce: the spec is unchanged, so the manager
   * simply replaces the tasks — under {@code start-first} with an overlap, under {@code stop-first}
   * with a gap, exactly as a deployment of the same service would.
   *
   * <p><b>A service scaled to zero has no task to force</b>, and swarm answers such an update
   * happily while nothing at all happens. That is refused here rather than reported as a bounce, so
   * an operator is told to scale it up instead of being told a stopped application was restarted.
   */
  @Override
  public ScaleResult restart(String name) {
    String target = resolveService(name);
    if (target == null) {
      return new ScaleResult(ScaleOutcome.REFUSED, "the orchestrator has no service " + name);
    }
    OptionalInt declared = parseReplicas(inspectReplicas(target).output());
    if (declared.isPresent() && declared.getAsInt() == 0) {
      return new ScaleResult(
          ScaleOutcome.REFUSED,
          target + " is declared to run 0 tasks, so there is nothing to replace — scale it up");
    }
    return issue(
        target,
        isSelf(target),
        SwarmArgv.serviceForceUpdate(runtime, target),
        "restart " + name);
  }

  /**
   * One operator-issued {@code service update}, and the one bookkeeping line it owes.
   *
   * <p><b>It CLEARS the issue instant rather than recording one.</b> {@code issuedUpdates} is
   * {@code awaitConverged}'s way of telling this deployment's verdict from the previous one, and
   * nothing waits for a scale — so remembering this update would leave an instant no verdict ever
   * consumes, while forgetting the deployment's would be worse: the two cannot overlap (both run on
   * {@code pd-deploy-worker}), so what is in the map at this point is always stale.
   */
  private ScaleResult issue(String target, boolean self, List<String> argv, String what) {
    ContainerProcess.Result result = run(argv, APPLY_TIMEOUT);
    if (result.exitCode() != 0 || result.timedOut()) {
      LOG.warnf("Could not %s: %s", what, result.output());
      return new ScaleResult(ScaleOutcome.REFUSED, safe(result.output()));
    }
    issuedUpdates.remove(target);
    if (self) {
      LOG.infof(
          "Issued %s on this process's own service %s: the swarm manager finishes it, and the"
              + " instance that survives is the one that sees the result",
          what, target);
      return new ScaleResult(
          ScaleOutcome.HANDED_OFF, "the swarm manager arbitrates this service's own succession");
    }
    LOG.infof("Issued %s on service %s", what, target);
    return new ScaleResult(ScaleOutcome.SCALED, null);
  }

  /**
   * The name the daemon actually holds this application under — the bare wire alias, or the seed
   * stack's twin of it — or null when it holds neither.
   *
   * <p>The caller passes the name the deployment ROW carries, which is the alias; resolving the twin
   * here is the same fallback {@link #observe} and {@link #runningImage} make, and it is what keeps
   * an operator's restart working on a platform whose deployer still runs as the seed.
   */
  private String resolveService(String name) {
    if (serviceExists(name)) {
      return name;
    }
    String twin = SEED_STACK_PREFIX + name;
    return serviceExists(twin) ? twin : null;
  }

  /** The seed stack's twin counts: it is the deployer too, under the stack's prefix. */
  @Override
  public boolean isOwnService(String name) {
    String own = ownServiceName();
    return !own.isBlank() && (own.equals(name) || own.equals(SEED_STACK_PREFIX + name));
  }

  /**
   * The union {@link #buildCreateArgv} would render — the repository's {@code publishes:} and
   * deployment config's — so a rename cutover orders itself by what the service will actually bind.
   * An extras read that refuses answers with the declaration alone: the deployment reaches the same
   * refusal at the argv build a moment later and changes nothing.
   */
  @Override
  public boolean publishesPorts(ServiceSpec spec) {
    try {
      return !shapeOf(spec, extrasOf(spec)).publishes().isEmpty();
    } catch (ServiceExtras.Refused e) {
      return !spec.shape().publishes().isEmpty();
    }
  }

  /** Whether the named service is the one this process is a task of. False outside a container. */
  private boolean isSelf(String service) {
    String own = ownServiceName();
    return !own.isBlank() && own.equals(service);
  }

  private ContainerProcess.Result observeTasks(String service) {
    return run(
        SwarmArgv.servicePs(
            runtime, service, List.of(DESIRED_RUNNING), true, CURRENT_STATE_FORMAT),
        INSPECT_TIMEOUT);
  }

  /**
   * Nothing to reap: a replace is in place, so the predecessor and the successor are one service.
   *
   * <p>Removing what the caller names here would remove the deployment that just went live, which
   * is why this is a stated no-op rather than a delegation to {@code service rm}.
   */
  @Override
  public void reap(List<String> names) {
    if (!names.isEmpty()) {
      LOG.debugf("Nothing to reap for %s: a swarm replace is an update of the same service", names);
    }
  }

  /**
   * One {@code service rm}, and the whole of it.
   *
   * <p><b>There is deliberately no task-drain wait here, and {@link #reapSeedTwin} has one.</b> That
   * one removes a service whose alias and host ports a SUCCESSOR is about to take, so seconds of
   * shutdown overlap are two writers on one volume — measured twice, and paid for in a corrupted
   * WAL. Nothing starts under this name: the application is already serving from the platform
   * plane, under the bare alias, out of a different service. So the seconds the daemon spends
   * stopping the task overlap nothing, and waiting for them would only park the deploy worker after
   * the deployment that queued this has finished.
   *
   * <p><b>{@code service rm} removes the service object, and nothing else.</b> Volumes are not the
   * service's to remove and swarm does not touch them — which is the property that makes this safe
   * to run at all: the tier service being retired and the plane service now serving are two
   * addresses over one store.
   *
   * <p><b>The self guard is a belt, and it is the one {@link #scale} wears.</b> The conversion
   * cannot reach it — this component's own flip is {@code HANDED_OFF} to the swarm manager and the
   * successor's startup sweep is what records it, so the code below never runs in the process being
   * replaced — but a driver that could remove the service answering the API is a driver one caller
   * away from a platform with no deployer, and the refusal costs one inspect.
   */
  @Override
  public void removeService(String name) {
    if (isSelf(name)) {
      LOG.warnf(
          "Refusing to remove service %s: it is this deployer's own service, and there would be"
              + " nothing left to answer for it — the scale-to-zero stance, for the same reason",
          name);
      return;
    }
    if (!serviceExists(name)) {
      LOG.infof("No service %s to remove: already absent", name);
      return;
    }
    ContainerProcess.Result removed = run(SwarmArgv.serviceRm(runtime, name), CLEANUP_TIMEOUT);
    if (removed.exitCode() != 0 || removed.timedOut()) {
      LOG.warnf(
          "Could not remove service %s — remove it by hand (docker service rm %s): %s",
          name, name, removed.output());
    } else {
      LOG.infof("Removed service %s", name);
    }
  }

  /**
   * What the service runs now, and swarm's own account of the update that put it there — one
   * inspect, because the two fields sit on one object.
   *
   * <p><b>The image is the verdict and {@code UpdateStatus} is only the wording</b>, which is the
   * whole reason the sweep asks this rather than reading the status alone: that field holds the
   * most recent update, so a later deployment overwrites what it said about the one a row is about.
   * The image a service is running cannot be out of date in that way.
   */
  @Override
  public Optional<RunningImage> runningImage(String name) {
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.serviceInspect(runtime, name, RUNNING_IMAGE_FORMAT),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      // The application may live under the seed stack's name: the deployer's own self-update
      // targets the stack-named service it runs as, so the successor's startup sweep must read
      // its evidence from the same place — asking only the bare alias settled the self-update's
      // row as "interrupted" while the service ran the row's exact image.
      inspected =
          run(
              SwarmArgv.serviceInspect(runtime, SEED_STACK_PREFIX + name, RUNNING_IMAGE_FORMAT),
              INSPECT_TIMEOUT);
    }
    if (inspected.exitCode() != 0) {
      return Optional.empty(); // swarm has no such service under either name
    }
    // image | state | startedAt | message — the sweep wants the first two words and the last; WHEN
    // the update started is `awaitConverged`'s business, and reading past it here is what keeps the
    // message whole.
    String[] parts = safe(inspected.output()).strip().split("\\|", 4);
    String image = parts[0].strip();
    if (image.isEmpty()) {
      return Optional.empty();
    }
    String state = parts.length > 1 ? parts[1].strip() : "";
    String message = parts.length > 3 ? parts[3].strip() : "";
    return Optional.of(
        new RunningImage(image, state.isEmpty() ? null : (state + ": " + message).strip()));
  }

  /**
   * The service this task belongs to — the label swarm puts on every task container
   * ({@value #SWARM_SERVICE_LABEL}), read via this container's own id. Empty outside a container,
   * which is every local run.
   *
   * <p>Asked by {@link #apply} alone, and it answers one question: may this process wait for the
   * verdict, or is it what is being replaced. The NAME matters as much as the yes: a deployer
   * still running as the seed stack's service must update that stack-named service in place.
   * Whether the succession then WORKED is a different question, asked of the image by the next
   * instance to boot ({@link #runningImage}) — "am I this service" is true of the successor and of
   * a predecessor swarm rolled back to, alike.
   */
  private String ownServiceName() {
    String hostname = selfContainerId();
    if (hostname.isBlank()) {
      return "";
    }
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.containerInspect(
                runtime, hostname, "{{index .Config.Labels \"" + SWARM_SERVICE_LABEL + "\"}}"),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return "";
    }
    String service = safe(inspected.output()).strip();
    return service.isBlank() || "<no value>".equals(service) ? "" : service;
  }

  /**
   * Remove the seed stack's service for this application, when one is still there — and WAIT for
   * its task containers to be gone before returning.
   * <p>
   * The wait is not about ports: a successor whose host port is briefly still held sits
   * {@code Pending} and schedules by itself. It is about VOLUMES. {@code service rm} returns
   * while the task is still shutting down, and a successor created in that window starts beside
   * it — for a stateless service an overlap of seconds is nothing, for postgres on its data
   * volume it is two writers on one cluster. Measured twice: the un-reaped twin corrupted the WAL
   * over hours, and the first reap-then-create did the same in its seconds of overlap — both
   * boots ended in "could not locate a valid checkpoint record" at the next cold start.
   *
   * <p><b>And when the wait runs out, it is {@link #removeForRecreate}'s rule</b> (qits-2118): a
   * spec holding a {@code private:} volume is refused rather than created beside the twin's task —
   * the sentence is returned, and the caller turns it into the REFUSED deployment. Without one the
   * successor is created anyway, with the WARN. Null is "go ahead".
   */
  private String reapSeedTwin(ServiceSpec spec, String name) {
    String twin = SEED_STACK_PREFIX + name;
    if (!serviceExists(twin)) {
      return null;
    }
    ContainerProcess.Result removed = run(SwarmArgv.serviceRm(runtime, twin), CLEANUP_TIMEOUT);
    if (removed.exitCode() != 0) {
      LOG.warnf(
          "Could not remove the seed service %s — the successor may wait on its ports: %s",
          twin, removed.output());
      return null;
    }
    Duration deadline = drainDeadline(spec);
    if (awaitTasksGone(twin, deadline)) {
      LOG.infof("Removed the seed service %s: %s takes the alias and the ports", twin, name);
      return null;
    }
    String volume = privateVolume(spec);
    if (volume != null) {
      return stillStopping(twin, deadline, volume);
    }
    LOG.warnf(
        "The seed service %s is removed but its task is still stopping after %ss — the successor"
            + " may start beside it",
        twin, deadline.toSeconds());
    return null;
  }

  /**
   * How long a removed service's task is waited for: its {@code stop_grace_period} — the moment
   * swarm SIGKILLs it — plus {@link DeploymentDriver#RECREATE_DRAIN_MARGIN} for the daemon to take
   * the container away (qits-2118). {@code DeployService.convergenceTimeout} counts the same sum.
   */
  static Duration drainDeadline(ServiceSpec spec) {
    return spec.stopGracePeriod().plus(DeploymentDriver.RECREATE_DRAIN_MARGIN);
  }

  /** The first {@code private:} volume the spec mounts, or null when it holds none. */
  private static String privateVolume(ServiceSpec spec) {
    return spec.volumes().stream()
        .filter(volume -> !volume.shared())
        .map(VolumeMount::source)
        .findFirst()
        .orElse(null);
  }

  /** The refusal sentence, one spelling for both callers. */
  private static String stillStopping(String service, Duration deadline, String volume) {
    return service
        + " is still stopping after "
        + deadline.toSeconds()
        + "s; not creating beside it on "
        + volume;
  }

  /**
   * Whether the removed service's task containers are gone yet, waited on for at most {@code
   * deadline} — polled every {@link #TWIN_DRAIN_WAIT}, the time read off {@link #clock}.
   *
   * <p>{@code service rm} returns while the task is still shutting down, and the wait is about
   * VOLUMES rather than ports — see {@link #reapSeedTwin}, which measured it twice on postgres.
   * Both callers remove a service whose successor is created on the same storage, so both owe the
   * same wait. The give-up arm exists so a wedged task cannot hold every deployment hostage, and
   * what each caller does with it depends on the volume: a private one is a refusal, never a
   * second writer (qits-2118).
   */
  private boolean awaitTasksGone(String service, Duration deadline) {
    Instant started = clock.instant();
    while (true) {
      ContainerProcess.Result tasks =
          run(
              SwarmArgv.psQuiet(
                  runtime, List.of(SwarmArgv.labelFilter(SWARM_SERVICE_LABEL, service))),
              INSPECT_TIMEOUT);
      if (tasks.exitCode() == 0 && safe(tasks.output()).strip().isEmpty()) {
        return true;
      }
      if (Duration.between(started, clock.instant()).compareTo(deadline) >= 0) {
        return false;
      }
      try {
        pause.pause(TWIN_DRAIN_WAIT);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /**
   * Where this task container's own id is read from. A field rather than a constant so the suite
   * can point it at a file of its own — a test that depended on the build host having an
   * {@code /etc/hostname} would be asserting something about the host.
   */
  Path hostnameFile = Path.of("/etc/hostname");

  /** This task container's own id. Blank outside a container, which is every local run. */
  private String selfContainerId() {
    try {
      return Files.readString(hostnameFile).strip();
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Create the overlay when it is missing — and <b>only</b> the two the collapse keeps.
   *
   * <p>A per-application or per-environment network would be a network no service is ever on: the
   * membership is declared at create time and a later join costs a task restart, so building the
   * hub-and-spoke topology under swarm would be paying that price on every deployment. The caller
   * still asks for them (the state machine computes a membership without knowing who runs it),
   * and the answer here is a
   * debug line rather than an overlay nothing uses.
   */
  @Override
  public boolean ensureNetwork(Network spec) {
    if (!collapsed(spec.name())) {
      LOG.debugf(
          "Not creating '%s': under swarm the topology is %s, declared at service create",
          spec.name(), flatNetwork);
      return false;
    }
    if (run(SwarmArgv.networkInspect(runtime, spec.name(), null), CLEANUP_TIMEOUT).exitCode()
        == 0) {
      // Already there, labels and all — including one the bootstrap made. The labels are how this
      // component FINDS the networks it made, not a claim of ownership over every network.
      return false;
    }
    ContainerProcess.Result created = run(buildNetworkCreateArgv(spec), CLEANUP_TIMEOUT);
    if (created.exitCode() != 0) {
      LOG.warnf("Could not ensure overlay '%s': %s", spec.name(), created.output());
      return false;
    }
    LOG.infof("Created attachable overlay %s (%s)", spec.name(), spec.kind());
    return true;
  }

  /**
   * Package-private for the argv test. {@code --attachable} is the load-bearing flag: it is what
   * lets plain {@code docker run} containers — CI steps, workspace containers, project agents —
   * live on the same network as the services, which is the whole reason the platform can move one
   * component at a time.
   */
  List<String> buildNetworkCreateArgv(Network spec) {
    if (isPrivateNetwork(spec.name())) {
      // The private overlay is the platform's, not an environment's or an application's, so it
      // carries the one label that says what it is for. Encrypted at birth — see `privateNetwork` —
      // and on a fixed subnet the private edge's allow-list names.
      return SwarmArgv.overlayNetworkCreate(
          runtime,
          spec.name(),
          true,
          true,
          privateNetworkSubnet.strip(),
          List.of(NETWORK_LABEL + "=private"));
    }
    List<String> labels = new ArrayList<>();
    labels.add(NETWORK_LABEL + "=" + spec.kind().name().toLowerCase(Locale.ROOT));
    if (spec.environmentId() != null) {
      labels.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    if (spec.applicationName() != null) {
      labels.add(APP_NAME_LABEL + "=" + spec.applicationName());
    }
    return SwarmArgv.overlayNetworkCreate(runtime, spec.name(), true, false, null, labels);
  }

  /**
   * Remove the network, retrying for a few seconds.
   *
   * <p>Measured: a network is removable about a second after the services on it are gone, not
   * immediately — the tasks' endpoints outlive the {@code service rm} that ordered them away. A
   * single attempt would report a failure that is only early.
   */
  @Override
  public void removeNetwork(String network) {
    for (int attempt = 1; attempt <= NETWORK_REMOVE_ATTEMPTS; attempt++) {
      ContainerProcess.Result removed =
          run(SwarmArgv.networkRm(runtime, network), CLEANUP_TIMEOUT);
      if (removed.exitCode() == 0) {
        return;
      }
      String output = safe(removed.output()).toLowerCase(Locale.ROOT);
      if (output.contains("not found") || output.contains("no such network")) {
        return; // somebody already did
      }
      if (attempt == NETWORK_REMOVE_ATTEMPTS) {
        LOG.debugf("Could not remove network '%s': %s", network, removed.output());
        return;
      }
      try {
        Thread.sleep(NETWORK_REMOVE_WAIT.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * Every network this component labelled, read back from the daemon.
   *
   * <p>{@code network ls} is the same command whatever created the networks — a bridge a retired
   * docker path made and an overlay this class made answer it alike — so the labels stay the one
   * record of the membership.
   */
  @Override
  public List<Network> networks() {
    ContainerProcess.Result listed =
        run(
            SwarmArgv.networkLs(
                runtime, List.of(SwarmArgv.labelFilter(NETWORK_LABEL)), "{{.Name}}|{{.Labels}}"),
            CLEANUP_TIMEOUT);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list this component's networks: %s", listed.output());
      return List.of();
    }
    return parseNetworks(listed.output());
  }

  /** Package-private for the parsing test: one {@code name|k=v,k=v} line per network. */
  static List<Network> parseNetworks(String output) {
    List<Network> networks = new ArrayList<>();
    for (String line : safe(output).split("\\R")) {
      String[] parts = line.trim().split("\\|", 2);
      if (parts.length < 2 || parts[0].isEmpty()) {
        continue;
      }
      String environmentId = null;
      String applicationName = null;
      NetworkKind kind = null;
      for (String label : parts[1].split(",")) {
        int equals = label.indexOf('=');
        if (equals < 0) {
          continue;
        }
        String key = label.substring(0, equals).trim();
        String value = label.substring(equals + 1).trim();
        switch (key) {
          case ENVIRONMENT_LABEL -> environmentId = value;
          case APP_NAME_LABEL -> applicationName = value;
          case NETWORK_LABEL -> kind = kind(value);
          default -> {
            /* someone else's label */
          }
        }
      }
      if (kind != null) {
        networks.add(new Network(parts[0], environmentId, kind, applicationName));
      }
    }
    return List.copyOf(networks);
  }

  private static NetworkKind kind(String value) {
    for (NetworkKind candidate : NetworkKind.values()) {
      if (candidate.name().equalsIgnoreCase(value)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Nothing to detach. A service's networks are declared when it is created and a teardown does not
   * reshape one; what makes the networks removable is the services going, which the reap before
   * this already ordered, and {@link #removeNetwork}'s retry loop waits for.
   *
   * <p>The name is the seam's and the seam's question outlived the platform plane it was asked
   * about; see {@code DeploymentDriver.reap}.
   */
  @Override
  public void detachPlatformPlane(List<String> networks) {
    LOG.debugf("Nothing to detach from %s: a service's networks are declared, not joined", networks);
  }

  /**
   * The environment's services, by the one label that means "this tier's own".
   *
   * <p><b>There was a second filter and it went with the plane.</b> It demanded {@code
   * qits.platform.deployments.target=environment} beside the environment id, because a platform
   * service carried a tier's label while serving every tier, and a teardown of the designated tier
   * would otherwise have taken the whole plane with it. There is no service that serves a tier it
   * does not belong to any more, so the environment label is the whole question again.
   */
  @Override
  public int removeEnvironmentContainers(String environmentId) {
    ContainerProcess.Result listed =
        run(
            SwarmArgv.serviceLsQuiet(
                runtime, List.of(SwarmArgv.labelFilter(ENVIRONMENT_LABEL, environmentId))),
            CLEANUP_TIMEOUT);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list services of environment %s: %s", environmentId, listed.output());
      return 0;
    }
    List<String> ids = lines(listed.output());
    if (ids.isEmpty()) {
      return 0;
    }
    run(SwarmArgv.serviceRm(runtime, ids), CLEANUP_TIMEOUT);
    return ids.size();
  }

  /**
   * One {@code docker volume ls -q --filter name=^<name>$}, read for an exact line.
   *
   * <p><b>A list rather than {@code volume inspect}</b>, because the list tells its two answers apart
   * by output and keeps the exit code for the third: an inspect of a missing volume exits non-zero
   * exactly as a daemon that is down does, and telling the two apart would mean matching wording.
   * Here exit zero is an answer — the name on a line of its own is present, anything else absent
   * (docker's {@code name} filter is a substring/regex match, so the exact line is the check and the
   * anchors only keep the output short) — and a non-zero exit or a timeout is {@code UNKNOWN} with
   * the CLI's own words.
   *
   * <p>The name is validated as a mount source first, like every name {@link #volumeFlags} renders:
   * one that could not be mounted is not a question worth asking the runtime, and the refusal comes
   * back as {@code UNKNOWN} so the deployment fails naming it.
   *
   * <p>The volume list is the node's this CLI talks to. That is where a private volume lives on the
   * single-node swarm this deployer drives, and it is the same daemon {@link #apply} creates the
   * service through.
   */
  @Override
  public VolumePresence volumeExists(String name) {
    String volume;
    try {
      volume = DeploymentIdentifiers.requireVolumeSource(name);
    } catch (RuntimeException e) {
      return VolumePresence.unknown(e.getMessage());
    }
    ContainerProcess.Result listed =
        run(
            DockerArgv.volumeLsByName(runtime, volume),
            INSPECT_TIMEOUT);
    if (listed.exitCode() != 0 || listed.timedOut()) {
      return VolumePresence.unknown(
          listed.timedOut()
              ? "docker volume ls timed out after " + INSPECT_TIMEOUT.toSeconds() + "s"
              : safe(listed.output()).strip());
    }
    return lines(listed.output()).contains(volume)
        ? VolumePresence.present()
        : VolumePresence.absent();
  }

  /**
   * Classifying a missing image is ours, not swarm's — see the class javadoc.
   *
   * <p>The wording match is deliberately narrow: anything it does not recognise is {@code ERROR},
   * so a daemon that is down never reads as "nothing published this build".
   *
   * <p><b>A refusal is asked about first</b>, because docker's refusal wording contains a
   * missing-image marker — see {@link #AUTH_REFUSED_MARKERS}.
   */
  @Override
  public PullResult pull(String imageRef) {
    List<String> argv;
    try {
      argv = DockerArgv.pull(runtime, imageRef);
    } catch (IllegalArgumentException refused) {
      // The library's image belt: a reference no registry could answer for. Nothing ran, and it is
      // not a missing image — a deployment that names nothing pullable is an ERROR saying so.
      return new PullResult(PullOutcome.ERROR, refused.getMessage());
    }
    ContainerProcess.Result result = run(argv, Duration.ofSeconds(pullTimeoutSeconds));
    if (result.exitCode() == 0 && !result.timedOut()) {
      return new PullResult(PullOutcome.OK, null);
    }
    String output = safe(result.output());
    String lowered = output.toLowerCase(Locale.ROOT);
    if (AUTH_REFUSED_MARKERS.stream().anyMatch(lowered::contains)) {
      return new PullResult(PullOutcome.AUTH_REFUSED, output);
    }
    boolean missing = IMAGE_MISSING_MARKERS.stream().anyMatch(lowered::contains);
    return new PullResult(missing ? PullOutcome.IMAGE_MISSING : PullOutcome.ERROR, output);
  }

  // --- the argv ------------------------------------------------------------------------------

  /**
   * Package-private for the argv test: the whole service, declared at once.
   *
   * <p>{@code --detach} because this component reads the verdict itself ({@link #awaitConverged});
   * without it the CLI blocks on convergence and the deployment's timeout would be the CLI's.
   * {@code --no-resolve-image} because the seed's {@code qits/*} tags exist only on this host and no
   * registry can resolve them to a digest.
   */
  List<String> buildCreateArgv(ServiceSpec spec, String name, List<String> networks) {
    return buildCreateArgv(spec, name, networks, privateEdgeResolvers(spec));
  }

  /**
   * The create, with the private edge's resolvers already looked up — {@link #apply} asks once and
   * hands the same answer to the drift check and here. See {@link #dnsFlags}.
   */
  List<String> buildCreateArgv(
      ServiceSpec spec, String name, List<String> networks, Resolvers resolvers) {
    // Read once, off ONE snapshot: the same refusal would otherwise be reached twice, the aliases
    // belong to the membership while everything else belongs to the flags below it, and one
    // snapshot is what keeps every reading of one deployment in agreement.
    //
    // The repository's declared shape (binds, publishes, aliases) is folded in here, ONCE, with the
    // socket's group when it binds the socket: from this line on there is one statement of what the
    // service needs from the host, and nothing below can render a target, a port or an alias twice.
    ServiceExtras extras = withSocketGroup(spec, shapeOf(spec, extrasOf(spec)));
    eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service =
        eu.wohlben.qits.containers.driver.swarm.ServiceSpec.builder(name, spec.imageRef())
            .replicas(DEPLOYED_REPLICAS)
            .noResolveImage(true)
            .withRegistryAuth(registryAuth);
    // The FULL membership, here and nowhere else: every later --network-add recreates the task.
    networkFlags(service, networks, extras.aliases());
    // ...and who answers its lookups, for a service on the private overlay (qits-2117).
    dnsFlags(service, spec, name, resolvers);
    // A deployed application outlives the daemon's restart.
    service.restartCondition("any");
    // PID 1 in the image is this repository's own binary, not an init that reaps orphans: a
    // detached grandchild adopted by PID 1 — `git maintenance run --auto --detach`, which git
    // 2.47+ leaves behind on every fetch and commit — is never reaped and piles up as a zombie.
    // qits-projects held 3,639 of them after 3.5h (qits-1066); qits-ci hit the same mechanism
    // (qits-965). `--init` runs docker-init/tini as PID 1 instead, which reaps on every SIGCHLD.
    // Unconditional: there is no application here for which an unreaped grandchild is desired.
    service.init(true);
    capabilityFlags(service, spec);
    sysctlFlags(service, spec);
    // Each label lands on the service AND its task container (SwarmArgv renders the pair), so
    // `docker ps --filter label=` still reads the platform the way a person and the environment
    // teardown both expect.
    service.labels(labels(spec));
    healthFlags(service, spec);
    updateFlags(service, spec);
    service.env(environment(spec));
    // The repository's own volumes first, and what they cover is what deployment config no longer
    // contributes. See `volumeFlags` for why the order and the dedupe are the whole migration.
    Set<String> declaredTargets = volumeFlags(service, spec);
    extras(service, extras, spec.publishMode(), declaredTargets);
    return SwarmArgv.serviceCreate(runtime, service.build());
  }

  /**
   * The volumes the repository DECLARED, as {@code --mount type=volume}, and the targets they
   * occupy.
   *
   * <p>Every mount here is a named volume by construction: the repository's grammar has no host
   * bind in it, because a host path is a statement about the machine rather than about the
   * application. So there is no kind to decide — {@code type=volume} is not a default, it is the
   * only thing this can be.
   *
   * <p><b>The names are re-validated here, at the last line before the argv</b>, which is the
   * health path's rule: the source is a derived name and the target is repository-authored, and
   * both are spliced into a comma-separated {@code --mount} argument where a stray comma would
   * forge a field.
   *
   * <p><b>The returned targets are what makes the migration safe</b>, and the caller is what uses
   * them: an application whose {@code deployments.yml} declares {@code /data} while the platform's
   * deployment config still carries a {@code mounts[0]} for {@code /data} would otherwise get two
   * {@code --mount} flags for one directory and a {@code service create} that fails outright. With
   * the dedupe, the declaration lands and is provably inert for as long as config supplies the same
   * value — which is the estate's two-step rule applied to this family:
   * ship the declaration, watch it change nothing, then retire the config entry.
   */
  private Set<String> volumeFlags(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service, ServiceSpec spec) {
    Set<String> targets = new LinkedHashSet<>();
    for (VolumeMount volume : spec.volumes()) {
      // This component's own belts first, so a refusal names the field the way it always did;
      // Mount.volume belts the same two values again inside the library.
      String source = DeploymentIdentifiers.requireVolumeSource(volume.source());
      String target = DeploymentIdentifiers.requireMountTarget(volume.target());
      service.mount(Mount.volume(source, target, volume.readOnly()));
      targets.add(target);
    }
    return targets;
  }

  /**
   * Every alias this service's attachment carries: what deployment config declared, plus what the
   * naming rule derives.
   *
   * <p><b>There is no derived half left.</b> It was the environment-qualified name a PLATFORM
   * service was granted beside its bare one — the additive first half of the plane's deletion, so a
   * dialer could move to {@code dev-qits-platform-idp} while {@code qits-platform-idp} still
   * answered. The plane is deleted and the qualified name is the service's own NAME now, so the
   * derivation could only ever produce an alias equal to the name, which is a line swarm has no use
   * for. What a service answers to beyond its name is deployment config's statement and nothing
   * else.
   *
   * <p>An application that declares no aliases therefore produces the byte-identical short-form argv
   * it always produced — and every application does, unless config says otherwise.
   */
  private List<String> aliasesOf(ServiceSpec spec, ServiceExtras extras, List<String> networks) {
    return shapeOf(spec, extras).aliases();
  }

  /**
   * The predecessor's wire address, when the repository declares {@code renamed_from:} — or null.
   * Derived from the spec, never written to deployment config: a dialer that still holds the old
   * name reaches the successor once the predecessor's service is gone. Never equal to the service's
   * own name, because the parser refuses a file that names itself as its predecessor.
   */
  private static String predecessorAlias(ServiceSpec spec) {
    String renamedFrom = spec.renamedFrom();
    return renamedFrom == null || renamedFrom.isBlank()
        ? null
        : PdNetworks.alias(spec.environmentName(), renamedFrom);
  }

  /**
   * Deployment config's extras with the repository's declared shape folded in — the UNION of the
   * two, the declaration winning wherever both speak about one thing:
   *
   * <ul>
   *   <li>a mount by TARGET — every declared bind, then every config mount on a target no bind
   *       holds (two mounts on one target is a create swarm refuses);
   *   <li>a publish by published port and PROTOCOL ({@link ServiceExtras.Publish#portKey}) —
   *       every declared one with {@code ${QITS_PORT}} resolved against {@link #qitsPort}, then
   *       every config one on a port none of them holds;
   *   <li>an alias by NAME — every declared one with {@code ${QITS_ENVIRONMENT}} resolved against
   *       this deployment's tier, then every config one not already among them.
   * </ul>
   *
   * <p>Groups and env are config's alone here; the socket's group is {@link #withSocketGroup}'s,
   * asked by the create only. <b>A spec that declares nothing returns {@code extras} itself</b>, so
   * every application that has not written one of the three keys gets the argv it always got, byte
   * for byte. While qits-1393 has not retired the extras source both sides say the same thing on
   * the estate, and the union is what makes that inert rather than a refused create.
   *
   * @throws ServiceExtras.Refused when a declared alias does not resolve to one name in this tier,
   *     or a declared publish is not one (or names a port twice) once its port is put in
   */
  private ServiceExtras shapeOf(ServiceSpec spec, ServiceExtras extras) {
    DeclaredShape shape = spec.shape();
    String predecessor = predecessorAlias(spec);
    if (shape.binds().isEmpty()
        && shape.publishes().isEmpty()
        && shape.aliases().isEmpty()
        && predecessor == null) {
      return extras;
    }
    List<ServiceExtras.Mount> mounts = new ArrayList<>(shape.binds());
    Set<String> targets = new HashSet<>();
    shape.binds().forEach(bind -> targets.add(bind.target()));
    for (ServiceExtras.Mount mount : extras.mounts()) {
      if (targets.add(mount.target())) {
        mounts.add(mount);
      }
    }
    List<ServiceExtras.Publish> declaredPublishes = shape.publishesFor(qitsPort);
    List<ServiceExtras.Publish> publishes = new ArrayList<>(declaredPublishes);
    Set<String> ports = new HashSet<>();
    declaredPublishes.forEach(publish -> ports.add(publish.portKey()));
    for (ServiceExtras.Publish publish : extras.publishes()) {
      if (ports.add(publish.portKey())) {
        publishes.add(publish);
      }
    }
    Set<String> aliases = new LinkedHashSet<>(shape.aliasesFor(spec.environmentName()));
    aliases.addAll(extras.aliases());
    if (predecessor != null) {
      // Last, so a service that declares nothing else keeps every other alias where it was.
      aliases.add(predecessor);
    }
    return new ServiceExtras(
        mounts, publishes, extras.groups(), extras.env(), List.copyOf(aliases));
  }

  /**
   * {@code extras} with the docker socket's group added when the repository BINDS the socket —
   * deduplicated by gid, because deployment config still sends {@code groups[0]=<gid>} for the
   * same socket until qits-1393, and two {@code --group} flags of one gid is noise at best.
   *
   * <p>The gid is a fact about the HOST, so no file states it: it is read off the socket this
   * process has mounted itself, which is the same host socket the bind hands the container. A
   * socket that cannot be read adds NO group and says so in a WARN — the container then gets the
   * bind without the permission to use it, which is a failure a person can read about, where a
   * guessed gid is one they cannot.
   */
  private ServiceExtras withSocketGroup(ServiceSpec spec, ServiceExtras extras) {
    if (!spec.shape().bindsDockerSocket()) {
      return extras;
    }
    String gid = socketGroup();
    if (gid == null || extras.groups().contains(gid)) {
      return extras;
    }
    List<String> groups = new ArrayList<>(extras.groups());
    groups.add(gid);
    return new ServiceExtras(
        extras.mounts(), extras.publishes(), groups, extras.env(), extras.aliases());
  }

  /** The socket's owning gid, or null — with a WARN — when it cannot be read. */
  private String socketGroup() {
    Path socket = dockerSocket;
    try {
      Object gid = Files.getAttribute(socket, "unix:gid");
      return String.valueOf(gid);
    } catch (Exception e) {
      LOG.warnf(
          "Could not read the group of %s, so a declared bind of the docker socket gets no --group:"
              + " %s",
          socket, e.toString());
      return null;
    }
  }

  /**
   * The membership, and the aliases that ride the shared network's attachment.
   *
   * <p><b>The short form is what a service with no aliases gets, byte for byte.</b> {@code
   * --network <net>} and {@code --network name=<net>} mean the same thing to swarm, but only one of
   * them is what every service on this platform was created with, and a shape change with no
   * intent behind it is a diff a reader has to rule out.
   *
   * <p><b>Every network the exposure attaches carries them</b> — the flat one, the private one, or
   * the two for {@code both} (qits-1976). An alias is an address, and the address has to be on
   * every network the service is reachable on. A network this driver does not manage keeps the
   * short form; under swarm none reaches here, because {@link #collapse} drops them.
   *
   * <p><b>Aliases with no network to hold them are a REFUSAL</b>, the publish-with-an-ip
   * stance: a name that was asked for and quietly not registered is a peer resolving nothing, and
   * that failure surfaces as somebody else's outage hours later.
   */
  private void networkFlags(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service,
      List<String> networks,
      List<String> aliases) {
    List<String> managed = managedNetworks();
    boolean carried = false;
    for (String network : networks) {
      if (aliases.isEmpty() || !managed.contains(network)) {
        // The short form, `--network <net>` — NetworkAttachment.createForm with no alias.
        service.network(network);
        continue;
      }
      // EVERY attached managed network carries them — the flat one, the private one, or both: an
      // address has to resolve wherever the service is reachable, and a `both` service is
      // reachable on the two. The long form, `name=<net>,alias=<a>...`.
      service.network(network, aliases);
      carried = true;
    }
    if (!aliases.isEmpty() && !carried) {
      throw new ServiceExtras.Refused(
          "aliases "
              + aliases
              + " are declared, and this service joins no network to hold them: an alias is an"
              + " address on qits.deployments.swarm.flat-network or"
              + " qits.deployments.swarm.private-network");
    }
  }


  // --- the private edge as resolver (qits-2117) ---------------------------------------------------

  /**
   * The resolvers a service on the private overlay is handed, in order — the private edge's VIP,
   * then this process's own upstreams — or none, with {@code why} saying which half was missing.
   * {@link #NOT_ASKED} is the answer for every service the rule does not cover (public, or an edge).
   */
  record Resolvers(List<String> servers, String why) {

    Resolvers {
      servers = servers == null ? List.of() : List.copyOf(servers);
    }

    boolean found() {
      return !servers.isEmpty();
    }

    /** The VIP, which is the first server — or null when none were found. */
    String vip() {
      return servers.isEmpty() ? null : servers.get(0);
    }
  }

  /** A service the rule does not cover: no lookup was made, so there is nothing to say. */
  static final Resolvers NOT_ASKED = new Resolvers(List.of(), null);

  /** The private edge's VIP on the overlay, or null with the reason. */
  record EdgeVip(String vip, String why) {}

  /**
   * Whether this spec takes the private edge as resolver: {@code private} or {@code both}, and not
   * an edge. The private edge would resolve through itself, and the public edge resolves the world
   * for the world — neither gets one.
   */
  boolean wantsPrivateEdgeResolvers(ServiceSpec spec) {
    return spec.exposure() != Exposure.PUBLIC && !isEdge(spec);
  }

  private boolean isEdge(ServiceSpec spec) {
    return PUBLIC_EDGE_APPLICATION.equals(spec.applicationName()) || isPrivateEdge(spec);
  }

  private boolean isPrivateEdge(ServiceSpec spec) {
    return privateEdgeApplication != null
        && !privateEdgeApplication.isBlank()
        && privateEdgeApplication.strip().equals(spec.applicationName());
  }

  /**
   * The resolvers for this spec — {@link #NOT_ASKED} when the rule does not cover it, else the
   * lookup. <b>All or nothing</b>, the runner's {@code PrivateEdge} rule: only the VIP <em>and</em>
   * at least one upstream make a list. {@code --dns} REPLACES the upstream rather than prepending to
   * it, so a VIP-only list would have docker's embedded resolver answer every public name with the
   * edge's REFUSED; and upstreams alone would put back exactly the resolution that answers a
   * private app's name with the public wildcard, which is why this exists. Never throws.
   */
  Resolvers privateEdgeResolvers(ServiceSpec spec) {
    if (!wantsPrivateEdgeResolvers(spec)) {
      return NOT_ASKED;
    }
    try {
      EdgeVip edge = privateEdgeVip();
      if (edge.vip() == null) {
        return new Resolvers(List.of(), edge.why());
      }
      List<String> upstreams = upstreamsBesides(edge.vip());
      if (upstreams.isEmpty()) {
        return new Resolvers(
            List.of(),
            resolvConf
                + " names no upstream resolver, and a resolver list holding only the private"
                + " edge's VIP would answer every public name REFUSED");
      }
      List<String> servers = new ArrayList<>();
      servers.add(edge.vip());
      servers.addAll(upstreams);
      return new Resolvers(servers, null);
    } catch (RuntimeException e) {
      return new Resolvers(List.of(), String.valueOf(e.getMessage()));
    }
  }

  /**
   * Where the private edge is on the overlay — qits-containers' {@code PrivateEdgeResolver} and the
   * runner's {@code PrivateEdge}, step for step:
   *
   * <ol>
   *   <li>{@code docker network inspect --format {{.Id}} <private-network>} — the overlay's id;
   *   <li>{@code docker service ls -q --filter label=<APP_NAME_LABEL>=<private-edge application>}
   *       — which must answer exactly one service;
   *   <li>{@code docker service inspect} over {@code .Endpoint.VirtualIPs} — the entry whose {@code
   *       NetworkID} is the overlay's, its {@code /prefix} stripped.
   * </ol>
   *
   * Read-only; a step that fails says why. Never throws.
   */
  EdgeVip privateEdgeVip() {
    if (privateNetwork == null || privateNetwork.isBlank()) {
      return new EdgeVip(null, "qits.deployments.swarm.private-network names no network");
    }
    if (privateEdgeApplication == null || privateEdgeApplication.isBlank()) {
      return new EdgeVip(null, "qits.deployments.swarm.private-edge.application names nothing");
    }
    String network = privateNetwork.strip();
    String application = privateEdgeApplication.strip();
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.networkInspect(runtime, network, "{{.Id}}"),
            INSPECT_TIMEOUT);
    List<String> idLines = inspected.exitCode() == 0 ? lines(inspected.output()) : List.of();
    String networkId = idLines.isEmpty() ? "" : idLines.get(idLines.size() - 1);
    if (!DOCKER_ID.matcher(networkId).matches()) {
      return new EdgeVip(
          null, "the network " + network + " has no id: " + safe(inspected.output()).strip());
    }
    ContainerProcess.Result listed =
        run(
            SwarmArgv.serviceLsQuiet(
                runtime, List.of(SwarmArgv.labelFilter(APP_NAME_LABEL, application))),
            INSPECT_TIMEOUT);
    if (listed.exitCode() != 0) {
      return new EdgeVip(null, "docker service ls answered " + safe(listed.output()).strip());
    }
    List<String> services = lines(listed.output());
    if (services.size() != 1 || !DOCKER_ID.matcher(services.get(0)).matches()) {
      return new EdgeVip(
          null,
          "expected exactly one swarm service labelled "
              + APP_NAME_LABEL
              + "="
              + application
              + ", found "
              + services.size());
    }
    String service = services.get(0);
    ContainerProcess.Result vips =
        run(
            SwarmArgv.serviceInspect(runtime, service, VIPS_FORMAT),
            INSPECT_TIMEOUT);
    if (vips.exitCode() != 0) {
      return new EdgeVip(
          null, "docker service inspect " + service + " answered " + safe(vips.output()).strip());
    }
    Optional<String> vip = vipOn(vips.output(), networkId);
    if (vip.isEmpty()) {
      return new EdgeVip(
          null,
          "the service " + service + " has no single virtual IP on " + network + " (" + networkId
              + ")");
    }
    return new EdgeVip(vip.get(), null);
  }

  /**
   * The {@code Addr} of the one line whose {@code NetworkID} is the overlay's, without its prefix;
   * empty when no line or more than one names it, or the address is not a literal.
   */
  static Optional<String> vipOn(String vipLines, String networkId) {
    List<String> found = new ArrayList<>();
    for (String line : lines(vipLines)) {
      String[] fields = line.split("\\s+");
      if (fields.length == 2 && fields[0].equals(networkId)) {
        int slash = fields[1].indexOf('/');
        found.add(slash < 0 ? fields[1] : fields[1].substring(0, slash));
      }
    }
    return found.size() == 1 ? dnsServer(found.get(0)) : Optional.empty();
  }

  /** This process's upstreams, read from {@link #resolvConf}, without {@code vip}. */
  private List<String> upstreamsBesides(String vip) {
    String text;
    try {
      text = Files.readString(Path.of(resolvConf), StandardCharsets.UTF_8);
    } catch (Exception e) {
      LOG.debugf("Could not read %s: %s", resolvConf, e.toString());
      return List.of();
    }
    return upstreams(text).stream().filter(server -> !server.equals(vip)).toList();
  }

  /**
   * The servers of the first {@code # ExtServers: [a b host(c)]} line — {@code host(x)} is {@code
   * x} — when docker wrote one, else every {@code nameserver} line's, in order. Loopback and
   * anything that is not an address literal are dropped, repeats kept once. On a user-defined
   * network the {@code nameserver} is docker's embedded {@code 127.0.0.11} and the comment holds the
   * real upstreams; this process's loopback is not a task's. The runner's {@code
   * PrivateEdge.upstreams}, byte for byte in what it answers.
   */
  static List<String> upstreams(String resolvConfText) {
    List<String> lines = resolvConfText.lines().map(String::strip).toList();
    List<String> candidates = new ArrayList<>();
    Optional<String> ext = lines.stream().filter(l -> l.startsWith(EXT_SERVERS)).findFirst();
    if (ext.isPresent()) {
      String rest = ext.get().substring(EXT_SERVERS.length()).strip();
      if (rest.startsWith("[")) {
        rest = rest.substring(1);
      }
      int close = rest.lastIndexOf(']');
      if (close >= 0) {
        rest = rest.substring(0, close);
      }
      for (String token : rest.strip().split("\\s+")) {
        Matcher host = HOST_FORM.matcher(token);
        candidates.add(host.matches() ? host.group(1) : token);
      }
    } else {
      for (String line : lines) {
        String[] fields = line.split("\\s+");
        if (fields.length >= 2 && fields[0].equals("nameserver")) {
          candidates.add(fields[1]);
        }
      }
    }
    List<String> servers = new ArrayList<>();
    for (String candidate : candidates) {
      dnsServer(candidate)
          .filter(server -> !loopback(server) && !servers.contains(server))
          .ifPresent(servers::add);
    }
    return List.copyOf(servers);
  }

  /** The candidate when it is an IPv4 or IPv6 literal, else empty — never a name to resolve. */
  private static Optional<String> dnsServer(String candidate) {
    if (candidate == null
        || !(IPV4_LITERAL.matcher(candidate).matches()
            || (candidate.contains(":") && IPV6_LITERAL.matcher(candidate).matches()))) {
      return Optional.empty();
    }
    try {
      // A literal never reaches a resolver: ofLiteral parses and nothing else.
      InetAddress.ofLiteral(candidate);
      return Optional.of(candidate);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** 127.0.0.0/8 or ::1, of a value {@link #dnsServer} passed. */
  private static boolean loopback(String literal) {
    try {
      return InetAddress.ofLiteral(literal).isLoopbackAddress();
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /**
   * The {@code Nameservers} of {@link #SPEC_DNS_FORMAT}'s output, in order — empty for {@code {}},
   * {@code null} or a config with none, which is every service created before qits-2117.
   */
  static List<String> nameservers(String dnsConfigJson) {
    Matcher array = NAMESERVERS.matcher(safe(dnsConfigJson));
    if (!array.find()) {
      return List.of();
    }
    List<String> servers = new ArrayList<>();
    Matcher quoted = QUOTED.matcher(array.group(1));
    while (quoted.find()) {
      servers.add(quoted.group(1));
    }
    return List.copyOf(servers);
  }

  /** The live service's resolvers, in order — or empty when the inspect cannot answer. */
  private Optional<List<String>> liveNameservers(String service) {
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.serviceInspect(runtime, service, SPEC_DNS_FORMAT),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return Optional.empty();
    }
    return Optional.of(nameservers(inspected.output()));
  }

  /**
   * The live resolver list when it is not {@code wanted} — or null. Fail-safe like {@link
   * #networkDrift}: an inspect that cannot answer recreates nothing, and the caller asks only once
   * the wanted list is established, so a private edge that is missing for a moment never turns
   * into every private service on the platform recreated without a resolver.
   */
  private String resolverDrift(String service, List<String> wanted) {
    Optional<List<String>> live = liveNameservers(service);
    if (live.isEmpty()) {
      LOG.warnf(
          "Could not read the resolvers of %s, so nothing is recreated over them — the next"
              + " deployment asks again",
          service);
      return null;
    }
    return live.get().equals(wanted) ? null : live.get().toString();
  }

  /**
   * {@code --dns <server>} per resolver, in order, on a create — and the one line a person (or a
   * verification, since {@code qits:agent} cannot inspect the swarm) reads to know which way it
   * went: INFO naming the resolvers, or WARN naming why there are none. Nothing at all for a service
   * the rule does not cover.
   */
  private void dnsFlags(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service,
      ServiceSpec spec,
      String name,
      Resolvers resolvers) {
    if (resolvers == NOT_ASKED || !wantsPrivateEdgeResolvers(spec)) {
      return;
    }
    if (!resolvers.found()) {
      LOG.warnf("%s gets no private-edge resolver: %s", name, resolvers.why());
      return;
    }
    service.dns(resolvers.servers());
    LOG.infof("%s resolves through the private edge: resolvers %s", name, resolvers.servers());
  }

  /**
   * The deployer's own update (qits-2119): {@link #buildUpdateArgv}, plus the two things an
   * ordinary update never states and a self-update cannot get any other way, because a self-update
   * is never recreated — removing the service this process answers on would leave nothing to
   * create the successor.
   *
   * <ul>
   *   <li><b>Its networks.</b> The managed networks the live service is on, against the ones its
   *       exposure names: {@code --network-rm <net>} for each one extra, {@code --network-add
   *       name=<net>} for each one missing — with {@code alias=<a>} for each alias its shape
   *       declares, since an attachment carries its aliases. Swarm registers the service name on
   *       every attached network, so the name needs none. One INFO names the move. A live list that
   *       cannot be read moves nothing — {@link #networkDrift}'s fail-safe.
   *   <li><b>Its resolvers</b>, when it is {@code private} or {@code both} and the private edge
   *       answered: {@code --dns-rm} each live one and {@code --dns-add} each wanted one, when they
   *       differ. The CLI does not promise to keep the order, and that is accepted for this one
   *       service — its own lookups of private names are its FQDN client calls — and checked after
   *       the hand-off ({@link #checkSelfResolverOrder}).
   * </ul>
   *
   * Package-private for the argv test.
   */
  List<String> buildSelfUpdateArgv(
      ServiceSpec spec, String name, List<String> networks, Resolvers resolvers) {
    ServiceUpdate.Builder delta = ServiceUpdate.builder();
    eu.wohlben.qits.containers.driver.swarm.ServiceSpec service = updateSpec(spec, name, delta);
    Optional<Set<String>> live = liveManagedNetworks(name, managedNetworkIds());
    if (live.isPresent()) {
      List<String> managed = managedNetworks();
      Set<String> wanted = new LinkedHashSet<>();
      networks.stream().filter(managed::contains).forEach(wanted::add);
      List<String> extra = live.get().stream().filter(net -> !wanted.contains(net)).toList();
      List<String> missing = wanted.stream().filter(net -> !live.get().contains(net)).toList();
      if (!extra.isEmpty() || !missing.isEmpty()) {
        LOG.infof("%s moves itself from %s to %s", spec.applicationName(), live.get(), wanted);
        extra.forEach(delta::networkRemoval);
        List<String> aliases =
            missing.isEmpty() ? List.of() : shapeOf(spec, extrasOf(spec)).aliases();
        // Always the long form, `name=<net>[,alias=<a>]*` — what a --network-add is spelled as.
        missing.forEach(network -> delta.networkAddition(network, aliases));
      }
    }
    if (resolvers.found()) {
      Optional<List<String>> liveDns = liveNameservers(name);
      if (liveDns.isPresent() && !liveDns.get().equals(resolvers.servers())) {
        delta.dnsRemovals(liveDns.get());
        delta.dnsAdditions(resolvers.servers());
        LOG.infof(
            "%s resolves through the private edge: resolvers %s", name, resolvers.servers());
      }
    } else if (wantsPrivateEdgeResolvers(spec)) {
      LOG.warnf("%s gets no private-edge resolver: %s", name, resolvers.why());
    }
    return SwarmArgv.serviceUpdate(runtime, service, delta.build());
  }

  /**
   * After the self-update is handed off, whether the spec swarm now holds asks the private edge
   * first — the order {@code --dns-add} does not promise. A WARN when it does not; this process is
   * being replaced, so there is nothing else to do about it here, and the next self-deploy asks
   * again.
   */
  private void checkSelfResolverOrder(String service, Resolvers resolvers) {
    Optional<List<String>> live = liveNameservers(service);
    if (live.isEmpty()) {
      return;
    }
    if (live.get().isEmpty() || !live.get().get(0).equals(resolvers.vip())) {
      LOG.warnf(
          "%s's resolvers are %s after its self-update: the private edge's VIP %s is not first, so"
              + " a private name may be answered by an upstream first",
          service, live.get(), resolvers.vip());
    }
  }

  /**
   * After the private edge was deployed and its VIP moved — which only a recreate does; an
   * ordinary release is a {@code service update} and keeps it — every private or both service this
   * process deployed whose first resolver is not the new VIP is applied again from its last spec.
   * Its resolver drift then recreates it with the new list. Each failure is a WARN and never fails
   * the edge's own deployment, which has already converged. A service whose live resolvers cannot
   * be read is left to its next deployment.
   */
  private void reapplyBehindPrivateEdge(String vip) {
    List<ServiceSpec> stale = new ArrayList<>();
    for (Map.Entry<String, ServiceSpec> entry : resolverSpecs.entrySet()) {
      Optional<List<String>> live = liveNameservers(entry.getKey());
      if (live.isEmpty()) {
        continue;
      }
      if (live.get().isEmpty() || !live.get().get(0).equals(vip)) {
        stale.add(entry.getValue());
      }
    }
    if (stale.isEmpty()) {
      return;
    }
    LOG.infof(
        "Re-applying %d service(s) after %s moved to %s",
        stale.size(), privateEdgeApplication.strip(), vip);
    for (ServiceSpec spec : stale) {
      try {
        ApplyResult result = apply(spec);
        if (result.outcome() == ApplyOutcome.REFUSED) {
          LOG.warnf(
              "Could not re-apply %s behind the private edge: %s",
              spec.wireAlias(), result.detail());
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not re-apply %s behind the private edge", spec.wireAlias());
      } finally {
        // Nobody awaits these verdicts: the edge's deployment is the one being recorded.
        issuedImages.remove(spec.wireAlias());
        issuedUpdates.remove(spec.wireAlias());
      }
    }
  }

  /**
   * Package-private for the argv test: the replace, which is this and nothing more.
   *
   * <p><b>Mounts, networks and published ports are deliberately absent.</b> A service update keeps
   * every part of the spec it is not asked to change, so re-stating them would at best be noise and
   * at worst would append a second copy of a mount. What changes on a deployment is the image, the
   * identity this deployment stamps on the service, the replica count, and the policy the update
   * itself runs under.
   *
   * <p><b>A declared volume that is missing does not change that — it means this argv is never
   * built.</b> {@link #apply} asks the question before it chooses, and a volume the live service
   * lacks makes the deployment a {@code service rm} and a {@link #buildCreateArgv}. Nothing was
   * added to the update path, and nothing should be: swarm has no add-a-mount, and a mount stated
   * here would be a second copy of one the service already holds. See {@link
   * #missingDeclaredVolume} for why the question is asked in one direction only.
   *
   * <p><b>The replica count is restated and that is a decision, not symmetry with the create.</b> It
   * is desired state rather than shape — see the flag's own comment below — and leaving it alone
   * would let a deployment onto a service an operator had scaled to 0 report a green {@code ACTIVE}
   * row with nothing running behind it.
   *
   * <p><b>The publish MODE rides with the ports, so changing it is not a deployment.</b> A
   * repository that starts saying {@code publish_mode: ingress} is describing a different shape of
   * service, and an existing service keeps the mode it was created with until it is removed and
   * created again — the {@code service rm} and redeploy every shape change here takes.
   *
   * <p><b>The network ALIASES ride with the networks, so changing them is not a deployment
   * either.</b> An attachment is restated as a whole or not at all: swarm has no add-an-alias, and
   * {@code --network-add} of a network the service is already on is an error. So a service that is
   * gaining or losing an alias takes {@code service update --network-rm <net> --network-add
   * name=<net>,alias=…} by hand — which recreates the task — or the {@code service rm} and redeploy.
   * A deployment after that keeps whatever the service holds, which is why a declared alias reaches
   * a LIVE service only on its next create.
   *
   * <p><b>Which is why a missing one means this argv is never built</b>, exactly as a missing volume
   * does. {@link #apply} asks before it chooses, and an alias the live service lacks makes the
   * deployment a {@code service rm} and a {@link #buildCreateArgv} — never an update with a network
   * bolted on. Nothing was added to the update path and nothing should be: see {@link
   * #missingDeclaredAlias} for why the question is asked in one direction only.
   *
   * <p><b>The environment is the exception, and it is re-stated in full</b> — this component's own
   * variables and the deployment config's alike. A variable is a value rather than a shape: config
   * naming a new address is a change the next deployment is supposed to carry, and {@code
   * --env-add} of an existing key replaces it.
   *
   * <p><b>Re-stated in full means REMOVALS too</b>, which it did not until 2026-08-17: see {@link
   * #envRemovals}. The environment is the one part of the spec this argv owns, so owning it means
   * the service ends up carrying what config states and nothing else.
   */
  List<String> buildUpdateArgv(ServiceSpec spec, String name) {
    ServiceUpdate.Builder delta = ServiceUpdate.builder();
    eu.wohlben.qits.containers.driver.swarm.ServiceSpec service = updateSpec(spec, name, delta);
    return SwarmArgv.serviceUpdate(runtime, service, delta.build());
  }

  /**
   * What {@link #buildUpdateArgv} restates, as the library's service, with {@code delta}'s {@code
   * --env-rm}s filled in — the self-update adds its network and resolver moves to the same delta.
   * The CLI calls it makes (the capability and environment inspects) happen here, in that order.
   */
  private eu.wohlben.qits.containers.driver.swarm.ServiceSpec updateSpec(
      ServiceSpec spec, String name, ServiceUpdate.Builder delta) {
    eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service =
        eu.wohlben.qits.containers.driver.swarm.ServiceSpec.builder(name, spec.imageRef())
            .noResolveImage(true)
            // AND THE REPLICA COUNT, which is the one piece of desired state an update owns beside
            // the image. An operator's scale-to-0 is a pause, and a service left at 0 takes a
            // `service update --image` without complaint: swarm has no task to converge, so the
            // update completes at once and the deployment is recorded ACTIVE while nothing runs.
            // Restating it is what makes a deployment mean "this application should be serving
            // this image" rather than "this image is what it would run if it ran". Nothing here
            // scales UP beyond one — see DEPLOYED_REPLICAS.
            .replicas(DEPLOYED_REPLICAS)
            .withRegistryAuth(registryAuth)
            // --label-add and --container-label-add, each.
            .labels(labels(spec));
    healthFlags(service, spec);
    updateFlags(service, spec);
    // Unconditional, same as the create and for the same reason — see that flag's own comment: a
    // service created before this line shipped is still running its old PID 1, so the update has
    // to restate `--init` for an already-live service to converge onto a reaping one.
    service.init(true);
    // Capabilities ARE restated, unlike the rest of the shape: `--cap-add` of a cap the service
    // already holds is a no-op, so a cap a repository starts declaring reaches its live service
    // here, without a recreate. A cap it STOPS declaring is not dropped — see `capabilityRemovals`.
    capabilityFlags(service, spec);
    capabilityRemovals(spec, name);
    // Sysctls are restated for the same reason: `--sysctl-add` of a pair the service already has
    // is a no-op. One it STOPS declaring is left as it is, and deliberately not inspected for — an
    // extra inspect on every update would buy a WARN; `--sysctl-rm` by hand or a recreate does it.
    sysctlFlags(service, spec);
    service.env(environment(spec));
    // One snapshot for this argv, as the create's is: an update states the environment and nothing
    // else, so this is the whole of what deployment config contributes here. Rendered after this
    // component's own, which is the precedence rule: the last assignment of a key wins, so what
    // config says outranks what this component defaults.
    ServiceExtras extras = extrasOf(spec);
    service.envOverrides(extras.env());
    // ...and what the service still carries that nothing above states any more. See envRemovals:
    // an update that only ever added is why a deleted entry outlived every deployment.
    delta.envRemovals(envRemovals(name, extras));
    return service.build();
  }

  /**
   * {@code --cap-add <CAP>} per capability the repository's {@code cap_add:} grants, in its order.
   * Re-checked against the parser's closed list at the last line before the argv, the health path's
   * rule: a capability is a privilege, and nothing but the reviewed file may name one.
   */
  private void capabilityFlags(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service, ServiceSpec spec) {
    for (String capability : spec.shape().capAdd()) {
      if (!DeploymentSpecParser.ALLOWED_CAPABILITIES.contains(capability)) {
        throw new ServiceExtras.Refused(
            "cap_add: `" + capability + "` is not a capability this platform grants");
      }
      service.capAdd(capability);
    }
  }

  /**
   * {@code <flag> <name>=<value>} per pair the repository's {@code sysctls:} sets, in its order —
   * {@code --sysctl} on the create, {@code --sysctl-add} on the update. Re-checked against the
   * parser's closed list at the argv, as capabilities are: only turning forwarding off is allowed.
   */
  private void sysctlFlags(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service, ServiceSpec spec) {
    for (String sysctl : spec.shape().sysctls()) {
      if (!DeploymentSpecParser.ALLOWED_SYSCTLS.contains(sysctl)) {
        throw new ServiceExtras.Refused(
            "sysctls: `" + sysctl + "` is not a kernel parameter this platform sets");
      }
      service.sysctl(sysctl);
    }
  }

  /**
   * A capability the live service holds that the spec no longer grants is LOGGED and kept, never
   * dropped: {@code --cap-drop} is a second statement with its own review, and a deployment that
   * silently took a privilege away from a running service is a failure nobody would trace to a
   * deleted line. An inspect that cannot answer says nothing.
   */
  private void capabilityRemovals(ServiceSpec spec, String name) {
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.serviceInspect(runtime, name, SPEC_CAPABILITIES_FORMAT),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return;
    }
    for (String live : lines(inspected.output())) {
      String capability = live.startsWith("CAP_") ? live.substring(4) : live;
      if (!spec.shape().capAdd().contains(capability)) {
        LOG.warnf(
            "%s holds the capability %s, which its spec no longer grants. It is NOT dropped: a"
                + " deployment never removes a capability — `docker service update --cap-drop %s"
                + " %s` by hand, or remove and redeploy the service",
            name, live, capability, name);
      }
    }
  }

  /**
   * The gate, enforced by docker inside the container — either the repository's own command,
   * passed through as ONE argv element, or the curl template over an allowlist-validated path.
   *
   * <p>The three timings are the {@code qits.deployments.health-*} keys, and they describe
   * the probe alone. The deadline is not among them: the window is these plus {@code
   * --update-monitor}, and both want measuring per application rather than deriving from one
   * platform-wide number.
   */
  private void healthFlags(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service, ServiceSpec spec) {
    // Re-validated here, at the last line before the argv, because this is the value that lands
    // inside a shell string the CONTAINER runs. Which value that is depends on the gate: a
    // repository that named a health_cmd replaced the path-shaped probe, so the path is neither
    // used nor checked.
    String command;
    if (spec.healthCmd() != null) {
      command = DeploymentIdentifiers.requireHealthCmd(spec.healthCmd());
    } else {
      command =
          "curl -fsS http://localhost:8080"
              + PdIdentifiers.requireHealthPath(spec.healthPath())
              + " || exit 1";
    }
    service.health(
        new Health(
            command,
            Duration.ofSeconds(healthIntervalSeconds),
            healthRetries,
            Duration.ofSeconds(healthStartPeriodSeconds)));
  }

  /**
   * The cutover, as five flags.
   *
   * <p>{@code --update-failure-action rollback} is not configurable and is not meant to be: a
   * successor that never goes healthy must leave the platform running whatever it replaced.
   *
   * <p>{@code --rollback-order} is the repository's {@code update_order} again, deliberately the
   * same word. Docker's own default is {@code stop-first}, so without the flag a {@code
   * start-first} service that fails its gate is rolled back by stopping the successor before the
   * predecessor's spec is started — a window with no task serving, on exactly the path that was
   * supposed to be lossless. And the opposite case rules out simply hard-coding {@code
   * start-first}: an application that declared {@code stop-first} did so because two of it cannot
   * run at once (one binder per host port, one writer per store), and a {@code start-first}
   * rollback would start the predecessor beside the failed successor — which for a host-mode port
   * never comes up, so the rollback deadlocks instead of restoring anything. One answer per
   * application, in both directions.
   *
   * <p>{@code --stop-grace-period} is the repository's {@code stop_grace_period}, thirty seconds
   * when it names none ({@link DeploymentDriver#DEFAULT_STOP_GRACE_PERIOD}). It is how long a task
   * told to stop keeps the transfers it already has: swarm takes it out of the load balancer on
   * SIGTERM and SIGKILLs it when the grace runs out, mid-download or not (qits-1170). Under {@code
   * start-first} it costs the cutover nothing — the update is {@code completed} while the
   * predecessor still drains; see {@code DeployService.convergenceTimeout} for the {@code
   * stop-first} arithmetic.
   *
   * <p>All five are valid on both {@code service create} and {@code service update}, which is why
   * they live here rather than on only one of the two argvs this method is shared by — a flag the
   * update forgot would be reset by nothing, and one the create forgot would be docker's default
   * until the first update.
   */
  private void updateFlags(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service, ServiceSpec spec) {
    service.updatePolicy(
        new UpdatePolicy(
            spec.updateOrder().spelling(),
            spec.updateOrder().spelling(),
            Duration.ofSeconds(updateMonitorSeconds),
            "rollback",
            spec.stopGracePeriod()));
  }

  /**
   * A duration in the spelling docker's flag parser reads ({@code time.ParseDuration}): whole
   * seconds as {@code <n>s}, so the default stays the {@code 30s} it always was on the argv, and
   * anything finer as milliseconds — the finest unit {@code stop_grace_period} can state.
   */
  static String goDuration(Duration duration) {
    return SwarmArgv.goDuration(duration);
  }

  /**
   * The bookkeeping labels — six, and everything that reads them (the environment teardown, a
   * person on the host) reads them by name and does not care what created them, which is what lets
   * a container the bootstrap seeded and a service this class made be found the same way.
   */
  private static List<String> labels(ServiceSpec spec) {
    List<String> labels = new ArrayList<>();
    // EVERY service carries the environment label: the label says which install and which tier a
    // container on the host belongs to, and an environment teardown reaps by it. Its absence used to
    // mean the platform plane, which is why the teardown briefly needed a target label beside it;
    // both the absence and the second label are gone.
    if (spec.environmentId() != null) {
      labels.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    labels.add(APPLICATION_LABEL + "=" + spec.applicationId());
    labels.add(DEPLOYMENT_LABEL + "=" + spec.deploymentId());
    labels.add(AVAILABLE_ON_ENV_LABEL + "=" + spec.availableOnEnv());
    labels.add(APP_NAME_LABEL + "=" + spec.applicationName());
    return List.copyOf(labels);
  }

  /**
   * The deployment config's view of this deployment — ONE snapshot of {@code
   * qits.deployments.extras.<application>.*}, read through the seam at the deployment's place. Every
   * argv reads it through here, so the three readings (create, update, the alias
   * check) and the settings preview ask exactly the same question.
   *
   * <p><b>Less every variable equal to an image default the spec declares</b> (qits-2048). The
   * deployment whose {@code image_defaults:} retires a row is built from the settings store before
   * the retirement runs, so read unfiltered its argv would {@code --env-add} the very variables it
   * is about to delete — and {@link #envRemovals} removes only what the argv does not state, so the
   * running service would keep them until some later, unrelated deployment. Dropped here, the one
   * reading every argv shares, an equal variable is simply no longer stated: a create does not
   * write it, and an update's diff {@code --env-rm}s it from the live service, whose image supplies
   * the same value. A row with any other value is an override and is stated as ever. A spec with no
   * image defaults reads exactly what it always read.
   */
  private ServiceExtras extrasOf(ServiceSpec spec) {
    return ServiceExtras.of(
            extrasSource.forDeployment(spec.applicationName(), spec.environmentName()),
            spec.applicationName())
        .withoutEnvEqualTo(spec.imageDefaults());
  }

  /**
   * {@link #environment}, followed — when {@code includeExtras} — by the deployment config's own
   * {@code env}: exactly the {@code --env}/{@code --env-add} values {@link #buildCreateArgv} and
   * {@link #buildUpdateArgv} state, in the same order, so the last entry of a key is the value the
   * service would get. Without the extras the seam is not asked at all.
   */
  @Override
  public List<String> stagedEnvironment(ServiceSpec spec, boolean includeExtras) {
    List<String> variables = new ArrayList<>(environment(spec));
    if (includeExtras) {
      variables.addAll(extrasOf(spec).env());
    }
    return List.copyOf(variables);
  }

  /**
   * The family {@link #envRemovals} never removes beyond what the argv states: {@link
   * #DEPLOYER_OWN_VARIABLES} and anything under {@link #RESOURCE_PREFIX}. One predicate, read by
   * the update diff and by the settings preview alike.
   */
  @Override
  public boolean keptWhenUnstated(String key) {
    return DeploymentDriver.keptByTheDeployer(key);
  }

  /**
   * The live service's {@link #SPEC_ENV_FORMAT}, one entry per variable. A line with no {@code =}
   * continues the value before it (a value may hold a newline) — {@link #currentSpecEnvKeys}' rule,
   * read for values rather than keys. An inspect that cannot answer is empty: the preview then
   * shows every variable as added, which is the honest reading of "nothing to compare with".
   */
  @Override
  public Optional<List<String>> liveEnvironment(String name) {
    ContainerProcess.Result inspected =
        run(SwarmArgv.serviceInspect(runtime, name, SPEC_ENV_FORMAT), INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return Optional.empty();
    }
    List<String> variables = new ArrayList<>();
    String output = safe(inspected.output());
    // The template's own println ends the last variable with a line break, and the docker CLI adds
    // one more after the whole template; take off both, so the last value is not read as ending in
    // a line break (which made the preview report it as changed).
    for (int breaks = 0; breaks < 2 && output.endsWith("\n"); breaks++) {
      output = output.substring(0, output.length() - 1);
    }
    for (String line : output.split("\n", -1)) {
      if (line.indexOf('=') > 0) {
        variables.add(line);
      } else if (!variables.isEmpty()) {
        variables.set(variables.size() - 1, variables.get(variables.size() - 1) + "\n" + line);
      }
    }
    return Optional.of(List.copyOf(variables));
  }

  /**
   * Who and where this service is, plus whatever was provisioned for it. Deliberately minimal —
   * application config (datasources, peers) is the image's and the environment's own story.
   */
  private List<String> environment(ServiceSpec spec) {
    List<String> variables = new ArrayList<>();
    // QITS_ENVIRONMENT is written for EVERY service, the platform plane included. It used to be
    // withheld from a platform service on the reasoning that it serves every environment and being
    // told it lived in one would be untrue — which read well and cost the plane the ability to
    // name its own install: no tier in its telemetry, no tier in its resource rows, and every
    // consumer of it having to special-case a null. A platform service is deployed into the
    // designated environment, and this says which one. What stays true of "serves every
    // environment" is the address, which is the wire alias and is bare.
    //
    // The null guard is a guard and not a plane test: a spec without a tier is a mid-bootstrap
    // install with none designated, and QITS_ENVIRONMENT=null is worse than the variable's absence.
    if (spec.environmentName() != null) {
      variables.add(ENVIRONMENT_VARIABLE + "=" + spec.environmentName());
    }
    variables.add(APPLICATION_VARIABLE + "=" + spec.applicationName());
    // QITS_VARIANT only for a variant (qits-1976): it gets no deployment settings and derives its
    // configuration from its declaration, so the one fact it needs is which variant it is. A main
    // deployment gets no variable — not an empty one — and its argv is unchanged by this line.
    if (spec.variant() != null && !spec.variant().isBlank()) {
      variables.add(VARIANT_VARIABLE + "=" + spec.variant());
    }
    // QITS_DOMAIN is written for EVERY service too, and for the same reason QITS_ENVIRONMENT is:
    // the platform's domain is ONE fact, and a service that has to know a hostname derives it from
    // this rather than carrying a composed copy of it in its own configuration. The five composed
    // spellings the bootstrap used to fan out — the edge's ACME domain, the idp's cookie domain,
    // the two browser-host lists, the webauthn origins and the canonical origin — could each go
    // stale on their own, and two of them did, which broke sign-in on the live platform. One value
    // written here cannot diverge from itself.
    //
    // The blank guard is the ENVIRONMENT guard exactly, one word further on: an installation that
    // has not stated its domain gets NO VARIABLE, because QITS_DOMAIN= is worse than the variable's
    // absence. A consumer reading an empty string has been told something — an empty hostname — and
    // will compose nonsense out of it; a consumer finding nothing falls back to the default it
    // ships, which is the behaviour every service had before this line existed.
    platformDomain
        .filter(domain -> !domain.isBlank())
        .ifPresent(domain -> variables.add(DOMAIN_VARIABLE + "=" + domain.trim()));
    String identity =
        DeployedIdentity.resourceAttributes(
            spec.commitSha(), spec.environmentName(), spec.wireAlias());
    variables.add(DeployedIdentity.OTEL_VARIABLE + "=" + identity);
    variables.add(DeployedIdentity.QUARKUS_OTEL_VARIABLE + "=" + identity);
    // service.name is named after the DEPLOYED APPLICATION, never the image (qits-2054): a variant
    // runs its main application's image, and without this a variant's telemetry read as its main
    // application's — qits-edge-private under qits-edge's name. Carried in its own pair of
    // variables rather than folded into the resource-attribute list above: Quarkus' build-time
    // service.name is merged OVER OTEL_RESOURCE_ATTRIBUTES (see DeployedIdentity), so a service.name
    // stated only there would be silently overridden by the image's own stamp.
    String serviceName = DeployedIdentity.serviceName(spec.applicationName());
    variables.add(DeployedIdentity.OTEL_SERVICE_NAME_VARIABLE + "=" + serviceName);
    variables.add(DeployedIdentity.QUARKUS_OTEL_SERVICE_NAME_VARIABLE + "=" + serviceName);
    // What ResourceProvisioning made exist a moment ago, as the generic contract. The name is
    // re-validated HERE, at the last line before the argv, exactly like the health path: it is
    // repository-authored input being spliced into an environment-variable key. The idp resource's
    // name is the reserved constant `idp`, so it passes trivially — the check still runs, because
    // this loop has no reason to know which resource type it is looking at.
    // The private postgres' superuser password, for that application alone (qits-2175): the same
    // value this deployer provisions with, so one admin credential serves both servers by
    // construction. Written like a resource password — a value on the argv, never in a log line.
    if (ResourceProvisioning.PRIVATE_POSTGRES_APPLICATION.equals(spec.applicationName())) {
      postgresAdminPassword
          .map(String::strip)
          .filter(password -> !password.isEmpty())
          .ifPresent(password -> variables.add(SERVER_PASSWORD_VARIABLE + "=" + password));
    }
    for (ResourceBinding binding : spec.resources()) {
      String key =
          PdIdentifiers.requireResourceName(binding.name())
              .toUpperCase(Locale.ROOT)
              .replace('-', '_');
      for (ResourceBinding.Value value : binding.values()) {
        variables.add(RESOURCE_PREFIX + key + "_" + value.suffix() + "=" + safe(value.value()));
      }
    }
    return List.copyOf(variables);
  }

  /**
   * The first volume this deployment declares that the live service does not have — or null, which
   * is the answer on every deployment this platform performs today.
   *
   * <p><b>Why this exists at all.</b> {@link #buildUpdateArgv} states no mounts, and deliberately:
   * a service update keeps the shape it is not asked to change, and re-stating a mount would append
   * a second copy of it. Swarm has no add-a-mount either. So a volume a repository has just started
   * declaring can reach a running service by exactly one route — {@code service rm} and a create —
   * and a deployment that did not take it would go green while the application kept writing to a
   * container layer that vanishes at the next cutover. Which is the failure this whole change is
   * about: a declared mount that can never arrive.
   *
   * <p><b>The rule is ONE-DIRECTIONAL and that is not a simplification.</b> A declared volume the
   * live service lacks means recreate. Everything else — a mount the live service has and nothing
   * declares, a mount at a different target, an application that declares nothing at all — takes
   * the ordinary update path, untouched. A symmetric "the two sets differ" comparison would be a
   * platform-wide outage on its first run: every service on this estate carries extras-supplied
   * mounts and declares none, so every one of them would be removed and recreated on its next
   * deployment, each losing its writable layer for no reason anybody asked for.
   *
   * <p><b>A mount matches on BOTH halves.</b> The same target fed by a different volume is not this
   * volume — it is the application reading somebody else's storage, or its own under the name it
   * had before — and answering "present" there would leave the declaration permanently unhonoured.
   *
   * <p><b>An inspect that cannot answer recreates NOTHING</b>, with a WARN, which is {@link
   * #currentSpecEnvKeys}' stance one step more strictly: that one risks carrying a stale variable
   * one more deployment, and this one risks destroying a running service over a CLI call that
   * failed. The next deployment asks again.
   */
  private String missingDeclaredVolume(ServiceSpec spec, String name) {
    if (spec.volumes().isEmpty()) {
      // The whole estate today. Not one CLI call is spent on a service that declares nothing.
      return null;
    }
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.serviceInspect(runtime, name, SPEC_MOUNTS_FORMAT),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      LOG.warnf(
          "Could not read the mounts of %s, so this deployment updates it in place — a declared"
              + " volume may still be missing, and the next deployment asks again: %s",
          name, inspected.output());
      return null;
    }
    Set<String> live = new HashSet<>(lines(inspected.output()));
    for (VolumeMount volume : spec.volumes()) {
      if (!live.contains(volume.source() + "|" + volume.target())) {
        return volume.source() + " at " + volume.target();
      }
    }
    return null;
  }

  /**
   * The first alias this deployment declares that the live service does not answer to — or null,
   * which is the answer on every deployment once the fleet has caught up.
   *
   * <p><b>Why this exists at all.</b> {@link #buildUpdateArgv} states no networks, deliberately: a
   * swarm attachment is restated whole or not at all, and {@code --network-add} of a network the
   * service is already on is an error. So an alias reaches a LIVE service by exactly one route — a
   * {@code service rm} and a create — and without this, {@link #aliasesOf}'s
   * environment-qualified alias would reach only services created after it shipped. Measured on the
   * estate the day before this landed: {@code qits-platform-idp} resolved and {@code
   * dev-qits-platform-idp} did not, on a service that had been deployed twice since the alias was
   * declared. A cutover cannot move a single dialer onto a name nothing answers to.
   *
   * <p><b>It is the lever that makes the cutover staggered rather than a big bang.</b> Nobody here
   * has host access to run {@code service rm} by hand, and recreating the platform at once is the
   * thing this staged plan exists to avoid. Deployer-driven, each service recreates once — on its
   * own next deployment, as its own release — and never again, because afterwards its aliases match.
   *
   * <p><b>The rule is ONE-DIRECTIONAL and that is not negotiable</b>, for {@link
   * #missingDeclaredVolume}'s reason exactly. A declared alias the live service LACKS means
   * recreate. Everything else — an alias the service carries that nothing declares, an alias on
   * another attachment, a service that declares none at all — takes the ordinary update path,
   * untouched. A symmetric "the two sets differ" comparison would recreate services across the
   * estate for no reason anybody asked for, each losing its writable layer.
   *
   * <p><b>An inspect that cannot answer recreates NOTHING</b>, with a WARN: the cost of being wrong
   * here is a destroyed service, and the next deployment asks again.
   *
   * <p>Never asked for a self-update — {@link #apply} guards that — because removing the service
   * this process answers on would leave nothing to create the successor. An alias is worth less
   * than a deployer.
   */
  private String missingDeclaredAlias(ServiceSpec spec, String name, List<String> networks) {
    List<String> declared;
    List<String> configAliases;
    try {
      ServiceExtras extras = extrasOf(spec);
      configAliases = extras.aliases();
      declared = aliasesOf(spec, extras, networks);
    } catch (ServiceExtras.Refused e) {
      // Config states something swarm cannot express. The argv build in `apply` reaches the same
      // refusal a line later and returns REFUSED, having changed nothing — so the one thing this
      // must not do is destroy a service on the way there.
      return null;
    }
    String predecessor = predecessorAlias(spec);
    if (predecessor != null
        && !spec.takesOverPredecessor()
        && !spec.shape().aliasesFor(spec.environmentName()).contains(predecessor)
        && !configAliases.contains(predecessor)) {
      // The `renamed_from` address is worth a recreate only while its predecessor is SERVING, the
      // one moment a dialer of the old name exists. A successor whose predecessor is a scaled-down
      // shell (or already retired) gains it on its next create instead: a recreate is a `service
      // rm`, and for qits-edge that is the platform's ingress going dark for an address nothing
      // can have been answering on.
      declared = declared.stream().filter(alias -> !alias.equals(predecessor)).toList();
    }
    if (declared.isEmpty()) {
      // Every environment application that declares no alias of its own. Not one CLI call is spent.
      return null;
    }
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.serviceInspect(runtime, name, SPEC_NETWORK_ALIASES_FORMAT),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      LOG.warnf(
          "Could not read the network aliases of %s, so this deployment updates it in place — a"
              + " declared alias may still be missing, and the next deployment asks again: %s",
          name, inspected.output());
      return null;
    }
    Set<String> live = new HashSet<>(lines(inspected.output()));
    for (String alias : declared) {
      if (!live.contains(alias)) {
        return alias;
      }
    }
    return null;
  }

  /**
   * Remove the service so the create that follows can declare the shape it needs — and WAIT for its
   * task to be gone, for {@link #reapSeedTwin}'s reason and more sharply: the successor is created
   * on the very volumes the predecessor is still writing to, so an overlap of seconds is two
   * writers on one store rather than a moment of duplicated serving.
   *
   * <p><b>The wait is the spec's stop deadline, and running out of it is a REFUSAL for a service
   * that holds a {@code private:} volume</b> (qits-2118). Creating anyway was the 2026-09-05
   * corruption recorded in qits-database-oci's {@code deployments.yml}: a stop-first postgres
   * whose old postmaster had not exited when the new one started on the same data directory. So
   * the sentence comes back and the caller refuses the deployment; the service stays absent, and
   * the next deployment takes the create path. A service with no private volume keeps the old
   * create-anyway behaviour, with the WARN — a stateless overlap of seconds is nothing. Null is "go
   * ahead".
   */
  private String removeForRecreate(ServiceSpec spec, String name) {
    ContainerProcess.Result removed = run(SwarmArgv.serviceRm(runtime, name), CLEANUP_TIMEOUT);
    if (removed.exitCode() != 0) {
      // The create that follows will fail on the name still being taken, and that is the honest
      // outcome: a REFUSED deployment naming what could not be removed, with the predecessor still
      // serving.
      LOG.warnf("Could not remove %s to recreate it with its declared shape: %s",
          name, removed.output());
      return null;
    }
    Duration deadline = drainDeadline(spec);
    if (awaitTasksGone(name, deadline)) {
      return null;
    }
    String volume = privateVolume(spec);
    if (volume != null) {
      return stillStopping(name, deadline, volume);
    }
    LOG.warnf(
        "The service %s is removed but its task is still stopping after %ss — the successor may"
            + " start beside it",
        name, deadline.toSeconds());
    return null;
  }

  /**
   * The environment keys the LIVE service carries, so an update can state what is no longer stated.
   *
   * <p>Read with the same {@code service inspect} the rest of this class asks its questions with.
   * An inspect that cannot answer removes NOTHING: a deployment must not lose an application's
   * environment because one CLI call failed, and the next deployment asks again.
   */
  private List<String> currentSpecEnvKeys(String name) {
    ContainerProcess.Result inspected =
        run(SwarmArgv.serviceInspect(runtime, name, SPEC_ENV_FORMAT), INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      LOG.warnf(
          "Could not read the environment of %s, so this update removes nothing from it: %s",
          name, inspected.output());
      return List.of();
    }
    List<String> keys = new ArrayList<>();
    for (String line : safe(inspected.output()).split("\n")) {
      // A variable's VALUE may hold anything, newlines included, so a line with no `=` in it is a
      // continuation rather than a key. Skipping it is what keeps a wrapped value from being read
      // as a variable nobody set — and an --env-rm of a name that does not exist is not free: it
      // is a whole deployment refused by the CLI.
      int equals = line.indexOf('=');
      if (equals > 0) {
        keys.add(line.substring(0, equals).strip());
      }
    }
    return keys;
  }

  /**
   * {@code --env-rm} for every key the live service carries that the deployment no longer states.
   *
   * <p><b>This is the other half of "config is platform state".</b> An update only ever added, so a
   * variable removed from an application's extras stayed on the running service until somebody
   * removed the service — measured on 2026-08-17, on the deployment that proved the flip. The diff
   * closes it, and the consequence is the point of the campaign rather than a side effect: a hand
   * {@code service update --env-add} no longer survives the next deployment, because the source of
   * an application's environment is the settings store and nothing else.
   *
   * <p><b>What is never removed is a family rather than a list of exceptions</b>, and it has three
   * members:
   *
   * <ul>
   *   <li>everything the deployment ITSELF states — {@code extras.env()}, which is the whole of
   *       what config says;
   *   <li>{@link #DEPLOYER_OWN_VARIABLES}, this component's identity set: it writes them on every
   *       argv and an operator never states them, so a diff against config alone would remove and
   *       re-add the same values on every deployment ({@link #VARIANT_VARIABLE} on a variant's);
   *   <li>anything under {@link #RESOURCE_PREFIX}, which {@code ResourceProvisioning} injects from
   *       the registry row. Config cannot state a provisioned credential and must not be able to
   *       delete one — a removed datasource triple is an application that cannot boot.
   * </ul>
   *
   * <p><b>Only an update does this.</b> A create has no predecessor to diff against, and {@code
   * service create} has no such flag.
   */
  private List<String> envRemovals(String name, ServiceExtras extras) {
    Set<String> stated = new HashSet<>();
    for (String variable : extras.env()) {
      int equals = variable.indexOf('=');
      stated.add(equals > 0 ? variable.substring(0, equals) : variable);
    }
    // Sorted, so one deployment's argv is the same argv twice and a diff of two run logs is
    // readable. A HashSet's order is not a contract and a reader would read it as one.
    List<String> removals = new ArrayList<>();
    for (String key : new TreeSet<>(currentSpecEnvKeys(name))) {
      if (stated.contains(key) || keptWhenUnstated(key)) {
        continue;
      }
      removals.add(key);
    }
    return removals;
  }

  /**
   * {@link ServiceExtras} in {@code service create}'s vocabulary. Only this application's own keys
   * are read, and that is the security property: one application's socket bind cannot ride along
   * on a sibling's deployment.
   *
   * <p>{@code declaredTargets} is what the repository's own {@code volumes:} already mounted, and a
   * config mount naming one of those targets is <b>dropped</b> rather than rendered beside it — see
   * {@link #volumeFlags}. It is silent by design: during the migration the two state the same
   * thing, so a warning would fire on every deployment of every migrated application and say
   * nothing a person can act on.
   */
  private void extras(
      eu.wohlben.qits.containers.driver.swarm.ServiceSpec.Builder service,
      ServiceExtras extras,
      PublishMode publishMode,
      Set<String> declaredTargets) {
    for (ServiceExtras.Mount mount : extras.mounts()) {
      if (declaredTargets.contains(mount.target())) {
        // The repository declared this directory itself and the flag is already in the argv. Two
        // --mounts for one target is not a duplicate swarm tolerates: it refuses the create.
        continue;
      }
      // Swarm names the kind rather than inferring it from a leading slash, which is what config
      // states — so this is a spelling, not a decision.
      service.mount(
          new Mount(
              mount.kind().name().toLowerCase(Locale.ROOT),
              mount.source(),
              mount.target(),
              mount.readOnly()));
    }
    for (ServiceExtras.Publish publish : extras.publishes()) {
      // The mode is the repository's `publish_mode`, and it defaults to host: the task binds the
      // port per node, like a plain `docker run`, which is what every publishing service does
      // today. `ingress` gives the port to the routing mesh instead, so a replacement can start
      // while the predecessor still holds the door open.
      //
      // AN IP IS A REFUSAL, NOT A WARNING. Swarm's publish syntax has no ip field in either mode
      // — measured: a host-mode publish listens on 0.0.0.0 — so a spec that asks for loopback
      // cannot be honoured, and honouring it approximately would put an endpoint that was
      // deliberately unreachable on every interface of the host.
      if (!publish.bindsAllInterfaces()) {
        throw new ServiceExtras.Refused(
            "swarm cannot publish "
                + publish.published()
                + " on "
                + publish.ip()
                + ": a service publish has no ip field, so this port would be on every interface");
      }
      service.publish(
          new Publish(
              publish.published(), publish.target(), publish.protocol(), publishMode.spelling()));
    }
    service.groups(extras.groups());
    // Last, after the groups and right before the image: what config says outranks this
    // component's own --env, because the last assignment of a key wins.
    service.envOverrides(extras.env());
  }

  // --- reading swarm back ----------------------------------------------------------------------

  /**
   * The overlays a service is on, by its exposure: {@code public} the flat one, {@code private} the
   * private one, {@code both} the flat one and then the private one. See the class javadoc.
   */
  List<String> collapse(ServiceSpec spec) {
    Set<String> networks = new LinkedHashSet<>();
    Exposure exposure = spec.exposure() == null ? Exposure.PUBLIC : spec.exposure();
    if (exposure != Exposure.PRIVATE && flatNetwork != null && !flatNetwork.isBlank()) {
      networks.add(flatNetwork.strip());
    }
    if (exposure != Exposure.PUBLIC && privateNetwork != null && !privateNetwork.isBlank()) {
      networks.add(privateNetwork.strip());
    }
    List<String> dropped =
        spec.networks().stream().filter(network -> !networks.contains(network)).toList();
    if (!dropped.isEmpty()) {
      LOG.debugf(
          "%s is declared on %s; %s are not made under swarm — a join after create restarts the"
              + " task, so the topology is flat",
          spec.applicationName(), networks, dropped);
    }
    return List.copyOf(networks);
  }

  private boolean collapsed(String network) {
    return (flatNetwork != null && flatNetwork.strip().equals(network)) || isPrivateNetwork(network);
  }

  private boolean isPrivateNetwork(String network) {
    return privateNetwork != null
        && !privateNetwork.isBlank()
        && privateNetwork.strip().equals(network);
  }

  /** The networks this driver attaches services to — the flat one and the private one, named. */
  private List<String> managedNetworks() {
    List<String> managed = new ArrayList<>();
    if (flatNetwork != null && !flatNetwork.isBlank()) {
      managed.add(flatNetwork.strip());
    }
    if (privateNetwork != null && !privateNetwork.isBlank()) {
      managed.add(privateNetwork.strip());
    }
    return managed;
  }

  /**
   * A network's ID as the daemon stores it in a service spec, or null when it cannot say — no such
   * network, or an inspect that failed. Null is never a guess: every caller reads it as "cannot
   * resolve" and decides nothing on it.
   */
  private String networkId(String network) {
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.networkInspect(runtime, network, "{{.ID}}"),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return null;
    }
    String id = inspected.output() == null ? "" : inspected.output().strip();
    return id.isEmpty() || id.contains("\n") ? null : id;
  }

  /**
   * The managed networks the live service is attached to, by NAME — or empty when that cannot be
   * established: the inspect failed, it printed nothing, or not one of its lines resolved to a
   * managed network. A line that is neither managed network's name nor ID is another network
   * altogether (one this driver never attaches) and is left out rather than counted against it.
   */
  private Optional<Set<String>> liveManagedNetworks(String service, Map<String, String> ids) {
    ContainerProcess.Result inspected =
        run(
            SwarmArgv.serviceInspect(runtime, service, SPEC_NETWORK_TARGETS_FORMAT),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      LOG.warnf(
          "Could not read the networks of %s, so nothing is recreated over them — the next"
              + " deployment asks again: %s",
          service, inspected.output());
      return Optional.empty();
    }
    List<String> live = lines(inspected.output());
    if (live.isEmpty()) {
      return Optional.empty();
    }
    Set<String> attached = new LinkedHashSet<>();
    for (String target : live) {
      for (Map.Entry<String, String> managed : ids.entrySet()) {
        if (target.equals(managed.getKey()) || target.equals(managed.getValue())) {
          attached.add(managed.getKey());
        }
      }
    }
    return attached.isEmpty() ? Optional.empty() : Optional.of(attached);
  }

  /** Each managed network's ID, keyed by name — an unresolvable one maps to null. */
  private Map<String, String> managedNetworkIds() {
    Map<String, String> ids = new LinkedHashMap<>();
    for (String network : managedNetworks()) {
      ids.put(network, networkId(network));
    }
    return ids;
  }

  /**
   * Why the live service's networks are not the ones its exposure names — or null, which is the
   * answer on every deployment of a service whose class has not changed, and therefore on every
   * deployment on the estate today.
   *
   * <p><b>Why this exists at all.</b> {@link #buildUpdateArgv} states no networks, and a service
   * update cannot move one: swarm restates an attachment whole or not at all. So a repository that
   * changes its {@code exposure:} reaches its running service by exactly one route — a {@code
   * service rm} and a create — the volume and alias arms' route, for the third shape change a
   * deployment performs for itself.
   *
   * <p><b>Both directions count, unlike those two</b>, because this is not a union a config source
   * adds to: the membership of the two managed networks IS the exposure. A {@code public} service
   * on {@code qits-private} is a private upstream the public edge can reach, and a {@code private}
   * service still on {@code qits-net} is one the move did not move. Only the managed pair is
   * compared — a network this driver never attaches is nobody's statement and decides nothing.
   *
   * <p><b>It is FAIL-SAFE, and that is what keeps the estate still.</b> Every service today
   * declares no exposure, so it is {@code public}, so its desired membership is exactly {@code
   * [qits-net]} — and its live one is too, which compares equal and recreates nothing. Every way
   * the comparison could go wrong answers "no drift": an inspect that fails or prints nothing, a
   * desired network whose ID cannot be resolved, a live list in which no line resolves to a managed
   * network. The daemon stores network IDs rather than names in the spec, so a comparison of names
   * alone would read every service as on no network at all; each line is matched against a managed
   * network's name AND its ID.
   *
   * <p>Never asked for a self-update — {@link #apply} guards that, as for its siblings.
   */
  private String networkDrift(String service, List<String> desired) {
    if (desired.isEmpty()) {
      return null;
    }
    Map<String, String> ids = managedNetworkIds();
    for (String network : desired) {
      if (ids.get(network) == null) {
        LOG.debugf(
            "Could not resolve the network %s, so %s is not compared against it", network, service);
        return null;
      }
    }
    Optional<Set<String>> live = liveManagedNetworks(service, ids);
    if (live.isEmpty()) {
      return null;
    }
    Set<String> wanted = new LinkedHashSet<>(desired);
    if (live.get().equals(wanted)) {
      return null;
    }
    return "its exposure names the networks "
        + wanted
        + " and the live service is on "
        + live.get();
  }

  /**
   * The live service's exposure, off the managed networks it is attached to: the flat one alone is
   * {@code public}, the private one alone {@code private}, the two {@code both}. Empty when that
   * cannot be established, which the caller answers with {@code public}.
   */
  @Override
  public Optional<Exposure> liveExposure(String name) {
    try {
      String service = resolveService(name);
      if (service == null) {
        return Optional.empty();
      }
      Optional<Set<String>> live = liveManagedNetworks(service, managedNetworkIds());
      if (live.isEmpty()) {
        return Optional.empty();
      }
      boolean flat = flatNetwork != null && live.get().contains(flatNetwork.strip());
      boolean hidden = privateNetwork != null && live.get().contains(privateNetwork.strip());
      if (flat && hidden) {
        return Optional.of(Exposure.BOTH);
      }
      return Optional.of(hidden ? Exposure.PRIVATE : Exposure.PUBLIC);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * The live service's own {@code stop_grace_period}, read back as {@link #STOP_GRACE_PERIOD_FORMAT}.
   * Empty on an inspect that fails (no such service, a daemon that cannot answer), on {@code <no
   * value>} (a service created with none stated) and on a parsed {@code 0} — all three mean "this
   * runtime cannot say what the grace is", never "the grace is zero", because a caller reading zero
   * as deliberate would wait only the margin for a service that may hold connections for minutes.
   */
  @Override
  public Optional<Duration> stopGracePeriod(String service) {
    ContainerProcess.Result inspected =
        run(SwarmArgv.serviceInspect(runtime, service, STOP_GRACE_PERIOD_FORMAT), INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return Optional.empty();
    }
    String raw = safe(inspected.output()).strip();
    if (raw.isEmpty() || "<no value>".equals(raw)) {
      return Optional.empty();
    }
    try {
      long nanos = Long.parseLong(raw);
      return nanos <= 0 ? Optional.empty() : Optional.of(Duration.ofNanos(nanos));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private boolean serviceExists(String name) {
    return run(
                SwarmArgv.serviceInspect(runtime, name, "{{.ID}}"),
                INSPECT_TIMEOUT)
            .exitCode()
        == 0;
  }

  /** The state word of every task of the current generation, lowercased. */
  private List<String> runningGenerationStates(String name) {
    ContainerProcess.Result listed =
        run(
            SwarmArgv.servicePs(runtime, name, List.of(DESIRED_RUNNING), true, CURRENT_STATE_FORMAT),
            INSPECT_TIMEOUT);
    return listed.exitCode() == 0 ? taskStates(listed.output()) : List.of();
  }

  /**
   * Package-private for the parsing test: {@code docker service ps} prints a phrase ({@code Running
   * 3 minutes ago}, {@code Starting less than a second ago}), and the first word is the state.
   */
  static List<String> taskStates(String output) {
    List<String> states = new ArrayList<>();
    for (String line : lines(output)) {
      String[] words = line.split("\\s+");
      if (words.length > 0 && !words[0].isBlank()) {
        states.add(words[0].toLowerCase(Locale.ROOT));
      }
    }
    return List.copyOf(states);
  }

  /** The tasks as a person would read them — a failed convergence's first diagnosis. */
  private String tasks(String name) {
    ContainerProcess.Result listed =
        run(SwarmArgv.servicePs(runtime, name, List.of(), true, null), INSPECT_TIMEOUT);
    return safe(listed.output());
  }

  /** A bounded tail of the service's own output — the second half of the diagnosis. */
  private String logs(String name) {
    ContainerProcess.Result result =
        run(
            SwarmArgv.serviceLogsTail(runtime, name, LOG_TAIL_LINES), CLEANUP_TIMEOUT);
    return safe(result.output());
  }

  /** One task of the service as {@link #TASK_EVIDENCE_FORMAT} prints it. */
  record TaskRow(String id, String image, String desiredState, String currentState, String error) {

    boolean running() {
      return currentState.toLowerCase(Locale.ROOT).startsWith("running");
    }
  }

  /** Package-private for the parsing test; a line with no id, no image or no state is skipped. */
  static List<TaskRow> taskRows(String output) {
    List<TaskRow> rows = new ArrayList<>();
    for (String line : lines(output)) {
      String[] parts = line.split("\\|", 5);
      if (parts.length < 4 || parts[0].isBlank() || parts[1].isBlank()) {
        continue;
      }
      rows.add(
          new TaskRow(
              parts[0].strip(),
              parts[1].strip(),
              parts[2].strip(),
              parts[3].strip(),
              parts.length > 4 ? parts[4].strip() : ""));
    }
    return List.copyOf(rows);
  }

  /**
   * The tasks a failure detail should name: the ones of the deployed image that are not running,
   * newest first ({@code service ps} lists a slot's tasks newest first), at most {@link
   * #FAILED_TASKS_KEPT}.
   *
   * <p><b>Why this exists at all.</b> {@code service logs <service>} interleaves every task swarm
   * remembers, and the long-running predecessor out-talks a successor that died in half a second:
   * a rollback of qits-containers 2026.1026.210534 stored a detail whose whole log tail was the
   * predecessor's, and the failed task's one line of output was nowhere in it. Asking each failed
   * task for its own logs is what keeps that line.
   *
   * <p>Matched by image when the image is known (this process issued the deployment). When it is
   * not — the verdict asked by an instance that did not apply it — the best reading left is "not
   * running, and not the image a running task runs", which is the predecessor excluded.
   *
   * <p>Package-private for the selection test.
   */
  static List<TaskRow> failedTasks(List<TaskRow> rows, String deployedImage) {
    Set<String> runningImages = new HashSet<>();
    for (TaskRow row : rows) {
      if (row.running()) {
        runningImages.add(withoutDigest(row.image()));
      }
    }
    return rows.stream()
        .filter(row -> !row.running())
        .filter(
            row ->
                deployedImage == null || deployedImage.isBlank()
                    ? !runningImages.contains(withoutDigest(row.image()))
                    : sameImage(row.image(), deployedImage))
        .limit(FAILED_TASKS_KEPT)
        .toList();
  }

  /**
   * Whether a task's image is the one deployed: equal once a pinned digest is dropped (swarm
   * prints {@code ref@sha256:…} when it resolved one), allowing for docker's {@code docker.io/}
   * normalisation on either side.
   */
  static boolean sameImage(String taskImage, String deployedImage) {
    String task = withoutDigest(taskImage);
    String deployed = withoutDigest(deployedImage);
    return task.equals(deployed) || task.endsWith("/" + deployed) || deployed.endsWith("/" + task);
  }

  private static String withoutDigest(String image) {
    String value = safe(image).strip();
    int at = value.indexOf('@');
    return at < 0 ? value : value.substring(0, at);
  }

  /**
   * The failed tasks of the deployed image, each with its id, state, error and ITS OWN log tail —
   * the section a failure detail puts first, so that whatever the detail is cut to later, the
   * service-wide tail behind it goes before this does. Empty when there is nothing to name.
   *
   * <p>Never throws: this is evidence about a failure, and failing to gather it must not turn into
   * a second one.
   */
  private String failedTaskEvidence(String name) {
    try {
      ContainerProcess.Result listed =
          run(
              SwarmArgv.servicePs(runtime, name, List.of(), true, TASK_EVIDENCE_FORMAT),
              INSPECT_TIMEOUT);
      if (listed.exitCode() != 0) {
        return "";
      }
      String deployedImage = issuedImages.get(name);
      List<TaskRow> failed = failedTasks(taskRows(listed.output()), deployedImage);
      if (failed.isEmpty()) {
        return "";
      }
      StringBuilder evidence =
          new StringBuilder("failed tasks")
              .append(deployedImage == null ? "" : " of " + deployedImage)
              .append(" (newest first):\n");
      for (TaskRow task : failed) {
        evidence
            .append("--- task ")
            .append(task.id())
            .append(": ")
            .append(task.currentState());
        if (!task.error().isBlank()) {
          evidence.append(" — ").append(task.error());
        }
        evidence.append('\n');
        ContainerProcess.Result logged =
            run(
                SwarmArgv.serviceLogsTail(runtime, task.id(), TASK_LOG_TAIL_LINES),
                CLEANUP_TIMEOUT);
        String output = safe(logged.output()).strip();
        evidence.append(output.isEmpty() ? "(no output)" : output).append('\n');
      }
      return evidence.append("--- all tasks:\n").toString();
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not gather the failed tasks of %s", name);
      return "";
    }
  }

  private static List<String> lines(String output) {
    return Arrays.stream((output == null ? "" : output).split("\\R"))
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
