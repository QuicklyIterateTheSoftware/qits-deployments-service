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
 * A tier's three lifecycle events, on the wire. {@link DeployEventsTest}'s sibling and written the
 * same way — plain JUnit, because an event class is data and the serializer it is asserted against
 * builds its own mapper precisely so no container is needed to know what it emits.
 *
 * <p>These assertions are the contract qits-edge is written against when it stops reading its
 * environment list out of static configuration, so a change here that is not also a change there is
 * a cross-repo break rather than a refactor.
 */
class EnvironmentEventsTest {

  /** The stable key. It is assigned at creation and survives every rename — see the tests below. */
  private static final String ENV_ID = "9c1a5f2e-0d3b-4f77-9a11-6d5e2c0b7a44";

  private static final Instant CREATED = Instant.parse("2026-09-26T08:00:00Z");
  private static final Instant CHANGED = Instant.parse("2026-09-26T09:30:00Z");
  private static final Instant DELETED = Instant.parse("2026-09-26T17:05:00Z");

  private static EnvironmentCreated aCreated() {
    return new EnvironmentCreated(ENV_ID, "preprod", false, CREATED);
  }

  private static EnvironmentChanged aChanged() {
    return new EnvironmentChanged(ENV_ID, "staging", true, CHANGED);
  }

  private static EnvironmentDeleted aDeleted() {
    return new EnvironmentDeleted(ENV_ID, "staging", DELETED);
  }

  @Test
  void everySignatureIsTheClassNameAndTheNameFollowsIt() {
    for (QitsEvent event : List.of(aCreated(), aChanged(), aDeleted())) {
      assertEquals(event.getClass().getSimpleName(), event.signature());
      assertEquals(event.signature(), event.name());
    }
  }

  @Test
  void occurredAtIsTheMomentTheStatementBecameTrue() {
    assertEquals(CREATED, aCreated().occurredAt());
    assertEquals(CHANGED, aChanged().occurredAt());
    assertEquals(DELETED, aDeleted().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    EnvironmentCreated event = aCreated();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    assertNotEquals(first, aCreated().eventId());
  }

  @Test
  void theCreatedEnvelopeIsTheRowsShape() {
    EventEnvelope envelope = EventEnvelope.of(aCreated());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "environment", "name", "occurredAt", "parentId", "payload", "publisher"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("EnvironmentCreated", json.get("name").asText());
    assertEquals("2026-09-26T08:00:00Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"createdAt\":\"2026-09-26T08:00:00Z\",\"designated\":false,"
            + "\"environmentId\":\""
            + ENV_ID
            + "\",\"environmentName\":\"preprod\"}",
        json.get("payload").asText());
  }

  @Test
  void theIdentityTravelsInTheEnvelopeAndNeverInThePayload() {
    for (QitsEvent event : List.of(aCreated(), aChanged(), aDeleted())) {
      String payload = CanonicalJson.payload(event);
      assertFalse(payload.contains("eventId"), payload);
      assertFalse(payload.contains(event.eventId().toString()), payload);
      assertFalse(payload.contains("signature"), payload);
    }
  }

  @Test
  void aRenameIsTheSameIdCarryingADifferentName() {
    // The whole reason environmentId is the identity: a consumer keyed by it updates one row in
    // place. Keyed by name, it would hold `preprod` for ever with nothing ever to retire it —
    // no EnvironmentDeleted is coming for a tier that was renamed rather than deleted.
    assertEquals(aCreated().environmentId(), aChanged().environmentId());
    assertNotEquals(aCreated().environmentName(), aChanged().environmentName());

    String payload = CanonicalJson.payload(aChanged());
    assertTrue(payload.contains("\"environmentId\":\"" + ENV_ID + "\""), payload);
    assertTrue(payload.contains("\"environmentName\":\"staging\""), payload);
    // No previousName, deliberately: the stable id makes it unnecessary, and carrying it would
    // invite the name-keyed projection the id exists to make impossible to need.
    assertFalse(payload.contains("previousName"), payload);
    assertFalse(payload.contains("preprod"), payload);
  }

  @Test
  void theDeletionNamesTheTierWithoutADesignationField() {
    String payload = CanonicalJson.payload(aDeleted());

    assertTrue(payload.contains("\"environmentId\":\"" + ENV_ID + "\""), payload);
    assertTrue(payload.contains("\"environmentName\":\"staging\""), payload);
    assertTrue(payload.contains("\"deletedAt\":\"2026-09-26T17:05:00Z\""), payload);
    // The designated tier cannot be deleted, so the field could only ever be false.
    assertFalse(payload.contains("designated"), payload);
  }

  @Test
  void aConsumerReadsEveryPayloadBackIntoItsEvent() {
    EnvironmentCreated created =
        CanonicalJson.payloadTo(CanonicalJson.payload(aCreated()), EnvironmentCreated.class);
    assertEquals(ENV_ID, created.environmentId());
    assertEquals("preprod", created.environmentName());
    assertFalse(created.designated());
    assertEquals(CREATED, created.occurredAt());

    EnvironmentChanged changed =
        CanonicalJson.payloadTo(CanonicalJson.payload(aChanged()), EnvironmentChanged.class);
    assertEquals(ENV_ID, changed.environmentId());
    assertEquals("staging", changed.environmentName());
    assertTrue(changed.designated());
    assertEquals(CHANGED, changed.occurredAt());

    EnvironmentDeleted deleted =
        CanonicalJson.payloadTo(CanonicalJson.payload(aDeleted()), EnvironmentDeleted.class);
    assertEquals(ENV_ID, deleted.environmentId());
    assertEquals("staging", deleted.environmentName());
    assertEquals(DELETED, deleted.occurredAt());
  }

  @Test
  void aHistoricalFrameWithNoIdentityIsGivenOne() {
    // The envelope carries the id, so a payload bound back into a record has none. A null there
    // must not become a null idempotency key.
    EnvironmentCreated bound =
        CanonicalJson.payloadTo(CanonicalJson.payload(aCreated()), EnvironmentCreated.class);
    assertEquals(4, bound.eventId().version());
  }
}
