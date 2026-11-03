package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the periodic observation settles, and — as importantly — what it refuses to touch. Package-local
 * so the pass is driven directly ({@link DeploymentObserver#observeOnce()}) and enqueued directly
 * ({@link DeployService#enqueueObservation()}), the {@link PdSweepAdoptionTest} shape: no tick runs
 * under a {@code @QuarkusTest}, because {@code onStart} returns early in test mode.
 *
 * <p>Rows are written straight to the table, like the sweep's tests, because the states under test are
 * ones no deployment can be talked into producing on demand — a {@code FAILED} row whose container is
 * nonetheless healthy is precisely the accident (eaa34fbc) this class exists for.
 */
@QuarkusTest
public class PdDeploymentObservationTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);

  /** The released coordinate a deployment carries now — the CalVer stamp, not a commit sha. */
  private static final String VERSION_B = "2026.903.193059";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject DeploymentObserver observer;
  @Inject DeployService deployService;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
  }

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      String detail) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.commitSha = SHA_A;
              row.status = status;
              row.containerName = containerName;
              row.detail = detail;
              row.createdAt = Instant.now();
              if (status != PdDeploymentStatus.QUEUED && status != PdDeploymentStatus.STARTING) {
                row.finishedAt = Instant.now();
              }
              deployments.persist(row);
            });
    return id;
  }

  private PdDeployment rowOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.findById(deploymentId);
              assertNotNull(row, "the row is still there");
              // Read every field the assertions need while the session is open.
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              copy.containerName = row.containerName;
              copy.finishedAt = row.finishedAt;
              return copy;
            });
  }

  private String statusOf(String deploymentId) {
    return rowOf(deploymentId).status.name();
  }

  @Test
  public void aFailedLatestRowWhoseOwnContainerIsHealthyIsRecoveredByObservation() {
    // eaa34fbc itself: the cutover went healthy, the bookkeeping died on connections the cutover had
    // just killed, and the row said FAILED about a container that was serving.
    String container = "qits-pd-prod-obs-recovered-eaa34fbc";
    String failed =
        deployment(
            "obs-recovered",
            "env-obs-recovered",
            PdDeploymentStatus.FAILED,
            container,
            "[unexpected: JDBCConnectionException: Unable to acquire JDBC Connection]");
    driver.scriptObservation(container, "running/healthy");

    observer.observeOnce();

    PdDeployment row = rowOf(failed);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertTrue(
        row.detail.contains("recovered by observation"),
        "the detail says an observation did this: " + row.detail);
    assertTrue(row.detail.contains(container), "and names the container it read: " + row.detail);
    assertTrue(
        row.detail.contains("JDBCConnectionException"),
        "the original failure is kept, not erased — it is the diagnosis: " + row.detail);
    // Rows only. A recovery is bookkeeping, and this class applies, reaps and removes nothing.
    assertNothingWasTouched();
  }

  @Test
  public void aGoneRowWhoseContainerCameBackIsRecoveredToo() {
    // The demotion's own word. A container absent for two passes and back on the third is the
    // restart nobody watched, and a recovery arm that healed only FAILED would leave the
    // observation able to write a status it could never take back.
    String container = "qits-pd-prod-obs-returned-00000009";
    String gone =
        deployment(
            "obs-returned",
            "env-obs-returned",
            PdDeploymentStatus.GONE,
            container,
            "[gone by observation at 2026-08-12T00:00:00Z: container "
                + container
                + " is gone on 2 consecutive observations]");
    driver.scriptObservation(container, "running/healthy");

    observer.observeOnce();

    PdDeployment row = rowOf(gone);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertTrue(
        row.detail.contains("recovered by observation"),
        "the detail says an observation did this: " + row.detail);
    assertTrue(
        row.detail.contains("gone by observation"),
        "and the demotion it undid is kept under it: " + row.detail);
    assertNothingWasTouched();
  }

  @Test
  public void aHealthyContainerOfAnotherDeploymentDoesNotResurrectAFailedRow() {
    // The row names the container that was removed when its deployment failed; something else on the
    // host is healthy. Only the row's OWN container may settle the row.
    String failed =
        deployment(
            "obs-foreign",
            "env-obs-foreign",
            PdDeploymentStatus.FAILED,
            "qits-pd-prod-obs-foreign-deadbeef",
            "[container exited]");
    driver.scriptObservation("qits-pd-prod-obs-foreign-somebodyelse", "running/healthy");

    observer.observeOnce();

    assertEquals("FAILED", statusOf(failed), "a foreign container proves nothing about this row");
  }

  @Test
  public void anActiveRowWhoseContainerVanishedIsDemotedOnlyOnTheSecondPass() {
    // Patience in the other direction: one docker call that could not answer must never flip a
    // deployment that is serving, so a single pass is not a verdict.
    String active =
        deployment(
            "obs-vanished",
            "env-obs-vanished",
            PdDeploymentStatus.ACTIVE,
            "qits-pd-prod-obs-vanished-00000001",
            null);

    observer.observeOnce();
    assertEquals("ACTIVE", statusOf(active), "one absent observation is a hiccup, not a death");

    observer.observeOnce();
    PdDeployment row = rowOf(active);
    assertEquals(
        PdDeploymentStatus.GONE,
        row.status,
        "the deployment succeeded and the place died afterwards — that is GONE, not FAILED");
    assertTrue(
        row.detail.contains("gone by observation"),
        "the detail says an observation did this: " + row.detail);
    assertTrue(
        row.detail.contains("qits-pd-prod-obs-vanished-00000001"),
        "and names what it observed: " + row.detail);
    assertNothingWasTouched();
  }

  @Test
  public void anActiveRowWhoseContainerExitedIsDemotedOnTheSecondPass() {
    // The other terminal answer: docker still has the container, and it is not coming back.
    String container = "qits-pd-prod-obs-exited-00000002";
    String active =
        deployment("obs-exited", "env-obs-exited", PdDeploymentStatus.ACTIVE, container, null);
    driver.scriptObservation(container, "exited/unhealthy");

    observer.observeOnce();
    observer.observeOnce();

    PdDeployment row = rowOf(active);
    assertEquals(PdDeploymentStatus.GONE, row.status);
    assertTrue(row.detail.contains("exited/unhealthy"), "docker's own words: " + row.detail);
  }

  @Test
  public void aRestartingOrUnhealthyContainerLeavesItsActiveRowAlone() {
    // The health gate's patience, applied to the observation: `--restart unless-stopped` brings a
    // PostgreSQL-backed container back seconds after its first boot died on an unresolvable alias,
    // and a container answering its probe with a failure is up. Neither is a dead deployment.
    String restarting = "qits-pd-prod-obs-restarting-00000003";
    String unhealthy = "qits-pd-prod-obs-unhealthy-00000004";
    String restartingRow =
        deployment(
            "obs-restarting", "env-obs-patience", PdDeploymentStatus.ACTIVE, restarting, null);
    String unhealthyRow =
        deployment("obs-unhealthy", "env-obs-patience", PdDeploymentStatus.ACTIVE, unhealthy, null);
    driver.scriptObservation(restarting, "restarting/unhealthy");
    driver.scriptObservation(unhealthy, "running/unhealthy");

    observer.observeOnce();
    observer.observeOnce();
    observer.observeOnce();

    assertEquals("ACTIVE", statusOf(restartingRow), "restarting is not dead");
    assertEquals("ACTIVE", statusOf(unhealthyRow), "running-but-unhealthy is not dead either");
  }

  @Test
  public void aFailedRowThatIsNotTheLatestForItsPlaceIsLeftAlone() {
    // History stays history. The older attempt really did fail; the container that is healthy today
    // belongs to the successor that replaced it, and reusing it to rewrite the past would erase the
    // only record that anything went wrong.
    String olderContainer = "qits-pd-prod-obs-history-00000005";
    String older =
        deployment(
            "obs-history",
            "env-obs-history",
            PdDeploymentStatus.FAILED,
            olderContainer,
            "[container exited]");
    String newerContainer = "qits-pd-prod-obs-history-00000006";
    String newer =
        deployment("obs-history", "env-obs-history", PdDeploymentStatus.ACTIVE, newerContainer, null);
    driver.scriptObservation(olderContainer, "running/healthy");
    driver.scriptObservation(newerContainer, "running/healthy");

    observer.observeOnce();

    assertEquals("FAILED", statusOf(older), "not the latest row for its place — never revisited");
    assertEquals("ACTIVE", statusOf(newer));
  }

  @Test
  public void aQueuedOrStartingRowIsNeverTouched() {
    // Those two belong to the worker's own state machine and to the startup sweep. A self-update
    // self-update sits in STARTING with a healthy successor on purpose, and an observation that promoted
    // it would take the surviving instance's decision away.
    String queued =
        deployment(
            "obs-inflight-q", "env-obs-inflight", PdDeploymentStatus.QUEUED, "qits-pd-q-00000007", null);
    String starting =
        deployment(
            "obs-inflight-s",
            "env-obs-inflight",
            PdDeploymentStatus.STARTING,
            "qits-pd-s-00000008",
            null);
    driver.scriptObservation("qits-pd-s-00000008", "running/healthy");

    observer.observeOnce();
    observer.observeOnce();

    assertEquals("QUEUED", statusOf(queued));
    assertEquals("STARTING", statusOf(starting), "a succession's outcome is the survivor's to record");
  }

  @Test
  public void aRecoveredRowRetiresThePredecessorItsOwnCutoverNeverDecommissioned() {
    // The bookkeeping that died in eaa34fbc was ONE bracket doing two things, so a row recovered
    // here may still have an older row claiming to serve. Two ACTIVE rows for one (application,
    // tier) is the invariant listActiveByApplication and the rollback pins are written around.
    String environmentId = "env-obs-pair";
    String predecessor =
        deployment(
            "obs-pair", environmentId, PdDeploymentStatus.ACTIVE, "qits-pd-prod-obs-pair-old", null);
    String container = "qits-pd-prod-obs-pair-new";
    String recovered =
        deployment(
            "obs-pair",
            environmentId,
            PdDeploymentStatus.FAILED,
            container,
            "[unexpected: JDBCConnectionException …]");
    driver.scriptObservation(container, "running/healthy");

    observer.observeOnce();

    assertEquals("ACTIVE", statusOf(recovered));
    assertEquals(
        "DECOMMISSIONED", statusOf(predecessor), "one ACTIVE row per (application, tier), still");
    // ...and its container was NOT reaped. The startup sweep's stance: whatever still holds the
    // alias is absorbed by the next deployment's predecessor search.
    assertNothingWasTouched();
  }

  @Test
  public void anObservationPassNeverInterleavesWithAQueuedDeployment() {
    // Both go through the single deploy worker, which is what keeps "the previous ACTIVE deployment"
    // an uncontended read during a cutover. The deployment here is deliberately slow (a container
    // that restarts its way into health), and the pass is enqueued while it runs: every observation
    // has to land AFTER the deployment's last driver call, not between two of them.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "obs-serial", "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201);
    // Two rows of this test's own, so a pass makes more than one observation and an interleaving
    // would be visible.
    deployment(
        "obs-serial-a", "env-obs-serial", PdDeploymentStatus.ACTIVE, "qits-pd-obs-serial-a", null);
    deployment(
        "obs-serial-b", "env-obs-serial", PdDeploymentStatus.ACTIVE, "qits-pd-obs-serial-b", null);
    // The deployment is deliberately slow to converge, so the pass is enqueued while it runs.
    driver.scriptConvergeDelay(java.time.Duration.ofMillis(400));

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "run-obs-serial",
                "repoId", "repo-obs-serial",
                "version", VERSION_B))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    // The intake has already submitted the event, so this lands strictly behind it in the queue.
    deployService.enqueueObservation();
    awaitWorkerIdle();

    List<String> calls = driver.calls();
    // The reap is the deployment's last driver call, and it happens after the rows — empty under
    // swarm, where a replace is in place, but made all the same.
    int lastDeployCall = -1;
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).startsWith("reap:")) {
        lastDeployCall = i;
      }
    }
    assertTrue(lastDeployCall >= 0, "the deployment ran to its cutover: " + calls);
    List<Integer> observations = new java.util.ArrayList<>();
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).startsWith("observe:")) {
        observations.add(i);
      }
    }
    assertTrue(observations.size() >= 2, "the pass observed more than one row: " + calls);
    assertTrue(
        observations.get(0) > lastDeployCall,
        "the pass waited for the whole deployment rather than interleaving with it: " + calls);
  }

  // --- the third arm: a deliberate zero is not a death ------------------------------------------

  @Test
  public void aPlaceTheOrchestratorDeclaresEmptyIsRecordedStoppedRatherThanDemoted() {
    // An operator scaled this application to zero — through the API, or by hand with `docker
    // service scale`. Every observation of it says "gone", because no task is running; without the
    // desired count this pass would demote a serving-until-a-minute-ago deployment to GONE two
    // ticks after somebody deliberately stopped it, and page whoever reads red rows.
    String container = "qits-pd-prod-obs-paused";
    String active =
        deployment("obs-paused", "env-obs-paused", PdDeploymentStatus.ACTIVE, container, null);
    driver.scriptObservationGone(container, "no such service");
    driver.scriptDeclaredReplicas(container, 0);

    observer.observeOnce();

    PdDeployment row = rowOf(active);
    assertEquals(
        PdDeploymentStatus.SCALED_TO_ZERO,
        row.status,
        "and on the FIRST pass: this is intent read off the runtime, not a guess needing a second"
            + " opinion");
    assertTrue(row.detail.contains("declared to run no tasks"), row.detail);
    assertNothingWasTouched();
  }

  @Test
  public void aStoppedPlaceIsNotDemotedByEveryLaterPassEither() {
    String container = "qits-pd-prod-obs-stays-paused";
    String active =
        deployment(
            "obs-stays-paused", "env-obs-stays-paused", PdDeploymentStatus.ACTIVE, container, null);
    driver.scriptObservationGone(container, "no such service");
    driver.scriptDeclaredReplicas(container, 0);

    observer.observeOnce();
    observer.observeOnce();
    observer.observeOnce();

    // Two consecutive dead passes are what demotes an ACTIVE row, and a paused place would reach
    // that count on its second tick. The strike is cleared instead, so a pause lasts as long as the
    // operator wants it to.
    assertEquals(PdDeploymentStatus.SCALED_TO_ZERO.name(), statusOf(active));
  }

  @Test
  public void aRuntimeThatCannotSayHowManyTasksAreDeclaredStillDemotes() {
    // The safety of the whole arm: an unreadable answer read as a deliberate zero would turn every
    // real outage into a pause nobody is ever paged for. Nothing scripts a count here, which is
    // exactly what a daemon that could not answer looks like.
    String container = "qits-pd-prod-obs-unanswerable";
    String active =
        deployment(
            "obs-unanswerable", "env-obs-unanswerable", PdDeploymentStatus.ACTIVE, container, null);
    driver.scriptObservationGone(container, "Error: No such object");

    observer.observeOnce();
    observer.observeOnce();

    assertEquals(PdDeploymentStatus.GONE.name(), statusOf(active));
  }

  @Test
  public void scalingBackUpTakesAStoppedRowBackThroughTheOrdinaryRecoveryArm() {
    String container = "qits-pd-prod-obs-resumed";
    String stopped =
        deployment(
            "obs-resumed",
            "env-obs-resumed",
            PdDeploymentStatus.SCALED_TO_ZERO,
            container,
            "[scaled to 0 by alice at t0: the deployment is otherwise untouched]");
    driver.scriptObservation(container, "running/healthy");

    observer.observeOnce();

    PdDeployment row = rowOf(stopped);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertTrue(row.detail.contains("recovered by observation"), row.detail);
    // The pause stamp is kept under the recovery, exactly as a failure's diagnosis is: a reader
    // asking "why was this down" gets an answer rather than a blank.
    assertTrue(row.detail.contains("scaled to 0 by alice"), row.detail);
  }

  /**
   * The reaping stance, asserted rather than trusted: the observer writes rows and touches no
   * service. It is the startup sweep's rule, and it matters more here — this runs forever, beside a
   * live platform.
   */
  private void assertNothingWasTouched() {
    assertEquals(List.of(), driver.applied(), "nothing was applied");
    assertEquals(List.of(), driver.reaped(), "nothing was reaped");
    assertEquals(List.of(), driver.removedNetworks(), "and no network was touched");
  }

  /** Drain the worker — the pass is queued behind the event, so idle means both are done. */
  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }
}
