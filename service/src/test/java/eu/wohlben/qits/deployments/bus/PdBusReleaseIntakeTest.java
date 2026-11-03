package eu.wohlben.qits.deployments.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
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
 * The bus door, end to end from a frame: what a {@code SoftwareRelease} deploys, what it declines
 * to look at, what it refuses to deploy, and what it swallows.
 *
 * <p><b>It drives {@code onFrame} directly rather than through a stream.</b> The bus is dark in the
 * suite — no socket is dialled and no log is paged, which {@code PdEventstreamDarknessTest} is what
 * asserts — and what belongs here is this component's half: the decode, the package-type filter,
 * the tip check and the call into {@code ReleaseAnnouncements}. The claim ledger, the funnel and the
 * catch-up sweep are the library's and are tested in its own repository; a stub qits-events here
 * would re-prove them and prove nothing about the deployment.
 *
 * <p>Every method creates its own environment and releases its own application, because the suite
 * shares one database across classes and {@code ReleaseTips} keeps a per-process memory of what it
 * announced.
 *
 * <p><b>Every environment here is created as the PLATFORM one</b>, and that is not incidental: with
 * branch matching gone, the designated platform environment is the tier a release enters at.
 * Creating one moves the designation, so each method makes its own the entry tier before it
 * releases anything.
 */
@QuarkusTest
public class PdBusReleaseIntakeTest {

  private static final String OLDER = "2026.903.93059";

  /**
   * The newer version of the pair, and the reason it is spelled this way. The platform's stamp is
   * unpadded, so 19:30:59 is {@code 193059} and 09:30:59 is {@code 93059} — lexically the LATER one
   * sorts first. Every ordering case below uses exactly this pair so a lexical comparison fails
   * them rather than passing by luck.
   */
  private static final String NEWER = "2026.903.193059";

  /** What a githost repository key looks like since the identity rollback: opaque, and internal. */
  private static final String STORAGE_UUID = "1b7e5f30-9c21-4d55-8f0a-77aa3c2e1b04";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject PdSoftwareReleaseSubscriber subscriber;
  @Inject PdDeploymentRequestRepository requests;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void theConsumerIdIsNewStorageAndTheListenerStartsAtTheHeadOfTheLog() {
    // A NEW id, deliberately: the retired pd-build-succeeded ledger is a watermark measured in
    // BuildSuccessful rows and says nothing about this consumer's work. And head-init is stated
    // rather than inherited — replaying from the epoch would redeploy the platform's whole release
    // history on the first boot after this ships.
    assertEquals("pd-software-released", subscriber.consumerId());
    assertEquals(java.util.Set.of("SoftwareRelease"), subscriber.signatures());
    assertFalse(
        subscriber.replayFromEpoch(),
        "a first deployment of this subscriber must not re-announce every release ever cut");
  }

  @Test
  public void aDockerReleaseDeploysTheVersionItNamesAndRecordsTheRequestThatAskedForIt() {
    String environmentId = createEntryTier("bus-plain");

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-plain-app", NEWER));

    awaitApplied(1);
    assertEquals("bus-plain-app", driver.applied().get(0).applicationName());
    assertEquals("bus-plain-app", driver.applied().get(0).applicationName());
    assertTrue(
        driver.applied().get(0).imageRef().endsWith("/bus-plain-app:" + NEWER),
        driver.applied().get(0).imageRef());
    assertTrue(
        driver.applied().get(0).deploymentName().startsWith("qits-pd-bus-plain-bus-plain-app-"),
        driver.applied().get(0).deploymentName());

    PdDeploymentRequest request = newestRequest("bus-plain-app");
    assertNotNull(request, "the release wrote a deployment request");
    assertEquals(NEWER, request.version);
    assertEquals(environmentId, request.environmentId);
    assertEquals("qits/bus-plain-app", request.packageName);
    assertEquals(STORAGE_UUID, request.repoId);
    assertEquals("qits", request.projectId);
    assertEquals(PdQualityGate.MET, request.qualityGate, "the placeholder gate is instantly met");
    assertNotNull(request.gateSettledAt);
    assertNotNull(request.deploymentId, "a met gate hands off to a deployment");
  }

  @Test
  public void aReleaseWithNoProjectIdDeploysExactlyLikeOneWithIt() {
    // projectId is identity enrichment and never a key: the spec is read through /git/<repoId> and
    // the application comes out of the package. qits-ci publishes NON_NULL, so a run with no
    // project leaves the key OUT of the payload entirely — which is what this frame does.
    createEntryTier("bus-noproject");

    subscriber.onFrame(
        frame(
            ("{\"packageName\":\"qits/bus-noproject-app\",\"packageType\":\"docker\","
                    + "\"repoId\":\"%s\",\"repository\":\"%s\",\"version\":\"%s\"}")
                .formatted(STORAGE_UUID, STORAGE_UUID, NEWER)));

    awaitApplied(1);
    assertEquals("bus-noproject-app", driver.applied().get(0).applicationName());
    assertNull(newestRequest("bus-noproject-app").projectId, "absent, not invented");
  }

  @Test
  public void repoNameTakesThePublicRouteAndItsAbsenceKeepsTheIdOne() {
    // repoName landed on SoftwareRelease on 2026-09-04 and this door was still dropping it on the
    // floor, so every release read its spec through /git/<repoId> — the internal route, the one
    // qits-projects alone speaks. Both arms in one case, because the claim is a CHOICE and half of
    // it proves nothing: the same subscriber, two frames, two addresses.
    //
    // What is asserted is the RepositoryRef the spec read was made with. That is where the choice
    // lives (RepositoryRef.nameAddressed), and GitHostSpecSourceTest holds the other half against a
    // real HTTP server — that a name-addressed ref really becomes /git/<projectId>/<repoName> and an
    // id-addressed one /git/<repoId>. Asserting the URL again here would re-prove that source's job
    // and would still not say whether this door filled the fields in.
    createEntryTier("bus-named");

    subscriber.onFrame(
        frame(
            ("{\"packageName\":\"qits/bus-named-app\",\"packageType\":\"docker\","
                    + "\"projectId\":\"qits\",\"repoName\":\"bus-named-repo\",\"repoId\":\"%s\","
                    + "\"repository\":\"%s\",\"version\":\"%s\"}")
                .formatted(STORAGE_UUID, STORAGE_UUID, NEWER)));

    awaitApplied(1);
    // Keyed by the ref's own applicationName, which is the repository NAME once the pair is there.
    RepositoryRef named = specs.refOf("bus-named-repo");
    assertNotNull(named, "the deployment read a spec, so it made an address to read it at");
    assertTrue(
        named.nameAddressed(),
        "both halves present, so the public /git/<projectId>/<repoName> route is the one taken");
    assertEquals("qits", named.projectId());
    assertEquals("bus-named-repo", named.repoName());
    assertEquals(STORAGE_UUID, named.repoId(), "the storage id still travels, as the fallback");
    assertEquals(
        "bus-named-app",
        driver.applied().get(0).applicationName(),
        "and the application still comes out of packageName — repoName addresses, it never names");

    // The other arm: an event from before the field existed, or replayed from before it. Same
    // subscriber, same frame shape minus one key.
    createEntryTier("bus-unnamed");

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-unnamed-app", NEWER));

    awaitApplied(2);
    RepositoryRef byId = specs.refOf(STORAGE_UUID);
    assertNotNull(byId, "the id-addressed read files itself under the id");
    assertFalse(
        byId.nameAddressed(),
        "no repoName, so the internal /git/<repoId> route — exactly what this door always did");
    assertNull(byId.repoName());
    assertEquals(STORAGE_UUID, byId.repoId());
  }

  @Test
  public void theDeclaredPriorityIsRecordedOnTheRequestAndReordersNothing() {
    // The priority a release request declared, carried down from qits-projects through qits-ci and
    // written on the request row. It is RECORDED and nothing else: the worker is FIFO, so what this
    // asserts is a column and an ordinary deployment beside it.
    createEntryTier("bus-priority");

    subscriber.onFrame(priorityFrame("qits/bus-priority-app", NEWER, "HIGHER"));

    awaitApplied(1);
    assertEquals("bus-priority-app", driver.applied().get(0).applicationName());
    assertEquals("HIGHER", newestRequest("bus-priority-app").priority);
  }

  @Test
  public void anAbsentPriorityIsNullAndAnOverlongOneCostsTheBadgeRatherThanTheRelease() {
    // Two absences that are the same answer. qits-ci publishes NON_NULL, so a release whose request
    // declared nothing carries no `priority` key at all — and so does every event in the log from
    // before the field existed, which is what a catch-up replays forever. Null is what those mean.
    createEntryTier("bus-nopriority");

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-nopriority-app", NEWER));

    awaitApplied(1);
    assertNull(
        newestRequest("bus-nopriority-app").priority,
        "an absent key is `the release stated none`, not a value to invent");

    // And the other end: a value too long for varchar(32). It is an advisory badge that reaches no
    // argv and no image path, so it is dropped with a WARN — a release must never fail to deploy
    // over a word somebody typed into a priority field.
    subscriber.onFrame(priorityFrame("qits/bus-longpriority-app", NEWER, "X".repeat(64)));

    awaitApplied(2);
    assertEquals(
        "bus-longpriority-app",
        driver.applied().get(1).applicationName(),
        "the release deployed exactly as it would have with no priority at all");
    assertNull(newestRequest("bus-longpriority-app").priority);
  }

  @Test
  public void onlyDockerArtifactsAreEvenLookedAt() {
    // One release publishes a jar, an npm package, a docs bundle and an image as four events. Only
    // the image names something this component can put live; the rest select false and are stored
    // nowhere at all, which is what keeps the claim ledger proportional to the deployments.
    createEntryTier("bus-types");

    assertTrue(subscriber.selects(dockerFrame(STORAGE_UUID, "qits", "qits/bus-types-app", NEWER)));
    assertFalse(
        subscriber.selects(
            releaseFrame(STORAGE_UUID, "qits", "maven", "eu.wohlben.qits:qits-ci", NEWER)));
    assertFalse(
        subscriber.selects(releaseFrame(STORAGE_UUID, "qits", "npm", "@qits/ui", NEWER)));

    subscriber.onFrame(releaseFrame(STORAGE_UUID, "qits", "maven", "eu.wohlben.qits:x", NEWER));
    awaitWorkerIdle();
    assertEquals(List.of(), driver.applied(), "a jar deploys nothing");
  }

  @Test
  public void aReplayedOlderVersionIsSkippedRatherThanRolledOverTheNewerOne() {
    // The failure this whole guard exists for: after a restart or a reconnect the catch-up sweep
    // hands over an event the stream never delivered, and it can name an OLDER version than the one
    // already live. Applying it would be a rollback nobody asked for.
    createEntryTier("bus-order");

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-order-app", NEWER));
    awaitApplied(1);

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-order-app", OLDER));
    awaitWorkerIdle();

    assertEquals(1, driver.applied().size(), "the stale release was not deployed");
    assertTrue(
        driver.applied().get(0).imageRef().endsWith(":" + NEWER),
        "the newer version is still what runs");
  }

  @Test
  public void twoReleasesSecondsApartBothDeployInOrder() {
    // The other half of the same check: the collapse is a floor, not a stop.
    createEntryTier("bus-pair");

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-pair-app", OLDER));
    awaitApplied(1);
    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-pair-app", NEWER));
    awaitApplied(2);

    assertTrue(driver.applied().get(1).imageRef().endsWith(":" + NEWER));
  }

  @Test
  public void aVersionOlderThanWhatTheOtherDoorAlreadyRequestedIsSkipped() {
    // The cross-restart floor, staged without a restart: the manual door deployed something and
    // this process never announced it, which is exactly the state a freshly booted subscriber is
    // in. The REQUEST row is then the only thing that knows, and it is enough.
    String environmentId = createEntryTier("bus-floor");
    postRelease("bus-floor-app", NEWER);
    awaitDeployments(environmentId, 1);
    driver.reset();

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-floor-app", OLDER));
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "an older version did not roll the running one");
  }

  @Test
  public void aVersionNewerThanTheLastRequestStillDeploys() {
    String environmentId = createEntryTier("bus-fresh");
    postRelease("bus-fresh-app", OLDER);
    awaitDeployments(environmentId, 1);
    driver.reset();

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-fresh-app", NEWER));

    awaitApplied(1);
    assertTrue(driver.applied().get(0).imageRef().endsWith(":" + NEWER));
  }

  @Test
  public void anUnreadablePayloadIsSwallowedRatherThanThrown() {
    // A throw here rolls the library's claim back and leaves the event owed FOREVER — offered
    // again on every sweep, with the watermark stuck behind it, so one poison event stops this
    // consumer's catch-up. Retrying a payload that will not parse changes nothing, so it is warned
    // about and settled.
    createEntryTier("bus-poison");
    EventFrame poison = frame("not json");

    assertFalse(subscriber.selects(poison), "an unreadable payload selects nothing");
    subscriber.onFrame(poison);
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied());
  }

  @Test
  public void aDockerReleaseThatDeclaresNoSpecIsRecordedAndNotDeployed() {
    // The 2026-09-04 accident, in one case. qits-workspace-daemon publishes qits/workspace,
    // qits-workspace-editor-oci publishes qits/workspace-editor and qits-workspace-oci publishes
    // qits/workspace-base — workspace CONTAINER images, each a docker package and a real release,
    // none of them a service. All three were announced here, read no deployments.yml, took
    // DeploymentSpec.DEFAULTS and were launched as dev-workspace, dev-workspace-editor and
    // dev-workspace-base, which sat "still created after 120s" and failed. A published image is not
    // necessarily a service, and a repository that declares no file has not asked to be deployed.
    String environmentId = createEntryTier("bus-nospec");
    // Keyed by the ref's applicationName, which for a frame carrying no repoName is the storage
    // id — the same coordinate GitHostSpecSource would address the blob by.
    specs.scriptNoSpec(STORAGE_UUID);

    subscriber.onFrame(dockerFrame(STORAGE_UUID, "qits", "qits/bus-nospec-app", NEWER));
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied(), "nothing was launched");
    assertEquals(List.of(), driver.pulled(), "and nothing was even pulled");

    // Recorded, though — this is a decision and not a silence. The request row is the shape its
    // own javadoc describes: a settled gate with no deployment behind it.
    PdDeploymentRequest request = newestRequest("bus-nospec-app");
    assertNotNull(request, "the release was recorded");
    assertEquals(NEWER, request.version);
    assertEquals(environmentId, request.environmentId, "recorded against the tier it would enter");
    assertEquals(PdQualityGate.UNMET, request.qualityGate);
    assertNotNull(request.gateSettledAt, "settled, not left open");
    assertNull(request.deploymentId, "a refused request hands off to nothing");
    assertTrue(
        request.gateDetail != null
            && request.gateDetail.contains(".config/qits/deployments.yml")
            && request.gateDetail.contains("refs/tags/" + NEWER),
        "the refusal says what is missing and where it was looked for: " + request.gateDetail);

    // And nothing was registered, which is the other half: registering first is what left
    // `workspace` and `workspace-editor` in the catalogue with no deployment that ever worked.
    List<String> services =
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get("/deployments/api/services")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("services.name");
    assertFalse(services.contains("bus-nospec-app"), "a base image is not a service: " + services);
  }

  @Test
  public void theManualDoorStillDeploysARepositoryThatDeclaresNoSpec() {
    // The other arm of the same rule, and the reason it is a door split rather than a global
    // refusal: this door is an operator or a bootstrap naming a version on purpose — including a
    // rollback to a tag cut before deployments.yml existed anywhere — and refusing that would be
    // refusing the choice. Same fake, same missing file, opposite answer.
    createEntryTier("bus-nospec-manual");
    specs.scriptNoSpec(STORAGE_UUID);

    postRelease("bus-nospec-manual-app", NEWER);

    awaitApplied(1);
    assertEquals("bus-nospec-manual-app", driver.applied().get(0).applicationName());
  }

  @Test
  public void aReleaseNamingNoApplicationIsSwallowedToo() {
    // Same reasoning, one step later: the payload parses but its package names nothing this
    // component could deploy. Announcing it would only reach the identifier validation and be
    // refused there.
    createEntryTier("bus-partial");

    subscriber.onFrame(
        frame(
            ("{\"packageName\":\"qits/\",\"packageType\":\"docker\",\"repoId\":\"%s\","
                    + "\"version\":\"%s\"}")
                .formatted(STORAGE_UUID, NEWER)));
    subscriber.onFrame(
        frame("{\"packageType\":\"docker\",\"repoId\":\"" + STORAGE_UUID + "\"}"));
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied());
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** One docker release as qits-ci publishes it: canonical JSON, alphabetical, NON_NULL. */
  private static EventFrame dockerFrame(
      String repoId, String projectId, String packageName, String version) {
    return releaseFrame(repoId, projectId, "docker", packageName, version);
  }

  /**
   * The same release with a priority on it — canonical JSON is alphabetical, so the key sits
   * between {@code packageType} and {@code projectId} exactly as qits-ci's mapper writes it.
   */
  private static EventFrame priorityFrame(String packageName, String version, String priority) {
    return frame(
        ("{\"packageName\":\"%s\",\"packageType\":\"docker\",\"priority\":\"%s\","
                + "\"projectId\":\"qits\",\"repoId\":\"%s\",\"repository\":\"%s\","
                + "\"version\":\"%s\"}")
            .formatted(packageName, priority, STORAGE_UUID, STORAGE_UUID, version));
  }

  private static EventFrame releaseFrame(
      String repoId, String projectId, String packageType, String packageName, String version) {
    return frame(
        ("{\"packageName\":\"%s\",\"packageType\":\"%s\",\"projectId\":\"%s\",\"repoId\":\"%s\","
                + "\"repository\":\"%s\",\"version\":\"%s\"}")
            .formatted(packageName, packageType, projectId, repoId, repoId, version));
  }

  private static EventFrame frame(String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "SoftwareRelease",
        Instant.now(),
        payload,
        null,
        null,
        null);
  }

  /**
   * The tier a release enters at. Created as the platform environment, which MOVES the designation
   * — so this method makes its caller's tier the entry tier for the rest of the method.
   */
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
                "runId", "run-http",
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
    fail("deployments of " + environmentId + " did not settle to " + count);
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
