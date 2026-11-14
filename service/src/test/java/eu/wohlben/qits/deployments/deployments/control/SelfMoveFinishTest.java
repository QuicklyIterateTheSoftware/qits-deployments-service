package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SelfMove.Step;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.persistence.PdDatabaseMoveRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The last part of the deployer's own move (qits-2209), once it serves: {@link SelfMoveFinish} over
 * {@link FakeDatabaseContainers}.
 */
@QuarkusTest
class SelfMoveFinishTest {

  @Inject SelfMoveFinish finish;
  @Inject FakeDatabaseContainers containers;
  @Inject FakeDeploymentDriver driver;
  @Inject PdDatabaseMoveRepository moves;

  @BeforeEach
  void reset() {
    containers.reset();
    driver.reset();
  }

  private static Step copy(String id) {
    return new Step(
        id, "sf", "db",
        DatabaseServer.PUBLIC, "sf-qits-oci-postgresql", "qits-net", "qits_deployments",
        DatabaseServer.PRIVATE, "sf-qits-oci-postgresql-private", "qits-private", "qits_deployments",
        "registry.example/qits/qits-oci-postgresql:1", "postgres", 600);
  }

  private static Step rename(String id) {
    return new Step(
        id, "sf", "db",
        DatabaseServer.PUBLIC, "sf-qits-oci-postgresql", "qits-net", "qits_platform_deployments",
        DatabaseServer.PUBLIC, "sf-qits-oci-postgresql", "qits-net", "qits_deployments",
        "registry.example/qits/qits-oci-postgresql:1", "postgres", 600);
  }

  private void marker(Step step) {
    containers.createVolume(step.volume(), Map.of(SelfMove.KIND_LABEL, SelfMove.KIND, SelfMove.PLAN_LABEL, step.encode()));
  }

  private static Map<String, String> boundTo(String host, String database) {
    Map<String, String> env = new HashMap<>();
    env.put("QITS_RESOURCE_DB_URL", "jdbc:postgresql://" + host + ":5432/" + database);
    return env;
  }

  private PdDatabaseMove row(String id) {
    return QuarkusTransaction.requiringNew().call(() -> moves.findById(id));
  }

  @Test
  void onlyTheMovesThisProcessIsBoundToTheTargetOfAreDue() {
    Step mine = copy(UUID.randomUUID().toString());
    marker(mine);

    assertEquals(List.of(mine), finish.due(boundTo("sf-qits-oci-postgresql-private", "qits_deployments")));
    assertEquals(List.of(), finish.due(boundTo("sf-qits-oci-postgresql", "qits_deployments")), "the old deployer's is an undo");
  }

  @Test
  void aCopyIsRecordedMarkedDoneItsSourceKeptAsideAndTheMarkerRemoved() {
    String id = UUID.randomUUID().toString();
    Step step = copy(id);
    marker(step);
    SelfMoveBoot.COUNTS.put(id, new SelfMoveBoot.Counts(Map.of("public.t", 3L), Map.of("public.t", 3L)));

    finish.finish(List.of(step));

    PdDatabaseMove done = row(id);
    assertEquals(PdDatabaseMoveState.MOVED, done.state);
    assertEquals("qits-deployments", done.applicationName);
    assertEquals("public", done.sourceServer);
    assertEquals("private", done.targetServer);
    assertNotNull(done.keptDatabase);
    assertTrue(done.keptDatabase.startsWith("qits_deployments_moved_"), done.keptDatabase);
    assertNotNull(done.dropAfter, "the retention sweep drops the kept copy");
    assertEquals("{\"public.t\":3}", done.targetCounts.replace(" ", ""));

    List<DatabaseContainers.OneShot> shots = containers.shots();
    assertEquals(List.of("self-mark", "admin"), shots.stream().map(DatabaseContainers.OneShot::purpose).toList());
    assertEquals("qits-private", shots.get(0).network());
    assertTrue(shots.get(0).script().contains("COMMENT ON DATABASE qits_deployments IS 'qits-self-move:" + id + ":done';"));
    assertEquals("qits-net", shots.get(1).network());
    assertTrue(shots.get(1).script().contains("RENAME TO " + done.keptDatabase), shots.get(1).script());
    assertTrue(containers.removedVolumes().contains(step.volume()));
    assertEquals(Map.of(), containers.labelledVolumes(SelfMove.KIND_LABEL, SelfMove.KIND));
  }

  @Test
  void aRenameIsRecordedAsOneAndKeepsNoCopy() {
    String id = UUID.randomUUID().toString();
    Step step = rename(id);
    marker(step);

    finish.finish(List.of(step));

    PdDatabaseMove done = row(id);
    assertEquals(PdDatabaseMoveState.MOVED, done.state);
    assertNull(done.keptDatabase);
    assertNull(done.dropAfter);
    assertEquals(DatabaseMoves.RENAMED_IN_PLACE, done.detail);
    assertEquals(List.of("self-mark"), containers.shots().stream().map(DatabaseContainers.OneShot::purpose).toList());
    assertTrue(containers.removedVolumes().contains(step.volume()));
  }

  @Test
  void aFinishThatFailsKeepsTheMarkerAndARepeatKeepsTheKeptName() {
    String id = UUID.randomUUID().toString();
    Step step = copy(id);
    marker(step);
    containers.script("admin", shot -> new DatabaseContainers.Outcome(1, "ERROR: still connected", false));

    finish.finish(List.of(step));

    assertFalse(containers.removedVolumes().contains(step.volume()), "the next start finishes it");
    String kept = row(id).keptDatabase;

    containers.reset();
    marker(step);
    finish.finish(List.of(step));

    assertEquals(kept, row(id).keptDatabase, "one name for the kept copy, however often it is tried");
    assertTrue(containers.removedVolumes().contains(step.volume()));
  }

  @Test
  void itWaitsForSwarmToAcceptTheUpdate() {
    driver.scriptConvergence(DeploymentDriver.Convergence.converged(List.of()));
    assertTrue(finish.awaitServing("sf-qits-deployments"));
    assertTrue(driver.calls().contains("await:sf-qits-deployments"), driver.calls().toString());
  }
}
