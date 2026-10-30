package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A scripted stand-in for the orchestrator — what {@link DeployService} sees, with no orchestrator
 * behind it. {@code @Mock} makes it the {@link DeploymentDriver} for every {@code @QuarkusTest} in
 * this module, which is what keeps a clone's {@code mvn verify} docker-free.
 *
 * <p><b>It sat one layer lower for a release, at the docker CLI</b>, so the flow tests drove the
 * hand-rolled cutover — the stop, the joins, the reconcile, the rollback — for real. That
 * choreography is gone with the docker path: a replace is one {@code service update} and the
 * verdict swarm already reached, so there is nothing left above the CLI worth exercising through a
 * booted application. What the argv says and what is read back out of it is
 * {@code SwarmDeploymentDriverTest}'s, in plain JUnit, and what remains here is the state machine —
 * the four status transitions, the four announcements and the reap.
 *
 * <p>Application-scoped and therefore shared across tests: reset it in {@code @BeforeEach} and use
 * distinct environment names per test. State is read through <b>methods only</b>: an injected
 * reference is a CDI client proxy, and a field read on a proxy sees the proxy's fields, never the
 * bean's.
 */
@Mock
@ApplicationScoped
public class FakeDeploymentDriver implements DeploymentDriver {

  private final List<ServiceSpec> applied = Collections.synchronizedList(new ArrayList<>());
  private final List<String> awaited = Collections.synchronizedList(new ArrayList<>());

  /** The deadline each {@link #awaitConverged} was given, in the order of {@link #awaited}. */
  private final List<Duration> awaitedTimeouts = Collections.synchronizedList(new ArrayList<>());
  private final List<String> reaped = Collections.synchronizedList(new ArrayList<>());
  private final List<String> pulled = Collections.synchronizedList(new ArrayList<>());
  private final List<Network> ensured = Collections.synchronizedList(new ArrayList<>());
  private final List<Network> existing = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedNetworks = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedEnvironments = Collections.synchronizedList(new ArrayList<>());
  private final List<String> detached = Collections.synchronizedList(new ArrayList<>());

  /** The services a plane conversion retired — one per tier the application used to serve in. */
  private final List<String> removedServices = Collections.synchronizedList(new ArrayList<>());

  /** Every driver call in arrival order, tagged {@code kind:target} — the ORDERING assertions. */
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  /** What {@link #runningImage} answers per name — the startup sweep's evidence. */
  private final Map<String, String> runningImages = new ConcurrentHashMap<>();

  /**
   * What {@link #stagedEnvironment} answers per application name — the deployer's own half; the
   * deployment config's half is {@link #stagedExtras}, appended only when asked for. Unscripted is
   * empty.
   */
  private final Map<String, List<String>> stagedEnvironments = new ConcurrentHashMap<>();

  private final Map<String, List<String>> stagedExtras = new ConcurrentHashMap<>();

  /** What {@link #liveEnvironment} answers per service name; unscripted is "no such service". */
  private final Map<String, List<String>> liveEnvironments = new ConcurrentHashMap<>();

  /**
   * What {@link #observe} answers per name. A name nothing scripted is <b>gone</b>: this fake runs
   * nothing, so the only services there can be are the ones a test said exist.
   */
  private final Map<String, HealthGate.Poll> observations = new ConcurrentHashMap<>();

  /**
   * What {@link #desiredReplicas} answers per name. A name nothing scripted answers <b>empty</b>,
   * which is the "cannot say" the observer must not read as a deliberate stop — so a test that wants
   * a scaled-down service says so, and every test that does not is unaffected.
   */
  private final Map<String, Integer> declaredReplicas = new ConcurrentHashMap<>();

  /** What {@link #scale} and {@link #restart} answer. Refuse one by scripting a refusal. */
  private volatile ScaleResult nextScale = new ScaleResult(ScaleOutcome.SCALED, null);

  private volatile PullResult nextPull = new PullResult(PullOutcome.OK, null);
  private volatile ApplyResult nextApply = new ApplyResult(ApplyOutcome.APPLIED, null);
  private volatile Convergence nextConvergence = Convergence.converged(List.of());

  /** How long {@link #awaitConverged} parks — a deployment slow enough to enqueue work behind. */
  private volatile Duration convergeDelay = Duration.ZERO;

  /**
   * Runs INSIDE {@link #removeEnvironmentContainers}, so a test can observe the world at the moment
   * the teardown happens. It is how the "the runtime first, rows last" order is asserted: with one
   * service the only place the ordering is visible from is inside a driver call.
   */
  private volatile Runnable duringContainerReap = () -> {};

  /**
   * The runtime refusing a retirement — the non-fatal claim's script. The shipped driver never
   * throws out of {@code removeService}, so the only way to exercise the caller's own belt is a
   * double that does, and what the assertion is then about is the DEPLOYMENT: still ACTIVE, still
   * announced, with a WARN as the whole cost.
   */
  private volatile RuntimeException removeServiceFailure;

  /** The service {@link #isOwnService} answers true for — the deployer's own; none by default. */
  private volatile String ownService;

  public void reset() {
    applied.clear();
    awaited.clear();
    awaitedTimeouts.clear();
    reaped.clear();
    pulled.clear();
    ensured.clear();
    existing.clear();
    removedNetworks.clear();
    removedEnvironments.clear();
    detached.clear();
    removedServices.clear();
    removeServiceFailure = null;
    ownService = null;
    calls.clear();
    runningImages.clear();
    stagedEnvironments.clear();
    stagedExtras.clear();
    liveEnvironments.clear();
    observations.clear();
    declaredReplicas.clear();
    nextScale = new ScaleResult(ScaleOutcome.SCALED, null);
    nextPull = new PullResult(PullOutcome.OK, null);
    nextApply = new ApplyResult(ApplyOutcome.APPLIED, null);
    nextConvergence = Convergence.converged(List.of());
    convergeDelay = Duration.ZERO;
    duringContainerReap = () -> {};
  }

  // --- scripting ---------------------------------------------------------------------------------

  public void scriptPull(PullResult result) {
    nextPull = result;
  }

  public void scriptApply(ApplyResult result) {
    nextApply = result;
  }

  public void scriptConvergence(Convergence convergence) {
    nextConvergence = convergence;
  }

  /** Make the applied deployment take this long to converge. See {@link #convergeDelay}. */
  public void scriptConvergeDelay(Duration delay) {
    convergeDelay = delay;
  }

  /** What the startup sweep is told is running, per name. Nothing scripted means no such service. */
  public void scriptRunningImage(String name, String imageRef) {
    runningImages.put(name, imageRef);
  }

  /**
   * The {@code KEY=VALUE} entries a deployment of {@code applicationName} would state: the
   * deployer's own, and the deployment config's {@code env} that follow them on the argv.
   */
  public void scriptStagedEnvironment(
      String applicationName, List<String> deployerOwn, List<String> extras) {
    stagedEnvironments.put(applicationName, List.copyOf(deployerOwn));
    stagedExtras.put(applicationName, List.copyOf(extras));
  }

  /** The {@code KEY=VALUE} entries the live service {@code name} carries. */
  public void scriptLiveEnvironment(String name, List<String> variables) {
    liveEnvironments.put(name, List.copyOf(variables));
  }

  /**
   * The state {@link #observe} reports for this service, in the {@code <status>/<health>} spelling
   * the gate and the observer both read ({@code running/healthy}, {@code starting/unhealthy}).
   */
  public void scriptObservation(String name, String state) {
    observations.put(name, HealthGate.Poll.of(state));
  }

  /** The service the runtime cannot answer about at all — gone underneath a row that names it. */
  public void scriptObservationGone(String name, String detail) {
    observations.put(name, HealthGate.Poll.gone(detail));
  }

  /**
   * How many tasks the orchestrator is declared to run for this service. Nothing scripted is the
   * runtime declining to answer, which is deliberately NOT a scale to zero.
   */
  public void scriptDeclaredReplicas(String name, int replicas) {
    declaredReplicas.put(name, replicas);
  }

  /** What the next scale or restart answers — a refusal, or a hand-off. */
  public void scriptScale(ScaleResult result) {
    nextScale = result;
  }

  /** A network the runtime already has when the test starts — {@link #networks} returns it. */
  public void scriptExistingNetwork(Network network) {
    existing.add(network);
  }

  /** What to run while the environment's services are being reaped. See the field. */
  public void scriptDuringContainerReap(Runnable hook) {
    duringContainerReap = hook;
  }

  /** Make every {@link #removeService} throw this. See {@link #removeServiceFailure}. */
  public void scriptRemoveServiceFailure(RuntimeException failure) {
    removeServiceFailure = failure;
  }

  /** Make {@link #isOwnService} answer true for this name: the deployer's own service. */
  public void scriptOwnService(String name) {
    ownService = name;
  }

  // --- what was recorded -------------------------------------------------------------------------

  public List<ServiceSpec> applied() {
    return List.copyOf(applied);
  }

  public List<String> awaited() {
    return List.copyOf(awaited);
  }

  public List<Duration> awaitedTimeouts() {
    return List.copyOf(awaitedTimeouts);
  }

  public List<String> reaped() {
    return List.copyOf(reaped);
  }

  public List<String> pulled() {
    return List.copyOf(pulled);
  }

  public List<Network> ensured() {
    return List.copyOf(ensured);
  }

  public List<String> ensuredNetworks() {
    return ensured().stream().map(Network::name).toList();
  }

  public List<String> removedNetworks() {
    return List.copyOf(removedNetworks);
  }

  public List<String> removedEnvironments() {
    return List.copyOf(removedEnvironments);
  }

  /** The networks the platform plane was asked to release, one entry per network. */
  public List<String> detached() {
    return List.copyOf(detached);
  }

  /**
   * The services that were actually retired — a refused one is in {@link #calls()} and not here,
   * which is what lets a test tell "it was asked for" from "it happened".
   */
  public List<String> removedServices() {
    return List.copyOf(removedServices);
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  // --- the seam ----------------------------------------------------------------------------------

  /**
   * The wire alias, which is swarm's answer: the service name IS the address, so the row, the
   * convergence and the reap all have to agree on a name that stays the same across deployments.
   */
  @Override
  public String nameOf(ServiceSpec spec) {
    return spec.wireAlias();
  }

  @Override
  public PullResult pull(String imageRef) {
    pulled.add(imageRef);
    calls.add("pull:" + imageRef);
    return nextPull;
  }

  @Override
  public ApplyResult apply(ServiceSpec spec) {
    applied.add(spec);
    calls.add("apply:" + spec.wireAlias());
    return nextApply;
  }

  @Override
  public Convergence awaitConverged(String name, Duration timeout) {
    awaited.add(name);
    awaitedTimeouts.add(timeout);
    calls.add("await:" + name);
    Duration delay = convergeDelay;
    if (!delay.isZero()) {
      try {
        Thread.sleep(delay.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return nextConvergence;
  }

  @Override
  public void reap(List<String> names) {
    reaped.addAll(names);
    calls.add("reap:" + names);
  }

  @Override
  public boolean isOwnService(String name) {
    calls.add("isOwnService:" + name);
    return name.equals(ownService);
  }

  @Override
  public void removeService(String name) {
    calls.add("removeService:" + name);
    RuntimeException failure = removeServiceFailure;
    if (failure != null) {
      throw failure;
    }
    removedServices.add(name);
  }

  @Override
  public List<String> stagedEnvironment(ServiceSpec spec, boolean includeExtras) {
    calls.add("stagedEnvironment:" + spec.applicationName() + ":" + includeExtras);
    List<String> variables =
        new ArrayList<>(stagedEnvironments.getOrDefault(spec.applicationName(), List.of()));
    if (includeExtras) {
      variables.addAll(stagedExtras.getOrDefault(spec.applicationName(), List.of()));
    }
    return List.copyOf(variables);
  }

  /**
   * The swarm driver's family, stated for the double: the identity variables and the resource
   * prefix. The real predicate is pinned by {@code SwarmDeploymentDriverTest}.
   */
  @Override
  public boolean keptWhenUnstated(String key) {
    return key.startsWith("QITS_RESOURCE_")
        || List.of(
                "QITS_ENVIRONMENT",
                "QITS_APPLICATION",
                "QITS_DOMAIN",
                "OTEL_RESOURCE_ATTRIBUTES",
                "QUARKUS_OTEL_RESOURCE_ATTRIBUTES")
            .contains(key);
  }

  @Override
  public Optional<List<String>> liveEnvironment(String name) {
    calls.add("liveEnvironment:" + name);
    return Optional.ofNullable(liveEnvironments.get(name));
  }

  @Override
  public Optional<RunningImage> runningImage(String name) {
    calls.add("runningImage:" + name);
    String image = runningImages.get(name);
    return image == null ? Optional.empty() : Optional.of(new RunningImage(image, null));
  }

  /**
   * Recorded in the call log like every other call, which is what lets a test assert that an
   * observation pass and a deployment never interleave: both run on the one deploy worker, so the
   * pass's observations form a block rather than sitting between a deployment's calls.
   */
  @Override
  public HealthGate.Poll observe(String name) {
    calls.add("observe:" + name);
    HealthGate.Poll scripted = observations.get(name);
    return scripted == null ? HealthGate.Poll.gone("no such service: " + name) : scripted;
  }

  @Override
  public OptionalInt desiredReplicas(String name) {
    calls.add("desiredReplicas:" + name);
    Integer declared = declaredReplicas.get(name);
    return declared == null ? OptionalInt.empty() : OptionalInt.of(declared);
  }

  /**
   * Records the request and applies it to what {@link #desiredReplicas} answers, so a test that
   * scales down and then drives an observation pass sees what a daemon would have shown it.
   */
  @Override
  public ScaleResult scale(String name, int replicas) {
    calls.add("scale:" + name + "=" + replicas);
    ScaleResult result = nextScale;
    if (result.applied()) {
      declaredReplicas.put(name, replicas);
    }
    return result;
  }

  @Override
  public ScaleResult restart(String name) {
    calls.add("restart:" + name);
    return nextScale;
  }

  @Override
  public boolean ensureNetwork(Network spec) {
    ensured.add(spec);
    calls.add("ensureNetwork:" + spec.name());
    boolean known = existing.stream().anyMatch(n -> n.name().equals(spec.name()));
    if (!known) {
      existing.add(spec);
    }
    return !known;
  }

  @Override
  public void removeNetwork(String network) {
    removedNetworks.add(network);
    calls.add("removeNetwork:" + network);
  }

  @Override
  public List<Network> networks() {
    return List.copyOf(existing);
  }

  @Override
  public void detachPlatformPlane(List<String> networks) {
    detached.addAll(networks);
    calls.add("detachPlatformPlane:" + networks);
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    removedEnvironments.add(environmentId);
    calls.add("removeEnvironmentContainers:" + environmentId);
    duringContainerReap.run();
    return 0;
  }
}
