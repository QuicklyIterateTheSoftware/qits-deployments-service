package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.api.SuiteBearers;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    // A release declaring no variants names none on its row — an empty list, never null
    // (qits-2296).
    List<Map<String, Object>> release = requests("repoId=repo-swarm-green&version=" + VERSION);
    assertEquals(1, release.size(), release.toString());
    assertEquals(List.of(), release.get(0).get("variants"));
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
  public void aPrivateRepositoryReachesTheDriverPrivateAndAnnouncesItSo() {
    // qits-1976, end to end: `exposure: private` is read from the spec, carried through the target
    // and the plan to the orchestrator, and published on DeploymentActive — the field each edge
    // filters its routes on.
    String environmentId = createEnvironment("swarm-private");
    specs.script(
        "repo-swarm-private",
        DeploymentSpecParser.parse("exposure: private\n", "deployments.yml of repo-swarm-private"));

    postRelease("run-private", "repo-swarm-private");
    awaitSettled(environmentId, 1);

    assertEquals(DeploymentDriver.Exposure.PRIVATE, fake.applied().get(0).exposure());
    OutboxEvent active = only("DeploymentActive");
    assertTrue(active.payload.contains("\"exposure\":\"private\""), active.payload);
  }

  @Test
  public void aRepositoryThatNamesNoExposureIsAnnouncedPublic() {
    String environmentId = createEnvironment("swarm-public");

    postRelease("run-public", "repo-swarm-public");
    awaitSettled(environmentId, 1);

    assertEquals(DeploymentDriver.Exposure.PUBLIC, fake.applied().get(0).exposure());
    OutboxEvent active = only("DeploymentActive");
    assertTrue(active.payload.contains("\"exposure\":\"public\""), active.payload);
  }

  @Test
  public void aReleaseWithAVariantDeploysOneImageAsTwoApplications() {
    // qits-1976, the private edge's shape: the main file says `variants: private`, the variant file
    // at the same tag names its own application and no image — so the MAIN image is deployed twice,
    // once per application, both from this one release.
    String environmentId = createEnvironment("swarm-variant");
    specs.script(
        "repo-swarm-edge",
        DeploymentSpecParser.parse("variants: private\n", "deployments.yml of repo-swarm-edge"));
    specs.scriptAt(
        "repo-swarm-edge",
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parse(
            "application: repo-swarm-edge-private\nexposure: private\ncap_add: NET_ADMIN\n",
            "deployments.private.yml of repo-swarm-edge"));

    postRelease("run-variant", "repo-swarm-edge");
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 2);

    assertEquals(
        List.of("repo-swarm-edge", "repo-swarm-edge-private"),
        deployments.stream().map(row -> (String) row.get("applicationName")).sorted().toList());
    for (Map<String, Object> row : deployments) {
      assertEquals("ACTIVE", row.get("status"), row.toString());
      assertEquals(VERSION, row.get("version"));
    }
    assertEquals(1, specs.readsOf("repo-swarm-edge", SpecSource.variantPath("private")));
    String image = "registry.qits.dev.localhost:8080/qits/repo-swarm-edge:" + VERSION;
    assertEquals(List.of(image, image), fake.pulled(), "one image, pulled for each application");
    DeploymentDriver.ServiceSpec variant =
        fake.applied().stream()
            .filter(spec -> spec.applicationName().equals("repo-swarm-edge-private"))
            .findFirst()
            .orElseThrow();
    assertEquals(image, variant.imageRef());
    assertEquals("swarm-variant-repo-swarm-edge-private", variant.wireAlias());
    assertEquals(DeploymentDriver.Exposure.PRIVATE, variant.exposure());
    assertEquals(List.of("NET_ADMIN"), variant.shape().capAdd());
    // The variant's container is told which variant it is (QITS_VARIANT); the main one is not.
    assertEquals("private", variant.variant());
    assertNull(
        fake.applied().stream()
            .filter(spec -> spec.applicationName().equals("repo-swarm-edge"))
            .findFirst()
            .orElseThrow()
            .variant());

    List<OutboxEvent> active = all("DeploymentActive");
    assertEquals(2, active.size());
    OutboxEvent main =
        active.stream()
            .filter(row -> row.payload.contains("\"applicationName\":\"repo-swarm-edge\""))
            .findFirst()
            .orElseThrow();
    OutboxEvent second =
        active.stream()
            .filter(
                row -> row.payload.contains("\"applicationName\":\"repo-swarm-edge-private\""))
            .findFirst()
            .orElseThrow();
    assertTrue(!main.payload.contains("\"variant\":"), "the main deployment names no variant");
    assertTrue(second.payload.contains("\"variant\":\"private\""), second.payload);
    assertTrue(second.payload.contains("\"exposure\":\"private\""), second.payload);
    assertNull(only("DeploymentFailed", 0));
  }

  @Test
  public void theEdgesPrivateVariantWritesTheLetsencryptVolumeAndReadsTheEdgesSettings() {
    // qits-2784: the owner's variant mounts its owned volume read-write, and its inherit_settings:
    // reach the driver naming the MAIN application — the one fact the variant file cannot state.
    String environmentId = createEnvironment("swarm-variant-writer");
    specs.script(
        "repo-swarm-writer",
        DeploymentSpecParser.parse(
            "application: qits-edge\nvariants: private\n", "deployments.yml of repo-swarm-writer"));
    specs.scriptAt(
        "repo-swarm-writer",
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parseVariant(
            "application: qits-edge-private\nexposure: private\n"
                + "volumes: shared:qits-edge-letsencrypt:/work/.letsencrypt\n"
                + "inherit_settings: QITS_EDGE_DNS_NODE_ADDRESS, QITS_EDGE_ACME_EMAIL\n",
            "deployments.private.yml of repo-swarm-writer"));

    postRelease("run-variant-writer", "repo-swarm-writer");
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 2);
    for (Map<String, Object> row : deployments) {
      assertEquals("ACTIVE", row.get("status"), row.toString());
    }

    DeploymentDriver.ServiceSpec variant =
        fake.applied().stream()
            .filter(spec -> spec.applicationName().equals("qits-edge-private"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        new DeploymentDriver.InheritedSettings(
            "qits-edge", List.of("QITS_EDGE_DNS_NODE_ADDRESS", "QITS_EDGE_ACME_EMAIL")),
        variant.inherited());
    DeploymentDriver.VolumeMount letsencrypt =
        variant.volumes().stream()
            .filter(mount -> mount.source().equals("qits-edge-letsencrypt"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(variant.volumes().toString()));
    assertFalse(letsencrypt.readOnly(), "the owner's variant writes the volume");
    assertTrue(
        fake.applied().stream()
            .filter(spec -> spec.applicationName().equals("qits-edge"))
            .findFirst()
            .orElseThrow()
            .inherited()
            .isEmpty(),
        "the main application inherits nothing");
  }

  @Test
  public void anotherApplicationsVariantWritingTheLetsencryptVolumeFailsAndTheMainDeploys() {
    String environmentId = createEnvironment("swarm-variant-thief");
    specs.script(
        "repo-swarm-thief",
        DeploymentSpecParser.parse("variants: private\n", "deployments.yml of repo-swarm-thief"));
    specs.scriptAt(
        "repo-swarm-thief",
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parseVariant(
            "application: repo-swarm-thief-private\n"
                + "volumes: shared:qits-edge-letsencrypt:/work/.letsencrypt\n",
            "deployments.private.yml of repo-swarm-thief"));

    postRelease("run-variant-thief", "repo-swarm-thief");
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 2);

    Map<String, Object> variant =
        deployments.stream()
            .filter(row -> "repo-swarm-thief-private".equals(row.get("applicationName")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(deployments.toString()));
    assertEquals("FAILED", variant.get("status"), variant.toString());
    assertTrue(
        String.valueOf(variant).contains("read-write only in a variant of its owner qits-edge"),
        variant.toString());
    assertEquals(1, fake.applied().size(), "only the main application was applied");
  }

  @Test
  public void aReleasesOwnRequestsLeaveOutItsVariants() {
    // qits-1984: qits-projects reads ?repoId=&version= as this release's OWN deployment and takes
    // the newest row — the variant deploys after the main application, so it must not be there.
    // It stays on its own application's listing, naming the variant it is.
    String environmentId = createEnvironment("swarm-variant-requests");
    specs.script(
        "repo-swarm-req",
        DeploymentSpecParser.parse("variants: private\n", "deployments.yml of repo-swarm-req"));
    specs.scriptAt(
        "repo-swarm-req",
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parse(
            "application: repo-swarm-req-private\n", "deployments.private.yml of repo-swarm-req"));

    postRelease("run-variant-requests", "repo-swarm-req");
    awaitSettled(environmentId, 2);

    List<Map<String, Object>> release = requests("repoId=repo-swarm-req&version=" + VERSION);
    assertEquals(1, release.size(), release.toString());
    assertEquals("repo-swarm-req", release.get(0).get("applicationName"));
    assertNull(release.get(0).get("variant"));
    // qits-2296: the main row names the variants the release declares, so a reader waiting on the
    // whole release knows a variant row is coming before it exists.
    assertEquals(List.of("private"), release.get(0).get("variants"));
    assertEquals(
        release,
        requests("repoId=repo-swarm-req&version=" + VERSION + "&includeVariants=false"),
        "false is the default listing");

    // ...and asked for, the release's listing carries the variant's row too, newest first.
    List<Map<String, Object>> whole =
        requests("repoId=repo-swarm-req&version=" + VERSION + "&includeVariants=true");
    assertEquals(
        List.of("repo-swarm-req-private", "repo-swarm-req"),
        whole.stream().map(row -> (String) row.get("applicationName")).toList(),
        whole.toString());
    assertEquals("private", whole.get(0).get("variant"));
    assertEquals(List.of(), whole.get(0).get("variants"), "a variant's row declares nothing");
    assertEquals("ACTIVE", whole.get(0).get("deploymentStatus"));
    assertEquals(List.of("private"), whole.get(1).get("variants"));

    List<Map<String, Object>> variant =
        requests("environmentId=" + environmentId + "&applicationName=repo-swarm-req-private");
    assertEquals(1, variant.size(), variant.toString());
    assertEquals("private", variant.get(0).get("variant"));
    assertEquals("repo-swarm-req", variant.get(0).get("repoId"));
    assertEquals(VERSION, variant.get(0).get("version"));

    assertEquals(
        List.of("repo-swarm-req", "repo-swarm-req-private"),
        requests("environmentId=" + environmentId).stream()
            .map(row -> (String) row.get("applicationName"))
            .sorted()
            .toList(),
        "the tier's listing still carries both");
  }

  @Test
  public void aBrokenVariantFileNeverStopsTheMainDeployment() {
    String environmentId = createEnvironment("swarm-variant-broken");
    specs.script(
        "repo-swarm-broken",
        DeploymentSpecParser.parse(
            "variants: private, missing, same\n", "deployments.yml of repo-swarm-broken"));
    specs.scriptFailureAt(
        "repo-swarm-broken",
        SpecSource.variantPath("private"),
        "deployments.private.yml line 1: unknown key `nonsense`");
    // `missing` is scripted nowhere: the release carries no such file.
    specs.scriptAt(
        "repo-swarm-broken",
        SpecSource.variantPath("same"),
        DeploymentSpecParser.parse("application: repo-swarm-broken\n", "deployments.same.yml"));

    postRelease("run-variant-broken", "repo-swarm-broken");
    // The main application, and one FAILED row for each broken variant (qits-2296).
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 4);

    Map<String, Object> main =
        deployments.stream()
            .filter(row -> "repo-swarm-broken".equals(row.get("applicationName")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(deployments.toString()));
    assertEquals("ACTIVE", main.get("status"));
    assertEquals(1, fake.applied().size(), "only the main application was applied");
    assertEquals(1, all("DeploymentActive").size());
  }

  @Test
  public void anUnregisteredVariantThatBreaksStillEndsInAFailedRow() {
    // qits-2296: the main row names every declared variant, and a reader waiting on the release
    // waits for a terminal row of each. A variant that breaks before it was ever registered has no
    // place of its own, so its FAILED row lands on the MAIN application's places, under the name
    // the variant would have carried (`<main>-<variant>`, or the file's own `application:`) and
    // with `variant` set — nothing is registered for it.
    String environmentId = createEnvironment("swarm-variant-unregistered");
    specs.script(
        "repo-swarm-unreg",
        DeploymentSpecParser.parse(
            "variants: private, missing, same\n", "deployments.yml of repo-swarm-unreg"));
    specs.scriptFailureAt(
        "repo-swarm-unreg",
        SpecSource.variantPath("private"),
        "deployments.private.yml line 1: unknown key `nonsense`");
    // `missing` is scripted nowhere; `same` names the main application, which a variant may not.
    specs.scriptAt(
        "repo-swarm-unreg",
        SpecSource.variantPath("same"),
        DeploymentSpecParser.parse("application: repo-swarm-unreg\n", "deployments.same.yml"));

    postRelease("run-variant-unregistered", "repo-swarm-unreg");
    awaitSettled(environmentId, 4);

    List<Map<String, Object>> whole =
        requests("repoId=repo-swarm-unreg&version=" + VERSION + "&includeVariants=true");
    Map<String, Object> mainRow =
        whole.stream()
            .filter(row -> row.get("variant") == null)
            .findFirst()
            .orElseThrow(() -> new AssertionError(whole.toString()));
    assertEquals(List.of("private", "missing", "same"), mainRow.get("variants"));
    assertEquals("ACTIVE", mainRow.get("deploymentStatus"));
    for (String variant : List.of("private", "missing", "same")) {
      Map<String, Object> row =
          whole.stream()
              .filter(r -> variant.equals(r.get("variant")))
              .findFirst()
              .orElseThrow(() -> new AssertionError(variant + " has no row: " + whole));
      assertEquals("repo-swarm-unreg-" + variant, row.get("applicationName"));
      assertEquals(environmentId, row.get("environmentId"), "the main application's place");
      assertEquals("FAILED", row.get("deploymentStatus"), row.toString());
      assertNotNull(row.get("deploymentId"));
    }
    assertEquals(4, whole.size(), whole.toString());
    assertEquals(
        1,
        requests("repoId=repo-swarm-unreg&version=" + VERSION).size(),
        "the default listing is still the main application's alone");

    List<OutboxEvent> failures = all("DeploymentFailed");
    assertEquals(3, failures.size());
    assertTrue(
        failures.stream()
            .allMatch(
                event ->
                    event.payload.contains("\"status\":\"FAILED\"")
                        && event.payload.contains("\"variant\":\"")),
        failures.toString());
    // Nothing was registered for them: a variant's next, usable release registers it for real.
    assertEquals(1, fake.applied().size(), "only the main application was applied");
  }

  @Test
  public void aVariantFileIsReadBeforeTheMainApplicationIsDeployed() {
    // qits-2252: the main deployment of qits-database-oci restarted the postgres the git host runs
    // on, and the variant read ten seconds later timed out. So every file of a release is read
    // before any of it is deployed — asked of the driver at the moment the variant file is read.
    String environmentId = createEnvironment("swarm-variant-order");
    specs.script(
        "repo-swarm-order",
        DeploymentSpecParser.parse("variants: private\n", "deployments.yml of repo-swarm-order"));
    specs.scriptAt(
        "repo-swarm-order",
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parse(
            "application: repo-swarm-order-private\n", "deployments.private.yml"));
    AtomicInteger appliedAtVariantRead = new AtomicInteger(-1);
    specs.onReadAt(
        "repo-swarm-order",
        SpecSource.variantPath("private"),
        () -> appliedAtVariantRead.set(fake.applied().size()));

    postRelease("run-variant-order", "repo-swarm-order");
    awaitSettled(environmentId, 2);

    assertEquals(0, appliedAtVariantRead.get(), "nothing was applied before the variant was read");
    assertEquals(2, fake.applied().size(), "and then both applications were");
    assertEquals(1, specs.readsOf("repo-swarm-order", SpecSource.variantPath("private")));
  }

  @Test
  public void aRegisteredVariantWhoseFileBreaksRecordsItsOwnFailure() {
    String environmentId = createEnvironment("swarm-variant-later");
    specs.script(
        "repo-swarm-later",
        DeploymentSpecParser.parse("variants: private\n", "deployments.yml of repo-swarm-later"));
    specs.scriptAt(
        "repo-swarm-later",
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parse(
            "application: repo-swarm-later-private\n", "deployments.private.yml"));
    postRelease("run-later-1", "repo-swarm-later");
    awaitSettled(environmentId, 2);

    specs.scriptFailureAt(
        "repo-swarm-later", SpecSource.variantPath("private"), "line 3: duplicate key `image`");
    postRelease("run-later-2", "repo-swarm-later");
    List<Map<String, Object>> deployments = awaitSettled(environmentId, 4);

    Map<String, Object> failed =
        deployments.stream()
            .filter(row -> "FAILED".equals(row.get("status")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(deployments.toString()));
    assertEquals("repo-swarm-later-private", failed.get("applicationName"));
    assertTrue(((String) failed.get("detail")).contains("variant private"), failed.toString());
    assertEquals(
        2,
        deployments.stream()
            .filter(
                row ->
                    "repo-swarm-later".equals(row.get("applicationName"))
                        && List.of("ACTIVE", "DECOMMISSIONED").contains(row.get("status")))
            .count(),
        "the main application deployed both times");
    OutboxEvent failure = only("DeploymentFailed");
    assertTrue(failure.payload.contains("\"variant\":\"private\""), failure.payload);
    // The failed variant's request is its own, and never the release's verdict (qits-1984).
    List<Map<String, Object>> release = requests("repoId=repo-swarm-later&version=" + VERSION);
    assertEquals(2, release.size(), release.toString());
    assertTrue(
        release.stream().allMatch(row -> "repo-swarm-later".equals(row.get("applicationName"))),
        release.toString());
    List<Map<String, Object>> variant =
        requests("environmentId=" + environmentId + "&applicationName=repo-swarm-later-private");
    assertEquals(2, variant.size(), variant.toString());
    assertTrue(variant.stream().allMatch(row -> "private".equals(row.get("variant"))));
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
        .header("Authorization", SuiteBearers.platform())
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
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", runId, "repoId", repoId, "version", VERSION))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private List<Map<String, Object>> requests(String query) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployment-requests?" + query)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deploymentRequests");
  }

  private List<Map<String, Object>> deployments(String environmentId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
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

  private List<OutboxEvent> all(String name) {
    return rows().stream().filter(row -> name.equals(row.name)).toList();
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
