package eu.wohlben.qits.deployments.dbhost;

import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.docker.SwarmArgv;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.deployments.deployments.control.DatabaseContainers;
import eu.wohlben.qits.deployments.deployments.control.DockerCredentialFile;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link DatabaseContainers}: the database move's and the
 * private server's one-shot containers, started on this process's own docker socket through the
 * platform's shared docker layer, {@code qits-containers-driver} (qits-2175, the owner's ruling of
 * 2026-11-13). Three of its types do the work:
 *
 * <ul>
 *   <li>{@link ContainerSpec} is the container — the image, the ONE {@code network} (never an extra
 *       network, never a private edge), the named volume, the environment and {@code sh -c <script>}
 *       as the entrypoint;
 *   <li>{@link DockerArgv} renders it ({@link DockerArgv#run} under {@link LifecyclePolicy#ephemeral},
 *       so no restart policy), and renders the {@code logs}, {@code rm} and {@code volume} calls
 *       around it;
 *   <li>{@link ContainerProcess} runs each argv with a deadline and a bounded output tail, and adds
 *       the secret variables to the CLI's own environment.
 * </ul>
 *
 * <p><b>What the library cannot express, and how each gap is closed here.</b>
 *
 * <ul>
 *   <li><b>A variable passed by name.</b> {@link DockerArgv#run} renders every variable as {@code -e
 *       KEY=VALUE}, which would put the admin password in the deployer's process table and in any log
 *       of the argv. So the spec carries the secrets like any variable and {@link #withheld} rewrites
 *       exactly those elements to {@code -e KEY} — docker's own "take it from my environment" form —
 *       while {@link ContainerProcess#run(Path, List, Map, Duration, int)} hands the CLI the values.
 *       Every other element is the library's, byte for byte.
 *   <li><b>stdin.</b> The library's run is detached ({@code -d}) and has no {@code -i}, so nothing
 *       can be piped in. The script is therefore {@code sh -c}'s argument; it names identifiers and
 *       file paths and no secret, and reads both passwords from its environment ({@code PGPASSWORD},
 *       and psql's {@code \getenv} for the role's).
 *   <li><b>Waiting for the end.</b> The library never renders {@code --rm} (a self-removing container
 *       races the log capture), so the run is followed by {@link DockerArgv#wait}, which answers the
 *       exit code; then {@link DockerArgv#logsTail} is the diagnosis and {@link DockerArgv#rm} the
 *       teardown, on every path.
 * </ul>
 *
 * <p>The swarm task listing {@link #drained} reads is {@link SwarmArgv#servicePs}. No argv here is
 * built by hand.</p>
 *
 * <p>Its labels are the deployer's own namespace, {@value #NAMESPACE_PREFIX}, never another
 * embedder's: qits-containers' volume collection reads {@code qits.containers.}, and a move's volume
 * must not be one of its candidates.
 */
@ApplicationScoped
public class DockerDatabaseContainers implements DatabaseContainers {

  private static final Logger LOG = Logger.getLogger(DockerDatabaseContainers.class);

  static final String NAMESPACE_PREFIX = "qits.platform.deployments.dbmove.";

  private static final LabelNamespace NAMESPACE = new LabelNamespace(NAMESPACE_PREFIX);

  private static final String OWNER = "qits-deployments";

  /**
   * The label a move's own one-shots carry, {@code qits.platform.deployments.dbmove.move=<move id>}:
   * what an abort's {@link #kill} finds the running extract or ingest by (qits-2243).
   */
  static final String MOVE_LABEL = NAMESPACE.key("move");

  /** Which process started a container — a diagnostic label, never a filter anything acts on. */
  private static final String INSTANCE = "pd-" + UUID.randomUUID().toString().substring(0, 8);

  /** How long the {@code docker run -d} itself may take — creating a container, not running it. */
  private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

  /** Every short call: logs, rm, volume, service ps. */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

  /** How much of a container's own output is read back — the manifest lines and the diagnosis. */
  private static final int LOG_LINES = 5000;

  /** Task states that are not over yet — anything else ({@code shutdown}, {@code complete}...) is. */
  private static final Set<String> LIVE_TASK_STATES =
      Set.of("new", "pending", "assigned", "accepted", "preparing", "ready", "starting", "running");

  @ConfigProperty(name = "qits.deployments.container-runtime")
  String runtime;

  @ConfigProperty(name = "qits.deployments.output-max-chars")
  int outputMaxChars;

  /** The credential directory every docker child is handed — see {@link #cliEnvironment}. */
  @ConfigProperty(name = "qits.deployments.docker-config-dir")
  Optional<String> dockerConfigDir;

  /**
   * One docker CLI call: the argv, the variables ADDED to the CLI's environment, the deadline. A seam
   * so the suite asserts the argv element for element with no daemon, the swarm driver's {@code Cli}.
   */
  @FunctionalInterface
  interface Cli {
    ContainerProcess.Result run(List<String> argv, Map<String, String> environment, Duration timeout);
  }

  private volatile Cli cli;

  /** Package-private, a method rather than a field write — the CDI client proxy reason. */
  void scriptCli(Cli scripted) {
    this.cli = scripted;
  }

  private ContainerProcess.Result call(
      List<String> argv, Map<String, String> environment, Duration timeout) {
    Cli scripted = cli;
    if (scripted != null) {
      return scripted.run(argv, environment, timeout);
    }
    return ContainerProcess.run(null, argv, environment, timeout, outputMaxChars);
  }

  @Override
  public Outcome run(OneShot shot, Duration timeout) {
    String name = containerName(shot.purpose());
    List<String> argv;
    try {
      argv = withheld(runArgv(shot, name), shot.secrets().keySet());
    } catch (RuntimeException refused) {
      // A belt in the library refused a value — an image, a network, a volume name. Nothing ran.
      return new Outcome(-1, "refused the container: " + refused.getMessage(), false);
    }
    Map<String, String> secrets = shot.secrets();
    LOG.infof(
        "Starting the %s container %s from %s on the one network %s%s",
        shot.purpose(),
        name,
        shot.image(),
        shot.network(),
        shot.volume() == null ? "" : ", with the volume " + shot.volume());

    ContainerProcess.Result started = call(argv, cliEnvironment(secrets), START_TIMEOUT);
    if (started.exitCode() != 0 || started.timedOut()) {
      remove(name);
      return new Outcome(
          -1,
          DatabaseContainers.redact("docker run failed: " + started.output(), secrets),
          started.timedOut());
    }
    ContainerProcess.Result waited = call(DockerArgv.wait(runtime, name), Map.of(), timeout);
    String output = logs(name);
    remove(name);
    if (waited.timedOut()) {
      return new Outcome(
          -1,
          DatabaseContainers.redact(
              "still running after " + timeout.toSeconds() + "s — removed\n" + output, secrets),
          true);
    }
    int exitCode = exitCodeOf(waited);
    return new Outcome(exitCode, DatabaseContainers.redact(output, secrets), false);
  }

  /**
   * The library's {@code docker run} for this one-shot. Package-private for the argv test: exactly
   * one {@code --network}, no {@code --network-add}, and the secrets present only by key.
   */
  List<String> runArgv(OneShot shot, String name) {
    ContainerSpec.Builder spec =
        ContainerSpec.builder(shot.image())
            .network(shot.network())
            .entrypoint("sh", "-c", shot.script())
            // The image is what a postgres service on this node is running, so it is here already;
            // a pull would only turn a registry hiccup into a failed move.
            .pullPolicy(ContainerSpec.PullPolicy.NEVER);
    if (shot.volume() != null) {
      spec.mount(shot.volume(), MOUNT);
    }
    shot.environment().forEach(spec::env);
    shot.secrets().forEach(spec::env);
    Map<String, String> labels =
        new LinkedHashMap<>(NAMESPACE.forContainer(OWNER, shot.purpose(), name, name, INSTANCE));
    if (shot.moveId() != null) {
      labels.put(MOVE_LABEL, shot.moveId());
    }
    return DockerArgv.run(
        runtime, NAMESPACE, name, spec.build(), labels, LifecyclePolicy.ephemeral(null));
  }

  /**
   * {@code argv} with every {@code -e KEY=VALUE} whose key is a secret turned into {@code -e KEY}:
   * docker then reads the value from the CLI's own environment, which {@link #cliEnvironment} sets.
   * Package-private for the test that holds "no password in an argv".
   */
  static List<String> withheld(List<String> argv, Set<String> secretKeys) {
    List<String> rendered = new ArrayList<>(argv.size());
    for (int i = 0; i < argv.size(); i++) {
      String element = argv.get(i);
      if (i > 0 && "-e".equals(argv.get(i - 1))) {
        int eq = element.indexOf('=');
        if (eq > 0 && secretKeys.contains(element.substring(0, eq))) {
          rendered.add(element.substring(0, eq));
          continue;
        }
      }
      rendered.add(element);
    }
    return List.copyOf(rendered);
  }

  /** The secrets, plus {@code DOCKER_CONFIG} when this process wrote its own credential file. */
  private Map<String, String> cliEnvironment(Map<String, String> secrets) {
    Map<String, String> environment = new HashMap<>(secrets);
    environment.putAll(DockerCredentialFile.dockerEnvironment(dockerConfigDir));
    return environment;
  }

  private String logs(String name) {
    ContainerProcess.Result logs =
        call(DockerArgv.logsTail(runtime, name, LOG_LINES), Map.of(), CALL_TIMEOUT);
    return logs.output() == null ? "" : logs.output();
  }

  private void remove(String name) {
    ContainerProcess.Result removed = call(DockerArgv.rm(runtime, name), Map.of(), CALL_TIMEOUT);
    if (removed.exitCode() != 0) {
      LOG.warnf("Could not remove the container %s: %s", name, firstLine(removed.output()));
    }
  }

  /** {@code docker wait} prints the exit code alone; anything else is a run that cannot say. */
  static int exitCodeOf(ContainerProcess.Result waited) {
    if (waited.exitCode() != 0) {
      return -1;
    }
    try {
      return Integer.parseInt(waited.output().strip());
    } catch (RuntimeException unreadable) {
      return -1;
    }
  }

  /**
   * An instance for use before Quarkus starts (qits-2209): the deployer's successor moves its own
   * databases in {@code main}, where there is no CDI and no config yet.
   */
  public static DockerDatabaseContainers standalone(String runtime, int outputMaxChars) {
    DockerDatabaseContainers containers = new DockerDatabaseContainers();
    containers.runtime = runtime;
    containers.outputMaxChars = outputMaxChars;
    containers.dockerConfigDir = Optional.empty();
    return containers;
  }

  @Override
  public boolean createVolume(String volume) {
    return createVolume(volume, Map.of());
  }

  @Override
  public boolean createVolume(String volume, Map<String, String> extra) {
    Map<String, String> labels = new LinkedHashMap<>(NAMESPACE.forVolume(OWNER, "database-move", volume));
    extra.forEach((key, value) -> labels.put(NAMESPACE.key(key), value));
    ContainerProcess.Result created =
        call(
            DockerArgv.volumeCreate(runtime, NAMESPACE, new VolumeSpec(volume), labels),
            Map.of(),
            CALL_TIMEOUT);
    if (created.exitCode() != 0) {
      LOG.warnf("Could not create the volume %s: %s", volume, firstLine(created.output()));
      return false;
    }
    return true;
  }

  /**
   * {@code docker volume ls -q --filter label=<ns.key>=<value>}, then one {@code docker volume
   * inspect} per volume for its labels — the library's two verbs. Labels outside this component's
   * namespace are left out; the namespace is cut off the rest.
   */
  @Override
  public Map<String, Map<String, String>> labelledVolumes(String key, String value) {
    ContainerProcess.Result listed =
        call(DockerArgv.volumeLs(runtime, Map.of(NAMESPACE.key(key), value)), Map.of(), CALL_TIMEOUT);
    if (listed.exitCode() != 0 || listed.timedOut()) {
      throw new IllegalStateException("could not list the volumes: " + firstLine(listed.output()));
    }
    Map<String, Map<String, String>> volumes = new LinkedHashMap<>();
    for (String line : String.valueOf(listed.output()).split("\n")) {
      String volume = line.strip();
      if (volume.isEmpty()) {
        continue;
      }
      ContainerProcess.Result inspected =
          call(DockerArgv.volumeInspectLabels(runtime, volume), Map.of(), CALL_TIMEOUT);
      if (inspected.exitCode() != 0 || inspected.timedOut()) {
        throw new IllegalStateException(
            "could not read the labels of " + volume + ": " + firstLine(inspected.output()));
      }
      volumes.put(volume, shortLabels(inspected.output()));
    }
    return volumes;
  }

  /** {@code key=value} lines, the namespace cut off; lines of another namespace are dropped. */
  static Map<String, String> shortLabels(String output) {
    Map<String, String> labels = new LinkedHashMap<>();
    for (String line : String.valueOf(output).split("\n")) {
      String entry = line.strip();
      int eq = entry.indexOf('=');
      if (eq <= 0 || !entry.startsWith(NAMESPACE_PREFIX)) {
        continue;
      }
      labels.put(entry.substring(NAMESPACE_PREFIX.length(), eq), entry.substring(eq + 1));
    }
    return labels;
  }

  @Override
  public boolean removeVolume(String volume) {
    ContainerProcess.Result removed =
        call(DockerArgv.volumeRm(runtime, volume), Map.of(), CALL_TIMEOUT);
    if (removed.exitCode() == 0
        || String.valueOf(removed.output()).toLowerCase(Locale.ROOT).contains("no such volume")) {
      return true;
    }
    LOG.warnf("Could not remove the volume %s: %s", volume, firstLine(removed.output()));
    return false;
  }

  /**
   * {@code docker ps -aq --filter label=<move label>=<id>}, then {@code docker rm -f} of each — the
   * library's own two verbs. The worker's {@code docker wait} on a removed container returns, and
   * its run is a failed outcome the move reads as the abort.
   */
  @Override
  public boolean kill(String moveId) {
    ContainerProcess.Result listed =
        call(DockerArgv.psByLabels(runtime, Map.of(MOVE_LABEL, moveId)), Map.of(), CALL_TIMEOUT);
    if (listed.exitCode() != 0 || listed.timedOut()) {
      LOG.warnf("Could not list the containers of the move %s: %s", moveId, firstLine(listed.output()));
      return false;
    }
    boolean all = true;
    for (String line : String.valueOf(listed.output()).split("\n")) {
      String container = line.strip();
      if (container.isEmpty()) {
        continue;
      }
      ContainerProcess.Result removed;
      try {
        removed = call(DockerArgv.rm(runtime, container), Map.of(), CALL_TIMEOUT);
      } catch (RuntimeException refused) {
        LOG.warnf("Refused to remove %s of the move %s: %s", container, moveId, refused.getMessage());
        all = false;
        continue;
      }
      if (removed.exitCode() != 0) {
        LOG.warnf("Could not remove %s of the move %s: %s", container, moveId, firstLine(removed.output()));
        all = false;
      } else {
        LOG.infof("Removed the container %s of the aborted move %s", container, moveId);
      }
    }
    return all;
  }

  @Override
  public Drain drained(String service) {
    ContainerProcess.Result listed =
        call(
            SwarmArgv.servicePs(runtime, service, List.of(), false, "{{.CurrentState}}"),
            Map.of(),
            CALL_TIMEOUT);
    return drainOf(listed);
  }

  /** Package-private for the parsing test. */
  static Drain drainOf(ContainerProcess.Result listed) {
    String output = listed.output() == null ? "" : listed.output();
    if (listed.exitCode() != 0 || listed.timedOut()) {
      return output.toLowerCase(Locale.ROOT).contains("no such service")
          ? Drain.DRAINED
          : Drain.UNKNOWN;
    }
    for (String line : output.split("\n")) {
      String state = line.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
      if (LIVE_TASK_STATES.contains(state)) {
        return Drain.RUNNING;
      }
    }
    return Drain.DRAINED;
  }

  /** {@code qits-dbmove-<purpose>-<12 hex>}: greppable on the host, unique per run. */
  static String containerName(String purpose) {
    return "qits-dbmove-"
        + purpose
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static String firstLine(String output) {
    String text = output == null ? "" : output.strip();
    int newline = text.indexOf('\n');
    return newline < 0 ? text : text.substring(0, newline);
  }
}
