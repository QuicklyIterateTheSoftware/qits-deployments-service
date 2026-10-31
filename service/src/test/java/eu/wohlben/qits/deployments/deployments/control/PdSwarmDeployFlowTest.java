package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A deployment end to end through the real intake and the real worker, with the seam's own answers
 * scripted one at a time.
 *
 * <p>What it is about is that there is nothing orchestrator-shaped left in {@link DeployService}:
 * the four status transitions happen in the same order whatever the driver says, the four events
 * are announced, and the driver is asked exactly the two questions the seam has — apply this spec,
 * did it converge. The three answers {@code apply} and {@code awaitConverged} can give are one test
 * each, which is what makes this the state machine's own suite rather than swarm's.
 *
 * <p>The bus is on and aimed at a closed port, the {@code PdDeployPublishTest} arrangement: every
 * published event lands as exactly one {@code outbox_event} row with the payload it would have been
 * sent with, and a row IS the publish from this side of the bus.
 */
@QuarkusTest
@TestProfile(PdSwarmDeployFlowTest.SwarmOrchestrator.class)
public class PdSwarmDeployFlowTest {

  private static final String VERSION = "2026.903.193059";

  public static class SwarmOrchestrator implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.eventstream.enabled", "true",
          // Nothing answers here, so the inline attempt costs no wall time and the event lands in
          // the outbox rather than on a socket.
          "qits.events.url", "http://localhost:1",
          "quarkus.scheduler.enabled", "false",
          "qits.eventstream.catchup-at-startup", "false");
    }
  }

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver fake;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
    fake.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  @Test
  public void aGreenBuildAppliesOneServiceAndRecordsItActive() {
    String environmentId = createEnvironment("swarm-green");

    postRelease("run-swarm", "repo-swarm-green");
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 1);

    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertEquals(VERSION, deployments.get(0).get("version"));
    // The row records the name the ORCHESTRATOR gave it, which under swarm is the wire alias: the
    // service name is the address, and a replace is an update of that same service.
    assertEquals("swarm-green-repo-swarm-green", deployments.get(0).get("containerName"));

    assertEquals(
        List.of("registry.qits.dev.localhost:8080/qits/repo-swarm-green:" + VERSION),
        fake.pulled(),
        "the missing-image classification is ours, not the orchestrator's");
    assertEquals(1, fake.applied().size());
    DeploymentDriver.ServiceSpec applied = fake.applied().get(0);
    // The FULL membership in one piece, primary first — an orchestrator that cannot join after the
    // fact has to be told all of it at once.
    assertEquals("qits-env-swarm-green-repo-swarm-green", applied.primaryNetwork());
    assertTrue(applied.networks().contains("qits-net"), applied.networks().toString());
    assertEquals("swarm-green-repo-swarm-green", applied.wireAlias());
    assertEquals(DeploymentDriver.UpdateOrder.START_FIRST, applied.updateOrder(), "the default");
    assertEquals(DeploymentDriver.PublishMode.HOST, applied.publishMode(), "the default");
    assertEquals(Duration.ofSeconds(30), applied.stopGracePeriod(), "the default");
    // start-first: swarm marks the predecessor for shutdown and does not wait for it to exit, so the
    // grace adds nothing to the verdict's deadline — health-timeout-seconds alone, as it always was.
    assertEquals(List.of(Duration.ofSeconds(180)), fake.awaitedTimeouts());
    // ...and the verdict was asked about the name the driver chose, not the container-shaped one.
    assertEquals(List.of("swarm-green-repo-swarm-green"), fake.awaited());
    // Nothing to reap: there was no predecessor, and a swarm replace is in place anyway.
    assertEquals(List.of(), fake.reaped());

    OutboxEvent queued = only("DeploymentQueued");
    OutboxEvent started = only("DeploymentStarted");
    OutboxEvent active = only("DeploymentActive");
    for (OutboxEvent event : List.of(queued, started, active)) {
      assertTrue(event.payload.contains("\"applicationName\":\"repo-swarm-green\""), event.payload);
      assertTrue(event.payload.contains("\"version\":\"" + VERSION + "\""), event.payload);
    }
    assertTrue(
        active.payload.contains("\"containerName\":\"swarm-green-repo-swarm-green\""),
        active.payload);
    assertTrue(queued.occurredAt.compareTo(started.occurredAt) <= 0, "queued before started");
    assertTrue(started.occurredAt.compareTo(active.occurredAt) <= 0, "started before active");
    assertNull(only("DeploymentFailed", 0), "a green deployment announces no failure");
  }

  @Test
  public void aRepositoryThatDeclaresIngressReachesTheDriverWithIt() {
    // The plumbing, end to end: publish_mode is read from the repository's spec, carried by value
    // through the target and the plan, and handed to the orchestrator — and it moves the update
    // order not at all.
    String environmentId = createEnvironment("swarm-ingress");
    specs.script(
        "repo-swarm-ingress",
        new SpecSource.DeploymentSpec(
            false,
            null,
            null,
            null,
            null,
            null,
            DeploymentDriver.PublishMode.INGRESS));

    postRelease("run-ingress", "repo-swarm-ingress");
    awaitSettled(environmentId, 1);

    DeploymentDriver.ServiceSpec applied = fake.applied().get(0);
    assertEquals(DeploymentDriver.PublishMode.INGRESS, applied.publishMode());
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST,
        applied.updateOrder(),
        "the publish mode decides nothing about the update order");
  }

  @Test
  public void aStopFirstRepositoryWithALongGraceIsGivenTheGraceOnTopOfTheHealthTimeout() {
    // qits-1170, end to end: stop_grace_period is read from the repository's spec, carried through
    // the target and the plan, and handed to the orchestrator. Under stop-first swarm waits the
    // predecessor out before the successor starts, and a failed successor out before the rollback
    // starts — so the verdict's deadline grows by the grace twice, or a ten-minute drain would read
    // as this process's timeout instead of swarm's verdict.
    String environmentId = createEnvironment("swarm-grace");
    specs.script(
        "repo-swarm-grace",
        new SpecSource.DeploymentSpec(
            false,
            null,
            null,
            null,
            null,
            List.of(),
            DeploymentDriver.UpdateOrder.STOP_FIRST,
            null,
            List.of(),
            8080,
            null,
            false,
            List.of(),
            null,
            null,
            null,
            Duration.ofMinutes(10)));

    postRelease("run-grace", "repo-swarm-grace");
    awaitSettled(environmentId, 1);

    DeploymentDriver.ServiceSpec applied = fake.applied().get(0);
    assertEquals(Duration.ofMinutes(10), applied.stopGracePeriod());
    assertEquals(DeploymentDriver.UpdateOrder.STOP_FIRST, applied.updateOrder());
    assertEquals(
        List.of(Duration.ofSeconds(180).plus(Duration.ofMinutes(20))), fake.awaitedTimeouts());
  }

  @Test
  public void aSecondBuildDecommissionsThePredecessorRowWithoutReapingTheService() {
    // The prior ACTIVE row of this (application, tier) is decommissioned, and NOTHING is reaped:
    // the predecessor and the successor are one service, so removing "the old one" would remove
    // the deployment that just went live.
    String environmentId = createEnvironment("swarm-cutover");
    postRelease("run-a", "repo-swarm-cutover");
    awaitSettled(environmentId, 1);

    postRelease("run-b", "repo-swarm-cutover");
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 2);

    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertEquals("DECOMMISSIONED", deployments.get(1).get("status"));
    assertEquals(
        deployments.get(0).get("containerName"),
        deployments.get(1).get("containerName"),
        "both rows name the same service, which is what an in-place replace is");
    assertEquals(List.of(), fake.reaped(), "so there is nothing to remove: " + fake.reaped());
  }

  @Test
  public void aRolledBackUpdateEndsTheRowRolledBackCarryingSwarmsOwnWords() {
    // The measured failure path under start-first: the predecessor kept serving, swarm reverted the
    // spec by itself, and what is left for this component to do is the row and the event.
    //
    // The row says ROLLED_BACK rather than FAILED, because the orchestrator answered the question a
    // reader actually has — the place is still served — and FAILED threw that answer away.
    fake.scriptConvergence(
        DeploymentDriver.Convergence.rolledBack(
            "swarm rolled dev-repo-swarm-sick back to its predecessor: rollback completed"));
    String environmentId = createEnvironment("swarm-sick");

    postRelease("run-sick", "repo-swarm-sick");
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 1);

    assertEquals("ROLLED_BACK", deployments.get(0).get("status"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("rollback completed"),
        "swarm's own words are on the row: " + deployments.get(0).get("detail"));
    // Still DeploymentFailed — the four events stay four, and the refined word rides its status
    // field, which is what a string on the wire was for.
    OutboxEvent failed = only("DeploymentFailed");
    assertTrue(failed.payload.contains("\"status\":\"ROLLED_BACK\""), failed.payload);
    assertTrue(failed.payload.contains("rollback completed"), failed.payload);
    assertNull(only("DeploymentActive", 0), "nothing was cut over");
  }

  @Test
  public void aSelfUpdateLeavesTheRowStartingForWhoeverSurvives() {
    // The manager arbitrates: it stops this task, starts the successor and reverts the spec if the
    // successor never goes healthy. So the driver hands the deployment over, and this component
    // records nothing — the row is settled by the instance that boots next.
    fake.scriptApply(
        new DeploymentDriver.ApplyResult(
            DeploymentDriver.ApplyOutcome.HANDED_OFF, "the swarm manager arbitrates"));
    String environmentId = createEnvironment("swarm-self");

    postRelease("run-self", "repo-swarm-self");
    awaitStarting(environmentId);

    assertEquals(List.of(), fake.awaited(), "nothing waits on a succession it is not part of");
    assertNull(only("DeploymentActive", 0));
    assertNull(only("DeploymentFailed", 0));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        // The entry tier: a release lands in the designated platform environment.
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postRelease(String runId, String repoId) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", runId, "repoId", repoId, "version", VERSION))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private List<Map<String, Object>> deployments(String environmentId) {
    return given()
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments");
  }

  /** Settled rows, then a drained worker — an announcement is published after the row a poll sees. */
  private List<Map<String, Object>> awaitSettled(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows = deployments(environmentId);
      boolean settled =
          rows.size() == count
              && rows.stream()
                  .noneMatch(
                      row ->
                          "QUEUED".equals(row.get("status")) || "STARTING".equals(row.get("status")));
      if (settled) {
        awaitIdle();
        return rows;
      }
      sleep();
    }
    return fail("the deployments of " + environmentId + " did not settle to " + count);
  }

  /** A self-update's own end state: a row that stays STARTING because nobody here may settle it. */
  private void awaitStarting(String environmentId) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows = deployments(environmentId);
      if (rows.size() == 1 && "STARTING".equals(rows.get(0).get("status"))) {
        awaitIdle();
        assertEquals("STARTING", deployments(environmentId).get(0).get("status"));
        return;
      }
      sleep();
    }
    fail("the deployment of " + environmentId + " never reached STARTING");
  }

  private void awaitIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private List<OutboxEvent> rows() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox.createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList());
  }

  private OutboxEvent only(String name) {
    OutboxEvent row = only(name, 1);
    assertNotNull(row, "expected one " + name + " row");
    return row;
  }

  private OutboxEvent only(String name, int expected) {
    List<OutboxEvent> matching = rows().stream().filter(row -> name.equals(row.name)).toList();
    assertEquals(expected, matching.size(), () -> "expected " + expected + " " + name + " row(s)");
    return matching.isEmpty() ? null : matching.get(0);
  }
}
