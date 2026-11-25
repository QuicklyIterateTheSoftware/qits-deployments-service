package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.dto.EgressGrantTableDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * <b>Carries the egress grant table to qits-egress</b> (epic qits-2523): {@code PUT
 * {url}/grant-table} through {@link EgressGrantClient}, with this component's own {@code
 * qits:system} bearer. qits-egress is public and this service is private, so qits-egress cannot
 * pull {@code GET /deployments/api/egress-grants}; this side pushes, as {@link EdgeRoutePusher}
 * does for the edges' route tables.
 *
 * <p><b>When</b>: on each observation pass and once at boot ({@link #reconcile}), and right after a
 * deployment reaches {@code ACTIVE} — a cutover or a rollback's restore — ({@link #afterDeploy}).
 * Each builds {@link EgressGrantTables#current()} (idempotent: a new version only when the content
 * moved), asks qits-egress the version it holds ({@code 404}: none), and pushes when the two
 * differ. A {@code 409} says qits-egress holds a newer version: nothing to do, logged at DEBUG.
 *
 * <p><b>A push never blocks or fails a deploy.</b> Every method here catches everything: a failure
 * (unreachable, a {@code 5xx}, a {@code 401}/{@code 403}) is one WARN per outage naming the status,
 * {@code egress grant table push failed: <reason>}, and the next observation pass tries again. All
 * of it runs on the deploy worker.
 */
@ApplicationScoped
public class EgressGrantPusher {

  private static final Logger LOG = Logger.getLogger(EgressGrantPusher.class);

  @Inject EgressGrantTables tables;

  @Inject EgressGrantClient client;

  @Inject EgressSecretHandOvers handOvers;

  @Inject ProjectEgressGrants projects;

  /** Whether the last read or push failed: an outage already WARNed about. */
  private final AtomicBoolean failing = new AtomicBoolean();

  /** After a deployment reached {@code ACTIVE}: {@link #reconcile}. Never throws. */
  public void afterDeploy() {
    reconcile();
  }

  /**
   * Re-read the projects' wrapper files when the poll interval has passed ({@link
   * ProjectEgressGrants#refreshIfDue}, qits-2659), build the table in force, read what qits-egress
   * holds, and push when the versions differ; then run the secret hand-overs the table triggers
   * ({@link EgressSecretHandOvers}), whatever the push answered. Whether qits-egress now holds this
   * table (or a newer one). Never throws.
   *
   * <p><b>No push before the project listing has answered once</b>: a fresh process that could not
   * reach qits-projects would otherwise push a table without any project's routes, and revoke every
   * agent's grants on an outage. The hand-overs still run.
   */
  public boolean reconcile() {
    try {
      projects.refreshIfDue();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not re-read the projects' egress grants; the next pass tries again");
    }
    EgressGrantTableDto table;
    String body;
    try {
      table = tables.current();
      body = EgressGrantTables.json(table);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not build the egress grant table; the next pass tries again");
      return false;
    }
    boolean held;
    if (projects.answered()) {
      held = push(table, body);
    } else {
      held = false;
      LOG.debugf(
          "egress grant table %d not pushed: the projects' grants were never read yet",
          table.version());
    }
    try {
      handOvers.apply(table);
    } catch (RuntimeException e) {
      LOG.warnf(e, "The egress secret hand-overs failed; the next pass tries again");
    }
    return held;
  }

  private boolean push(EgressGrantTableDto table, String body) {
    OptionalLong held;
    try {
      held = client.heldVersion();
    } catch (RuntimeException e) {
      failed("could not read the version qits-egress holds: " + reason(e));
      return false;
    }
    if (held.isPresent() && held.getAsLong() == table.version()) {
      recovered();
      return true;
    }
    EgressGrantClient.Pushed answer;
    try {
      answer = client.push(body);
    } catch (RuntimeException e) {
      failed(reason(e));
      return false;
    }
    switch (answer) {
      case EgressGrantClient.Stored ignored -> {
        recovered();
        LOG.infof(
            "pushed egress grant table %d to qits-egress (%d routes, %d refused; it held %s)",
            table.version(),
            table.routes().size(),
            table.refused().size(),
            held.isPresent() ? Long.toString(held.getAsLong()) : "none");
        return true;
      }
      case EgressGrantClient.Newer newer -> {
        recovered();
        LOG.debugf(
            "qits-egress holds egress grant table %d, newer than %d: nothing to push",
            newer.held(), table.version());
        return true;
      }
    }
  }

  private void failed(String reason) {
    if (failing.compareAndSet(false, true)) {
      LOG.warnf("egress grant table push failed: %s; the next pass tries again", reason);
    } else {
      LOG.debugf("egress grant table push failed again: %s", reason);
    }
  }

  private void recovered() {
    if (failing.compareAndSet(true, false)) {
      LOG.infof("egress grant table push works again");
    }
  }

  private static String reason(RuntimeException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
