package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The {@code %test} posture: {@code qits.auth.machine.required} is pinned {@code false} there (the
 * prod default ships {@code true} since qits-765), so every guarded write accepts the
 * credential-free call its sender makes today. This is the test that says adopting the guard
 * changed nothing for a credential-free suite — it runs on the default test profile, against the
 * same `%test` config a clone-alone `./mvnw test` gets.
 *
 * <p>It matters here for the reason it did in both ancestors: a clone-alone build has no
 * qits-platform-idp to validate a bearer against, so "the guard is present and inert" is the state
 * this test fixes in place for `%test`/`%dev`.
 */
@QuarkusTest
class MachineGuardOffTest {

  @Test
  void theIntakeAcceptsACredentialFreeCall() {
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"repoId":"unguarded-repo","version":"2026.903.193059"}
            """)
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  @Test
  void anEnvironmentIsCreatedWithNoCredentialAtAll() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"unguarded-env\"}")
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201);
  }

  @Test
  void aServiceIsUpsertedWithNoCredentialAtAll() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}")
        .when()
        .put("/deployments/api/services/unguarded-service")
        .then()
        .statusCode(201);
  }
}
