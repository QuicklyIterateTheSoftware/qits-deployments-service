package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.entity.PdEdgeRouteTable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>Carries each edge's route table to it</b> (epic qits-2174, task qits-2198): {@code PUT
 * {url}/edge-route-table} through {@link EdgeRouteClient}, with this component's own {@code
 * qits:system} bearer.
 *
 * <p><b>When:</b>
 *
 * <ul>
 *   <li>after a new version is stored ({@link #refresh}, called after every apply, retirement and
 *       teardown that finishes);
 *   <li>after a deploy of {@code qits-edge}, {@code qits-edge-private} or {@code qits-edge-overlay}
 *       reaches healthy ({@link #afterDeploy}) — whatever its version, because a new edge task may
 *       hold nothing yet;
 *   <li>on each observation pass ({@link #observe}), when the edge's {@code GET /edge-route-table}
 *       reports a version below the stored one, or none. The same version is not pushed again.
 * </ul>
 *
 * <p><b>Per push target, not per table</b> (qits-2304): a table that moved goes to every target that
 * carries it ({@link EdgeRouteTables#targetsOf}) — the private one to qits-edge-private AND
 * qits-edge-overlay — and each target is read, compared and WARNed about on its own. The overlay
 * edge before its first deployment is one WARN for the {@code overlay} target and nothing else.
 *
 * <p><b>A push never blocks or fails a deploy.</b> Every method here catches everything: a failure
 * is one WARN per edge per outage, {@code route table push to <edge> failed: <reason>}, and the
 * next observation pass tries again. All of it runs on the deploy worker.
 */
@ApplicationScoped
public class EdgeRoutePusher {

  private static final Logger LOG = Logger.getLogger(EdgeRoutePusher.class);

  @Inject EdgeRouteTables tables;

  @Inject EdgeRouteClient client;

  /** The push targets whose last push or read failed: an outage already WARNed about. */
  private final Map<String, Boolean> failing = new ConcurrentHashMap<>();

  /** Rebuild both tables and push each whose version moved, to every target carrying it. Never throws. */
  public void refresh() {
    List<String> moved;
    try {
      moved = tables.rebuild();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not rebuild the route tables; the next pass tries again");
      return;
    }
    for (String target : targetsOf(moved)) {
      push(target);
    }
  }

  /**
   * After a deploy of {@code application} reached healthy: {@link #refresh}, and when the
   * application is one of the edges, push that edge its table even if the version did not move.
   */
  public void afterDeploy(String application) {
    List<String> moved;
    try {
      moved = tables.rebuild();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not rebuild the route tables; the next pass tries again");
      moved = List.of();
    }
    List<String> pushed = targetsOf(moved);
    for (String target : pushed) {
      push(target);
    }
    String target = EdgeRouteTables.edgeOf(application);
    if (target != null && !pushed.contains(target)) {
      push(target);
    }
  }

  /**
   * The observation pass: rebuild and push what moved, then ask each other target what it holds
   * and push when that is below the stored version of the table it carries. Never throws.
   */
  public void observe() {
    List<String> moved;
    try {
      moved = tables.rebuild();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not rebuild the route tables; the next pass tries again");
      moved = List.of();
    }
    for (String edge : EdgeRouteTables.TARGETS) {
      if (moved.contains(EdgeRouteTables.tableOf(edge))) {
        push(edge);
        continue;
      }
      Optional<PdEdgeRouteTable> stored = stored(edge);
      if (stored.isEmpty()) {
        continue;
      }
      OptionalLong held;
      try {
        held = client.held(edge);
      } catch (RuntimeException e) {
        failed(edge, "could not read the version it holds: " + reason(e));
        continue;
      }
      if (held.isPresent() && held.getAsLong() >= stored.get().version) {
        recovered(edge);
        continue;
      }
      push(edge);
    }
  }

  /**
   * Push one target the stored table it carries. Whether the edge now holds it. Never throws.
   */
  boolean push(String edge) {
    Optional<PdEdgeRouteTable> stored = stored(edge);
    if (stored.isEmpty()) {
      return false;
    }
    PdEdgeRouteTable table = stored.get();
    EdgeRouteClient.Pushed answer;
    try {
      answer = client.push(edge, table.body);
    } catch (RuntimeException e) {
      failed(edge, reason(e));
      return false;
    }
    switch (answer) {
      case EdgeRouteClient.Stored ignored -> {
        recovered(edge);
        LOG.infof(
            "pushed route table %s %d (%d applications)",
            edge, table.version, applications(table));
        return true;
      }
      case EdgeRouteClient.Held held -> {
        recovered(edge);
        LOG.debugf("the %s edge already holds route table %d (pushed %d)", edge, held.held(), table.version);
        return true;
      }
      case EdgeRouteClient.Refused refused -> {
        failed(edge, "the edge refused version " + table.version + ": " + refused.reason());
        return false;
      }
    }
  }

  /** The stored row of the table {@code target} carries. */
  private Optional<PdEdgeRouteTable> stored(String target) {
    try {
      return tables.storedRow(EdgeRouteTables.tableOf(target));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the stored route table of the %s edge", target);
      return Optional.empty();
    }
  }

  /** Every target carrying one of {@code moved}'s tables, in {@link EdgeRouteTables#TARGETS} order. */
  private static List<String> targetsOf(List<String> moved) {
    return EdgeRouteTables.TARGETS.stream()
        .filter(target -> moved.contains(EdgeRouteTables.tableOf(target)))
        .toList();
  }

  private static int applications(PdEdgeRouteTable table) {
    try {
      return eu.wohlben.qits.deployments.deployments.dto.EdgeRouteTableDto.of(
              table, EdgeRouteTables.mapper())
          .applications()
          .size();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private void failed(String edge, String reason) {
    if (failing.put(edge, Boolean.TRUE) == null) {
      LOG.warnf("route table push to %s failed: %s", edge, reason);
    } else {
      LOG.debugf("route table push to %s failed again: %s", edge, reason);
    }
  }

  private void recovered(String edge) {
    if (failing.remove(edge) != null) {
      LOG.infof("route table push to %s works again", edge);
    }
  }

  private static String reason(RuntimeException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
