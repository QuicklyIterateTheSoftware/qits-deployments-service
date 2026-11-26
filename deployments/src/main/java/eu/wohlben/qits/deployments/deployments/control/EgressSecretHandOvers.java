package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.deployments.deployments.dto.EgressGrantTableDto;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdSetting;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdSettingRepository;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.error.ConflictException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>The egress secret hand-over</b> (epic qits-2523, the step qits-2615 planned): a credential an
 * application held as its own setting moves to qits-egress once a route of that application
 * injecting it is in force, so the gateway holds the secret and the application no longer does.
 *
 * <p><b>Table-driven.</b> {@link #DECLARED} is the whole of what moves; a later move is another
 * {@link HandOver}, not new code. One entry today: qits-edge's Hetzner DNS token, which qits-egress
 * injects into the edge's {@code hetzner} route as {@code HETZNER_DNS}.
 *
 * <p><b>When</b>: {@link EgressGrantPusher#reconcile}, on the deploy worker, after the table is
 * built — boot, every observation pass, every {@code ACTIVE}. For each entry and each tier whose
 * table in force holds a route of {@code triggerApplication} whose {@code secret} is {@code
 * triggerSecret}, while the source {@code pd_setting} row exists:
 *
 * <ol>
 *   <li>one transaction: the target row is inserted with the same value, {@code secret=true},
 *       {@code updated_by} {@code egress hand-over from <fromApplication>} — unless it exists, and
 *       an existing target wins and is not overwritten — and the source row is deleted;
 *   <li>after the commit, both applications' live versions are redeployed ({@link
 *       DatabaseMoves#queueRedeployOfLive}, {@link DeployService#redeploy}): {@code toApplication}
 *       so its argv is rebuilt from the settings store and carries the moved secret — a restart
 *       replaces the tasks on the unchanged swarm service spec and never rebuilds the argv, so it
 *       would never carry it — and {@code fromApplication} so its next update {@code --env-rm}s the
 *       moved key.
 * </ol>
 *
 * <p><b>Idempotent</b>: with the source row gone the trigger never fires again. Nothing moves while
 * a deployment of either application in that tier is {@code QUEUED} or {@code STARTING}; the next
 * tick tries again. A redeploy that could not be queued after the commit is OWED in memory and
 * retried on the next tick. Every failure is a WARN; nothing throws out of the tick. The log names
 * applications, the tier and keys — never a value.
 *
 * <p>The move writes its rows through the repository, never through {@link Settings}, so it notes
 * no secret write for {@link EgressSecretRedeploys}; and once it has queued the target's redeploy it
 * tells {@link EgressSecretRedeploys#absorbed} so a door write pending in that tier rides it rather
 * than queueing a second one.
 */
@ApplicationScoped
public class EgressSecretHandOvers {

  private static final Logger LOG = Logger.getLogger(EgressSecretHandOvers.class);

  /** Who the redeploy's log name. */
  static final String ACTOR = "egress hand-over";

  /**
   * One declared move: {@code fromApplication}'s setting {@code fromKey} becomes {@code
   * toApplication}'s secret {@code toKey}, once a route of {@code triggerApplication} whose {@code
   * secret} is {@code triggerSecret} is in force in that tier.
   */
  public record HandOver(
      String fromApplication,
      String fromKey,
      String toApplication,
      String toKey,
      String triggerApplication,
      String triggerSecret) {}

  /** Every hand-over this deployer performs. */
  public static final List<HandOver> DECLARED =
      List.of(
          new HandOver(
              "qits-edge",
              "QITS_EDGE_ACME_HETZNER_TOKEN",
              "qits-egress",
              "QITS_EGRESS_SECRET_HETZNER_DNS",
              "qits-edge",
              "HETZNER_DNS"));

  private static final Set<PdDeploymentStatus> IN_FLIGHT =
      EnumSet.of(PdDeploymentStatus.QUEUED, PdDeploymentStatus.STARTING);

  @Inject PdSettingRepository settings;
  @Inject PdDeploymentRepository deployments;
  @Inject EnvironmentService environments;
  @Inject DatabaseMoves databaseMoves;
  @Inject EgressSecretRedeploys secretRedeploys;

  /** The entries {@link #apply} reads — {@link #DECLARED}, swapped only by the suite. */
  private volatile List<HandOver> entries = DECLARED;

  /** What a committed move still owes: a step not yet queued, by (entry, tier). */
  private enum Step {
    REDEPLOY_TARGET,
    REDEPLOY_SOURCE
  }

  private record Owed(HandOver handOver, String tier) {}

  private final Map<Owed, Set<Step>> owed = new ConcurrentHashMap<>();

  /** The suite's seam: the entries to apply instead of {@link #DECLARED}. */
  void declare(List<HandOver> declared) {
    entries = List.copyOf(declared);
  }

  /** Every move the table in force triggers, then every owed step. Never throws. */
  public void apply(EgressGrantTableDto table) {
    for (HandOver handOver : entries) {
      for (String tier : triggeredTiers(handOver, table)) {
        try {
          move(handOver, tier);
        } catch (RuntimeException e) {
          LOG.warnf(
              e,
              "egress hand-over of %s:%s to %s:%s in %s failed; the next pass tries again",
              handOver.fromApplication(),
              handOver.fromKey(),
              handOver.toApplication(),
              handOver.toKey(),
              tier);
        }
      }
    }
    for (Map.Entry<Owed, Set<Step>> entry : owed.entrySet()) {
      try {
        settle(entry.getKey(), entry.getValue());
      } catch (RuntimeException e) {
        LOG.warnf(e, "egress hand-over: a step owed in %s failed; the next pass tries again",
            entry.getKey().tier());
      }
    }
  }

  /** The tiers whose table in force holds the entry's trigger route. */
  private static Set<String> triggeredTiers(HandOver handOver, EgressGrantTableDto table) {
    Set<String> tiers = new LinkedHashSet<>();
    for (EgressGrantTableDto.Route route : table.routes()) {
      if (route.environment() != null
          && handOver.triggerApplication().equals(route.application())
          && handOver.triggerSecret().equals(route.secret())) {
        tiers.add(route.environment());
      }
    }
    return tiers;
  }

  private void move(HandOver handOver, String tier) {
    boolean present =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    settings
                        .findOne(handOver.fromApplication(), tier, handOver.fromKey())
                        .isPresent());
    if (!present) {
      return;
    }
    Optional<PdEnvironment> environment = environmentNamed(tier);
    if (environment.isEmpty()) {
      return;
    }
    String environmentId = environment.get().id;
    if (inFlight(handOver.fromApplication(), environmentId)
        || inFlight(handOver.toApplication(), environmentId)) {
      LOG.debugf(
          "egress hand-over of %s to %s in %s waits: a deployment of either is in flight",
          handOver.fromApplication(), handOver.toApplication(), tier);
      return;
    }
    Boolean targetKept =
        DbRetry.inNewTx(
            "The egress hand-over of " + handOver.fromApplication() + " in " + tier,
            () -> {
              Optional<PdSetting> source =
                  settings.findOneForUpdate(handOver.fromApplication(), tier, handOver.fromKey());
              if (source.isEmpty()) {
                return null; // a door delete won the race: nothing to move
              }
              boolean kept =
                  settings.findOneForUpdate(handOver.toApplication(), tier, handOver.toKey())
                      .isPresent();
              if (!kept) {
                PdSetting target = new PdSetting();
                target.id = UUID.randomUUID().toString();
                target.applicationName = handOver.toApplication();
                target.environmentName = tier;
                target.settingKey = handOver.toKey();
                target.value = source.get().value;
                target.secret = true;
                target.updatedAt = Instant.now();
                target.updatedBy = "egress hand-over from " + handOver.fromApplication();
                settings.persist(target);
              }
              settings.delete(source.get());
              settings.flush();
              return kept;
            });
    if (targetKept == null) {
      return;
    }
    LOG.infof(
        "egress hand-over in %s: moved %s:%s to %s:%s (%s); redeploying %s, redeploying %s",
        tier,
        handOver.fromApplication(),
        handOver.fromKey(),
        handOver.toApplication(),
        handOver.toKey(),
        targetKept ? "its existing value kept" : "inserted as a secret",
        handOver.toApplication(),
        handOver.fromApplication());
    Owed key = new Owed(handOver, tier);
    owed.put(key, ConcurrentHashMap.newKeySet());
    owed.get(key).add(Step.REDEPLOY_TARGET);
    owed.get(key).add(Step.REDEPLOY_SOURCE);
    settle(key, owed.get(key));
  }

  /** Queue whatever the move still owes, unless a deployment of that application is in flight. */
  private void settle(Owed key, Set<Step> steps) {
    HandOver handOver = key.handOver();
    Optional<PdEnvironment> environment = environmentNamed(key.tier());
    if (environment.isEmpty()) {
      owed.remove(key); // the tier is gone, and everything in it
      return;
    }
    PdEnvironment tier = environment.get();
    if (steps.contains(Step.REDEPLOY_TARGET) && !inFlight(handOver.toApplication(), tier.id)) {
      try {
        databaseMoves.queueRedeployOfLive(handOver.toApplication(), tier, "The " + ACTOR);
      } catch (ConflictException | NotFoundException e) {
        // Never deployed here: its first deployment reads the moved row, so there is nothing to
        // redeploy.
        LOG.debugf(
            "egress hand-over: nothing to redeploy in %s: %s", key.tier(), e.getMessage());
      }
      // A secret write pending in that tier rides this redeploy: no second one (qits-2666).
      secretRedeploys.absorbed(handOver.toApplication(), key.tier());
      steps.remove(Step.REDEPLOY_TARGET);
    }
    if (steps.contains(Step.REDEPLOY_SOURCE) && !inFlight(handOver.fromApplication(), tier.id)) {
      databaseMoves.queueRedeployOfLive(handOver.fromApplication(), tier, "The " + ACTOR);
      steps.remove(Step.REDEPLOY_SOURCE);
    }
    if (steps.isEmpty()) {
      owed.remove(key);
    }
  }

  private Optional<PdEnvironment> environmentNamed(String name) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                environments.list().stream()
                    .filter(environment -> name.equals(environment.name))
                    .findFirst());
  }

  private boolean inFlight(String application, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              for (PdDeployment row :
                  deployments.listForPlaceNewestFirst(application, environmentId)) {
                if (IN_FLIGHT.contains(row.status)) {
                  return true;
                }
              }
              return false;
            });
  }
}
