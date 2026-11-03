package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.deployments.bus.PdSoftwareReleaseSubscriber;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdOwedReleaseRepository;
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
 * <b>Acceptance is durable: a release accepted from the bus is deployed even if the process that
 * accepted it dies before deploying it.</b>
 *
 * <p>The defect this holds shut, measured on 2026-09-05. qits-eventstream claims a {@code
 * SoftwareRelease} in {@code consumed_event} the moment the subscriber's handler returns — and the
 * handler returns as soon as the release is a {@code Runnable} on the in-memory {@code
 * pd-deploy-worker} queue, which is serialized platform-wide, is legitimately an hour deep, and is
 * discarded by {@code @PreDestroy}. So every cutover of this component (which it performs on
 * itself, {@code stop-first}) dropped the whole queue while the bus's ledger correctly said the
 * events had been handled: the successor's catch-up found the claims and skipped them, and no
 * deployment request was ever written. Seven applications went that way between 13:00 and 17:15
 * UTC; four had to be replayed by hand.
 *
 * <p><b>A dead process is staged by writing its row, not by killing a JVM.</b> The whole re-drive
 * predicate is {@code accepted_by}: an obligation stamped with an instance id that is not this
 * process's belonged to a queue that did not survive. A test writes exactly that row — which is the
 * state a crash leaves, byte for byte — and then runs the sweep the successor runs at boot.
 *
 * <p>Every method creates its own environment and releases its own application, because the suite
 * shares one database across classes and {@link ReleaseTips} keeps a per-process memory of what it
 * announced. Each environment is created as the PLATFORM one, which moves the designation and makes
 * it the tier the method's releases enter at.
 */
@QuarkusTest
public class PdOwedReleaseTest {

  /** The pair every ordering case here uses; unpadded, so a lexical compare gets them backwards. */
  private static final String OLDER = "2026.903.93059";

  private static final String NEWER = "2026.903.193059";

  private static final String STORAGE_UUID = "2c8f6a41-0d32-4e66-9a1b-88bb4d3f2c15";

  /** The instance id of a process that is not this one — the whole of "orphaned". */
  private static final String A_DEAD_PROCESS = "00000000-dead-dead-dead-000000000000";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject OwedReleaseSweep sweep;
  @Inject ReleaseAcceptance acceptance;
  @Inject PdOwedReleaseRepository owed;
  @Inject PdDeploymentRequestRepository requests;
  @Inject PdSoftwareReleaseSubscriber subscriber;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  // --- the door writes the obligation down, and the worker discharges it -------------------------

  @Test
  public void aReleaseTakenOffTheBusIsRecordedAsOwedAndSettledWhenItHasDeployed() {
    // The mechanism itself: the bus door announces DURABLY, so between the claim committing and
    // the container coming up there is a row that says this release is somebody's responsibility.
    createEntryTier("owed-door");
    String eventId = UUID.randomUUID().toString();

    subscriber.onFrame(dockerFrame(eventId, "qits/owed-door-app", NEWER));
    awaitApplied(1);
    awaitWorkerIdle();

    PdOwedRelease row = obligation(eventId);
    assertNotNull(row, "the bus door recorded the acceptance before it queued anything");
    assertEquals("owed-door-app", row.applicationName);
    assertEquals(NEWER, row.version);
    assertEquals(STORAGE_UUID, row.repoId);
    assertEquals(acceptance.instanceId(), row.acceptedBy, "this process took it up");
    assertEquals(1, row.attempts);
    assertNotNull(row.settledAt, "and discharged it once the deployment had run");
    assertEquals(ReleaseAcceptance.Outcome.DISCHARGED.name(), row.outcome);
  }

  @Test
  public void anObligationThisProcessIsHoldingIsNeverReDriven() {
    // The other half of the predicate, and the one that makes an hour-deep queue safe: a row this
    // process accepted is either queued or deploying right now. A time-based sweep could not tell
    // that from an orphan without a grace period longer than the outage it is meant to cover.
    createEntryTier("owed-held");
    String eventId = UUID.randomUUID().toString();
    acceptance.accept(
        new ReleaseAcceptance.Accepted(
            eventId,
            null,
            new RepositoryRef(STORAGE_UUID, "qits", "owed-held-repo"),
            "owed-held-app",
            NEWER,
            "qits/owed-held-app",
            null,
            null));

    assertEquals(0, sweep.sweep(), "nothing was orphaned");
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "the sweep did not deploy behind the worker's back");
    assertNull(obligation(eventId).settledAt, "and it left the obligation alone");
  }

  // --- the recovery ------------------------------------------------------------------------------

  @Test
  public void aReleaseAcceptedByADeadProcessIsReDrivenAndMintsItsDeploymentRequest() {
    // THE DEFECT, held shut. The previous container claimed the event, queued the release and was
    // stopped before the worker reached it. Nothing on the bus will ever offer that event again.
    String environmentId = createEntryTier("owed-lost");
    String eventId = orphan("owed-lost-app", NEWER, "qits/owed-lost-app");

    assertEquals(1, sweep.sweep(), "the successor re-drove the release nobody deployed");
    awaitApplied(1);

    assertEquals("owed-lost-app", driver.applied().get(0).applicationName());
    assertTrue(
        driver.applied().get(0).imageRef().endsWith("/owed-lost-app:" + NEWER),
        driver.applied().get(0).imageRef());

    PdDeploymentRequest request = newestRequest("owed-lost-app");
    assertNotNull(request, "the request the lost release never wrote now exists");
    assertEquals(NEWER, request.version);
    assertEquals(environmentId, request.environmentId);
    assertEquals(PdQualityGate.MET, request.qualityGate);
    assertNotNull(request.deploymentId);

    awaitWorkerIdle();
    PdOwedRelease row = obligation(eventId);
    assertEquals(ReleaseAcceptance.Outcome.DISCHARGED.name(), row.outcome);
    assertEquals(acceptance.instanceId(), row.acceptedBy, "this process took the obligation over");
    assertEquals(2, row.attempts, "the dead process's attempt and this one's");
  }

  @Test
  public void theObligationCarriesThePriorityAndAReDriveDeploysWithIt() {
    // WHY pd_owed_release HAS THE COLUMN AT ALL. A re-drive rebuilds the whole announcement from
    // this row — another process, after a cutover, with the event already claimed on the bus and
    // nothing left to ask — so a field the ledger does not store is a field the recovered release
    // silently deploys without. Both halves in one case: the door writes it down, and the sweep
    // hands exactly that value to the request row the lost release never wrote.
    createEntryTier("owed-priority");
    String eventId = UUID.randomUUID().toString();

    subscriber.onFrame(dockerFrame(eventId, "qits/owed-priority-app", NEWER, "BLOCKING"));
    awaitApplied(1);
    awaitWorkerIdle();

    assertEquals("BLOCKING", obligation(eventId).priority, "the acceptance recorded it");
    assertEquals("BLOCKING", newestRequest("owed-priority-app").priority);

    // And the recovery path, off a row a dead process left behind.
    createEntryTier("owed-priority-redrive");
    String orphaned =
        orphan(
            "owed-priority-redrive-app",
            NEWER,
            "qits/owed-priority-redrive-app",
            RepositoryRef.ofId(STORAGE_UUID),
            1,
            "HIGHER");

    assertEquals(1, sweep.sweep());
    awaitApplied(2);
    awaitWorkerIdle();

    assertEquals(
        "HIGHER",
        newestRequest("owed-priority-redrive-app").priority,
        "the re-driven release deployed with the priority the lost one carried");
    assertEquals(ReleaseAcceptance.Outcome.DISCHARGED.name(), obligation(orphaned).outcome);
  }

  @Test
  public void theReDriveGoesThroughTheReleaseEventDoorRatherThanAroundIt() {
    // A re-drive is an ordinary announcement and not a bypass: same spec read, at the released tag,
    // through the address the obligation recorded.
    createEntryTier("owed-route");
    orphan(
        "owed-route-app",
        NEWER,
        "qits/owed-route-app",
        new RepositoryRef(STORAGE_UUID, "qits", "owed-route-repo"));

    sweep.sweep();
    awaitApplied(1);

    // Keyed by RepositoryRef#applicationName(), which is the repository's NAME when the ref is
    // name-addressed — the fake records what the real GitHostSpecSource composes its URL from.
    assertEquals("refs/tags/" + NEWER, specs.revOf("owed-route-repo"));
    assertEquals(
        new RepositoryRef(STORAGE_UUID, "qits", "owed-route-repo"),
        specs.refOf("owed-route-repo"),
        "the whole address the release carried, re-driven verbatim");
  }

  // --- what a re-drive must never do -------------------------------------------------------------

  @Test
  public void aReDriveOfAVersionThatWasAlreadyRequestedMintsNoSecondRequest() {
    // The process died AFTER writing the request row — between that write and the settle, or during
    // the cutover itself. The obligation is still open and the work is already done, so announcing
    // it again would be a second deployment of one version.
    String environmentId = createEntryTier("owed-dup");
    postRelease("owed-dup-app", NEWER);
    awaitDeployments(environmentId, 1);
    driver.reset();
    String eventId = orphan("owed-dup-app", NEWER, "qits/owed-dup-app");

    assertEquals(0, sweep.sweep(), "nothing was announced");
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "and nothing was deployed a second time");
    assertEquals(
        1,
        requestCount("owed-dup-app", NEWER),
        "exactly one request for (application, version), as before the re-drive");
    assertEquals(ReleaseAcceptance.Outcome.ALREADY_REQUESTED.name(), obligation(eventId).outcome);
  }

  @Test
  public void aReDriveOfAStaleReleaseIsSupersededRatherThanRolledBack() {
    // The one thing a re-drive must never be able to cause. The obligation was accepted hours ago,
    // a newer version has been deployed since, and replaying it would roll the platform back.
    String environmentId = createEntryTier("owed-stale");
    postRelease("owed-stale-app", NEWER);
    awaitDeployments(environmentId, 1);
    driver.reset();
    String eventId = orphan("owed-stale-app", OLDER, "qits/owed-stale-app");

    assertEquals(0, sweep.sweep(), "the stale release was not announced");
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "and no older version rolled over the newer one");
    assertEquals(NEWER, newestRequest("owed-stale-app").version);
    assertEquals(ReleaseAcceptance.Outcome.SUPERSEDED.name(), obligation(eventId).outcome);
  }

  @Test
  public void aReDrivenReleaseThatDeclaresNoSpecIsRefusedExactlyAsTheLiveDoorRefusesIt() {
    // The intake rules are the door's, and a re-drive is the same door. A release whose tag carries
    // no deployments.yml published an image and never asked to be a service — recorded, not
    // deployed — and going around that on the recovery path would launch the workspace container
    // images this rule exists to keep out.
    createEntryTier("owed-nospec");
    // Scripted under the REPOSITORY's name, which is what the spec read is addressed by; the
    // application the release deploys as comes out of the package and is a different string.
    specs.scriptNoSpec("owed-nospec-repo");
    String eventId =
        orphan(
            "owed-nospec-app",
            NEWER,
            "qits/owed-nospec-app",
            new RepositoryRef(STORAGE_UUID, "qits", "owed-nospec-repo"));

    assertEquals(1, sweep.sweep(), "it was announced — the refusal is the door's, not the sweep's");
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "and nothing was launched");
    PdDeploymentRequest request = newestRequest("owed-nospec-app");
    assertNotNull(request, "the refusal is recorded where a person looks");
    assertEquals(PdQualityGate.UNMET, request.qualityGate);
    assertNull(request.deploymentId);
    assertEquals(ReleaseAcceptance.Outcome.DISCHARGED.name(), obligation(eventId).outcome);
  }

  @Test
  public void anObligationThatHasBeenTakenUpTooOftenIsParkedRatherThanReDrivenForever() {
    // The poison-event shape the bus library warns about, one layer down: an obligation that fails
    // identically on every boot would otherwise be announced again at every boot forever.
    createEntryTier("owed-poison");
    String eventId =
        orphan(
            "owed-poison-app",
            NEWER,
            "qits/owed-poison-app",
            new RepositoryRef(STORAGE_UUID, null, null),
            ReleaseAcceptance.MAX_ATTEMPTS);

    assertEquals(0, sweep.sweep());
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied());
    assertEquals(ReleaseAcceptance.Outcome.EXHAUSTED.name(), obligation(eventId).outcome);
  }

  @Test
  public void aSecondSweepOverASettledObligationDoesNothing() {
    // Settled is terminal: the row stays as the account of what was accepted, and no later sweep
    // reads it as work. Without this a discharged obligation would redeploy on every tick.
    createEntryTier("owed-terminal");
    orphan("owed-terminal-app", NEWER, "qits/owed-terminal-app");

    sweep.sweep();
    awaitApplied(1);
    awaitWorkerIdle();
    driver.reset();

    assertEquals(0, sweep.sweep());
    awaitWorkerIdle();
    assertEquals(List.of(), driver.applied());
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** The row a process that died holding an obligation leaves behind. Returns its event id. */
  private String orphan(String applicationName, String version, String packageName) {
    return orphan(applicationName, version, packageName, RepositoryRef.ofId(STORAGE_UUID));
  }

  private String orphan(
      String applicationName, String version, String packageName, RepositoryRef repository) {
    return orphan(applicationName, version, packageName, repository, 1);
  }

  private String orphan(
      String applicationName,
      String version,
      String packageName,
      RepositoryRef repository,
      int attempts) {
    return orphan(applicationName, version, packageName, repository, attempts, null);
  }

  private String orphan(
      String applicationName,
      String version,
      String packageName,
      RepositoryRef repository,
      int attempts,
      String priority) {
    String eventId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdOwedRelease row = new PdOwedRelease();
              row.id = UUID.randomUUID().toString();
              row.eventId = eventId;
              row.applicationName = applicationName;
              row.version = version;
              row.packageName = packageName;
              row.priority = priority;
              row.repoId = repository.repoId();
              row.projectId = repository.projectId();
              row.repoName = repository.repoName();
              row.acceptedBy = A_DEAD_PROCESS;
              row.acceptedAt = Instant.now();
              row.attempts = attempts;
              owed.persist(row);
            });
    return eventId;
  }

  private PdOwedRelease obligation(String eventId) {
    return QuarkusTransaction.requiringNew().call(() -> owed.findByEventId(eventId).orElse(null));
  }

  private static EventFrame dockerFrame(String eventId, String packageName, String version) {
    return dockerFrame(eventId, packageName, version, null);
  }

  /** Canonical JSON is alphabetical and NON_NULL, so a null priority is an absent key. */
  private static EventFrame dockerFrame(
      String eventId, String packageName, String version, String priority) {
    return new EventFrame(
        eventId,
        "SoftwareRelease",
        Instant.now(),
        ("{\"packageName\":\"%s\",\"packageType\":\"docker\",%s\"projectId\":\"qits\","
                + "\"repoId\":\"%s\",\"repository\":\"%s\",\"version\":\"%s\"}")
            .formatted(
                packageName,
                priority == null ? "" : "\"priority\":\"" + priority + "\",",
                STORAGE_UUID,
                STORAGE_UUID,
                version),
        null,
        null,
        null);
  }

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

  private void postRelease(String application, String version) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "repoId", STORAGE_UUID,
                "application", application,
                "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private PdDeploymentRequest newestRequest(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                requests.listByApplicationNewestFirst(applicationName).stream()
                    .findFirst()
                    .orElse(null));
  }

  private long requestCount(String applicationName, String version) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                requests.listByApplicationNewestFirst(applicationName).stream()
                    .filter(request -> version.equals(request.version))
                    .count());
  }

  private void awaitApplied(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.applied().size() < count && System.currentTimeMillis() < deadline) {
      sleep();
    }
    assertEquals(count, driver.applied().size(), "applied services");
  }

  private void awaitDeployments(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments =
          given()
              .header("Authorization", SuiteBearers.operator())
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      if (deployments.size() == count
          && deployments.stream()
              .noneMatch(
                  d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")))) {
        return;
      }
      sleep();
    }
    throw new IllegalStateException(
        "deployments of " + environmentId + " did not settle to " + count);
  }

  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
