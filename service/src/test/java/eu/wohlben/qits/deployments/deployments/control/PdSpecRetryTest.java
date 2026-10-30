package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.deployments.bus.PdSoftwareReleaseSubscriber;
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
 * A spec read that did not answer is <b>held</b>, not failed — and a spec that will never answer
 * still is.
 *
 * <p><b>The measurement this class exists for.</b> On 2026-09-04 qits-githost intermittently
 * answered 403 on {@code /git/<repoId>/blob/refs%2Ftags%2F<version>/.config/qits/deployments.yml};
 * the deployer recorded {@code FAILED [deployment spec unreadable: …]}, which nothing ever
 * revisits, and three releases sat stranded for 13 to 17 minutes each until a person nudged the
 * door by hand. The blob was readable the whole time — the second request always worked.
 *
 * <p>So the outcome is split in two, and the split is the whole subject here: a read that never saw
 * the file ends {@code SPEC_UNREADABLE} and is re-read on the observation tick until it answers or
 * a newer version supersedes it, while a file that was read and refused stays {@code FAILED} and is
 * never asked again. Both halves are pinned, because either alone is a bug — one strands releases,
 * the other turns a broken commit into a permanent background job.
 *
 * <p>The passes are driven directly ({@link DeployService#enqueueObservation()}, then {@code
 * awaitIdle}) rather than waited on: no tick runs under a {@code @QuarkusTest}, the {@link
 * PdDeploymentObservationTest} arrangement.
 */
@QuarkusTest
public class PdSpecRetryTest {

  // Unpadded on purpose, the flow suite's stance: the platform's CalVer stamp is built by integer
  // arithmetic, so V_B is later than V_A and yet sorts FIRST as a string.
  private static final String V_A = "2026.904.93059";
  private static final String V_B = "2026.904.193059";
  private static final String V_C = "2026.904.203059";

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;

  /** The bus door, for the one case whose rule is the bus door's — see the method. */
  @Inject PdSoftwareReleaseSubscriber subscriber;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
  }

  @Test
  public void aRefusedSpecReadIsHeldAndDeploysForRealOnceTheGitHostAnswers() {
    // One green release first, so the repository is registered somewhere — a spec failure for a
    // repository nothing has registered has no place to be recorded, which is the arm below.
    String environmentId = createEnvironment("spec-held");
    postRelease("repo-held", V_A);
    awaitSettled(environmentId, 1);

    driver.reset(); // what the first, green release did is not what this test is about

    // The measured accident: the same blob that read a minute ago answers 403.
    specs.scriptRetryableFailure("repo-held", "the git host answered 403");
    postRelease("repo-held", V_B);

    List<Map<String, Object>> held = awaitSettled(environmentId, 2);
    assertEquals(
        "SPEC_UNREADABLE",
        held.get(0).get("status"),
        "a read that never saw the file is not a failed deployment");
    assertEquals(V_B, held.get(0).get("version"));
    assertTrue(
        ((String) held.get(0).get("detail")).contains("the git host answered 403"),
        "the cause is on the row: " + held.get(0).get("detail"));
    // Nothing was pulled and nothing started — a held release still never guesses a topology.
    assertEquals(List.of(), driver.pulled());
    // ...and what was serving is still serving.
    assertEquals("ACTIVE", held.get(1).get("status"));

    // The tick that used to only observe containers now also re-reads the file.
    int readsBefore = specs.readsOf("repo-held");
    specs.recover("repo-held");
    tick();

    assertTrue(specs.readsOf("repo-held") > readsBefore, "the held release was read again");
    List<Map<String, Object>> deployed = awaitSettled(environmentId, 3);
    assertEquals("ACTIVE", deployed.get(0).get("status"), "the release deployed for real");
    assertEquals(V_B, deployed.get(0).get("version"));
    // The held row is history now rather than erased: it is a true statement about 13:17.
    assertEquals("SPEC_UNREADABLE", deployed.get(1).get("status"));
    assertEquals("DECOMMISSIONED", deployed.get(2).get("status"));
  }

  @Test
  public void aHeldReleaseIsReadAgainEveryTickUntilItAnswers() {
    String environmentId = createEnvironment("spec-held-twice");
    postRelease("repo-held-twice", V_A);
    awaitSettled(environmentId, 1);

    specs.scriptRetryableFailure("repo-held-twice", "the git host answered 503");
    postRelease("repo-held-twice", V_B);
    awaitSettled(environmentId, 2);

    // Two ticks with the git host still down: the release stays held, and no row is added per
    // attempt — the retry re-reads the FILE, it does not re-queue the deployment.
    int afterIntake = specs.readsOf("repo-held-twice");
    tick();
    tick();

    assertEquals(afterIntake + 2, specs.readsOf("repo-held-twice"), "one read per tick");
    List<Map<String, Object>> rows = awaitSettled(environmentId, 2);
    assertEquals("SPEC_UNREADABLE", rows.get(0).get("status"));

    // Ended here rather than left held, and that is a requirement of the suite rather than tidiness:
    // the held set lives on the application-scoped DeployService, so a release this test abandoned
    // would deploy into the NEXT test's tier the moment its scripted failure was reset away.
    specs.recover("repo-held-twice");
    tick();
    assertEquals("ACTIVE", awaitSettled(environmentId, 3).get(0).get("status"));
  }

  @Test
  public void aSpecThatCannotBeUnderstoodStaysFailedAndIsNeverReadAgain() {
    // The other half, and it matters as much: the file WAS read, so reading it again answers the
    // same thing. Held, it would be a broken commit re-asked forever at the platform's expense.
    String environmentId = createEnvironment("spec-permanent");
    postRelease("repo-permanent", V_A);
    awaitSettled(environmentId, 1);

    specs.scriptFailure("repo-permanent", "line 3: indented lines — this file has no nesting");
    postRelease("repo-permanent", V_B);

    List<Map<String, Object>> failed = awaitSettled(environmentId, 2);
    assertEquals("FAILED", failed.get(0).get("status"));

    int reads = specs.readsOf("repo-permanent");
    specs.recover("repo-permanent"); // even if the world changed, nothing here re-asks
    tick();

    assertEquals(reads, specs.readsOf("repo-permanent"), "a permanent refusal is not re-read");
    assertEquals("FAILED", awaitSettled(environmentId, 2).get(0).get("status"));
  }

  @Test
  public void aRepositoryThatCarriesNoSpecAtTheTagDeploysAndIsNotHeld() {
    // A 404 is an answer: the reader turns it into the defaults before this ever sees a failure,
    // so there is nothing to hold. Pinned here because the 404 arm is what SPEC_UNREADABLE must
    // NOT catch — a missing file is not a git host that would not answer.
    //
    // It deploys because this is the MANUAL door, which is the half of the 2026-09-05 rule that
    // did not change: somebody named this version, including possibly a tag cut before the file
    // existed anywhere. The bus door records the same release and deploys nothing, which is
    // PdBusReleaseIntakeTest's claim.
    String environmentId = createEnvironment("spec-absent");
    specs.scriptNoSpec("repo-absent");
    postRelease("repo-absent", V_A);

    assertEquals("ACTIVE", awaitSettled(environmentId, 1).get(0).get("status"));

    int reads = specs.readsOf("repo-absent");
    tick();

    assertEquals(reads, specs.readsOf("repo-absent"), "nothing was held, so nothing is re-read");
  }

  @Test
  public void aNewerVersionSupersedesAHeldReleaseRatherThanRacingIt() {
    // The ordinary exit. A held release must never deploy after a newer one already has — that is
    // the monotonic collapse ReleaseTips performs, and a retry is exactly where it could be lost.
    String environmentId = createEnvironment("spec-superseded");
    postRelease("repo-superseded", V_A);
    awaitSettled(environmentId, 1);

    specs.scriptRetryableFailure("repo-superseded", "the git host answered 403");
    postRelease("repo-superseded", V_B);
    awaitSettled(environmentId, 2);

    // A newer version arrives while the older one is still held, and its read works.
    specs.recover("repo-superseded");
    postRelease("repo-superseded", V_C);
    List<Map<String, Object>> rows = awaitSettled(environmentId, 3);
    assertEquals("ACTIVE", rows.get(0).get("status"));
    assertEquals(V_C, rows.get(0).get("version"));

    // ...and the tick deploys nothing further: the held entry went with the newer release.
    tick();

    List<Map<String, Object>> after = awaitSettled(environmentId, 3);
    assertEquals(V_C, after.get(0).get("version"), "the newest deployment is still the newest");
    assertEquals("ACTIVE", after.get(0).get("status"));
    assertEquals("SPEC_UNREADABLE", after.get(1).get("status"), "the held row stayed history");
  }

  @Test
  public void aHeldReleaseWhoseSpecTurnsOutToBeAbsentIsSettledRatherThanDeployed() {
    // The two arms meeting, and the only case here that goes through the BUS door — because the
    // rule it exercises is that door's. A git host that refused the blob holds the release
    // SPEC_UNREADABLE and re-reads it; when that read finally answers 404 the file is not
    // unreadable, it is absent. So the held rows are settled with the same sentence rather than
    // waiting for an answer that has already arrived, and nothing is deployed.
    String environmentId = createEnvironment("spec-held-absent");
    String repoId = "repo-held-absent";
    releaseOnTheBus(repoId, "qits/held-absent-app", V_A);
    awaitSettled(environmentId, 1); // registered, so a failed read has somewhere to be recorded
    driver.reset();

    specs.scriptRetryableFailure(repoId, "the git host answered 503");
    releaseOnTheBus(repoId, "qits/held-absent-app", V_B);
    assertEquals("SPEC_UNREADABLE", awaitSettled(environmentId, 2).get(0).get("status"));

    specs.recover(repoId);
    specs.scriptNoSpec(repoId);
    tick();

    assertEquals(List.of(), driver.pulled(), "an absent file deploys nothing");
    List<Map<String, Object>> rows = awaitSettled(environmentId, 2);
    assertEquals("FAILED", rows.get(0).get("status"), "the held row was settled, not left waiting");
    assertTrue(
        ((String) rows.get(0).get("detail")).contains(".config/qits/deployments.yml"),
        "with the reason the wait ended: " + rows.get(0).get("detail"));
    assertEquals("ACTIVE", rows.get(1).get("status"), "and what was serving is still serving");
  }

  // --- helpers ----------------------------------------------------------------------------------

  /**
   * One tick of the periodic work, drained: the held spec reads and then the observation pass, both
   * on the deploy worker exactly as the ticker enqueues them.
   */
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
        // The entry tier: a release lands in the designated platform environment.
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  /**
   * One {@code SoftwareRelease} through the bus door, as qits-ci publishes it. The rest of this
   * class uses the manual door, which is the right door for everything about HOLDING a read; this
   * exists for the one case that is about the release door's own refusal.
   */
  private void releaseOnTheBus(String repoId, String packageName, String version) {
    subscriber.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SoftwareRelease",
            Instant.now(),
            ("{\"packageName\":\"%s\",\"packageType\":\"docker\",\"repoId\":\"%s\","
                    + "\"repository\":\"%s\",\"version\":\"%s\"}")
                .formatted(packageName, repoId, repoId, version),
            null,
            null,
            null));
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
