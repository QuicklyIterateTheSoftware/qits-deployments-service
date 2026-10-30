package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /deployments/api/settings} — the store's three doors and the preview (qits-1390).
 *
 * <p>Every case uses application and environment names of its own, because the suite shares one
 * database across classes. Calls with no header run as the {@code %test} dev user, which holds
 * every platform role; the role cases send the forward-auth pair, whose roles are then exactly
 * {@code X-Qits-Roles}.
 */
@QuarkusTest
class PdSettingApiTest {

  private static final String BASE = "/deployments/api";
  private static final String SETTINGS = BASE + "/settings";
  private static final String SECRET_VALUE = "a-secret-nobody-should-ever-see-on-this-door";

  @Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  private static RequestSpecification as(String user, String roles) {
    return given().header("X-Qits-User", user).header("X-Qits-Roles", roles);
  }

  private static RequestSpecification asAgent() {
    return as("dyn-workspace-agent-settings", "qits:agent");
  }

  private static void put(String app, String env, String key, String value, boolean secret) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("value", value, "secret", secret))
        .when()
        .put(SETTINGS + "/" + app + "/" + env + "/" + key)
        .then()
        .statusCode(200);
  }

  @Test
  void aPlainSettingRoundTripsAndNamesWhoWroteIt() {
    as("op-plain", "qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("value", "https://example.test", "secret", false))
        .when()
        .put(SETTINGS + "/set-plain/set-dev/PEER_URL")
        .then()
        .statusCode(200)
        .body("application", equalTo("set-plain"))
        .body("environment", equalTo("set-dev"))
        .body("key", equalTo("PEER_URL"))
        .body("value", equalTo("https://example.test"))
        .body("secret", equalTo(false))
        .body("updatedBy", equalTo("op-plain"))
        .body("updatedAt", not(nullValue()));

    asAgent()
        .when()
        .get(SETTINGS + "?application=set-plain&environment=set-dev")
        .then()
        .statusCode(200)
        .body("settings", hasSize(1))
        .body("settings[0].key", equalTo("PEER_URL"))
        .body("settings[0].value", equalTo("https://example.test"))
        .body("settings[0].updatedBy", equalTo("op-plain"));
  }

  @Test
  void anUpsertReplacesTheValueInPlace() {
    put("set-upsert", "set-dev", "LEVEL", "1", false);
    put("set-upsert", "set-dev", "LEVEL", "2", false);

    given()
        .when()
        .get(SETTINGS + "?application=set-upsert")
        .then()
        .statusCode(200)
        .body("settings", hasSize(1))
        .body("settings[0].value", equalTo("2"));
  }

  @Test
  void aSecretIsMaskedOnThePutAnswerAndOnTheListing() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("value", SECRET_VALUE, "secret", true))
        .when()
        .put(SETTINGS + "/set-secret/set-dev/API_KEY")
        .then()
        .statusCode(200)
        .body("secret", equalTo(true))
        .body("value", nullValue());

    String listing =
        asAgent()
            .when()
            .get(SETTINGS + "?environment=set-dev")
            .then()
            .statusCode(200)
            .body("settings.key", hasItem("API_KEY"))
            .extract()
            .asString();
    assertFalse(listing.contains(SECRET_VALUE), "the stored secret must never appear in a listing");

    List<Map<String, Object>> rows =
        given().when().get(SETTINGS).then().statusCode(200).extract().path("settings");
    Map<String, Object> row =
        rows.stream()
            .filter(r -> "set-secret".equals(r.get("application")))
            .findFirst()
            .orElseThrow();
    assertEquals(true, row.get("secret"));
    assertTrue(row.containsKey("value"));
    assertNull(row.get("value"));
  }

  @Test
  void aKeyThatIsNotAnEnvironmentVariableNameIs400() {
    for (String key : List.of("lower_case", "1LEADING_DIGIT", "DASH-ED")) {
      given()
          .contentType(ContentType.JSON)
          .body(Map.of("value", "v", "secret", false))
          .when()
          .put(SETTINGS + "/set-invalid/set-dev/" + key)
          .then()
          .statusCode(400);
    }
  }

  @Test
  void aMissingValueIs400() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("secret", true))
        .when()
        .put(SETTINGS + "/set-novalue/set-dev/SOME_KEY")
        .then()
        .statusCode(400);
  }

  @Test
  void aDeleteRemovesTheRowAndASecondDeleteIs404() {
    put("set-delete", "set-dev", "DOOMED", "x", false);

    given().when().delete(SETTINGS + "/set-delete/set-dev/DOOMED").then().statusCode(204);
    given().when().delete(SETTINGS + "/set-delete/set-dev/DOOMED").then().statusCode(404);
    given()
        .when()
        .get(SETTINGS + "?application=set-delete")
        .then()
        .statusCode(200)
        .body("settings", hasSize(0));
  }

  @Test
  void anAgentReadsButCannotWrite() {
    put("set-agent", "set-dev", "READ_ME", "x", false);

    asAgent().when().get(SETTINGS).then().statusCode(200);
    asAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("value", "y", "secret", false))
        .when()
        .put(SETTINGS + "/set-agent/set-dev/READ_ME")
        .then()
        .statusCode(403);
    asAgent().when().delete(SETTINGS + "/set-agent/set-dev/READ_ME").then().statusCode(403);
    // ...and an admin agent is a reader here too, not a writer.
    as("dyn-admin-workspace", "qits:admin-agent")
        .when()
        .delete(SETTINGS + "/set-agent/set-dev/READ_ME")
        .then()
        .statusCode(403);

    given()
        .when()
        .get(SETTINGS + "?application=set-agent")
        .then()
        .body("settings[0].value", equalTo("x"));
  }

  @Test
  void aSystemCallerWrites() {
    as("svc-settings-writer", "qits:system")
        .contentType(ContentType.JSON)
        .body(Map.of("value", "on", "secret", false))
        .when()
        .put(SETTINGS + "/set-system/set-dev/FEATURE")
        .then()
        .statusCode(200)
        .body("updatedBy", equalTo("svc-settings-writer"));
    as("svc-settings-writer", "qits:system")
        .when()
        .delete(SETTINGS + "/set-system/set-dev/FEATURE")
        .then()
        .statusCode(204);
  }

  // --- the preview -------------------------------------------------------------------------------

  private static String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + name + "\"}")
        .when()
        .post(BASE + "/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private static void registerService(String name, String environmentId) {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"branch\":null,\"availableOnEnv\":false,\"environmentIds\":[\""
                + environmentId
                + "\"]}")
        .when()
        .put(BASE + "/services/" + name)
        .then()
        .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(201)));
  }

  /** One place, scripted the same way for both previews: returns {@code app}. */
  private String scriptPreviewPlace(String env, String app) {
    registerService(app, createEnvironment(env));
    driver.scriptStagedEnvironment(
        app,
        List.of("QITS_APPLICATION=" + app, "QITS_RESOURCE_DB_PASSWORD=fresh"),
        List.of("FROM_EXTRAS=extras", "OVERRIDDEN=extras-value"));
    driver.scriptLiveEnvironment(
        PdNetworks.alias(env, app),
        List.of(
            "QITS_APPLICATION=" + app,
            "QITS_RESOURCE_DB_PASSWORD=stale",
            "QITS_RESOURCE_DB_URL=jdbc:postgresql://db/app",
            "FROM_EXTRAS=extras",
            "OVERRIDDEN=extras-value",
            "HAND_ADDED=by-an-operator",
            "SIGNING_KEY=old"));
    put(app, env, "OVERRIDDEN", "setting-value", false);
    put(app, env, "SIGNING_KEY", SECRET_VALUE, true);
    put(app, env, "NEW_SETTING", "new", false);
    return app;
  }

  @Test
  void theDefaultPreviewIsTheDeploymentAfterTheSwitchWithNoExtras() {
    String env = "set-preview";
    String app = scriptPreviewPlace(env, "set-preview-app");

    String body =
        asAgent()
            .when()
            .get(SETTINGS + "/preview/" + app + "/" + env)
            .then()
            .statusCode(200)
            .body("application", equalTo(app))
            .body("environment", equalTo(env))
            .body("extrasIncluded", equalTo(false))
            .body("unchanged", equalTo(1))
            .body("added.key", equalTo(List.of("NEW_SETTING")))
            // The extras key is what the switch would drop — the very thing this door exists to show.
            .body("removed.key", equalTo(List.of("FROM_EXTRAS", "HAND_ADDED")))
            .body("removed[1].value", equalTo("by-an-operator"))
            .body("changed.key", equalTo(List.of("OVERRIDDEN", "QITS_RESOURCE_DB_PASSWORD", "SIGNING_KEY")))
            .body("changed[0].from", equalTo("extras-value"))
            .body("changed[0].to", equalTo("setting-value"))
            .body("changed[0].secret", equalTo(false))
            .body("changed[1].from", nullValue())
            .body("changed[1].to", nullValue())
            .body("changed[1].secret", equalTo(true))
            .body("changed[2].from", nullValue())
            .body("changed[2].to", nullValue())
            .body("changed[2].secret", equalTo(true))
            // A resource key the next deployment does not state is KEPT by the update, never removed.
            .body("kept.key", equalTo(List.of("QITS_RESOURCE_DB_URL")))
            .body("kept[0].value", equalTo("jdbc:postgresql://db/app"))
            .extract()
            .asString();
    assertFalse(body.contains(SECRET_VALUE));
    assertFalse(body.contains("stale"));
    assertFalse(body.contains("fresh"));
    assertTrue(driver.calls().contains("stagedEnvironment:" + app + ":false"), driver.calls().toString());
  }

  @Test
  void extrasIncludeIsTodaysDeploymentWithTheSettingsOverlaid() {
    String env = "set-preview-extras";
    String app = scriptPreviewPlace(env, "set-extras-app");

    given()
        .when()
        .get(SETTINGS + "/preview/" + app + "/" + env + "?extras=include")
        .then()
        .statusCode(200)
        .body("extrasIncluded", equalTo(true))
        .body("unchanged", equalTo(2))
        .body("added.key", equalTo(List.of("NEW_SETTING")))
        .body("removed.key", equalTo(List.of("HAND_ADDED")))
        .body("changed.key", equalTo(List.of("OVERRIDDEN", "QITS_RESOURCE_DB_PASSWORD", "SIGNING_KEY")))
        .body("changed[0].to", equalTo("setting-value"))
        .body("kept.key", equalTo(List.of("QITS_RESOURCE_DB_URL")));
    given()
        .when()
        .get(SETTINGS + "/preview/" + app + "/" + env + "?extras=sometimes")
        .then()
        .statusCode(400);
  }

  @Test
  void aServiceThatIsNotRunningMakesEverythingAdded() {
    String env = "set-preview-cold";
    String app = "set-cold-app";
    registerService(app, createEnvironment(env));
    driver.scriptStagedEnvironment(app, List.of("QITS_APPLICATION=" + app, "A=1"), List.of());

    given()
        .when()
        .get(SETTINGS + "/preview/" + app + "/" + env)
        .then()
        .statusCode(200)
        .body("added.key", equalTo(List.of("A", "QITS_APPLICATION")))
        .body("removed", hasSize(0))
        .body("changed", hasSize(0))
        .body("kept", hasSize(0))
        .body("unchanged", equalTo(0));
  }

  @Test
  void anUnknownApplicationOrEnvironmentIs404() {
    String env = "set-preview-unknown";
    createEnvironment(env);
    given().when().get(SETTINGS + "/preview/set-nobody-knows/" + env).then().statusCode(404);
    given().when().get(SETTINGS + "/preview/set-nobody-knows/set-no-such-env").then().statusCode(404);
  }
}
