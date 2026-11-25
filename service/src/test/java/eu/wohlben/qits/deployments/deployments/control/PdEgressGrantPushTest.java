package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressDeclaration;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressGrantTable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The egress grant table pushed to qits-egress (epic qits-2523), off {@link FakeEgressGrantClient}:
 * a push when qits-egress holds nothing or another version, none when it holds this one, a newer
 * held version that is nothing to do, a failure that fails nothing, and the observation pass that
 * runs it. The push after a cutover is {@code PdEgressGrantApiTest}'s.
 */
@QuarkusTest
class PdEgressGrantPushTest {

  private static final Instant BUILT_AT = Instant.parse("2025-11-25T10:00:00Z");

  @Inject EgressGrantPusher pusher;
  @Inject EgressGrantTables tables;
  @Inject FakeEgressGrantClient egress;
  @Inject DeployService deployService;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    egress.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdEgressDeclaration.deleteAll();
              PdEgressGrantTable.deleteAll();
            });
  }

  /** The table stored now, built at a pinned instant when there is none yet. */
  private long stored() {
    return tables.current(BUILT_AT).version();
  }

  @Test
  void nothingHeldIsPushedTheTableVerbatimAndTheSameVersionIsNotPushedAgain() throws Exception {
    long version = stored();

    assertTrue(pusher.reconcile());
    assertEquals(List.of(version), egress.pushedVersions());
    JsonNode body = EdgeRouteTables.mapper().readTree(egress.pushes().get(0));
    assertEquals(
        List.of("version", "routes", "refused"),
        iterable(body.fieldNames()),
        "exactly the GET /deployments/api/egress-grants body");
    assertEquals(EgressGrantTables.json(tables.current()), egress.pushes().get(0));

    assertTrue(pusher.reconcile());
    assertEquals(1, egress.pushes().size(), "qits-egress holds this version: no second push");
    assertEquals(2, egress.reads());
  }

  @Test
  void anotherHeldVersionIsPushed() {
    long version = stored();
    egress.reportHeld(version - 1);

    assertTrue(pusher.reconcile());

    assertEquals(List.of(version), egress.pushedVersions());
  }

  @Test
  void aNewerHeldVersionIsNothingToDo() {
    long version = stored();
    egress.reportHeld(version + 1000);

    assertTrue(assertDoesNotThrow(() -> pusher.reconcile()));

    assertEquals(List.of(version), egress.pushedVersions(), "pushed once, answered 409");
    assertEquals(java.util.OptionalLong.of(version + 1000), egress.heldVersion());
  }

  @Test
  void anUnreachableEgressFailsNothingAndTheNextPassPushes() {
    long version = stored();
    egress.failWith("qits-egress answered 503 to GET …/grant-table");

    assertFalse(assertDoesNotThrow(() -> pusher.reconcile()));
    assertFalse(assertDoesNotThrow(() -> pusher.reconcile()), "the outage goes on, quietly");
    assertTrue(egress.pushes().isEmpty(), "a failed read pushes nothing");

    egress.clearFailure();
    assertTrue(pusher.reconcile());
    assertEquals(List.of(version), egress.pushedVersions());
  }

  @Test
  void theObservationPassPushes() throws Exception {
    long version = stored();

    deployService.enqueueObservation();
    deployService.awaitIdle();

    assertEquals(List.of(version), egress.pushedVersions());
  }

  private static List<String> iterable(java.util.Iterator<String> names) {
    List<String> all = new java.util.ArrayList<>();
    names.forEachRemaining(all::add);
    return all;
  }
}
