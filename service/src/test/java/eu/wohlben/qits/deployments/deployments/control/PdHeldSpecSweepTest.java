package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
 * The boot sweep settles the {@code SPEC_UNREADABLE} rows a dead process was holding — {@link
 * PdSweepAdoptionTest}'s claim one word further on, and driven the same way: rows written straight
 * to the table, {@link DeployService#sweepHeldSpecReads()} called package-locally, no StartupEvent.
 *
 * <p><b>The measurement.</b> On 2026-09-07 {@code qits-ci@2026.907.184918} and {@code
 * qits-deployments@2026.907.192519} sat {@code SPEC_UNREADABLE} for over two hours across several
 * deployer restarts, while both versions were serving for real off re-fired events. The hold that
 * would have ended them is {@link DeployService}'s in-memory map, which dies with the JVM, and
 * {@code SPEC_UNREADABLE} is on {@link RequestLifecycle}'s in-flight list — so the SPA drew two
 * pending deployments that could never move. An operator-facing lie with no process behind it.
 *
 * <p>Both arms are pinned, because either alone is a defect: settling everything would erase a
 * release that is genuinely still recoverable, and settling nothing is the stranding itself.
 */
@QuarkusTest
public class PdHeldSpecSweepTest {

  // Unpadded, PdSpecRetryTest's stance: V_B is the later release and yet sorts FIRST as a string.
  private static final String V_A = "2026.907.93059";
  private static final String V_B = "2026.907.193059";

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
  }

  @Test
  public void aHeldRowWhoseApplicationIsNowServingALaterDeploymentIsSuperseded() {
    // The measured shape: the release was re-fired, deployed for real, and the row the dead
    // process was holding is the only thing still claiming to be in flight.
    String environmentId = "env-held-superseded";
    String held =
        deployment("qits-ci", environmentId, PdDeploymentStatus.SPEC_UNREADABLE, V_A);
    deployment("qits-ci", environmentId, PdDeploymentStatus.ACTIVE, V_B);

    deployService.sweepHeldSpecReads();

    assertEquals("SUPERSEDED", statusOf(held));
    String detail = detailOf(held);
    assertTrue(
        detail.contains("the process holding this spec retry died"),
        "the row names the death rather than the git host: " + detail);
    assertTrue(detail.contains("qits-ci already serves " + V_B), detail);
    assertTrue(detail.contains("settled by the boot sweep"), detail);
  }

  @Test
  public void aHeldRowIsSupersededByAnEarlierRowSERVINGTheVeryVersionItWasHeldOn() {
    // The version arm, which the seq arm cannot answer: the ACTIVE row was created BEFORE the held
    // one and is serving the same version — a redeploy that landed while the retry was still
    // holding. Nothing is owed about a version that is already live.
    String environmentId = "env-held-same-version";
    deployment("qits-docs", environmentId, PdDeploymentStatus.ACTIVE, V_B);
    String held =
        deployment("qits-docs", environmentId, PdDeploymentStatus.SPEC_UNREADABLE, V_B);

    deployService.sweepHeldSpecReads();

    assertEquals("SUPERSEDED", statusOf(held));
    assertTrue(detailOf(held).contains("qits-docs already serves " + V_B), detailOf(held));
  }

  @Test
  public void aHeldRowWithNothingServingItsPlaceIsFailedAndIsNotReHeld() {
    // The other arm. The row carries an application name and no repository identity — V1's own rule
    // — so no SpecRetry can be rebuilt from it, and the recovery that CAN be rebuilt lives in
    // pd_owed_release, one layer out. So the honest word is the terminal one, naming the death.
    String environmentId = "env-held-orphan";
    // A serving row of ANOTHER tier must not answer for this one: the place is (application, tier).
    deployment("qits-gateway", "env-held-orphan-elsewhere", PdDeploymentStatus.ACTIVE, V_B);
    String held =
        deployment("qits-gateway", environmentId, PdDeploymentStatus.SPEC_UNREADABLE, V_A);

    deployService.sweepHeldSpecReads();

    assertEquals("FAILED", statusOf(held));
    String detail = detailOf(held);
    assertTrue(
        detail.contains("the process holding this spec retry died"),
        "the row names the death: " + detail);
    assertTrue(detail.contains("nothing serves qits-gateway here"), detail);

    // ...and it is terminal rather than re-held: the retry pass has nothing to re-read for it, so
    // the row keeps the word the sweep gave it.
    int reads = specs.readsOf("qits-gateway");
    deployService.retrySpecReads();

    assertEquals(reads, specs.readsOf("qits-gateway"), "a settled row is not held again");
    assertEquals("FAILED", statusOf(held));
  }

  @Test
  public void aRowThisProcessIsSTILLHoldingIsLeftAloneByThePass() {
    // The contract the pass is written to: it settles rows NOBODY is carrying. At boot that is all
    // of them, and this is what keeps that true if the pass is ever driven beside a live retry —
    // settling a release retrySpecReads() is about to re-read would be the stranding inverted.
    String environmentId = createEnvironment("held-sweep-live");
    postRelease("repo-held-live", V_A);
    awaitSettled(environmentId, 1); // registered, so a failed read has somewhere to be recorded
    driver.reset();

    specs.scriptRetryableFailure("repo-held-live", "the git host answered 503");
    postRelease("repo-held-live", V_B);
    List<Map<String, Object>> rows = awaitSettled(environmentId, 2);
    assertEquals("SPEC_UNREADABLE", rows.get(0).get("status"));

    deployService.sweepHeldSpecReads();

    assertEquals(
        "SPEC_UNREADABLE",
        awaitSettled(environmentId, 2).get(0).get("status"),
        "the release is still held, so the row is still the hold's to settle");

    // Ended here rather than left held, PdSpecRetryTest's rule: the map is on the
    // application-scoped DeployService and outlives this method.
    specs.recover("repo-held-live");
    tick();
    assertEquals("ACTIVE", awaitSettled(environmentId, 3).get(0).get("status"));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String deployment(
      String applicationName, String environmentId, PdDeploymentStatus status, String version) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              // V8 made the plane a not-null column; these rows are tier rows.
              row.version = version;
              row.status = status;
              row.createdAt = Instant.now();
              if (status == PdDeploymentStatus.ACTIVE) {
                row.finishedAt = Instant.now();
              }
              deployments.persist(row);
            });
    return id;
  }

  private String statusOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).status.name());
  }

  private String detailOf(String deploymentId) {
    return QuarkusTransaction.requiringNew().call(() -> deployments.findById(deploymentId).detail);
  }

  /** One tick of the periodic work, drained — {@link PdSpecRetryTest}'s helper. */
  private void tick() {
    deployService.enqueueObservation();
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postRelease(String repoId, String version) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-" + version, "repoId", repoId, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  /** The tier's deployments, newest first, once there are {@code count} of them and none moving. */
  private List<Map<String, Object>> awaitSettled(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows =
          given()
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      boolean settled =
          rows.size() == count
              && rows.stream()
                  .noneMatch(
                      r -> "QUEUED".equals(r.get("status")) || "STARTING".equals(r.get("status")));
      if (settled) {
        return rows;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return fail("deployments of " + environmentId + " did not settle to " + count);
  }
}
