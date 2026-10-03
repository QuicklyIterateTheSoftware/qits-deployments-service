package eu.wohlben.qits.deployments.swarmhost;

import eu.wohlben.qits.deployments.deployments.control.DeployedIdentity;
import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.DeploymentExtrasSource;
import eu.wohlben.qits.deployments.deployments.control.DeploymentIdentifiers;
import eu.wohlben.qits.deployments.deployments.control.ExtrasSnapshot;
import eu.wohlben.qits.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.deployments.deployments.control.PdProcess;
import eu.wohlben.qits.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
 * and a task does not enter DNS until its healthcheck passes — measured on this host: while a task
 * was {@code Starting}, {@code getent hosts} answered nothing for its name while the VIP already
 * existed. So there is no predecessor to find, nothing to stop, nothing to restart and nobody to
 * referee: this class issues one command and then reads a verdict.
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
 * intent swarm cannot express — a publish bound to an ip — is refused rather than widened.
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
   * A network is removable roughly a second after the services on it go, not immediately —
   * measured. So a teardown retries rather than reporting a failure that is only a moment early.
   */
  /**
   * How long a reaped seed twin's task may take to stop before the successor is created anyway.
   * Ten seconds covers a postgres shutdown; the give-up arm exists so a wedged task cannot hold
   * every deployment hostage, and it says what it risks.
   */
  private static final int TWIN_DRAIN_ATTEMPTS = 10;

  private static final Duration TWIN_DRAIN_WAIT = Duration.ofSeconds(1);

  private static final int NETWORK_REMOVE_ATTEMPTS = 5;

  private static final Duration NETWORK_REMOVE_WAIT = Duration.ofSeconds(1);

  /** Lines of service log kept as a failed convergence's diagnosis. */
  private static final String LOG_TAIL_LINES = "200";

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
   * The DESIRED task count the service spec holds. Guarded by {@code if}, because a global-mode
   * service has no {@code Replicated} block at all and the template would print {@code <no value>}
   * — an empty answer is "this service has no replica count", which {@link #parseReplicas} reads as
   * "cannot say" rather than as zero.
   */
  static final String REPLICAS_FORMAT =
      "{{if .Spec.Mode.Replicated}}{{.Spec.Mode.Replicated.Replicas}}{{end}}";

  /**
   * How many tasks a service this component creates runs. One, everywhere: the applications on this
   * platform bind host ports from inside the task and write to stores with a single writer, so a
   * second task is a port collision or a corrupted volume rather than capacity.
   *
   * <p>It is stated once and used twice — the create declares it, and an update <b>restates</b> it.
   * See {@link #buildUpdateArgv} for why the update must.
   */
  static final int DEPLOYED_REPLICAS = 1;

  /** Which tier this application is deployed into — an environment application's, and only one. */
  static final String ENVIRONMENT_VARIABLE = "QITS_ENVIRONMENT";

  /** Which application this is, on every plane. */
  static final String APPLICATION_VARIABLE = "QITS_APPLICATION";

  /**
   * The platform's domain, stated once and handed to every container — the one fact a service needs
   * in order to derive its own public names.
   *
   * <p>It used to be fanned out by the bootstrap into five per-service composed spellings — the
   * edge's ACME domain, the idp's cookie domain, two browser-host lists, the webauthn origins and
   * the canonical origin — each of which could go stale on its own. The edge's copy and the idp's
   * did diverge, and sign-in on the live platform broke. So the domain travels the way {@link
   * #ENVIRONMENT_VARIABLE} already does: one value, written by the deployer, derived from by
   * whoever needs a hostname. No service carries a hostname in its configuration.
   */
  static final String DOMAIN_VARIABLE = "QITS_DOMAIN";

  /**
   * What {@code ResourceProvisioning} injects, as the generic contract: {@code
   * QITS_RESOURCE_<NAME>_URL} and its two siblings. Config states none of them — the registry row
   * is the single authority for the credential — so the update diff must not be able to remove one.
   */
  static final String RESOURCE_PREFIX = "QITS_RESOURCE_";

  /**
   * This component's own five, written on every argv before the deployment's own and therefore
   * never stated by config. They are the exception the update diff needs: measured against the
   * extras alone, every deployment would remove and immediately re-add all five.
   *
   * <p>{@link #DOMAIN_VARIABLE} is the newest member and belongs here for exactly the reason the
   * other four do — the deployer writes it and config states it nowhere, so a diff against config
   * alone would {@code --env-rm} the domain off every live service on its next deployment. That it
   * is written CONDITIONALLY changes nothing: membership protects a key from removal, it does not
   * assert that the key is present.
   *
   * <p>{@code QITS_RESOURCE_*} is the sixth member of the family and is a PREFIX rather than a
   * name, which is why it is {@link #RESOURCE_PREFIX} beside this set rather than in it.
   */
  static final Set<String> DEPLOYER_OWN_VARIABLES =
      Set.of(
          ENVIRONMENT_VARIABLE,
          APPLICATION_VARIABLE,
          DOMAIN_VARIABLE,
          DeployedIdentity.OTEL_VARIABLE,
          DeployedIdentity.QUARKUS_OTEL_VARIABLE);

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
   * <p>See {@link #registryAuthFlag} for what the flag does and why the key exists.
   */
  @ConfigProperty(name = "qits.deployments.registry-auth")
  boolean registryAuth;

  @ConfigProperty(name = "qits.deployments.output-max-chars")
  int outputMaxChars;

  /**
   * Where this component's own docker credential is written when it was handed an idp client — see
   * {@code DockerCredentialFile}. Every CLI call here passes it to {@link PdProcess}, which sets
   * {@code DOCKER_CONFIG} to it only when a {@code config.json} is actually there, so the warm-up
   * pull and the {@code --with-registry-auth} the create and update serialise for the agents read
   * the same credential.
   */
  @ConfigProperty(name = "qits.deployments.docker-config-dir")
  Optional<String> dockerConfigDir;

  /**
   * Where the extras are read from, ONCE PER ARGV — see {@link DeploymentExtrasSource} and, for the
   * staleness a boot snapshot costs, {@link ExtrasSnapshot}. It is a seam because the answer may be
   * qits-configuration's rather than the config volume's, and an HTTP call does not belong in a
   * driver.
   */
  @Inject DeploymentExtrasSource extrasSource;

  /**
   * One docker CLI call. A seam so the suite can script the conversation: the argv IS the contract
   * with swarm, and asserting it — and the verdicts read back out of it — needs no daemon.
   */
  @FunctionalInterface
  interface Cli {
    PdProcess.Result run(List<String> argv, Duration timeout);
  }

  private volatile Cli cli;

  /**
   * Package-private, and a method rather than a field write, because an injected reference is a CDI
   * client proxy: a field set on the proxy would never reach the bean.
   */
  void scriptCli(Cli scripted) {
    this.cli = scripted;
  }

  private PdProcess.Result run(List<String> argv, Duration timeout) {
    Cli scripted = cli;
    return scripted != null
        ? scripted.run(argv, timeout)
        : PdProcess.run(
            null,
            argv,
            dockerConfigDir == null ? null : dockerConfigDir.map(Path::of).orElse(null),
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
    boolean recreate = missingVolume != null || missingAlias != null;
    boolean updating = exists && !recreate;
    List<String> argv;
    try {
      argv = updating ? buildUpdateArgv(spec, target) : buildCreateArgv(spec, target, networks);
    } catch (ServiceExtras.Refused e) {
      // Deployment config said something swarm cannot express. Nothing was applied — the argv is
      // built before the command runs — so this deployment changed nothing.
      LOG.warnf("Refusing to deploy %s: %s", name, e.getMessage());
      return new ApplyResult(ApplyOutcome.REFUSED, e.getMessage());
    }
    if (recreate) {
      // Destructive, and announced before it happens: the writable layer of the running task goes
      // with the service. Everything an application is supposed to keep is on a volume — which is
      // exactly what this deployment is putting right — but the sentence is what a person reads
      // afterwards to know why a container they were looking at is gone. Removed after the argv is
      // built, like the seed twin below, so a REFUSED deployment changes nothing.
      if (missingVolume != null) {
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
      removeForRecreate(target);
    }
    if (!self) {
      // The seed twin dies at cutover, and it dies FIRST. It holds the wire alias (DNS would
      // round-robin between seed and successor — measured: a step's ci-daemon registered with the
      // instance that had not launched it and exited 6) and any host-mode ports (the successor's
      // task then sits Pending on "port already in use" forever). Removed after the argv is built,
      // so a REFUSED deployment changes nothing; if the create still fails, the task the twin ran
      // is what the last boot's stack file restores.
      reapSeedTwin(name);
    }
    PdProcess.Result result = run(argv, APPLY_TIMEOUT);
    if (result.exitCode() != 0 || result.timedOut()) {
      LOG.warnf(
          "Could not %s service %s: %s", updating ? "update" : "create", name, result.output());
      return new ApplyResult(ApplyOutcome.REFUSED, result.output());
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
   */
  @Override
  public Convergence awaitConverged(String name, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    // Null when nothing issued an update for this service — the first deployment of an
    // application, or an instance that did not perform the update it is asking about.
    Instant issued = issuedUpdates.get(name);
    String last = "(never inspected)";
    try {
      while (true) {
        PdProcess.Result inspected =
            run(
                List.of(runtime, "service", "inspect", "--format", UPDATE_STATUS_FORMAT, name),
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
                      + tasks(name)
                      + "\n"
                      + logs(name));
            }
            case "paused", "rollback_paused" -> {
              return Convergence.failed(
                  "swarm paused the update of " + name + ": " + message + "\n" + tasks(name));
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
              + tasks(name)
              + "\n"
              + logs(name));
    } finally {
      // The verdict is reached, however it went: this update is nobody's in-flight state now.
      issuedUpdates.remove(name);
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
          "no task of " + name + " came up: " + String.join(", ", states) + "\n" + tasks(name));
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
    PdProcess.Result listed = observeTasks(name);
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
    PdProcess.Result inspected = inspectReplicas(name);
    if (inspected.exitCode() != 0) {
      inspected = inspectReplicas(SEED_STACK_PREFIX + name);
    }
    return inspected.exitCode() != 0 ? OptionalInt.empty() : parseReplicas(inspected.output());
  }

  private PdProcess.Result inspectReplicas(String service) {
    return run(
        List.of(runtime, "service", "inspect", "--format", REPLICAS_FORMAT, service),
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
        List.of(
            runtime,
            "service",
            "update",
            "--detach",
            "--replicas",
            String.valueOf(replicas),
            target),
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
        List.of(runtime, "service", "update", "--detach", "--force", target),
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
    PdProcess.Result result = run(argv, APPLY_TIMEOUT);
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

  /** Whether the named service is the one this process is a task of. False outside a container. */
  private boolean isSelf(String service) {
    String own = ownServiceName();
    return !own.isBlank() && own.equals(service);
  }

  private PdProcess.Result observeTasks(String service) {
    return run(
        List.of(
            runtime,
            "service",
            "ps",
            service,
            "--filter",
            "desired-state=running",
            "--no-trunc",
            "--format",
            "{{.CurrentState}}"),
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
    PdProcess.Result removed = run(List.of(runtime, "service", "rm", name), CLEANUP_TIMEOUT);
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
    PdProcess.Result inspected =
        run(
            List.of(runtime, "service", "inspect", "--format", RUNNING_IMAGE_FORMAT, name),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      // The application may live under the seed stack's name: the deployer's own self-update
      // targets the stack-named service it runs as, so the successor's startup sweep must read
      // its evidence from the same place — asking only the bare alias settled the self-update's
      // row as "interrupted" while the service ran the row's exact image.
      inspected =
          run(
              List.of(
                  runtime,
                  "service",
                  "inspect",
                  "--format",
                  RUNNING_IMAGE_FORMAT,
                  SEED_STACK_PREFIX + name),
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
    PdProcess.Result inspected =
        run(
            List.of(
                runtime,
                "inspect",
                "--format",
                "{{index .Config.Labels \"" + SWARM_SERVICE_LABEL + "\"}}",
                hostname),
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
   */
  private void reapSeedTwin(String name) {
    String twin = SEED_STACK_PREFIX + name;
    if (!serviceExists(twin)) {
      return;
    }
    PdProcess.Result removed = run(List.of(runtime, "service", "rm", twin), CLEANUP_TIMEOUT);
    if (removed.exitCode() != 0) {
      LOG.warnf(
          "Could not remove the seed service %s — the successor may wait on its ports: %s",
          twin, removed.output());
      return;
    }
    if (awaitTasksGone(twin)) {
      LOG.infof("Removed the seed service %s: %s takes the alias and the ports", twin, name);
      return;
    }
    LOG.warnf(
        "The seed service %s is removed but its task is still stopping — the successor may start"
            + " beside it",
        twin);
  }

  /**
   * Whether the removed service's task containers are gone yet, waited on for at most {@link
   * #TWIN_DRAIN_ATTEMPTS} × {@link #TWIN_DRAIN_WAIT}.
   *
   * <p>{@code service rm} returns while the task is still shutting down, and the wait is about
   * VOLUMES rather than ports — see {@link #reapSeedTwin}, which measured it twice on postgres.
   * Both callers remove a service whose successor is created on the same storage, so both owe the
   * same wait; the give-up arm exists so a wedged task cannot hold every deployment hostage, and
   * each caller says what it risks.
   */
  private boolean awaitTasksGone(String service) {
    for (int attempt = 0; attempt < TWIN_DRAIN_ATTEMPTS; attempt++) {
      PdProcess.Result tasks =
          run(
              List.of(
                  runtime,
                  "ps",
                  "--quiet",
                  "--filter",
                  "label=" + SWARM_SERVICE_LABEL + "=" + service),
              INSPECT_TIMEOUT);
      if (tasks.exitCode() == 0 && safe(tasks.output()).strip().isEmpty()) {
        return true;
      }
      try {
        Thread.sleep(TWIN_DRAIN_WAIT.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
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
    if (run(List.of(runtime, "network", "inspect", spec.name()), CLEANUP_TIMEOUT).exitCode() == 0) {
      // Already there, labels and all — including one the bootstrap made. The labels are how this
      // component FINDS the networks it made, not a claim of ownership over every network.
      return false;
    }
    PdProcess.Result created = run(buildNetworkCreateArgv(spec), CLEANUP_TIMEOUT);
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
    List<String> argv =
        new ArrayList<>(List.of(runtime, "network", "create", "-d", "overlay", "--attachable"));
    argv.add("--label");
    argv.add(NETWORK_LABEL + "=" + spec.kind().name().toLowerCase(Locale.ROOT));
    if (spec.environmentId() != null) {
      argv.add("--label");
      argv.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    if (spec.applicationName() != null) {
      argv.add("--label");
      argv.add(APP_NAME_LABEL + "=" + spec.applicationName());
    }
    argv.add(spec.name());
    return List.copyOf(argv);
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
      PdProcess.Result removed =
          run(List.of(runtime, "network", "rm", network), CLEANUP_TIMEOUT);
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
    PdProcess.Result listed =
        run(
            List.of(
                runtime,
                "network",
                "ls",
                "--filter",
                "label=" + NETWORK_LABEL,
                "--format",
                "{{.Name}}|{{.Labels}}"),
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
    PdProcess.Result listed =
        run(
            List.of(
                runtime,
                "service",
                "ls",
                "-q",
                "--filter",
                "label=" + ENVIRONMENT_LABEL + "=" + environmentId),
            CLEANUP_TIMEOUT);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list services of environment %s: %s", environmentId, listed.output());
      return 0;
    }
    List<String> ids = lines(listed.output());
    if (ids.isEmpty()) {
      return 0;
    }
    List<String> argv = new ArrayList<>(List.of(runtime, "service", "rm"));
    argv.addAll(ids);
    run(argv, CLEANUP_TIMEOUT);
    return ids.size();
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
    PdProcess.Result result =
        run(List.of(runtime, "pull", imageRef), Duration.ofSeconds(pullTimeoutSeconds));
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
    // Read once, off ONE snapshot: the same refusal would otherwise be reached twice, the aliases
    // belong to the membership while everything else belongs to the flags below it, and one
    // snapshot is what keeps every reading of one deployment in agreement.
    ServiceExtras extras =
        ServiceExtras.of(
            extrasSource.forDeployment(
                spec.applicationName(),
                spec.environmentName(),
                spec.version(),
                spec.declarationSeeded()),
            spec.applicationName());
    List<String> argv =
        new ArrayList<>(
            List.of(
                runtime,
                "service",
                "create",
                "--detach",
                "--name",
                name,
                "--replicas",
                String.valueOf(DEPLOYED_REPLICAS),
                "--no-resolve-image"));
    registryAuthFlag(argv);
    // The FULL membership, here and nowhere else: every later --network-add recreates the task.
    networkFlags(argv, networks, aliasesOf(spec, extras, networks));
    // A deployed application outlives the daemon's restart.
    argv.add("--restart-condition");
    argv.add("any");
    for (String label : labels(spec)) {
      argv.add("--label");
      argv.add(label);
      // The task container carries the same labels, so `docker ps --filter label=` still reads the
      // platform the way a person and the environment teardown both expect.
      argv.add("--container-label");
      argv.add(label);
    }
    healthFlags(argv, spec, "--health-cmd");
    updateFlags(argv, spec, "--update-order");
    for (String variable : environment(spec)) {
      argv.add("--env");
      argv.add(variable);
    }
    // The repository's own volumes first, and what they cover is what deployment config no longer
    // contributes. See `volumeFlags` for why the order and the dedupe are the whole migration.
    Set<String> declaredTargets = volumeFlags(argv, spec);
    extras(argv, extras, spec.publishMode(), declaredTargets);
    argv.add(spec.imageRef());
    return List.copyOf(argv);
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
   * value — which is the estate's two-step rule (configuration-yml.md §7.5) applied to this family:
   * ship the declaration, watch it change nothing, then retire the config entry.
   */
  private Set<String> volumeFlags(List<String> argv, ServiceSpec spec) {
    Set<String> targets = new LinkedHashSet<>();
    for (VolumeMount volume : spec.volumes()) {
      String source = DeploymentIdentifiers.requireVolumeSource(volume.source());
      String target = DeploymentIdentifiers.requireMountTarget(volume.target());
      argv.add("--mount");
      argv.add(
          "type=volume,source="
              + source
              + ",target="
              + target
              + (volume.readOnly() ? ",readonly" : ""));
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
    return extras.aliases();
  }

  /**
   * The membership, and the aliases that ride the shared network's attachment.
   *
   * <p><b>The short form is what a service with no aliases gets, byte for byte.</b> {@code
   * --network <net>} and {@code --network name=<net>} mean the same thing to swarm, but only one of
   * them is what every service on this platform was created with, and a shape change with no
   * intent behind it is a diff a reader has to rule out.
   *
   * <p><b>Only the flat network carries them.</b> An alias is an address, and the address has to be
   * on the network the platform's names are resolved on — the one every service joins. {@code
   * qits-platform} is the plane's own network and keeps the short form.
   *
   * <p><b>Aliases with no flat network to hold them are a REFUSAL</b>, the publish-with-an-ip
   * stance: a name that was asked for and quietly not registered is a peer resolving nothing, and
   * that failure surfaces as somebody else's outage hours later.
   */
  private void networkFlags(List<String> argv, List<String> networks, List<String> aliases) {
    String shared = flatNetwork == null ? "" : flatNetwork.strip();
    boolean carried = false;
    for (String network : networks) {
      argv.add("--network");
      if (aliases.isEmpty() || !network.equals(shared)) {
        argv.add(network);
        continue;
      }
      StringBuilder attachment = new StringBuilder("name=").append(network);
      for (String alias : aliases) {
        attachment.append(",alias=").append(alias);
      }
      argv.add(attachment.toString());
      carried = true;
    }
    if (!aliases.isEmpty() && !carried) {
      throw new ServiceExtras.Refused(
          "aliases "
              + aliases
              + " are declared, and this service joins no shared network to hold them: an alias is"
              + " an address on qits.deployments.swarm.flat-network");
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
    List<String> argv =
        new ArrayList<>(
            List.of(
                runtime,
                "service",
                "update",
                "--detach",
                "--no-resolve-image",
                "--image",
                spec.imageRef(),
                // AND THE REPLICA COUNT, which is the one piece of desired state an update owns
                // beside the image. An operator's scale-to-0 is a pause, and a service left at 0
                // takes a `service update --image` without complaint: swarm has no task to
                // converge, so the update completes at once and the deployment is recorded ACTIVE
                // while nothing runs. Restating it is what makes a deployment mean "this
                // application should be serving this image" rather than "this image is what it
                // would run if it ran". Nothing here scales UP beyond one — see DEPLOYED_REPLICAS.
                "--replicas",
                String.valueOf(DEPLOYED_REPLICAS)));
    registryAuthFlag(argv);
    for (String label : labels(spec)) {
      argv.add("--label-add");
      argv.add(label);
      argv.add("--container-label-add");
      argv.add(label);
    }
    healthFlags(argv, spec, "--health-cmd");
    updateFlags(argv, spec, "--update-order");
    for (String variable : environment(spec)) {
      argv.add("--env-add");
      argv.add(variable);
    }
    // One snapshot for this argv, as the create's is: an update states the environment and nothing
    // else, so this is the whole of what deployment config contributes here.
    ServiceExtras extras =
        ServiceExtras.of(
            extrasSource.forDeployment(
                spec.applicationName(),
                spec.environmentName(),
                spec.version(),
                spec.declarationSeeded()),
            spec.applicationName());
    for (String variable : extras.env()) {
      // After this component's own, which is the precedence rule: the last assignment of a key
      // wins, so what config says outranks what this component defaults.
      argv.add("--env-add");
      argv.add(variable);
    }
    // ...and what the service still carries that nothing above states any more. See envRemovals:
    // an update that only ever added is why a deleted entry outlived every deployment.
    envRemovals(argv, name, extras);
    argv.add(name);
    return List.copyOf(argv);
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
  private void healthFlags(List<String> argv, ServiceSpec spec, String cmdFlag) {
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
    argv.add(cmdFlag);
    argv.add(command);
    argv.add("--health-interval");
    argv.add(healthIntervalSeconds + "s");
    argv.add("--health-retries");
    argv.add(String.valueOf(healthRetries));
    argv.add("--health-start-period");
    argv.add(healthStartPeriodSeconds + "s");
  }

  /**
   * {@code --with-registry-auth}, on a create and on an update alike, when {@code
   * qits.deployments.registry-auth} says so. Unset — the shipped state — this writes
   * nothing and both argvs are what they were byte for byte.
   *
   * <p><b>What the flag does.</b> It serialises the credential the CLI holds for the registry into
   * the service spec, so the swarm agent authenticates the task's own pull. Without it only the
   * warm-up {@code docker pull} above is authenticated — that one runs as this process, with this
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
  private void registryAuthFlag(List<String> argv) {
    if (registryAuth) {
      argv.add("--with-registry-auth");
    }
  }

  /**
   * The cutover, as three flags.
   *
   * <p>{@code --update-failure-action rollback} is not configurable and is not meant to be: a
   * successor that never goes healthy must leave the platform running whatever it replaced.
   */
  private void updateFlags(List<String> argv, ServiceSpec spec, String orderFlag) {
    argv.add(orderFlag);
    argv.add(spec.updateOrder().spelling());
    argv.add("--update-monitor");
    argv.add(updateMonitorSeconds + "s");
    argv.add("--update-failure-action");
    argv.add("rollback");
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
    // What ResourceProvisioning made exist a moment ago, as the generic contract. The name is
    // re-validated HERE, at the last line before the argv, exactly like the health path: it is
    // repository-authored input being spliced into an environment-variable key. The idp resource's
    // name is the reserved constant `idp`, so it passes trivially — the check still runs, because
    // this loop has no reason to know which resource type it is looking at.
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
    PdProcess.Result inspected =
        run(
            List.of(runtime, "service", "inspect", "--format", SPEC_MOUNTS_FORMAT, name),
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
    try {
      declared =
          aliasesOf(
              spec,
              ServiceExtras.of(
                  extrasSource.forDeployment(
                      spec.applicationName(),
                      spec.environmentName(),
                      spec.version(),
                      spec.declarationSeeded()),
                  spec.applicationName()),
              networks);
    } catch (ServiceExtras.Refused e) {
      // Config states something swarm cannot express. The argv build in `apply` reaches the same
      // refusal a line later and returns REFUSED, having changed nothing — so the one thing this
      // must not do is destroy a service on the way there.
      return null;
    }
    if (declared.isEmpty()) {
      // Every environment application that declares no alias of its own. Not one CLI call is spent.
      return null;
    }
    PdProcess.Result inspected =
        run(
            List.of(runtime, "service", "inspect", "--format", SPEC_NETWORK_ALIASES_FORMAT, name),
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
   */
  private void removeForRecreate(String name) {
    PdProcess.Result removed = run(List.of(runtime, "service", "rm", name), CLEANUP_TIMEOUT);
    if (removed.exitCode() != 0) {
      // The create that follows will fail on the name still being taken, and that is the honest
      // outcome: a REFUSED deployment naming what could not be removed, with the predecessor still
      // serving.
      LOG.warnf("Could not remove %s to recreate it with its declared shape: %s",
          name, removed.output());
      return;
    }
    if (!awaitTasksGone(name)) {
      LOG.warnf(
          "The service %s is removed but its task is still stopping — the successor may start"
              + " beside it, on the same volumes",
          name);
    }
  }

  /**
   * The environment keys the LIVE service carries, so an update can state what is no longer stated.
   *
   * <p>Read with the same {@code service inspect} the rest of this class asks its questions with.
   * An inspect that cannot answer removes NOTHING: a deployment must not lose an application's
   * environment because one CLI call failed, and the next deployment asks again.
   */
  private List<String> currentSpecEnvKeys(String name) {
    PdProcess.Result inspected =
        run(List.of(runtime, "service", "inspect", "--format", SPEC_ENV_FORMAT, name),
            INSPECT_TIMEOUT);
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
   * an application's environment is qits-configuration and nothing else.
   *
   * <p><b>What is never removed is a family rather than a list of exceptions</b>, and it has three
   * members:
   *
   * <ul>
   *   <li>everything the deployment ITSELF states — {@code extras.env()}, which is the whole of
   *       what config says;
   *   <li>{@link #DEPLOYER_OWN_VARIABLES}, this component's identity set: it writes them on every
   *       argv and an operator never states them, so a diff against config alone would remove and
   *       re-add the same four values on every deployment;
   *   <li>anything under {@link #RESOURCE_PREFIX}, which {@code ResourceProvisioning} injects from
   *       the registry row. Config cannot state a provisioned credential and must not be able to
   *       delete one — a removed datasource triple is an application that cannot boot.
   * </ul>
   *
   * <p><b>Only an update does this.</b> A create has no predecessor to diff against, and {@code
   * service create} has no such flag.
   */
  private void envRemovals(List<String> argv, String name, ServiceExtras extras) {
    Set<String> stated = new HashSet<>();
    for (String variable : extras.env()) {
      int equals = variable.indexOf('=');
      stated.add(equals > 0 ? variable.substring(0, equals) : variable);
    }
    // Sorted, so one deployment's argv is the same argv twice and a diff of two run logs is
    // readable. A HashSet's order is not a contract and a reader would read it as one.
    for (String key : new TreeSet<>(currentSpecEnvKeys(name))) {
      if (stated.contains(key)
          || DEPLOYER_OWN_VARIABLES.contains(key)
          || key.startsWith(RESOURCE_PREFIX)) {
        continue;
      }
      argv.add("--env-rm");
      argv.add(key);
    }
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
      List<String> argv,
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
      argv.add("--mount");
      argv.add(
          "type="
              + mount.kind().name().toLowerCase(Locale.ROOT)
              + ",source="
              + mount.source()
              + ",target="
              + mount.target()
              + (mount.readOnly() ? ",readonly" : ""));
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
      argv.add("--publish");
      argv.add(
          "published="
              + publish.published()
              + ",target="
              + publish.target()
              + (publish.protocol() == null ? "" : ",protocol=" + publish.protocol())
              + ",mode="
              + publishMode.spelling());
    }
    for (String group : extras.groups()) {
      argv.add("--group");
      argv.add(group);
    }
    for (String variable : extras.env()) {
      argv.add("--env");
      argv.add(variable);
    }
  }

  // --- reading swarm back ----------------------------------------------------------------------

  /** The two overlays a service may be on, in declaration order. See the class javadoc. */
  private List<String> collapse(ServiceSpec spec) {
    Set<String> networks = new LinkedHashSet<>();
    if (flatNetwork != null && !flatNetwork.isBlank()) {
      networks.add(flatNetwork.strip());
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
    return flatNetwork != null && flatNetwork.strip().equals(network);
  }

  private boolean serviceExists(String name) {
    return run(
                List.of(runtime, "service", "inspect", "--format", "{{.ID}}", name),
                INSPECT_TIMEOUT)
            .exitCode()
        == 0;
  }

  /** The state word of every task of the current generation, lowercased. */
  private List<String> runningGenerationStates(String name) {
    PdProcess.Result listed =
        run(
            List.of(
                runtime,
                "service",
                "ps",
                name,
                "--filter",
                "desired-state=running",
                "--no-trunc",
                "--format",
                "{{.CurrentState}}"),
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
    PdProcess.Result listed =
        run(List.of(runtime, "service", "ps", name, "--no-trunc"), INSPECT_TIMEOUT);
    return safe(listed.output());
  }

  /** A bounded tail of the service's own output — the second half of the diagnosis. */
  private String logs(String name) {
    PdProcess.Result result =
        run(
            List.of(runtime, "service", "logs", "--tail", LOG_TAIL_LINES, name), CLEANUP_TIMEOUT);
    return safe(result.output());
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
