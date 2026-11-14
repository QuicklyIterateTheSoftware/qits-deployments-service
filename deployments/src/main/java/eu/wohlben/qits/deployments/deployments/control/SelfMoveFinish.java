package eu.wohlben.qits.deployments.deployments.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.SelfMove.Stage;
import eu.wohlben.qits.deployments.deployments.control.SelfMove.Step;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.persistence.PdDatabaseMoveRepository;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The last part of the deployer moving its own databases (qits-2209), once this process serves:
 * the move recorded, the target marked done, a copy's source renamed aside and kept, the marker
 * volume removed.
 *
 * <p><b>Only after swarm accepted the update.</b> Until the update is {@code completed}, swarm may
 * still roll it back, and the old deployer then needs its source under its own name. So this waits
 * for the own service to converge, and does nothing at all before that. A restart before it is done
 * finds the marker again and finishes then.
 *
 * <p>Which moves are due is what {@code main} found ({@link SelfMoveBoot#DUE}): every marked move
 * whose TARGET this process is bound to. That covers the successor that moved, and any later
 * deployer that started before the finish did. The running process asks docker nothing to find
 * them.
 */
@ApplicationScoped
public class SelfMoveFinish {

  private static final Logger LOG = Logger.getLogger(SelfMoveFinish.class);

  /** How long one wait for the own service to converge lasts, before it is asked again. */
  static final Duration WAIT = Duration.ofMinutes(5);

  /** How many waits before this process gives up; the next start tries again. */
  static final int WAITS = 12;

  private static final TypeReference<TreeMap<String, Long>> COUNTS = new TypeReference<>() {};

  static final String DETAIL = "moved by the deployer's successor before it started (qits-2209)";

  @Inject DatabaseContainers containers;
  @Inject DeploymentDriver driver;
  @Inject PostgresAdmin admin;
  @Inject PdDatabaseMoveRepository moves;
  @Inject DeployService worker;
  @Inject ObjectMapper json;

  @ConfigProperty(name = "qits.deployments.database-move.retention")
  Duration retention;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    // What main found due (SelfMoveBoot.DUE): no docker call here, so an ordinary start costs the
    // running process nothing.
    List<Step> due = List.copyOf(SelfMoveBoot.DUE);
    if (due.isEmpty()) {
      return;
    }
    String service =
        driver.ownService().orElseGet(() -> PdNetworks.alias(due.get(0).environment(), BootResourceRegistration.APPLICATION));
    Thread waiter =
        new Thread(
            () -> {
              if (awaitServing(service)) {
                worker.enqueue(() -> finish(due));
              }
            },
            "pd-self-move-finish");
    waiter.setDaemon(true);
    waiter.start();
  }

  /** Whether swarm accepted the update that started this process, waiting up to {@link #WAITS} times. */
  boolean awaitServing(String service) {
    for (int attempt = 0; attempt < WAITS; attempt++) {
      DeploymentDriver.Convergence verdict;
      try {
        verdict = driver.awaitConverged(service, WAIT);
      } catch (RuntimeException e) {
        LOG.warnf("Could not ask whether %s converged: %s", service, e.getMessage());
        continue;
      }
      if (verdict.converged()) {
        return true;
      }
      LOG.infof("The move of the deployer's own databases is finished once %s converges: %s", service, verdict.detail());
    }
    LOG.errorf(
        "%s did not converge, so the move of the deployer's own databases is not finished; the next"
            + " start tries again",
        service);
    return false;
  }

  /** Finish every due move; one that fails keeps its marker for the next start. */
  void finish(List<Step> due) {
    for (Step step : due) {
      try {
        finish(step);
      } catch (RuntimeException e) {
        LOG.errorf(
            "Could not finish the move of the deployer's `%s` to %s; its marker %s stays and the next"
                + " start tries again: %s",
            step.resource(), step.target(), step.volume(), e.getMessage());
      }
    }
  }

  private void finish(Step step) {
    PdDatabaseMove row = record(step);
    String adminPassword = admin.requireAdminPassword();
    ok(
        containers.run(
            SelfMove.shot(
                "self-mark", step, true, adminPassword, Map.of(), null,
                DatabaseScripts.comment(step.targetDatabase(), step.mark(Stage.DONE))),
            SelfMoveBoot.SHORT),
        "marking " + step.target() + " done");
    if (!step.inPlace()) {
      // Renamed aside and closed, as every moved source is; the retention sweep drops it.
      DatabaseContainers.Outcome renamed =
          containers.run(
              SelfMove.shot(
                  "admin", step, false, adminPassword, Map.of(), null,
                  DatabaseScripts.rename(step.sourceDatabase(), row.keptDatabase)),
              SelfMoveBoot.SHORT);
      ok(renamed, "renaming " + step.source() + " to " + row.keptDatabase);
    }
    if (!containers.removeVolume(step.volume())) {
      throw new IllegalStateException("could not remove the marker volume " + step.volume());
    }
    SelfMoveBoot.COUNTS.remove(step.id());
    if (step.inPlace()) {
      LOG.infof(
          "Renamed the database %s of %s (%s) in place on %s: %s → %s",
          step.resource(), BootResourceRegistration.APPLICATION, step.environment(),
          step.sourceServer().wire(), step.sourceDatabase(), step.targetDatabase());
    } else {
      LOG.infof(
          "Moved the database %s of %s (%s): %s with %s rows → %s with %s rows; the source is kept"
              + " as %s until %s",
          step.resource(), BootResourceRegistration.APPLICATION, step.environment(),
          step.source(), total(row.sourceCounts), step.target(), total(row.targetCounts),
          row.keptDatabase, row.dropAfter);
    }
  }

  /** The move's row, {@code MOVED}: written once, read back on a repeat so the kept name holds. */
  PdDatabaseMove record(Step step) {
    SelfMoveBoot.Counts counts = SelfMoveBoot.COUNTS.get(step.id());
    String sourceCounts = counts == null ? null : write(counts.source());
    String targetCounts = counts == null ? null : write(counts.target());
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDatabaseMove row = moves.findById(step.id());
              if (row != null) {
                return row;
              }
              Instant now = Instant.now();
              row = new PdDatabaseMove();
              row.id = step.id();
              row.batchId = step.id();
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.applicationName = BootResourceRegistration.APPLICATION;
              row.environmentName = step.environment();
              row.resourceName = step.resource();
              row.sourceServer = step.sourceServer().wire();
              row.sourceDatabase = step.sourceDatabase();
              row.targetServer = step.targetServer().wire();
              row.targetDatabase = step.targetDatabase();
              row.state = PdDatabaseMoveState.MOVED;
              row.sourceCounts = sourceCounts;
              row.targetCounts = targetCounts;
              row.createdAt = now;
              row.startedAt = now;
              row.finishedAt = now;
              if (step.inPlace()) {
                row.detail = DatabaseMoves.RENAMED_IN_PLACE;
              } else {
                row.keptDatabase = DatabaseScripts.keptName(step.sourceDatabase(), now);
                row.dropAfter = now.plus(retention);
                row.detail = DETAIL;
              }
              moves.persist(row);
              return row;
            });
  }

  private String write(Map<String, Long> counts) {
    try {
      return json.writeValueAsString(counts);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private String total(String counts) {
    if (counts == null) {
      return "?";
    }
    try {
      return Long.toString(DatabaseScripts.total(json.readValue(counts, COUNTS)));
    } catch (JsonProcessingException e) {
      return "?";
    }
  }

  private static void ok(DatabaseContainers.Outcome outcome, String what) {
    if (!outcome.ok()) {
      throw new IllegalStateException(what + " failed: exit " + outcome.exitCode() + ": " + outcome.output());
    }
  }
}
