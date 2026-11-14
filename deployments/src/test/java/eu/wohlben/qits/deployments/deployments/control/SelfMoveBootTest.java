package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SelfMove.Step;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The deployer moving its own databases (qits-2209), {@link SelfMoveBoot} driven over a simulated
 * pair of postgres servers: which databases exist, their comments, their rows.
 *
 * <p>The owner's rule is the point of every failure test here: whatever fails after the old deployer
 * stopped, the old deployer must start again on its UNTOUCHED source. Each failure therefore runs
 * the old deployer's own start afterwards — what swarm's rollback does — and checks the source.
 */
class SelfMoveBootTest {

  private static final String ENV = "dev";
  private static final String PUBLIC_HOST = "dev-qits-oci-postgresql";
  private static final String PRIVATE_HOST = "dev-qits-oci-postgresql-private";
  private static final String PASSWORD = "the-claims-own-password";
  private static final String ADMIN = "the-admin-password";
  private static final String MANIFEST = "public.pd_deployment=7\npublic.flyway_schema_history=21";

  private Postgres pg;

  @BeforeEach
  void fresh() {
    pg = new Postgres();
    SelfMoveBoot.COUNTS.clear();
    SelfMoveBoot.DUE.clear();
  }

  // --- the simulated servers --------------------------------------------------------------------

  /** Two servers, by network; a database is a mark ("" for none) and a manifest. */
  static final class Postgres implements DatabaseContainers {
    final Map<String, Map<String, String>> marks = new HashMap<>();
    final Map<String, Map<String, String>> rows = new HashMap<>();
    final Map<String, Map<String, String>> volumes = new LinkedHashMap<>();
    final List<OneShot> shots = new ArrayList<>();
    final Set<String> failing = new HashSet<>();
    String ingestManifest;
    boolean dockerDown;

    Postgres() {
      marks.put("qits-net", new TreeMap<>());
      marks.put("qits-private", new TreeMap<>());
      rows.put("qits-net", new TreeMap<>());
      rows.put("qits-private", new TreeMap<>());
    }

    void database(String network, String name, String mark) {
      marks.get(network).put(name, mark);
      rows.get(network).put(name, MANIFEST);
    }

    boolean exists(String network, String name) {
      return marks.get(network).containsKey(name);
    }

    String mark(String network, String name) {
      return marks.get(network).get(name);
    }

    List<String> purposes() {
      return shots.stream().map(OneShot::purpose).toList();
    }

    private static Outcome ok(String output) {
      return new Outcome(0, output, false);
    }

    private static Outcome failed(String output) {
      return new Outcome(1, output, false);
    }

    private static String first(String regex, String text) {
      Matcher m = Pattern.compile(regex).matcher(text);
      return m.find() ? m.group(1) : null;
    }

    @Override
    public Outcome run(OneShot shot, Duration timeout) {
      shots.add(shot);
      if (failing.contains(shot.purpose())) {
        return failed("ERROR: scripted failure of " + shot.purpose());
      }
      Map<String, String> dbs = marks.get(shot.network());
      Map<String, String> data = rows.get(shot.network());
      String script = shot.script();
      switch (shot.purpose()) {
        case "self-check" -> {
          StringBuilder out = new StringBuilder();
          Matcher m = Pattern.compile("'qits-db=([a-z0-9_]+)='").matcher(script);
          while (m.find()) {
            String name = m.group(1);
            out.append("qits-db=").append(name).append('=').append(dbs.containsKey(name) ? "true" : "false")
                .append('=').append(dbs.getOrDefault(name, "")).append('\n');
          }
          return ok(out.toString());
        }
        case "self-mark" -> {
          Matcher m = Pattern.compile("COMMENT ON DATABASE (\\w+) IS (NULL|'([^']*)')").matcher(script);
          if (!m.find() || !dbs.containsKey(m.group(1))) {
            return failed("ERROR: database does not exist");
          }
          dbs.put(m.group(1), m.group(3) == null ? "" : m.group(3));
          return ok("");
        }
        case "rename", "admin" -> {
          String from = first("ALTER DATABASE (\\w+) RENAME TO", script);
          String to = first("ALTER DATABASE \\w+ RENAME TO (\\w+)", script);
          String leftover = first("DROP DATABASE (\\w+) WITH \\(FORCE\\)'", script);
          if (dbs.containsKey(from)) {
            if (leftover != null && dbs.containsKey(leftover)) {
              dbs.remove(leftover);
              data.remove(leftover);
            }
            if (dbs.containsKey(to)) {
              return failed("ERROR: database \"" + to + "\" already exists");
            }
            dbs.put(to, dbs.remove(from));
            data.put(to, data.remove(from));
            return ok("");
          }
          return dbs.containsKey(to) ? ok("") : failed("ERROR: database \"" + to + "\" does not exist");
        }
        case "extract" -> {
          String name = first("pg_dump -d (\\w+)", script);
          if (!dbs.containsKey(name)) {
            return failed("pg_dump: database \"" + name + "\" does not exist");
          }
          return ok(manifest(data.get(name)));
        }
        case "ingest" -> {
          String name = first("CREATE DATABASE (\\w+)", script);
          if (script.contains("DROP DATABASE IF EXISTS " + name)) {
            dbs.remove(name);
            data.remove(name);
          }
          if (dbs.containsKey(name)) {
            return failed("ERROR: database \"" + name + "\" already exists");
          }
          String mark = first("COMMENT ON DATABASE " + name + " IS '([^']*)'", script);
          dbs.put(name, mark == null ? "" : mark);
          String restored = ingestManifest == null ? MANIFEST : ingestManifest;
          data.put(name, restored);
          return ok(manifest(restored));
        }
        case "drop" -> {
          String name = first("DROP DATABASE IF EXISTS (\\w+)", script);
          dbs.remove(name);
          data.remove(name);
          return ok("");
        }
        default -> {
          return failed("unknown purpose " + shot.purpose());
        }
      }
    }

    private static String manifest(String rows) {
      return DatabaseScripts.MANIFEST_BEGIN + "\n" + rows + "\n" + DatabaseScripts.MANIFEST_END + "\n";
    }

    @Override
    public boolean createVolume(String volume) {
      return createVolume(volume, Map.of());
    }

    @Override
    public boolean createVolume(String volume, Map<String, String> labels) {
      volumes.putIfAbsent(volume, Map.copyOf(labels));
      return true;
    }

    @Override
    public Map<String, Map<String, String>> labelledVolumes(String key, String value) {
      if (dockerDown) {
        throw new IllegalStateException("Cannot connect to the Docker daemon");
      }
      Map<String, Map<String, String>> found = new LinkedHashMap<>();
      volumes.forEach((volume, labels) -> {
        if (value.equals(labels.get(key))) {
          found.put(volume, labels);
        }
      });
      return found;
    }

    @Override
    public boolean removeVolume(String volume) {
      volumes.remove(volume);
      return true;
    }

    @Override
    public boolean kill(String moveId) {
      return true;
    }

    @Override
    public Drain drained(String service) {
      return Drain.DRAINED;
    }
  }

  // --- the two processes ------------------------------------------------------------------------

  /** public/qits_deployments → private/qits_deployments: what qits-2134 needs. */
  private static Step copy(String id, String resource, String database) {
    return new Step(
        id, ENV, resource,
        DatabaseServer.PUBLIC, PUBLIC_HOST, "qits-net", database,
        DatabaseServer.PRIVATE, PRIVATE_HOST, "qits-private", database,
        "registry.example/qits/qits-oci-postgresql:1", "postgres", 600);
  }

  /** public/<from> → public/<to>: a rename in place. */
  private static Step rename(String id, String from, String to) {
    return new Step(
        id, ENV, "db",
        DatabaseServer.PUBLIC, PUBLIC_HOST, "qits-net", from,
        DatabaseServer.PUBLIC, PUBLIC_HOST, "qits-net", to,
        "registry.example/qits/qits-oci-postgresql:1", "postgres", 600);
  }

  /** The successor's environment: bound to each step's target, the step handed over. */
  private static Map<String, String> successor(Step... steps) {
    Map<String, String> env = new HashMap<>();
    env.put("QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD", ADMIN);
    for (Step step : steps) {
      String key = step.resource().toUpperCase().replace('-', '_');
      env.put("QITS_RESOURCE_" + key + "_URL", "jdbc:postgresql://" + step.targetHost() + ":5432/" + step.targetDatabase());
      env.put("QITS_RESOURCE_" + key + "_USERNAME", step.targetDatabase());
      env.put("QITS_RESOURCE_" + key + "_PASSWORD", PASSWORD);
      env.put("QITS_RESOURCE_" + key + "_MOVE", step.encode());
    }
    return env;
  }

  /** The old deployer's environment, as swarm's rollback restores it: bound to each source. */
  private static Map<String, String> oldDeployer(Step... steps) {
    Map<String, String> env = new HashMap<>();
    env.put("QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD", ADMIN);
    for (Step step : steps) {
      String key = step.resource().toUpperCase().replace('-', '_');
      env.put("QITS_RESOURCE_" + key + "_URL", "jdbc:postgresql://" + step.sourceHost() + ":5432/" + step.sourceDatabase());
      env.put("QITS_RESOURCE_" + key + "_USERNAME", step.sourceDatabase());
      env.put("QITS_RESOURCE_" + key + "_PASSWORD", PASSWORD);
    }
    return env;
  }

  /** The source as the old deployer left it: there, unmarked, with its rows. */
  private void assertSourceUntouched(String network, String database) {
    assertTrue(pg.exists(network, database), database + " is still there");
    assertEquals("", pg.mark(network, database), "and carries no mark");
    assertEquals(MANIFEST, pg.rows.get(network).get(database), "and its rows are the ones it had");
  }

  // --- the copy ---------------------------------------------------------------------------------

  @Test
  void theSuccessorCopiesTheDeployersDatabaseToThePrivateServerBeforeItStarts() {
    pg.database("qits-net", "qits_deployments", "");
    Step step = copy("m-1", "db", "qits_deployments");

    assertTrue(SelfMoveBoot.run(successor(step), pg));

    assertEquals(List.of("self-check", "self-check", "extract", "ingest", "self-mark"), pg.purposes());
    assertTrue(pg.exists("qits-private", "qits_deployments"));
    assertEquals("qits-self-move:m-1:verified", pg.mark("qits-private", "qits_deployments"));
    // The source is not written until swarm accepted the update: a rollback finds it as it was.
    assertSourceUntouched("qits-net", "qits_deployments");
    assertTrue(pg.volumes.containsKey("qits-dbmove-m-1"), "the marker stays until the move is finished");
    assertEquals(step.encode(), pg.volumes.get("qits-dbmove-m-1").get(SelfMove.PLAN_LABEL));
    assertEquals(28L, DatabaseScripts.total(SelfMoveBoot.COUNTS.get("m-1").target()));
    assertEquals(List.of(step), SelfMoveBoot.DUE, "the finish takes it from here, asking docker nothing");
    // Each container joins exactly one network: the extract the source's, the ingest the target's.
    assertEquals("qits-net", pg.shots.get(2).network());
    assertEquals("qits-private", pg.shots.get(3).network());
    // No password in a script; the role's reaches the ingest as a secret.
    assertEquals(PASSWORD, pg.shots.get(3).secrets().get(DatabaseScripts.ROLE_PASSWORD));
    assertTrue(pg.shots.stream().noneMatch(shot -> shot.script().contains(PASSWORD) || shot.script().contains(ADMIN)));
  }

  @Test
  void aFailedExtractStartsNothingAndTheOldDeployerRunsOnItsSource() {
    pg.database("qits-net", "qits_deployments", "");
    Step step = copy("m-2", "db", "qits_deployments");
    pg.failing.add("extract");

    assertFalse(SelfMoveBoot.run(successor(step), pg), "the successor exits non-zero");
    assertEquals(List.of(), SelfMoveBoot.DUE);

    assertFalse(pg.exists("qits-private", "qits_deployments"));
    assertTrue(pg.volumes.isEmpty(), "the marker went with the undo");
    pg.failing.clear();
    assertTrue(SelfMoveBoot.run(oldDeployer(step), pg), "swarm's rollback: the old deployer starts");
    assertSourceUntouched("qits-net", "qits_deployments");
  }

  @Test
  void aFailedIngestDropsWhatItRestoredAndTheOldDeployerRunsOnItsSource() {
    pg.database("qits-net", "qits_deployments", "");
    Step step = copy("m-3", "db", "qits_deployments");
    // The restore created the database and then died: the target exists, marked copying.
    DatabaseContainers ingestDies =
        new Delegate(pg) {
          @Override
          public Outcome run(OneShot shot, Duration timeout) {
            Outcome outcome = pg.run(shot, timeout);
            return shot.purpose().equals("ingest") ? new Outcome(1, "pg_restore: error", false) : outcome;
          }
        };

    assertFalse(SelfMoveBoot.run(successor(step), ingestDies));

    assertFalse(pg.exists("qits-private", "qits_deployments"), "the half-restored copy is dropped");
    assertTrue(pg.purposes().contains("drop"));
    assertTrue(pg.volumes.isEmpty());
    assertTrue(SelfMoveBoot.run(oldDeployer(step), pg));
    assertSourceUntouched("qits-net", "qits_deployments");
  }

  @Test
  void aCopyWhoseCountsDifferIsDroppedAndTheOldDeployerRunsOnItsSource() {
    pg.database("qits-net", "qits_deployments", "");
    Step step = copy("m-4", "db", "qits_deployments");
    pg.ingestManifest = "public.pd_deployment=6\npublic.flyway_schema_history=21";

    assertFalse(SelfMoveBoot.run(successor(step), pg));

    assertFalse(pg.exists("qits-private", "qits_deployments"));
    assertTrue(pg.volumes.isEmpty());
    assertTrue(SelfMoveBoot.run(oldDeployer(step), pg));
    assertSourceUntouched("qits-net", "qits_deployments");
  }

  @Test
  void aSuccessorThatDiesAfterTheCopyIsUndoneByTheOldDeployersStart() {
    // The copy worked; then Flyway, the health check or anything else failed, the process died,
    // and swarm rolled back. The marker is all that is left of the successor.
    pg.database("qits-net", "qits_deployments", "");
    Step step = copy("m-5", "db", "qits_deployments");
    assertTrue(SelfMoveBoot.run(successor(step), pg));
    pg.shots.clear();

    assertTrue(SelfMoveBoot.run(oldDeployer(step), pg));

    assertFalse(pg.exists("qits-private", "qits_deployments"), "the copy is dropped");
    assertTrue(pg.volumes.isEmpty(), "and the marker removed");
    assertSourceUntouched("qits-net", "qits_deployments");
    assertEquals(List.of("self-check", "self-check", "drop"), pg.purposes());
    assertTrue(SelfMoveBoot.DUE.stream().noneMatch(due -> due.id().equals("m-5")), "an undone move is nobody's to finish");
  }

  @Test
  void bothDatabasesMoveTogetherAndASecondFailureUndoesTheFirst() {
    pg.database("qits-net", "qits_deployments", "");
    pg.database("qits-net", "qits_deployments_eventstream", "");
    Step db = copy("m-6a", "db", "qits_deployments");
    Step events = copy("m-6b", "eventstream", "qits_deployments_eventstream");
    // The first copy works; the second one's extract fails.
    DatabaseContainers secondExtractDies =
        new Delegate(pg) {
          int extracts;

          @Override
          public Outcome run(OneShot shot, Duration timeout) {
            if (shot.purpose().equals("extract") && ++extracts == 2) {
              pg.shots.add(shot);
              return new Outcome(1, "pg_dump: error", false);
            }
            return pg.run(shot, timeout);
          }
        };

    assertFalse(SelfMoveBoot.run(successor(db, events), secondExtractDies));

    assertFalse(pg.exists("qits-private", "qits_deployments"), "the first copy is undone too");
    assertFalse(pg.exists("qits-private", "qits_deployments_eventstream"));
    assertTrue(pg.volumes.isEmpty());
    assertTrue(SelfMoveBoot.run(oldDeployer(db, events), pg));
    assertSourceUntouched("qits-net", "qits_deployments");
    assertSourceUntouched("qits-net", "qits_deployments_eventstream");
  }

  @Test
  void aRestartedSuccessorDoesNotCopyAgainWhatItAlreadyVerified() {
    // After swarm accepted the update the successor wrote to its copy; a restart then must not
    // replace that copy with the stale source.
    pg.database("qits-net", "qits_deployments", "");
    pg.database("qits-private", "qits_deployments", "qits-self-move:m-7:verified");
    Step step = copy("m-7", "db", "qits_deployments");
    pg.volumes.put(step.volume(), Map.of(SelfMove.KIND_LABEL, SelfMove.KIND, SelfMove.PLAN_LABEL, step.encode()));

    assertTrue(SelfMoveBoot.run(successor(step), pg));

    assertEquals(List.of("self-check", "self-check"), pg.purposes(), "nothing copied, nothing dropped");
    assertEquals("qits-self-move:m-7:verified", pg.mark("qits-private", "qits_deployments"));
  }

  @Test
  void aTargetNoMoveLeftIsNeverDroppedOrOverwritten() {
    pg.database("qits-net", "qits_deployments", "");
    pg.database("qits-private", "qits_deployments", "");
    Step step = copy("m-8", "db", "qits_deployments");

    assertFalse(SelfMoveBoot.run(successor(step), pg));

    assertTrue(pg.exists("qits-private", "qits_deployments"), "somebody's database is left alone");
    assertFalse(pg.purposes().contains("drop"));
    assertFalse(pg.purposes().contains("ingest"));
    assertSourceUntouched("qits-net", "qits_deployments");
  }

  @Test
  void aLeftoverOfAnEarlierAttemptIsReplaced() {
    pg.database("qits-net", "qits_deployments", "");
    pg.database("qits-private", "qits_deployments", "qits-self-move:m-old:copying");
    Step step = copy("m-9", "db", "qits_deployments");

    assertTrue(SelfMoveBoot.run(successor(step), pg));

    assertEquals("qits-self-move:m-9:verified", pg.mark("qits-private", "qits_deployments"));
    DatabaseContainers.OneShot ingest = pg.shots.stream().filter(s -> s.purpose().equals("ingest")).findFirst().orElseThrow();
    assertTrue(ingest.script().contains("DROP DATABASE IF EXISTS qits_deployments WITH (FORCE);"), ingest.script());
  }

  @Test
  void theOldDeployerNeverUndoesAMoveMarkedDone() {
    // A finished move whose marker could not be removed: the target is the data now.
    pg.database("qits-private", "qits_deployments", "qits-self-move:m-10:done");
    pg.database("qits-net", "qits_deployments", "");
    Step step = copy("m-10", "db", "qits_deployments");
    pg.volumes.put(step.volume(), Map.of(SelfMove.KIND_LABEL, SelfMove.KIND, SelfMove.PLAN_LABEL, step.encode()));

    assertTrue(SelfMoveBoot.run(oldDeployer(step), pg));

    assertTrue(pg.exists("qits-private", "qits_deployments"));
    assertFalse(pg.purposes().contains("drop"));
    assertTrue(pg.volumes.containsKey(step.volume()), "kept, and said so in the log");
  }

  @Test
  void aLaterDeployerBoundToTheTargetLeavesTheMoveForItsFinish() {
    pg.database("qits-net", "qits_deployments", "");
    pg.database("qits-private", "qits_deployments", "qits-self-move:m-11:verified");
    Step step = copy("m-11", "db", "qits_deployments");
    pg.volumes.put(step.volume(), Map.of(SelfMove.KIND_LABEL, SelfMove.KIND, SelfMove.PLAN_LABEL, step.encode()));
    Map<String, String> later = successor(step);
    later.remove("QITS_RESOURCE_DB_MOVE");

    assertTrue(SelfMoveBoot.run(later, pg));

    assertEquals(List.of(), pg.purposes());
    assertEquals(List.of(step), SelfMoveBoot.DUE, "a later deployer still finishes it");
    assertTrue(pg.exists("qits-private", "qits_deployments"));
  }

  // --- the rename -------------------------------------------------------------------------------

  @Test
  void twoNamesOnOneServerAreARenameAndTheMarkTravelsWithIt() {
    pg.database("qits-net", "qits_platform_deployments", "");
    Step step = rename("r-1", "qits_platform_deployments", "qits_deployments");

    assertTrue(SelfMoveBoot.run(successor(step), pg));

    assertEquals(List.of("self-check", "self-mark", "rename"), pg.purposes(), "no dump, no restore");
    assertFalse(pg.exists("qits-net", "qits_platform_deployments"));
    assertEquals("qits-self-move:r-1:renaming", pg.mark("qits-net", "qits_deployments"));
    DatabaseContainers.OneShot renamed = pg.shots.get(2);
    assertTrue(renamed.script().contains("ALTER DATABASE qits_platform_deployments RENAME TO qits_deployments;"));
    assertEquals(PASSWORD, renamed.secrets().get(DatabaseScripts.ROLE_PASSWORD), "set again after the role rename");
  }

  @Test
  void aSuccessorThatDiesAfterTheRenameIsRenamedBackByTheOldDeployersStart() {
    pg.database("qits-net", "qits_platform_deployments", "");
    Step step = rename("r-2", "qits_platform_deployments", "qits_deployments");
    assertTrue(SelfMoveBoot.run(successor(step), pg));
    pg.shots.clear();

    assertTrue(SelfMoveBoot.run(oldDeployer(step), pg));

    assertFalse(pg.exists("qits-net", "qits_deployments"));
    assertSourceUntouched("qits-net", "qits_platform_deployments");
    assertTrue(pg.volumes.isEmpty());
    DatabaseContainers.OneShot back = pg.shots.get(1);
    assertEquals("rename", back.purpose());
    assertTrue(back.script().contains("ALTER DATABASE qits_deployments RENAME TO qits_platform_deployments;"));
  }

  @Test
  void aFailedRenameLeavesTheSourceUnderItsNameAndUnmarked() {
    pg.database("qits-net", "qits_platform_deployments", "");
    Step step = rename("r-3", "qits_platform_deployments", "qits_deployments");
    pg.failing.add("rename");

    assertFalse(SelfMoveBoot.run(successor(step), pg));

    assertSourceUntouched("qits-net", "qits_platform_deployments");
    assertTrue(pg.volumes.isEmpty());
  }

  @Test
  void aSecondCopyFailingRenamesTheFirstBack() {
    pg.database("qits-net", "qits_platform_deployments", "");
    pg.database("qits-net", "qits_deployments_eventstream", "");
    Step db = rename("r-4a", "qits_platform_deployments", "qits_deployments");
    Step events = copy("r-4b", "eventstream", "qits_deployments_eventstream");
    pg.failing.add("ingest");

    assertFalse(SelfMoveBoot.run(successor(db, events), pg));

    assertSourceUntouched("qits-net", "qits_platform_deployments");
    assertFalse(pg.exists("qits-net", "qits_deployments"));
    assertSourceUntouched("qits-net", "qits_deployments_eventstream");
    assertTrue(pg.volumes.isEmpty());
  }

  // --- nothing to do ----------------------------------------------------------------------------

  @Test
  void anOrdinaryStartAsksDockerOnceAndRunsNothing() {
    assertTrue(SelfMoveBoot.run(oldDeployer(copy("x", "db", "qits_deployments")), pg));
    assertEquals(List.of(), pg.shots);
    assertEquals(List.of(), SelfMoveBoot.DUE, "so the running process asks docker nothing either");
  }

  @Test
  void anOrdinaryStartWithoutDockerStillStartsButAMoveWithoutDockerDoesNot() {
    pg.dockerDown = true;
    Step step = copy("d-1", "db", "qits_deployments");
    assertTrue(SelfMoveBoot.run(oldDeployer(step), pg));
    assertFalse(SelfMoveBoot.run(successor(step), pg));
  }

  @Test
  void aMoveWithoutTheAdminPasswordDoesNotStart() {
    pg.database("qits-net", "qits_deployments", "");
    Step step = copy("p-1", "db", "qits_deployments");
    Map<String, String> env = successor(step);
    env.remove("QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD");

    assertFalse(SelfMoveBoot.run(env, pg));
    assertEquals(List.of(), pg.shots);
  }

  // --- the plan's encoding ----------------------------------------------------------------------

  @Test
  void aStepReadsBackAsItWasWritten() {
    Step step = copy("0f8c3e2a-1b2c-4d5e-8f90-123456789abc", "eventstream", "qits_deployments_eventstream");
    assertEquals(step, Step.parse(step.encode()));
    Step renamed = rename("r", "qits_platform_deployments", "qits_deployments");
    assertEquals(renamed, Step.parse(renamed.encode()));
    assertTrue(renamed.inPlace());
    assertFalse(step.inPlace());
  }

  @Test
  void aStepThatDoesNotReadIsRefused() {
    String good = copy("m", "db", "qits_deployments").encode();
    assertThrows(IllegalArgumentException.class, () -> Step.parse(good + "|extra"));
    assertThrows(RuntimeException.class, () -> Step.parse(good.replace("qits_deployments", "x; DROP")));
    assertThrows(IllegalArgumentException.class, () -> Step.parse(good.replace("|600", "|0")));
    Map<String, String> env = successor(copy("m", "db", "qits_deployments"));
    env.put("QITS_RESOURCE_EVENTSTREAM_MOVE", good);
    assertThrows(IllegalArgumentException.class, () -> SelfMove.fromEnvironment(env), "a step under another resource's name");
    assertFalse(SelfMoveBoot.run(env, pg));
  }

  @Test
  void theUrlIsWhatSaysWhichEndAProcessIsOn() {
    assertEquals("dev-qits-oci-postgresql-private/qits_deployments",
        SelfMove.hostAndDatabase("jdbc:postgresql://dev-qits-oci-postgresql-private:5432/qits_deployments?sslmode=disable"));
    assertEquals("", SelfMove.hostAndDatabase("not a url"));
    Step step = copy("m", "db", "qits_deployments");
    assertTrue(SelfMove.boundToTarget(successor(step), step));
    assertTrue(SelfMove.boundToSource(oldDeployer(step), step));
    assertFalse(SelfMove.boundToSource(successor(step), step));
    assertTrue(SelfMove.leftover("qits-self-move:a:copying"));
    assertFalse(SelfMove.leftover("qits-self-move:a:done"));
    assertFalse(SelfMove.leftover(""));
    assertNull(SelfMoveBoot.COUNTS.get("m"));
  }

  /** A {@link DatabaseContainers} that hands everything to the simulated servers. */
  private static class Delegate implements DatabaseContainers {
    final Postgres pg;

    Delegate(Postgres pg) {
      this.pg = pg;
    }

    @Override
    public Outcome run(OneShot shot, Duration timeout) {
      return pg.run(shot, timeout);
    }

    @Override
    public boolean createVolume(String volume) {
      return pg.createVolume(volume);
    }

    @Override
    public boolean createVolume(String volume, Map<String, String> labels) {
      return pg.createVolume(volume, labels);
    }

    @Override
    public Map<String, Map<String, String>> labelledVolumes(String key, String value) {
      return pg.labelledVolumes(key, value);
    }

    @Override
    public boolean removeVolume(String volume) {
      return pg.removeVolume(volume);
    }

    @Override
    public boolean kill(String moveId) {
      return true;
    }

    @Override
    public Drain drained(String service) {
      return Drain.DRAINED;
    }
  }
}
