package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdOwedReleaseRepository;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A release a restart interrupted is deployed by the successor, not settled as already done</b>
 * (qits-1803).
 *
 * <p>The defect, as it was measured. qits-deployments' single FIFO worker applies its own
 * self-update ({@code HANDED_OFF}), moves straight on to the next queued release, writes that
 * release's request and its {@code QUEUED} deployment — and swarm replaces the process mid-way. The
 * successor boots and runs the two sweeps in their fixed order: {@code sweepInFlight()} settles the
 * dead process's row {@code FAILED}, and {@link OwedReleaseSweep} finds the obligation nobody settled
 * and asks whether the version was already requested. It was — the dead worker wrote the request
 * first thing — so the obligation was settled {@code ALREADY_REQUESTED}, the version never deployed,
 * and the release request's gate waited for a {@code DeploymentActive} that nothing would ever
 * publish. Even past that guard, {@link ReleaseTips#claim} refused it: the floor it reads off the
 * request rows was this very version.
 *
 * <p><b>A dead process is staged by writing its rows</b>, {@link PdOwedReleaseTest}'s arrangement:
 * an obligation stamped with somebody else's instance id, the request the dead worker wrote, and the
 * deployment row it left in flight. Then the two sweeps the successor runs at boot, in that order.
 *
 * <p><b>The bus is on and aimed at a closed port</b> — {@link PdSweepAdoptionPublishTest}'s profile,
 * reused so the two classes share one application — because the whole point of the re-drive is the
 * {@code DeploymentActive} a release request's gate is waiting on, and an outbox row IS the publish
 * from this side of the bus.
 *
 * <p>Every method uses its own tier and its own application: {@link ReleaseTips} remembers per
 * application for the life of the process, and the sweeps read every row in the database.
 */
@QuarkusTest
@TestProfile(PdSweepAdoptionPublishTest.BusEnabled.class)
public class PdInterruptedReleaseRedriveTest {

  private static final String OLDER = "2026.1101.93059";

  private static final String VERSION = "2026.1101.193059";

  private static final String NEWER = "2026.1101.203059";

  private static final String STORAGE_UUID = "7d1e3c52-0a41-4c8e-9b6f-21c4a8e0d913";

  /** The instance id of a process that is not this one — the whole of "orphaned". */
  private static final String A_DEAD_PROCESS = "00000000-dead-dead-dead-000000001803";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject OwedReleaseSweep sweep;
  @Inject PdOwedReleaseRepository owed;
  @Inject PdDeploymentRepository deployments;
  @Inject PdDeploymentRequestRepository requests;

  /** Read through the eventstream unit's own EntityManager — see {@code PdDeployPublishTest}. */
  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  // --- the interruption is re-driven --------------------------------------------------------------

  @Test
  public void aReleaseLeftQueuedByASelfUpdateIsDeployedByTheSuccessor() {
    // THE MEASURED CASE: the worker had written the request and the QUEUED row, and nothing else.
    assertTheSuccessorDeploysIt("intr-queued", PdDeploymentStatus.QUEUED, null);
  }

  @Test
  public void aReleaseLeftStartingWithNoNameIsDeployedByTheSuccessor() {
    // The worker had moved the row to STARTING — provisioning, or the pull, which is the long step —
    // but had not yet asked the runtime what the service will be called.
    assertTheSuccessorDeploysIt("intr-starting", PdDeploymentStatus.STARTING, null);
  }

  @Test
  public void aReleaseLeftStartingUnderANameNothingRunsIsDeployedByTheSuccessor() {
    // The other arm of the same verdict: a name, and no such service. Nothing is known to serve the
    // place, so nothing decided this deployment either.
    assertTheSuccessorDeploysIt("intr-vanished", PdDeploymentStatus.STARTING, "vanished-intr");
  }

  // --- what an interruption still must not cause -------------------------------------------------

  @Test
  public void aNewerRequestedVersionStillBlocksTheReDrive() {
    // The rollback guard survives the change. OLDER was interrupted, but NEWER has been requested
    // since, so deploying OLDER now would roll the application back — the one thing a re-drive must
    // never be able to cause, interrupted or not.
    String environmentId = createEntryTier("intr-newer");
    String application = "intr-newer-app";
    String interrupted =
        deadProcessLeft(application, OLDER, environmentId, PdDeploymentStatus.QUEUED, null);
    request(application, NEWER, environmentId, null);
    String eventId = orphan(application, OLDER);

    deployService.sweepInFlight();
    assertTrue(interruptedOf(interrupted));
    sweep.sweep();
    awaitWorkerIdle();

    assertEquals(List.of(), appliedFor(application), "no older version rolled over the newer one");
    assertEquals(1, deploymentsOf(application).size(), "and no deployment was queued for it");
    assertEquals(ReleaseAcceptance.Outcome.SUPERSEDED.name(), obligation(eventId).outcome);
  }

  @Test
  public void aRequestThatReallyFailedIsStillAlreadyRequested() {
    // The guard's ordinary job, unchanged: a deployment that ran and FAILED is a decision a person is
    // looking at, and a sweep that retried it would be a second deployment behind their back. Only
    // the interrupted word is owed work.
    String environmentId = createEntryTier("intr-decided");
    String application = "intr-decided-app";
    deadProcessLeft(application, VERSION, environmentId, PdDeploymentStatus.FAILED, null);
    String eventId = orphan(application, VERSION);

    deployService.sweepInFlight();
    sweep.sweep();
    awaitWorkerIdle();

    assertEquals(List.of(), appliedFor(application));
    assertEquals(1, deploymentsOf(application).size());
    assertEquals(ReleaseAcceptance.Outcome.ALREADY_REQUESTED.name(), obligation(eventId).outcome);
  }

  @Test
  public void oneDecidedPlaceIsEnoughToMeetTheRequest() {
    // A release asked for in two places, one of which went live and one of which a restart cut off.
    // The release was not lost — the decided place is the evidence — so the obligation is met, as
    // it always was. Re-driving would redeploy the live place too.
    String environmentId = createEntryTier("intr-mixed");
    String application = "intr-mixed-app";
    deadProcessLeft(application, VERSION, environmentId, PdDeploymentStatus.ACTIVE, "live-mixed");
    deadProcessLeft(application, VERSION, "intr-mixed-other-tier", PdDeploymentStatus.QUEUED, null);
    String eventId = orphan(application, VERSION);

    deployService.sweepInFlight();
    sweep.sweep();
    awaitWorkerIdle();

    assertEquals(List.of(), appliedFor(application));
    assertEquals(ReleaseAcceptance.Outcome.ALREADY_REQUESTED.name(), obligation(eventId).outcome);
  }

  // --- the shared story --------------------------------------------------------------------------

  private void assertTheSuccessorDeploysIt(
      String tier, PdDeploymentStatus leftIn, String containerName) {
    String environmentId = createEntryTier(tier);
    String application = tier + "-app";
    String leftBehind =
        deadProcessLeft(application, VERSION, environmentId, leftIn, containerName);
    String eventId = orphan(application, VERSION);

    // The successor's boot, in the order the two observers run.
    deployService.sweepInFlight();

    assertEquals(PdDeploymentStatus.FAILED, deployment(leftBehind).status);
    assertTrue(interruptedOf(leftBehind), "the sweep marked what it could not decide");

    assertEquals(1, sweep.sweep(), "the interrupted release was announced again");
    awaitWorkerIdle();

    List<PdDeployment> rows = deploymentsOf(application);
    assertEquals(2, rows.size(), "exactly one new deployment beside the interrupted one: " + rows);
    PdDeployment redriven = rows.get(0);
    assertNotEquals(leftBehind, redriven.id);
    assertEquals(VERSION, redriven.version, "the same version, not a newer or an older one");
    assertEquals(PdDeploymentStatus.ACTIVE, redriven.status, redriven.detail);
    assertFalse(redriven.interrupted);
    assertEquals(PdDeploymentStatus.FAILED, deployment(leftBehind).status, "history is kept");

    assertEquals(1, appliedFor(application).size(), "deployed once");
    assertTrue(
        appliedFor(application).get(0).imageRef().endsWith("/" + application + ":" + VERSION),
        appliedFor(application).get(0).imageRef());

    PdOwedRelease obligation = obligation(eventId);
    assertEquals(ReleaseAcceptance.Outcome.DISCHARGED.name(), obligation.outcome);
    assertEquals(2, obligation.attempts, "the dead process's attempt and this one's, on one row");

    // WHAT THE GATE WAS WAITING FOR: the ordinary announcement of an ordinary deployment.
    List<OutboxEvent> active =
        outboxRows("DeploymentActive").stream()
            .filter(row -> row.payload.contains("\"deploymentId\":\"" + redriven.id + "\""))
            .toList();
    assertEquals(1, active.size(), "one DeploymentActive for the re-driven deployment");
    assertTrue(
        active.get(0).payload.contains("\"applicationName\":\"" + application + "\""),
        active.get(0).payload);

    // And it is settled: a second pass reads nothing for it.
    driver.reset();
    sweep.sweep();
    awaitWorkerIdle();
    assertEquals(List.of(), appliedFor(application), "a settled obligation is never re-driven");
    assertEquals(2, deploymentsOf(application).size());
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * What the dead worker wrote for one place before it died: the request and, in the same
   * transaction ({@code DeployService.queue}'s), the deployment it points at. Returns the
   * deployment's id.
   */
  private String deadProcessLeft(
      String application,
      String version,
      String environmentId,
      PdDeploymentStatus status,
      String containerName) {
    String deploymentId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = deploymentId;
              row.applicationName = application;
              row.environmentId = environmentId;
              row.version = version;
              row.status = status;
              row.containerName = containerName;
              row.createdAt = Instant.now();
              if (status != PdDeploymentStatus.QUEUED && status != PdDeploymentStatus.STARTING) {
                row.finishedAt = Instant.now();
              }
              deployments.persist(row);
            });
    request(application, version, environmentId, deploymentId);
    return deploymentId;
  }

  private void request(
      String application, String version, String environmentId, String deploymentId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeploymentRequest request = new PdDeploymentRequest();
              request.id = UUID.randomUUID().toString();
              request.applicationName = application;
              request.version = version;
              request.environmentId = environmentId;
              request.packageName = "qits/" + application;
              request.repoId = STORAGE_UUID;
              request.qualityGate = PdQualityGate.MET;
              request.gateSettledAt = Instant.now();
              request.deploymentId = deploymentId;
              request.createdAt = Instant.now();
              requests.persist(request);
            });
  }

  /** The obligation a process that died inside {@code deploy()} never settled. */
  private String orphan(String application, String version) {
    String eventId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdOwedRelease row = new PdOwedRelease();
              row.id = UUID.randomUUID().toString();
              row.eventId = eventId;
              row.applicationName = application;
              row.version = version;
              row.packageName = "qits/" + application;
              row.repoId = STORAGE_UUID;
              row.acceptedBy = A_DEAD_PROCESS;
              row.acceptedAt = Instant.now();
              row.attempts = 1;
              owed.persist(row);
            });
    return eventId;
  }

  private PdOwedRelease obligation(String eventId) {
    return QuarkusTransaction.requiringNew().call(() -> owed.findByEventId(eventId).orElse(null));
  }

  private PdDeployment deployment(String deploymentId) {
    return QuarkusTransaction.requiringNew().call(() -> deployments.findById(deploymentId));
  }

  private boolean interruptedOf(String deploymentId) {
    return deployment(deploymentId).interrupted;
  }

  /** Newest first, so the first row is the re-drive's. */
  private List<PdDeployment> deploymentsOf(String application) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.listByApplicationNewestFirst(application));
  }

  private List<DeploymentDriver.ServiceSpec> appliedFor(String application) {
    return driver.applied().stream()
        .filter(spec -> application.equals(spec.applicationName()))
        .toList();
  }

  private List<OutboxEvent> outboxRows(String name) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o where o.name = :name", OutboxEvent.class)
                    .setParameter("name", name)
                    .getResultList());
  }

  /** Created as the designated tier, so the re-drive's release enters here. */
  private String createEntryTier(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }
}
