package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.error.ConflictException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>A changed egress secret redeploys qits-egress</b> (qits-2666, epic qits-2525). qits-egress reads
 * every {@code QITS_EGRESS_SECRET_*} once, at boot, and a setting reaches a container only through
 * a deployment that rebuilds its argv from the settings store. So when a setting of {@value
 * #APPLICATION} whose key starts with {@value #PREFIX} is created, changed or deleted, its live
 * version in that tier is redeployed — {@link DatabaseMoves#queueRedeployOfLive}, {@link
 * DeployService#redeploy}, exactly as {@link EgressSecretHandOvers} does. Never a restart ({@code
 * ApplicationScaling#restart}): it replaces the tasks on the unchanged swarm spec, so the new value
 * would never reach the container.
 *
 * <p><b>Who notes a write</b>: {@link Settings#put} (a new row, or a changed value or secret flag)
 * and {@link Settings#delete} — the settings door's {@code PUT}/{@code DELETE}, the only writers of
 * a lone row. The store's other writers each ride a deployment of the application they write
 * ({@code copyForRename} in the successor's cutover, {@code retireImageDefaults} after the
 * deployment that stopped stating the rows, {@code retire} for a decommissioned predecessor), so
 * there is nothing to redeploy. The hand-over writes through the repository and redeploys
 * qits-egress itself; it also calls {@link #absorbed}, so a door write pending in that tier rides
 * its redeploy rather than queueing a second one.
 *
 * <p><b>When</b>: {@link #settle}, on the reconcile tick ({@link EgressGrantPusher#reconcile}, on the
 * deploy worker), once {@code qits.deployments.egress.secret-redeploy-debounce} (10 s) has passed
 * since the tier's last secret write — so writes within it give one redeploy. A deployment of
 * qits-egress in that tier created at or after the last write ({@code QUEUED}, {@code STARTING} or
 * {@code ACTIVE}) already reads the new rows and absorbs the write; one in flight that started
 * before it is waited for, and one more redeploy is queued after it. A redeploy that cannot be
 * queued stays OWED in memory and the next tick tries again (a process restart loses it — the next
 * ordinary deployment of qits-egress reads the store fresh anyway). A tier qits-egress was never
 * deployed in owes nothing: its first deployment reads the rows. The log names the keys, the tier
 * and the version — never a value.
 */
@ApplicationScoped
public class EgressSecretRedeploys {

  private static final Logger LOG = Logger.getLogger(EgressSecretRedeploys.class);

  /** The application whose secrets this watches. */
  static final String APPLICATION = "qits-egress";

  /** The keys that are its secrets. */
  static final String PREFIX = "QITS_EGRESS_SECRET_";

  /** Who the redeploy's log line names. */
  static final String ACTOR = "egress secret change";

  /** The deployments that read the settings store after the write that created them. */
  private static final Set<PdDeploymentStatus> CARRIES =
      EnumSet.of(PdDeploymentStatus.QUEUED, PdDeploymentStatus.STARTING, PdDeploymentStatus.ACTIVE);

  private static final Set<PdDeploymentStatus> IN_FLIGHT =
      EnumSet.of(PdDeploymentStatus.QUEUED, PdDeploymentStatus.STARTING);

  @ConfigProperty(name = "qits.deployments.egress.secret-redeploy-debounce", defaultValue = "10s")
  Duration debounce;

  @Inject PdDeploymentRepository deployments;
  @Inject EnvironmentService environments;
  @Inject DatabaseMoves databaseMoves;

  /** How a redeploy is queued: {@link DatabaseMoves#queueRedeployOfLive}, swapped only by the suite. */
  interface Queue {
    String redeploy(String application, PdEnvironment tier, String description);
  }

  private volatile Queue queue;

  /** What a tier owes: the keys written and the last write's instant. Immutable, replaced whole. */
  record Owed(Set<String> keys, Instant lastWrite) {}

  private final Map<String, Owed> owed = new ConcurrentHashMap<>();

  /** The suite's seam: queue through {@code replacement}, or the real door when null. */
  void queueWith(Queue replacement) {
    queue = replacement;
  }

  /** Whether {@code application}'s {@code key} is one this watches. */
  static boolean watched(String application, String key) {
    return APPLICATION.equals(application) && key != null && key.startsWith(PREFIX);
  }

  /**
   * A committed write of a setting: noted when it is one of qits-egress's secrets, ignored
   * otherwise. Never throws; queues nothing itself — {@link #settle} does, on the deploy worker.
   */
  public void written(String application, String tier, String key) {
    if (!watched(application, key) || tier == null) {
      return;
    }
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    owed.compute(
        tier,
        (ignored, before) -> {
          Set<String> keys = new TreeSet<>(before == null ? Set.of() : before.keys());
          keys.add(key);
          return new Owed(Set.copyOf(keys), now);
        });
  }

  /**
   * The hand-over queued a redeploy of {@code application} in {@code tier}: every write noted so far
   * rides it, so nothing more is owed for them.
   */
  void absorbed(String application, String tier) {
    if (APPLICATION.equals(application)) {
      owed.remove(tier);
    }
  }

  /** What is owed now, by tier — for the suite. */
  Map<String, Owed> owed() {
    return Map.copyOf(owed);
  }

  /** Every owed redeploy whose debounce is over. Never throws. */
  public void settle() {
    settle(Instant.now());
  }

  /** {@link #settle()} as if it were {@code now} — the suite's clock. */
  void settle(Instant now) {
    for (Map.Entry<String, Owed> entry : owed.entrySet()) {
      try {
        settle(entry.getKey(), entry.getValue(), now);
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "the redeploy of %s owed for %s in %s could not be queued; the next tick tries again",
            APPLICATION,
            entry.getValue().keys(),
            entry.getKey());
      }
    }
  }

  private void settle(String tierName, Owed due, Instant now) {
    if (now.isBefore(due.lastWrite().plus(debounce))) {
      return;
    }
    Optional<PdEnvironment> environment = environmentNamed(tierName);
    if (environment.isEmpty()) {
      owed.remove(tierName, due); // no such tier: nothing runs there to redeploy
      return;
    }
    PdEnvironment tier = environment.get();
    List<PdDeployment> rows =
        QuarkusTransaction.requiringNew()
            .call(() -> deployments.listForPlaceNewestFirst(APPLICATION, tier.id));
    for (PdDeployment row : rows) {
      boolean after = row.createdAt != null && !row.createdAt.isBefore(due.lastWrite());
      if (after && CARRIES.contains(row.status)) {
        LOG.debugf(
            "egress secret %s in %s rides the deployment of %s %s",
            due.keys(), tierName, APPLICATION, row.version);
        owed.remove(tierName, due);
        return;
      }
      if (!after && IN_FLIGHT.contains(row.status)) {
        LOG.debugf(
            "egress secret %s in %s waits: a deployment of %s that started before it is in flight",
            due.keys(), tierName, APPLICATION);
        return;
      }
      if (!after) {
        break; // everything older predates the write
      }
    }
    Queue through = queue == null ? databaseMoves::queueRedeployOfLive : queue;
    String version;
    try {
      version = through.redeploy(APPLICATION, tier, "The " + ACTOR);
    } catch (ConflictException | NotFoundException e) {
      // Never deployed here: its first deployment reads the rows, so there is nothing to redeploy.
      LOG.debugf("egress secret %s: nothing to redeploy in %s: %s", due.keys(), tierName, e.getMessage());
      owed.remove(tierName, due);
      return;
    }
    owed.remove(tierName, due);
    LOG.infof(
        "egress secret %s changed in %s; redeploying %s %s",
        String.join(", ", due.keys()), tierName, APPLICATION, version);
  }

  private Optional<PdEnvironment> environmentNamed(String name) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                environments.list().stream()
                    .filter(environment -> name.equals(environment.name))
                    .findFirst());
  }
}
