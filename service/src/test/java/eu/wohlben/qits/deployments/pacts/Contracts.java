package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.confighost.ConfigHostDeclarationSeed;
import eu.wohlben.qits.deployments.confighost.ExtrasBearer;
import eu.wohlben.qits.deployments.confighost.ExtrasStub;
import eu.wohlben.qits.deployments.deployments.control.IdpClientProvisioner;
import eu.wohlben.qits.deployments.idphost.IdpStub;
import eu.wohlben.qits.deployments.pacts.Contract.Request;
import eu.wohlben.qits.deployments.pacts.Contract.Row;
import eu.wohlben.qits.deployments.pacts.Contract.Trigger;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.control.EventsProbe;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * <b>Every JSON REST call qits-deployments-service makes to another qits service</b> (ticket
 * qits-1149), one {@link Contract} per provider. The inventory beside this ticket lists where each
 * call is made; this is the same list as rows a pact can be built from.
 *
 * <ul>
 *   <li>{@link #EVENTS} — the bus library's catch-up reads and its publish, which this service runs
 *       for its two durable listeners ({@code SoftwareRelease}, {@code RepositoryRenamed}) and its
 *       outbox;
 *   <li>{@link #CONFIGURATION} — {@code ConfigHostExtrasSource}'s resolved read and {@code
 *       ConfigHostDeclarationSeed}'s declaration POST;
 *   <li>{@link #IDP} — {@code HttpIdpClientProvisioner}'s three service-client calls.
 * </ul>
 *
 * <p>qits-configuration and qits-idp publish no golden masters yet, so their rows wait, each naming
 * the provider state it needs. Their operationIds are the provider's Java method names: neither
 * provider declares an {@code @Operation} for these routes yet.
 */
public final class Contracts {

  private Contracts() {}

  /** Every contract this repository holds. */
  public static List<Contract> all() {
    return List.of(EVENTS, CONFIGURATION, IDP);
  }

  // --- qits-events -----------------------------------------------------------------------------

  static final String NO_EVENTS = "no events";
  static final String A_PAGE_WITH_MORE_TO_COME = "a page of SoftwareRelease events with more to come";
  static final String A_NEW_EVENT_ID = "an event id the log does not hold";

  private static final String EVENTS_PATH = "/events/api/events";
  private static final String PUBLISH_ID = "00000000-0000-4000-8000-0000000000aa";

  private static final EventEnvelope PUBLISHED =
      new EventEnvelope(
          "DeploymentActive",
          Instant.parse("2026-01-01T00:00:00Z"),
          "{\"application\":\"qits-ci\"}",
          "qits-ci is deployed",
          null,
          "dev");

  /** What the catch-up sweep reads of a page: every frame field it hands on, and the cursor. */
  private static final List<String> PAGE_READ =
      List.of(
          "events[].id",
          "events[].name",
          "events[].occurredAt",
          "events[].payload",
          "events[].description",
          "events[].parentId",
          "events[].environment",
          "nextCursor");

  private static Map<String, String> query(String... pairs) {
    Map<String, String> query = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      query.put(pairs[i], pairs[i + 1]);
    }
    return query;
  }

  public static final Contract EVENTS =
      new Contract(
          "qits-events-service",
          "qits-events",
          List.of(
              // A durable listener that has never run starts at the head: the newest matching event.
              new Row(
                  Trigger.schedule("CatchupSweeper.initialize"),
                  NO_EVENTS,
                  "listEvents",
                  Request.get(EVENTS_PATH, query("limit", "1", "name", "SoftwareRelease")),
                  List.of("events[].id", "events[].occurredAt"),
                  (url, params) ->
                      assertNull(EventsProbe.newest(url, List.of("SoftwareRelease")), "an empty log has no head")),
              // RepositoryRenamed replays from the epoch: the first page, no cursor, oldest first.
              new Row(
                  Trigger.schedule("CatchupSweeper.catchUp"),
                  NO_EVENTS,
                  "listEvents",
                  Request.get(
                      EVENTS_PATH,
                      query("order", "asc", "limit", String.valueOf(EventsProbe.pageSize()), "name", "RepositoryRenamed")),
                  PAGE_READ,
                  (url, params) -> {
                    EventsProbe.Page page =
                        EventsProbe.after(url, List.of("RepositoryRenamed"), null, EventsProbe.pageSize());
                    assertTrue(page.events().isEmpty(), "an empty log is an empty page");
                    assertNull(page.nextCursor(), "and the last one");
                  }),
              // A page that is not the last: frames to hand on, and the cursor to the next page.
              new Row(
                  Trigger.schedule("CatchupSweeper.catchUp"),
                  A_PAGE_WITH_MORE_TO_COME,
                  "listEvents",
                  Request.get(
                      EVENTS_PATH,
                      query(
                          "order", "asc",
                          "limit", String.valueOf(EventsProbe.pageSize()),
                          "name", "SoftwareRelease",
                          "cursor", "2026-01-01T00:00:00Z,00000000-0000-4000-8000-000000000001")),
                  PAGE_READ,
                  (url, params) -> {
                    EventsProbe.Page page =
                        EventsProbe.after(
                            url,
                            List.of("SoftwareRelease"),
                            "2026-01-01T00:00:00Z,00000000-0000-4000-8000-000000000001",
                            EventsProbe.pageSize());
                    assertFalse(page.events().isEmpty(), "a page with more to come holds events");
                    EventFrame first = page.events().get(0);
                    assertNotNull(first.id());
                    assertEquals("SoftwareRelease", first.name());
                    assertNotNull(first.occurredAt());
                    assertNotNull(page.nextCursor(), "and names the next page");
                  }),
              // The outbox delivers each event once, under the id it was stored with.
              new Row(
                  Trigger.schedule("OutboxSweeper.sweep"),
                  A_NEW_EVENT_ID,
                  "publish",
                  Request.send(
                      "PUT",
                      EVENTS_PATH + "/" + PUBLISH_ID,
                      Map.of(),
                      EventsProbe.body(PUBLISHED),
                      "application/json"),
                  List.of(),
                  (url, params) ->
                      assertTrue(
                          EventsProbe.put(url, params.getOrDefault("eventId", PUBLISH_ID), PUBLISHED).delivered(),
                          "a new id is delivered"))));

  // --- qits-configuration ----------------------------------------------------------------------

  static final String A_RESOLVED_CONFIGURATION_AT_A_VERSION =
      "an application with a declaration and stored entries at a version";
  static final String A_RESOLVED_CONFIGURATION_WITHOUT_A_DECLARATION =
      "an application with stored entries and no declaration";
  static final String AN_APPLICATION_WITH_NO_DECLARATION_AT_THE_VERSION =
      "an application with no declaration at the version";

  private static final ExtrasBearer NO_BEARER = Optional::empty;
  private static final String APPLICATION = "qits-ci";
  private static final String ENVIRONMENT = "dev";
  private static final String VERSION = "2026.101.120000";
  private static final String DECLARATION = "entries: {}\n";

  private static Config boot() {
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(Map.of(), "boot", 260))
        .build();
  }

  /** What the resolved read binds: the properties map (every value a string), and the revision it logs. */
  private static final List<String> RESOLVED_READ = List.of("properties", "headRevision");

  public static final Contract CONFIGURATION =
      new Contract(
          "qits-configuration-service",
          "qits-configuration",
          List.of(
              new Row(
                  Trigger.event("SoftwareRelease"),
                  A_RESOLVED_CONFIGURATION_AT_A_VERSION,
                  "resolvedIn",
                  Request.get(
                      "/configuration/api/applications/" + APPLICATION + "/envs/" + ENVIRONMENT + "/resolved",
                      query("version", VERSION)),
                  RESOLVED_READ,
                  (url, params) -> {
                    Config served =
                        ExtrasStub.source(boot(), "config/application.properties", NO_BEARER, url)
                            .forDeployment(
                                params.getOrDefault("application", APPLICATION),
                                params.getOrDefault("env", ENVIRONMENT),
                                params.getOrDefault("version", VERSION),
                                true);
                    assertNotNull(served);
                  }),
              // A release that declared nothing reads the stored entries alone.
              new Row(
                  Trigger.event("SoftwareRelease"),
                  A_RESOLVED_CONFIGURATION_WITHOUT_A_DECLARATION,
                  "resolvedIn",
                  Request.get(
                      "/configuration/api/applications/" + APPLICATION + "/envs/" + ENVIRONMENT + "/resolved",
                      Map.of()),
                  RESOLVED_READ,
                  (url, params) -> {
                    Config served =
                        ExtrasStub.source(boot(), "config/application.properties", NO_BEARER, url)
                            .forDeployment(
                                params.getOrDefault("application", APPLICATION),
                                params.getOrDefault("env", ENVIRONMENT),
                                params.getOrDefault("version", VERSION),
                                false);
                    assertNotNull(served);
                  }),
              // The seed reads the status alone: 2xx is stored, 409/422 is the file's own fault.
              new Row(
                  Trigger.event("SoftwareRelease"),
                  AN_APPLICATION_WITH_NO_DECLARATION_AT_THE_VERSION,
                  "declare",
                  Request.send(
                      "POST",
                      "/configuration/api/applications/" + APPLICATION + "/declarations/" + VERSION,
                      query("deploymentTarget", "environment"),
                      DECLARATION,
                      "application/yaml"),
                  List.of(),
                  (url, params) -> {
                    ConfigHostDeclarationSeed seed = ExtrasStub.seed(NO_BEARER, url);
                    seed.seed(
                        params.getOrDefault("application", APPLICATION),
                        params.getOrDefault("version", VERSION),
                        DECLARATION);
                  })));

  // --- qits-idp --------------------------------------------------------------------------------

  static final String A_DATABASE_SERVICE_CLIENT = "a database service client";
  static final String NO_SERVICE_CLIENT = "no service client with the id";

  private static final String CLIENT_ID = "dev-qits-ci";
  private static final String SERVICE_CLIENTS = "/idp/api/service-clients";

  private static IdpClientProvisioner idp(String url) {
    return IdpStub.adapterAt(url + SERVICE_CLIENTS, "dev-qits-deployments", "secret");
  }

  public static final Contract IDP =
      new Contract(
          "qits-idp-service",
          "qits-idp",
          List.of(
              new Row(
                  Trigger.event("SoftwareRelease"),
                  A_DATABASE_SERVICE_CLIENT,
                  "get",
                  Request.get(SERVICE_CLIENTS + "/" + CLIENT_ID, Map.of()),
                  List.of("source"),
                  (url, params) ->
                      assertTrue(idp(url).databaseClientPresent(params.getOrDefault("clientId", CLIENT_ID)))),
              new Row(
                  Trigger.event("SoftwareRelease"),
                  NO_SERVICE_CLIENT,
                  "create",
                  Request.send(
                      "POST",
                      SERVICE_CLIENTS,
                      Map.of(),
                      "{\"clientId\":\"" + CLIENT_ID + "\"}",
                      "application/json"),
                  List.of("secret"),
                  (url, params) ->
                      assertTrue(idp(url).create(params.getOrDefault("clientId", CLIENT_ID)).ok())),
              new Row(
                  Trigger.event("SoftwareRelease"),
                  A_DATABASE_SERVICE_CLIENT,
                  "rotate",
                  Request.send(
                      "POST", SERVICE_CLIENTS + "/" + CLIENT_ID + "/secret", Map.of(), "", "application/json"),
                  List.of("secret"),
                  (url, params) ->
                      assertTrue(idp(url).rotate(params.getOrDefault("clientId", CLIENT_ID)).ok()))));
}
