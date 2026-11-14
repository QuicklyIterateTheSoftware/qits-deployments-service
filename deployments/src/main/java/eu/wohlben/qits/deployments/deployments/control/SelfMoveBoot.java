package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.control.SelfMove.Stage;
import eu.wohlben.qits.deployments.deployments.control.SelfMove.Step;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * What the deployer does in {@code main}, before Quarkus starts and before any datasource opens
 * (qits-2209). Plain Java on purpose: there is no CDI and no config yet, only the environment and
 * the docker socket.
 *
 * <p><b>Two jobs, in this order.</b>
 *
 * <ol>
 *   <li><b>Undo.</b> A marker volume whose step has this process bound to its SOURCE means swarm
 *       rolled a self-update back and this is the old deployer again. Whatever the step changed is
 *       undone, so the old deployer starts on its source exactly as it left it: a copy's target is
 *       dropped (the source was never touched), a rename is renamed back.
 *   <li><b>Move.</b> Each step this process was handed ({@code QITS_RESOURCE_<NAME>_MOVE}) is run:
 *       a copy is extracted, ingested and compared, a rename is renamed in place. Each is idempotent
 *       over a restart: what is done already is not done again.
 * </ol>
 *
 * <p><b>A failed move never starts the process.</b> Every step this process started is undone and
 * {@link #run} answers false: {@code main} exits non-zero, the task fails, and swarm's {@code
 * --update-failure-action rollback} starts the old deployer, whose own {@code main} finds nothing
 * left to undo. The source is not written by a copy at all, and a rename is put back before the
 * exit, so the old deployer always starts on its untouched source.
 *
 * <p><b>The source is not renamed aside here.</b> That is {@link SelfMoveFinish}'s, after swarm has
 * accepted the update: until then a rollback must find the source where it was.
 */
public final class SelfMoveBoot {

  private static final Logger LOG = Logger.getLogger(SelfMoveBoot.class);

  /** The deadline of every short one-shot: describe, comment, rename, drop. */
  static final Duration SHORT = Duration.ofMinutes(2);

  /** What each copy counted, by move id — {@link SelfMoveFinish} writes it on the move's row. */
  static final Map<String, Counts> COUNTS = new ConcurrentHashMap<>();

  /** Both manifests of one copy. */
  record Counts(Map<String, Long> source, Map<String, Long> target) {}

  private SelfMoveBoot() {}

  /** A step that cannot go on; its message is the sentence the log carries. */
  static final class Failed extends RuntimeException {
    Failed(String message) {
      super(message);
    }
  }

  /**
   * Undo what a rolled-back self-update left, then run this process's own steps.
   *
   * @return true when the process may start; false when a step failed and was undone, and the
   *     process must exit non-zero so swarm puts the old deployer back
   */
  public static boolean run(Map<String, String> env, DatabaseContainers containers) {
    List<Step> own;
    try {
      own = SelfMove.fromEnvironment(env);
    } catch (RuntimeException unreadable) {
      LOG.errorf("Not starting: the database move this deployer was handed does not read: %s", unreadable.getMessage());
      return false;
    }
    Map<String, Map<String, String>> markers;
    try {
      markers = containers.labelledVolumes(SelfMove.KIND_LABEL, SelfMove.KIND);
    } catch (RuntimeException unreachable) {
      if (own.isEmpty()) {
        // Nothing to move. An unfinished self-move cannot be seen, so none can be undone either;
        // a rename left half-done then fails the datasource, and swarm restarts and asks again.
        LOG.warnf("Could not look for an unfinished move of the deployer's own databases: %s", unreachable.getMessage());
        return true;
      }
      LOG.errorf("Not starting: could not reach docker to move the deployer's own databases: %s", unreachable.getMessage());
      return false;
    }
    if (own.isEmpty() && markers.isEmpty()) {
      return true;
    }
    Optional<String> adminPassword = SelfMove.adminPassword(env);
    if (adminPassword.isEmpty()) {
      // With steps of its own the process must not start; with only markers it starts and cannot
      // undo them, which a later start with the password does.
      LOG.errorf(
          "A move of the deployer's own databases is due and none of %s is set%s",
          SelfMove.ADMIN_PASSWORD_VARIABLES, own.isEmpty() ? "" : ", so this deployer does not start");
      return own.isEmpty();
    }
    Set<String> ownIds = new HashSet<>();
    own.forEach(step -> ownIds.add(step.id()));

    for (Map.Entry<String, Map<String, String>> marker : markers.entrySet()) {
      Step step;
      try {
        step = Step.parse(marker.getValue().get(SelfMove.PLAN_LABEL));
      } catch (RuntimeException unreadable) {
        LOG.warnf("The marker volume %s holds no step that reads: %s", marker.getKey(), unreadable.getMessage());
        continue;
      }
      if (ownIds.contains(step.id())) {
        continue;
      }
      if (SelfMove.boundToSource(env, step)) {
        try {
          undo(step, env, containers, adminPassword.get());
          LOG.warnf(
              "Undid the unfinished move of the deployer's `%s` from %s to %s: the update that"
                  + " started it was rolled back, so this deployer starts on %s again",
              step.resource(), step.source(), step.target(), step.source());
        } catch (Failed failed) {
          LOG.errorf(
              "Could not undo the unfinished move of the deployer's `%s` from %s to %s, so the"
                  + " marker %s stays and the next start tries again: %s",
              step.resource(), step.source(), step.target(), step.volume(), failed.getMessage());
        }
      } else if (SelfMove.boundToTarget(env, step)) {
        LOG.infof("The move of the deployer's `%s` to %s is in effect; it is finished once this deployer serves", step.resource(), step.target());
      } else {
        LOG.warnf("The marker %s names a move of `%s` this deployer is bound to neither end of; left as it is", step.volume(), step.resource());
      }
    }

    // Only what THIS start changed is undone on a failure: a step found done, or verified, may be
    // the database a converged successor has been writing to since.
    List<Step> performed = new ArrayList<>();
    for (Step step : own) {
      if (!SelfMove.boundToTarget(env, step)) {
        // Not a move this process can run: it would start on another database than it moved to.
        // Moving nothing is the safe answer; the process starts on whatever it is bound to.
        LOG.errorf(
            "The move of `%s` to %s was handed to a process not bound to %s, so it is not run",
            step.resource(), step.target(), step.target());
        continue;
      }
      try {
        if (ensure(step, env, containers, adminPassword.get())) {
          performed.add(step);
        }
      } catch (Failed failed) {
        LOG.errorf(
            "Not starting: the move of the deployer's `%s` from %s to %s failed, so every move of"
                + " this start is undone and swarm puts the old deployer back on its source: %s",
            step.resource(), step.source(), step.target(), failed.getMessage());
        List<Step> undo = new ArrayList<>(performed);
        undo.add(step);
        undoAll(undo, env, containers, adminPassword.get());
        return false;
      }
    }
    return true;
  }

  private static void undoAll(List<Step> steps, Map<String, String> env, DatabaseContainers containers, String adminPassword) {
    for (Step step : steps) {
      try {
        undo(step, env, containers, adminPassword);
      } catch (Failed failed) {
        LOG.errorf(
            "Could not undo the move of `%s` from %s to %s; the marker %s stays, and the old"
                + " deployer's start tries again: %s",
            step.resource(), step.source(), step.target(), step.volume(), failed.getMessage());
      }
    }
  }

  // --- the move ---------------------------------------------------------------------------------

  /** Run one step, or find it done. True when this call changed something. */
  static boolean ensure(Step step, Map<String, String> env, DatabaseContainers containers, String adminPassword) {
    String password =
        SelfMove.rolePassword(env, step)
            .orElseThrow(() -> new Failed("no password for `" + step.resource() + "` was handed over"));
    return step.inPlace()
        ? rename(step, password, containers, adminPassword)
        : copy(step, password, containers, adminPassword);
  }

  private static boolean rename(Step step, String password, DatabaseContainers containers, String adminPassword) {
    Map<String, DatabaseScripts.Described> seen =
        describe(step, false, containers, adminPassword, step.sourceDatabase(), step.targetDatabase());
    DatabaseScripts.Described source = seen.get(step.sourceDatabase());
    DatabaseScripts.Described target = seen.get(step.targetDatabase());
    if (!source.exists() && target.exists()) {
      LOG.infof("The deployer's `%s` already is %s", step.resource(), step.target());
      return false;
    }
    if (!source.exists()) {
      throw new Failed("neither " + step.source() + " nor " + step.target() + " exists");
    }
    boolean leftover = false;
    if (target.exists()) {
      if (!SelfMove.leftover(target.mark())) {
        throw new Failed(step.target() + " already exists and no earlier move of the deployer left it");
      }
      leftover = true;
    }
    marker(step, containers);
    ok(
        containers.run(
            SelfMove.shot(
                "self-mark", step, false, adminPassword, Map.of(), null,
                DatabaseScripts.comment(step.sourceDatabase(), step.mark(Stage.RENAMING))),
            SHORT),
        "marking " + step.source());
    ok(
        containers.run(
            SelfMove.shot(
                "rename", step, false, adminPassword,
                Map.of(DatabaseScripts.ROLE_PASSWORD, password), null,
                DatabaseScripts.renameInPlace(step.sourceDatabase(), step.targetDatabase(), leftover, true)),
            SHORT),
        "renaming " + step.source() + " to `" + step.targetDatabase() + "`");
    LOG.infof("Renamed the deployer's `%s` in place on %s: %s → %s", step.resource(), step.sourceServer().wire(), step.sourceDatabase(), step.targetDatabase());
    return true;
  }

  private static boolean copy(Step step, String password, DatabaseContainers containers, String adminPassword) {
    DatabaseScripts.Described source =
        describe(step, false, containers, adminPassword, step.sourceDatabase()).get(step.sourceDatabase());
    DatabaseScripts.Described target =
        describe(step, true, containers, adminPassword, step.targetDatabase()).get(step.targetDatabase());
    if (target.exists() && (target.mark().equals(step.mark(Stage.DONE)) || !source.exists())) {
      LOG.infof("The deployer's `%s` already is %s", step.resource(), step.target());
      return false;
    }
    if (!source.exists()) {
      throw new Failed("neither " + step.source() + " nor " + step.target() + " exists");
    }
    if (target.exists() && target.mark().equals(step.mark(Stage.VERIFIED))) {
      LOG.infof("The deployer's `%s` was copied to %s and verified before this start", step.resource(), step.target());
      return false;
    }
    if (target.exists() && !SelfMove.leftover(target.mark())) {
      throw new Failed(step.target() + " already exists and no earlier move of the deployer left it");
    }
    marker(step, containers);
    Duration timeout = Duration.ofSeconds(step.timeoutSeconds());
    DatabaseContainers.Outcome extracted =
        containers.run(
            SelfMove.shot(
                "extract", step, false, adminPassword, Map.of(), step.volume(),
                DatabaseScripts.extract(step.sourceDatabase())),
            timeout);
    Map<String, Long> before = extracted.ok() ? DatabaseScripts.manifest(extracted.output()) : null;
    if (before == null) {
      throw new Failed("the extract of " + step.source() + " failed: " + tail(extracted));
    }
    DatabaseContainers.Outcome ingested =
        containers.run(
            SelfMove.shot(
                "ingest", step, true, adminPassword,
                Map.of(DatabaseScripts.ROLE_PASSWORD, password), step.volume(),
                DatabaseScripts.ingest(
                    step.targetDatabase(), step.targetDatabase(), target.exists(), step.mark(Stage.COPYING))),
            timeout);
    Map<String, Long> after = ingested.ok() ? DatabaseScripts.manifest(ingested.output()) : null;
    if (after == null) {
      throw new Failed("the ingest into " + step.target() + " failed: " + tail(ingested));
    }
    String difference = DatabaseScripts.firstDifference(before, after);
    if (difference != null) {
      throw new Failed("the copy of " + step.source() + " does not match: " + difference);
    }
    ok(
        containers.run(
            SelfMove.shot(
                "self-mark", step, true, adminPassword, Map.of(), null,
                DatabaseScripts.comment(step.targetDatabase(), step.mark(Stage.VERIFIED))),
            SHORT),
        "marking " + step.target() + " verified");
    COUNTS.put(step.id(), new Counts(before, after));
    LOG.infof(
        "Copied the deployer's `%s`: %s with %d rows → %s with %d rows",
        step.resource(), step.source(), DatabaseScripts.total(before), step.target(), DatabaseScripts.total(after));
    return true;
  }

  /** The marker volume, before the first change. */
  private static void marker(Step step, DatabaseContainers containers) {
    if (!containers.createVolume(
        step.volume(), Map.of(SelfMove.KIND_LABEL, SelfMove.KIND, SelfMove.PLAN_LABEL, step.encode()))) {
      throw new Failed("could not create the marker volume " + step.volume());
    }
  }

  // --- the undo ---------------------------------------------------------------------------------

  /**
   * Put the step's source back as it was, and remove the marker. Never touches a database the step
   * did not mark, never drops a target while its source is gone, and never undoes a step marked
   * done.
   */
  static void undo(Step step, Map<String, String> env, DatabaseContainers containers, String adminPassword) {
    if (step.inPlace()) {
      Map<String, DatabaseScripts.Described> seen =
          describe(step, false, containers, adminPassword, step.sourceDatabase(), step.targetDatabase());
      DatabaseScripts.Described source = seen.get(step.sourceDatabase());
      DatabaseScripts.Described target = seen.get(step.targetDatabase());
      if (target.exists() && target.mark().equals(step.mark(Stage.DONE))) {
        throw new Failed(step.target() + " is marked done, so the move is not undone");
      }
      if (!source.exists() && target.exists() && target.mark().startsWith(step.markPrefix())) {
        Optional<String> password = SelfMove.rolePassword(env, step);
        ok(
            containers.run(
                SelfMove.shot(
                    "rename", step, false, adminPassword,
                    password.map(p -> Map.of(DatabaseScripts.ROLE_PASSWORD, p)).orElse(Map.of()), null,
                    DatabaseScripts.renameInPlace(
                        step.targetDatabase(), step.sourceDatabase(), false, password.isPresent())),
                SHORT),
            "renaming " + step.target() + " back to `" + step.sourceDatabase() + "`");
        clearMark(step, containers, adminPassword);
      } else if (source.exists()) {
        // The rename never happened: the source is still under its name. A target beside it is not
        // this move's to touch.
        if (source.mark().startsWith(step.markPrefix())) {
          clearMark(step, containers, adminPassword);
        }
      } else {
        throw new Failed(
            "found " + step.source() + (source.exists() ? "" : " missing") + " and " + step.target()
                + (target.exists() ? " marked `" + target.mark() + "`" : " missing")
                + ", which no rename of this move leaves");
      }
    } else {
      DatabaseScripts.Described source =
          describe(step, false, containers, adminPassword, step.sourceDatabase()).get(step.sourceDatabase());
      DatabaseScripts.Described target =
          describe(step, true, containers, adminPassword, step.targetDatabase()).get(step.targetDatabase());
      if (target.exists() && target.mark().equals(step.mark(Stage.DONE))) {
        throw new Failed(step.target() + " is marked done, so the move is not undone");
      }
      if (!source.exists()) {
        throw new Failed(step.source() + " is gone, so " + step.target() + " is not dropped");
      }
      if (target.exists() && target.mark().startsWith(step.markPrefix())) {
        ok(
            containers.run(
                SelfMove.shot(
                    "drop", step, true, adminPassword, Map.of(), null,
                    DatabaseScripts.drop(step.targetDatabase(), step.targetDatabase())),
                SHORT),
            "dropping the copy " + step.target());
      }
    }
    if (!containers.removeVolume(step.volume())) {
      throw new Failed("could not remove the marker volume " + step.volume());
    }
  }

  private static void clearMark(Step step, DatabaseContainers containers, String adminPassword) {
    ok(
        containers.run(
            SelfMove.shot(
                "self-mark", step, false, adminPassword, Map.of(), null,
                DatabaseScripts.comment(step.sourceDatabase(), null)),
            SHORT),
        "clearing the mark on " + step.source());
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** What the given databases on one side are: exists, and their mark. */
  static Map<String, DatabaseScripts.Described> describe(
      Step step, boolean target, DatabaseContainers containers, String adminPassword, String... databases) {
    DatabaseContainers.Outcome seen =
        containers.run(
            SelfMove.shot("self-check", step, target, adminPassword, Map.of(), null, DatabaseScripts.describe(databases)),
            SHORT);
    Map<String, DatabaseScripts.Described> answer =
        seen.ok() ? DatabaseScripts.describeAnswer(seen.output(), databases) : null;
    if (answer == null) {
      throw new Failed(
          "could not ask the " + (target ? step.targetServer() : step.sourceServer()).wire()
              + " postgres about " + String.join(", ", databases) + ": " + tail(seen));
    }
    return answer;
  }

  private static void ok(DatabaseContainers.Outcome outcome, String what) {
    if (!outcome.ok()) {
      throw new Failed(what + " failed: " + tail(outcome));
    }
  }

  private static String tail(DatabaseContainers.Outcome outcome) {
    String output = outcome.output() == null ? "" : outcome.output().strip();
    String verdict = outcome.timedOut() ? "timed out" : "exit " + outcome.exitCode();
    return verdict + (output.isEmpty() ? "" : ": " + (output.length() > 400 ? "…" + output.substring(output.length() - 400) : output));
  }
}
