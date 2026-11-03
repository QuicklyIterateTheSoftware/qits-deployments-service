package eu.wohlben.qits.deployments.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A tier's own lifecycle on the bus: created, renamed, deleted, driven through the real environment
 * door and asserted as the outbox rows a consumer would read.
 *
 * <p>Everything {@link PdDeployPublishTest} argues about the method applies unchanged — the bus is
 * aimed at a closed port, so every published event becomes exactly one row carrying the canonical
 * payload it would have been sent with, and a row IS the publish from this side. <b>It shares that
 * class's {@code BusEnabled} profile deliberately</b>: a second profile class of identical content
 * would be a second Quarkus application to start and another ~125 MB of retained metaspace, for no
 * configuration difference at all.
 *
 * <p><b>What this covers that the deployment suite cannot.</b> A deployment announces a tier's name
 * in passing; nothing there says the tier EXISTS, and nothing there could ever say it stopped. These
 * three events are the lifecycle qits-edge follows instead of the static {@code
 * qits.edge.environments} list it used to drift against, so the assertions below — id as the
 * identity, a rename as an in-place update under that id, a delete that names the tier — are the
 * contract that consumer is written against.
 */
@QuarkusTest
@TestProfile(PdDeployPublishTest.BusEnabled.class)
public class PdEnvironmentPublishTest {

  @Inject FakeDeploymentDriver driver;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  @Test
  public void creatingATierAnnouncesItWithItsIdNameAndDesignation() {
    String cause = UUID.randomUUID().toString();
    String environmentId = createEnvironment("env-pub-created", false, cause);

    OutboxEvent created = only("EnvironmentCreated");
    assertTrue(
        created.payload.contains("\"environmentId\":\"" + environmentId + "\""), created.payload);
    assertTrue(created.payload.contains("\"environmentName\":\"env-pub-created\""), created.payload);
    assertTrue(created.payload.contains("\"designated\":false"), created.payload);
    assertNotNull(created.occurredAt, "createdAt is the event's occurredAt");
    assertEquals(cause, created.parentId, "the door's causation header is the ambient cause");

    // The library's mix-in keeps the identity in the envelope and out of the payload. Asserted
    // here because an unregistered mix-in in the native binary changes the wire contract silently.
    assertFalse(created.payload.contains("eventId"), created.payload);

    // The bundle network is a docker detail of this host and is deliberately not on the wire.
    assertFalse(created.payload.contains("network"), created.payload);

    assertNull(only("EnvironmentChanged", 0), "creating a tier changes nothing");
    assertNull(only("EnvironmentDeleted", 0), "creating a tier deletes nothing");
  }

  @Test
  public void designatingATierOnCreationTravelsOnTheEvent() {
    createEnvironment("env-pub-designated", true, null);

    OutboxEvent created = only("EnvironmentCreated");
    assertTrue(created.payload.contains("\"designated\":true"), created.payload);
    assertNull(created.parentId, "a bare POST with no header is a rootless statement");
  }

  @Test
  public void aRenameIsAnInPlaceUpdateUnderTheSameId() {
    // THE claim of this design. The id is the identity, so a rename is one row changing its name
    // and never a new tier appearing beside an old one nothing will ever retire.
    String environmentId = createEnvironment("env-pub-before", false, null);
    clearOutbox();

    patchEnvironment(environmentId, Map.of("name", "env-pub-after"));

    OutboxEvent changed = only("EnvironmentChanged");
    assertTrue(
        changed.payload.contains("\"environmentId\":\"" + environmentId + "\""),
        "the id is unchanged by a rename, which is what makes it the key: " + changed.payload);
    assertTrue(changed.payload.contains("\"environmentName\":\"env-pub-after\""), changed.payload);
    assertFalse(changed.payload.contains("env-pub-before"), changed.payload);

    // No previousName, and no second event standing in for one: the stable id is the whole answer.
    assertFalse(changed.payload.contains("previousName"), changed.payload);
    assertNull(only("EnvironmentCreated", 0), "a rename creates nothing");
    assertNull(only("EnvironmentDeleted", 0), "a rename deletes nothing");
  }

  @Test
  public void movingTheDesignationAnnouncesTheTierThatGainedIt() {
    String environmentId = createEnvironment("env-pub-promote", false, null);
    clearOutbox();

    patchEnvironment(environmentId, Map.of("designated", true));

    OutboxEvent changed = only("EnvironmentChanged");
    assertTrue(changed.payload.contains("\"designated\":true"), changed.payload);
    // The whole current state travels, not a diff: the name is on the event although it did not
    // move, so a consumer replaces the row it holds rather than merging two halves.
    assertTrue(changed.payload.contains("\"environmentName\":\"env-pub-promote\""), changed.payload);
  }

  @Test
  public void deletingATierAnnouncesItsIdAndTheNameItHadAtTheEnd() {
    String environmentId = createEnvironment("env-pub-doomed", false, null);
    patchEnvironment(environmentId, Map.of("name", "env-pub-renamed-then-deleted"));
    clearOutbox();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .delete("/deployments/api/environments/" + environmentId)
        .then()
        .statusCode(204);

    OutboxEvent deleted = only("EnvironmentDeleted");
    assertTrue(
        deleted.payload.contains("\"environmentId\":\"" + environmentId + "\""), deleted.payload);
    // The name as of the deletion, which a rename moved since the tier was created.
    assertTrue(
        deleted.payload.contains("\"environmentName\":\"env-pub-renamed-then-deleted\""), deleted.payload);
    // The designated tier cannot be deleted, so the field would carry one value and is absent.
    assertFalse(deleted.payload.contains("designated"), deleted.payload);
    assertNotNull(deleted.occurredAt);
  }

  @Test
  public void aRefusedDeleteAnnouncesNothing() {
    // The platform tier is refused, and the announcement must not run ahead of the row: a
    // consumer told a tier is gone has nothing that would ever tell it otherwise.
    String environmentId = createEnvironment("env-pub-platform", true, null);
    clearOutbox();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .delete("/deployments/api/environments/" + environmentId)
        .then()
        .statusCode(409);

    assertNull(only("EnvironmentDeleted", 0), "the delete was refused, so the tier is still there");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String createEnvironment(String name, boolean designated, String cause) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("designated", designated);
    var request = given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON).body(body);
    if (cause != null) {
      request = request.header(CausationHeader.NAME, cause);
    }
    return request
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void patchEnvironment(String environmentId, Map<String, Object> body) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .patch("/deployments/api/environments/" + environmentId)
        .then()
        .statusCode(200);
  }

  private void clearOutbox() {
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  private List<OutboxEvent> rows() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList());
  }

  /** The one outbox row of that event type, failing the test if there is not exactly one. */
  private OutboxEvent only(String name) {
    OutboxEvent row = only(name, 1);
    assertNotNull(row, "expected one " + name + " row");
    return row;
  }

  /** The single row of that type, or null when {@code expected} is 0 and there are none. */
  private OutboxEvent only(String name, int expected) {
    List<OutboxEvent> matching = rows().stream().filter(row -> name.equals(row.name)).toList();
    assertEquals(expected, matching.size(), () -> "expected " + expected + " " + name + " row(s)");
    return matching.isEmpty() ? null : matching.get(0);
  }
}
