package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The startup sweep settles an in-flight row from what is RUNNING, package-local so the suite can
 * drive {@link DeployService#sweepInFlight()} without a real StartupEvent.
 *
 * <p>That is the half of a self-update no orchestrator does for us: the instance that deployed
 * itself died before it could record the outcome, so the next one asks what the service is running
 * and compares it with the row's own IMAGE TAG — the released version, and the commit sha behind
 * it on a row written before versions were the coordinate. Carrying that tag is this deployment
 * serving; carrying a NEWER version is a later deployment; anything else under the name — an older
 * version, a sha, nothing at all — is a row a restart interrupted (qits-2306).
 *
 * <p><b>Most rows here are written the LEGACY way, with a sha and no version</b>, and that is
 * deliberate: it is the arm that says a row predating the version column still adopts rather than
 * coming back SUPERSEDED by its own successor. {@link #aRowDeployedByAReleaseIsAdoptedOnItsVersion}
 * is the other arm.
 *
 * <p>The rows are written straight to the table rather than through an environment, and that is the
 * point since the extraction: the sweep must read nothing but this component's own columns.
 */
@QuarkusTest
public class PdSweepAdoptionTest {

  private static final String SHA = "c".repeat(40);
  private static final String OTHER_SHA = "d".repeat(40);

  /** What a release-era row records — and therefore what its image is tagged with. */
  private static final String VERSION = "2026.903.113443";

  /** A release after {@link #VERSION} — and lexically BEFORE it ("1…" < "9…"), deliberately. */
  private static final String NEWER = "2026.1001.10000";

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver driver;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  private static String image(String tag) {
    return "registry.qits.dev.localhost:8080/qits/qits-platform-deployments:" + tag;
  }

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.commitSha = SHA;
              row.status = status;
              row.containerName = containerName;
              row.createdAt = Instant.now();
              if (status == PdDeploymentStatus.ACTIVE) {
                row.finishedAt = Instant.now();
              }
              deployments.persist(row);
            });
    return id;
  }

  /** A release-era row: its version is its image tag, the commit sha beside it as ever. */
  private String versioned(
      String environmentId, PdDeploymentStatus status, String containerName, String version) {
    String id = deployment("qits-platform-deployments", environmentId, status, containerName);
    QuarkusTransaction.requiringNew().run(() -> deployments.findById(id).version = version);
    return id;
  }

  private String statusOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).status.name());
  }

  private String detailOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).detail);
  }

  private boolean interruptedOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).interrupted);
  }

  @Test
  public void aStartingRowWhoseServiceRunsItsShaIsAdoptedAndItsPredecessorDecommissioned() {
    String environmentId = "env-sweep-adopt";
    String predecessor =
        deployment("qits-platform-deployments", environmentId, PdDeploymentStatus.ACTIVE, "old-cd");
    String handedOff =
        deployment("qits-platform-deployments", environmentId, PdDeploymentStatus.STARTING, "new-cd");
    driver.scriptRunningImage("new-cd", image(SHA));

    deployService.sweepInFlight();

    assertEquals("ACTIVE", statusOf(handedOff));
    assertEquals("DECOMMISSIONED", statusOf(predecessor));
    assertTrue(detailOf(handedOff).contains("adopted at startup"), detailOf(handedOff));
    assertFalse(interruptedOf(handedOff), "an adoption is a decision, not an interruption");
  }

  @Test
  public void aPlatformRowIsAdoptedToo() {
    // A platform row carries no environment, and the predecessor lookup has to treat that null as
    // a value rather than as "any tier" — nulls are distinct to `=`, so the query tests for null
    // explicitly. Getting it wrong means this component's own self-update comes back having failed
    // its own deployment while a second row still claims to be ACTIVE.
    String predecessor = deployment("qits-platform-deployments", null, PdDeploymentStatus.ACTIVE, "old-single");
    String otherTier =
        deployment("qits-platform-deployments", "some-tier", PdDeploymentStatus.ACTIVE, "another-tiers-cd");
    String handedOff = deployment("qits-platform-deployments", null, PdDeploymentStatus.STARTING, "new-single");
    driver.scriptRunningImage("new-single", image(SHA));

    deployService.sweepInFlight();

    assertEquals("ACTIVE", statusOf(handedOff));
    assertEquals("DECOMMISSIONED", statusOf(predecessor));
    assertEquals("ACTIVE", statusOf(otherTier), "another tier's copy is not this plane's to retire");
  }

  @Test
  public void aRowDeployedByAReleaseIsAdoptedOnItsVersion() {
    // The release-era arm. The row records a version, the image is tagged with it, and the sweep
    // has to compare THAT rather than the commit sha beside it — a self-update that compared the
    // sha would find `qits/...:2026.903.113443` carrying no such string and come back SUPERSEDED by
    // its own successor, which is the failure this component's own deployment would hit first.
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = "qits-platform-deployments";
              row.environmentId = "env-sweep-version";
              row.version = VERSION;
              // The commit the tag resolved to, deliberately DIFFERENT from the tag: a sweep that
              // read this column would compare the wrong string and still be green if the two
              // happened to match.
              row.commitSha = SHA;
              row.status = PdDeploymentStatus.STARTING;
              row.containerName = "new-release";
              row.createdAt = Instant.now();
              deployments.persist(row);
            });
    driver.scriptRunningImage("new-release", image(VERSION));

    deployService.sweepInFlight();

    assertEquals("ACTIVE", statusOf(id));
    assertTrue(detailOf(id).contains("adopted at startup"), detailOf(id));
  }

  @Test
  public void aStartingRowWhoseServiceRunsANewerVersionIsSuperseded() {
    // A later deployment took the place while this one was in flight: the service is there and
    // healthy, and it runs a version that orders AFTER this row's. Adopting it would record a
    // succession that did not happen; re-driving this row would roll the place back.
    String superseded =
        versioned("env-sweep-superseded", PdDeploymentStatus.STARTING, "overtaken", VERSION);
    driver.scriptRunningImage("overtaken", image(NEWER));

    deployService.sweepInFlight();

    assertEquals(
        "SUPERSEDED",
        statusOf(superseded),
        "a newer version is serving this place, which is the one interrupted row that is not a"
            + " FAILED");
    String detail = detailOf(superseded);
    assertTrue(detail.contains("superseded"), detail);
    assertTrue(detail.contains(image(NEWER)), detail);
    assertFalse(
        interruptedOf(superseded),
        "something newer serves the place, which is a decision — the owed sweep must not re-drive"
            + " a release over it");
  }

  @Test
  public void aStartingRowWhoseServiceStillRunsAnOlderVersionIsInterruptedNotSuperseded() {
    // qits-2306, the live values: a swarm service keeps its name, so what runs under it after the
    // dead process is the PREDECESSOR it never finished replacing. Lexically "7…" sorts after
    // "1…", so only a numeric order reads 2026.1116.71506 as the older one.
    String row =
        versioned("env-sweep-predecessor", PdDeploymentStatus.STARTING, "dev-docs", "2026.1116.161443");
    String predecessor = image("2026.1116.71506");
    driver.scriptRunningImage("dev-docs", predecessor);

    deployService.sweepInFlight();

    assertEquals("FAILED", statusOf(row), "nothing newer serves the place; this deployment is owed");
    assertTrue(
        interruptedOf(row),
        "the marker the owed sweep re-drives on — SUPERSEDED here stranded the release");
    String detail = detailOf(row);
    assertTrue(detail.contains(predecessor), detail);
    assertFalse(detail.contains("superseded"), detail);
  }

  @Test
  public void aShaRowWhoseServiceRunsAnotherShaCannotBeOrderedAndIsInterrupted() {
    // A row written before versions were the coordinate has nothing to order against: another sha
    // could be the predecessor as easily as a successor, so it is the interruption, which a
    // re-drive resolves, and never the SUPERSEDED that settles the release for good.
    String row =
        deployment(
            "qits-platform-deployments", "env-sweep-sha", PdDeploymentStatus.STARTING, "sha-place");
    driver.scriptRunningImage("sha-place", image(OTHER_SHA));

    deployService.sweepInFlight();

    assertEquals("FAILED", statusOf(row));
    assertTrue(interruptedOf(row));
    assertTrue(detailOf(row).contains(OTHER_SHA), detailOf(row));
  }

  @Test
  public void aStartingRowWithNoSuccessorStillFails() {
    String interrupted =
        deployment("qits-platform-deployments", "env-sweep-fail", PdDeploymentStatus.STARTING, "vanished");
    // Nothing scripted for "vanished": the runtime has no such service, so there is no evidence
    // that this deployment ever got anywhere.

    deployService.sweepInFlight();

    assertEquals(
        "FAILED",
        statusOf(interrupted),
        "nothing is known to serve the place, so this is the narrowed FAILED and not SUPERSEDED");
    assertEquals("[interrupted by a qits-deployments restart]", detailOf(interrupted));
    // THE MARKER, which is what the owed sweep reads — never the sentence above, which is prose and
    // has been reworded once already. Without it the release this row was performing is settled
    // ALREADY_REQUESTED by its own request row and never deployed (qits-1803).
    assertTrue(interruptedOf(interrupted), "nothing decided this deployment; the row says so");
  }

  @Test
  public void aQueuedRowIsFailedWithoutAskingTheRuntimeAnything() {
    // It never reached a name, so there is nothing to ask about — and the worker queue does not
    // survive the JVM.
    String queued =
        deployment("qits-platform-deployments", "env-sweep-queued", PdDeploymentStatus.QUEUED, null);

    deployService.sweepInFlight();

    assertEquals("FAILED", statusOf(queued));
    assertEquals("[interrupted by a qits-deployments restart]", detailOf(queued));
    assertTrue(interruptedOf(queued), "a queue that died with the JVM decided nothing");
    assertTrue(
        driver.calls().stream().noneMatch(call -> call.equals("runningImage:null")),
        "a row with no name is not asked about: " + driver.calls());
  }
}
