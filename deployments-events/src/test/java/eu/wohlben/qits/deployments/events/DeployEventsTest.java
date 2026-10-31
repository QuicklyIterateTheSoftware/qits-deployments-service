package eu.wohlben.qits.deployments.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The deploy lifecycle's four events, on the wire. Plain JUnit — an event class is data, and the
 * serializer it is asserted against builds its own mapper precisely so no container is needed to
 * know what it emits.
 *
 * <p>These assertions are the contract every consumer is written against, so a change here that is
 * not also a change there is a cross-repo break rather than a refactor.
 */
class DeployEventsTest {

  /** The released coordinate — the tag the image carries, and what a deployment is identified by. */
  private static final String VERSION = "2026.812.90042";

  /** The commit the released tag resolved to: the trace edge, and nullable on the wire. */
  private static final String SHA = "a".repeat(40);
  private static final Instant QUEUED = Instant.parse("2026-08-12T09:00:00Z");
  private static final Instant STARTED = Instant.parse("2026-08-12T09:00:01Z");
  private static final Instant FINISHED = Instant.parse("2026-08-12T09:00:42Z");

  private static DeploymentQueued aQueued() {
    return new DeploymentQueued(
        "d-1", "qits-gateway", "env-1", "dev", VERSION, SHA, "run-1", QUEUED);
  }

  private static DeploymentStarted aStarted() {
    return new DeploymentStarted(
        "d-1", "qits-gateway", "env-1", "dev", VERSION, SHA, "run-1", STARTED);
  }

  private static DeploymentActive anActive() {
    return new DeploymentActive(
        "d-1", "qits-gateway", "env-1", "dev", VERSION, SHA, "run-1",
        "qits-pd-dev-qits-gateway-d1", FINISHED);
  }

  private static DeploymentFailed aFailed() {
    return new DeploymentFailed(
        "d-1", "qits-gateway", "env-1", "dev", VERSION, SHA, "run-1", "FAILED",
        "container exited 1", FINISHED);
  }

  @Test
  void everySignatureIsTheClassNameAndTheNameFollowsIt() {
    for (QitsEvent event : List.of(aQueued(), aStarted(), anActive(), aFailed())) {
      assertEquals(event.getClass().getSimpleName(), event.signature());
      assertEquals(event.signature(), event.name());
    }
  }

  @Test
  void occurredAtIsTheRowsOwnTimestampRatherThanThePublishMoment() {
    assertEquals(QUEUED, aQueued().occurredAt());
    assertEquals(STARTED, aStarted().occurredAt());
    assertEquals(FINISHED, anActive().occurredAt());
    assertEquals(FINISHED, aFailed().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    DeploymentQueued event = aQueued();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    // Two deployments of the same facts are two occurrences and must not collide on one id.
    assertNotEquals(first, aQueued().eventId());
  }

  @Test
  void theQueuedEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(aQueued());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "environment", "name", "occurredAt", "parentId", "payload", "publisher"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("DeploymentQueued", json.get("name").asText());
    assertEquals("2026-08-12T09:00:00Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"applicationName\":\"qits-gateway\",\"commitSha\":\""
            + SHA
            + "\",\"deploymentId\":\"d-1\",\"environmentId\":\"env-1\","
            + "\"environmentName\":\"dev\",\"queuedAt\":\"2026-08-12T09:00:00Z\","
            + "\"runId\":\"run-1\",\"version\":\""
            + VERSION
            + "\"}",
        json.get("payload").asText());
  }

  @Test
  void theIdentityTravelsInTheEnvelopeAndNeverInThePayload() {
    DeploymentActive event = anActive();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("\"occurredAt\""), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void aPlatformPlaneDeploymentOmitsTheTierRatherThanNullingIt() {
    // Both nulls are the statement "this belongs to no tier", and an absent field is not written as
    // an explicit null.
    DeploymentActive platform =
        new DeploymentActive(
            "d-2", "qits-platform-idp", null, null, VERSION, SHA, "run-2",
            "qits-pd-qits-platform-idp-d2", FINISHED);

    String payload = CanonicalJson.payload(platform);

    assertFalse(payload.contains("environmentId"), payload);
    assertFalse(payload.contains("environmentName"), payload);
    assertFalse(payload.contains("null"), payload);
    assertTrue(payload.contains("\"containerName\":\"qits-pd-qits-platform-idp-d2\""), payload);
  }

  @Test
  void aFailureWithNoDetailOmitsIt() {
    DeploymentFailed bare =
        new DeploymentFailed(
            "d-3", "qits-gateway", "env-1", "dev", VERSION, SHA, null, "IMAGE_MISSING", null,
            FINISHED);

    String payload = CanonicalJson.payload(bare);

    assertFalse(payload.contains("detail"), payload);
    assertFalse(payload.contains("runId"), payload);
    assertTrue(payload.contains("\"status\":\"IMAGE_MISSING\""), payload);
  }

  @Test
  void aSubscriberReadsEveryPayloadBackIntoItsEvent() {
    DeploymentQueued queued =
        CanonicalJson.payloadTo(CanonicalJson.payload(aQueued()), DeploymentQueued.class);
    assertEquals("d-1", queued.deploymentId());
    assertEquals("qits-gateway", queued.applicationName());
    assertEquals("dev", queued.environmentName());
    assertEquals(QUEUED, queued.occurredAt());

    DeploymentStarted started =
        CanonicalJson.payloadTo(CanonicalJson.payload(aStarted()), DeploymentStarted.class);
    assertEquals(STARTED, started.occurredAt());

    DeploymentActive active =
        CanonicalJson.payloadTo(CanonicalJson.payload(anActive()), DeploymentActive.class);
    assertEquals("qits-pd-dev-qits-gateway-d1", active.containerName());
    assertEquals(VERSION, active.version());
    assertEquals(SHA, active.commitSha());

    DeploymentFailed failed =
        CanonicalJson.payloadTo(CanonicalJson.payload(aFailed()), DeploymentFailed.class);
    assertEquals("FAILED", failed.status());
    assertEquals("container exited 1", failed.detail());
    assertEquals(FINISHED, failed.occurredAt());
  }

  @Test
  void anActiveEventCarriesTheCompleteResolvedEndpointSnapshot() {
    DeploymentActive active =
        new DeploymentActive(
            "d-1",
            "qits-refinement",
            "env-1",
            "dev",
            VERSION,
            SHA,
            "run-1",
            "dev-qits-refinement",
            FINISHED,
            "refinement",
            "/refinement/q/swagger-ui",
            List.of(
                new NavigationEntry("services.details", "Refinement", 3),
                new NavigationEntry("platform", "Refinement", 1)),
            List.of(
                new DeploymentEndpoint("/refinement", "dev-qits-refinement", 8080),
                new DeploymentEndpoint("/refinement/api", "dev-qits-refinement", 8080)));

    String payload = CanonicalJson.payload(active);
    assertTrue(payload.contains("\"path\":\"/refinement\""), payload);
    assertTrue(payload.contains("\"upstreamHost\":\"dev-qits-refinement\""), payload);
    assertTrue(payload.contains("\"upstreamPort\":8080"), payload);
    // The host is one LABEL and the navigation is the application's, not a route's.
    assertTrue(payload.contains("\"browserHost\":\"refinement\""), payload);
    // An entry with no subpath spells exactly what it always did — the field is omitted, so every
    // consumer that predates it reads the entry it always read.
    assertTrue(
        payload.contains("{\"label\":\"Refinement\",\"position\":3,\"slot\":\"services.details\"}"),
        payload);
    assertFalse(payload.contains("navigationLabel"), payload);
    // The api-docs path is a PATH, never an origin — the edge composes the authority around it.
    assertTrue(payload.contains("\"apiDocsPath\":\"/refinement/q/swagger-ui\""), payload);

    DeploymentActive decoded = CanonicalJson.payloadTo(payload, DeploymentActive.class);
    assertEquals(active.endpoints(), decoded.endpoints());
    assertEquals(active.navigation(), decoded.navigation());
    assertEquals("refinement", decoded.browserHost());
    assertEquals("/refinement/q/swagger-ui", decoded.apiDocsPath());
  }

  @Test
  void aNavigationEntryCarriesItsSubpathAndAnOlderFrameReadsBackWithout() {
    DeploymentActive active =
        new DeploymentActive(
            "d-1",
            "qits-refinement",
            "env-1",
            "dev",
            VERSION,
            SHA,
            "run-1",
            "dev-qits-refinement",
            FINISHED,
            "refinement",
            null,
            List.of(new NavigationEntry("services.details", "Api Docs", 6, "api-docs")),
            List.of(new DeploymentEndpoint("/refinement", "dev-qits-refinement", 8080)));

    String payload = CanonicalJson.payload(active);
    assertTrue(payload.contains("\"subpath\":\"api-docs\""), payload);
    assertFalse(payload.contains("apiDocsPath"), payload);

    DeploymentActive decoded = CanonicalJson.payloadTo(payload, DeploymentActive.class);
    assertEquals("api-docs", decoded.navigation().get(0).subpath());

    // A frame written before either field existed reads back as the null both records normalize:
    // the shape every already-recorded event on the log has.
    DeploymentActive old =
        CanonicalJson.payloadTo(
            payload
                .replace("\"subpath\":\"api-docs\",", "")
                .replace(",\"subpath\":\"api-docs\"", ""),
            DeploymentActive.class);
    assertEquals(null, old.navigation().get(0).subpath());
    assertEquals(null, old.apiDocsPath());
  }

  @Test
  void anApplicationWithNoHostOfItsOwnOmitsItAndSaysSoAboutItsNavigation() {
    // The common answer, and both halves matter: an omitted host is an application reached under
    // its path prefix, and an explicit empty list is "this deployment creates no menu option" —
    // which a consumer replaces its own state with rather than reading as an older publisher.
    String payload = CanonicalJson.payload(anActive());

    assertFalse(payload.contains("browserHost"), payload);
    assertTrue(payload.contains("\"navigation\":[]"), payload);
  }

  @Test
  void aReleaseWithNoResolvedCommitOmitsTheShaAndStillCarriesItsVersion() {
    // The 404 case: a repository that carries no deployments.yml gets the defaults and no commit,
    // because a missing blob says nothing about where the tag points. The VERSION is the
    // coordinate and is always there; the sha is a trace edge and is absent rather than null.
    DeploymentActive noCommit =
        new DeploymentActive(
            "d-4", "qits-oci-postgresql", "env-1", "dev", VERSION, null, "run-4",
            "qits-pd-dev-qits-oci-postgresql-d4", FINISHED);

    String payload = CanonicalJson.payload(noCommit);

    assertFalse(payload.contains("commitSha"), payload);
    assertTrue(payload.contains("\"version\":\"" + VERSION + "\""), payload);
  }

  @Test
  void anActiveEventWithNoRoutesStillPublishesAnExplicitEmptySnapshot() {
    String payload = CanonicalJson.payload(anActive());

    // Omission would be ambiguous to the edge: it could mean an older publisher, or it could mean
    // that this successful deployment removed all routes. The wire must say the latter explicitly.
    assertTrue(payload.contains("\"endpoints\":[]"), payload);
  }
}
