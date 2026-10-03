package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /deployments/api/claims/idp-clients} — the shape qits-idp's service-client garbage
 * collection reads, carrying no secret and ordered by {@code clientId}.
 *
 * <p>Every case uses its own clientId so the shared suite database — other classes write
 * {@code idp-client} rows too, including every deployed application's boot row — cannot make one
 * test's assertion depend on another's fixtures; each reads its own entry out of the whole listing
 * rather than asserting the document in full.
 */
@QuarkusTest
public class PdIdpClientClaimApiTest {

  @Inject PdResourceRepository resources;

  private PdResource idpClientRow(
      String clientId, String applicationName, String environmentName, Instant createdAt) {
    PdResource row = new PdResource();
    row.id = UUID.randomUUID().toString();
    row.applicationName = applicationName;
    row.environmentName = environmentName;
    row.resourceName = "idp";
    row.resourceType = "idp-client";
    row.clientId = clientId;
    row.password = "a-secret-nobody-should-ever-see-on-this-door";
    row.createdAt = createdAt;
    return row;
  }

  private void persist(PdResource row) {
    QuarkusTransaction.requiringNew().run(() -> resources.persist(row));
  }

  @Test
  void aTieredIdpClientRowCarriesItsApplicationAndEnvironment() {
    Instant createdAt = Instant.parse("2026-10-03T12:00:00Z");
    persist(idpClientRow("claims-a-staging-idp", "claims-a", "staging", createdAt));

    Map<String, Object> claim = claimOf("claims-a-staging-idp");
    assertEquals("claims-a-staging-idp", claim.get("clientId"));
    assertEquals("claims-a", claim.get("applicationName"));
    assertEquals("staging", claim.get("environmentName"));
    assertEquals(createdAt.toString(), Instant.parse((String) claim.get("createdAt")).toString());
  }

  @Test
  void aPlatformPlaneRowHasNoEnvironment() {
    // The deployer's own boot row is exactly this shape: an idp-client claim with no tier, because
    // BootResourceRegistration records it under the platform plane rather than an environment.
    persist(idpClientRow("claims-boot-idp", "claims-boot", null, Instant.now()));

    Map<String, Object> claim = claimOf("claims-boot-idp");
    assertNull(claim.get("environmentName"));
  }

  @Test
  void noSecretEverReachesTheWire() {
    persist(idpClientRow("claims-secret-idp", "claims-secret", "staging", Instant.now()));

    List<Map<String, Object>> claims = claims();
    for (Map<String, Object> claim : claims) {
      assertFalse(claim.containsKey("password"), "a claim must never carry a credential field");
      assertFalse(claim.containsKey("secret"), "a claim must never carry a credential field");
      assertFalse(
          claim.values().stream()
              .anyMatch(
                  value ->
                      value instanceof String string
                          && string.contains("a-secret-nobody-should-ever-see-on-this-door")),
          "the stored secret must never appear in the answer");
    }
    assertTrue(claims.stream().anyMatch(c -> "claims-secret-idp".equals(c.get("clientId"))));
  }

  @Test
  void anAgentReadsTheIdpClientClaims() {
    given()
        .header("X-Qits-User", "dyn-workspace-agent-reads")
        .header("X-Qits-Roles", "qits:agent")
        .when()
        .get("/deployments/api/claims/idp-clients")
        .then()
        .statusCode(200);
  }

  // An anonymous caller's refusal needs the machine gate ON, which this suite's dev identity (every
  // platform role, %test only) makes unreachable here — see MachineGuardEnforcedTest's
  // theClaimsEndpointAnswersTheSameThreeDoorsThePinsDo, which is the real posture.

  @SuppressWarnings("unchecked")
  private Map<String, Object> claimOf(String clientId) {
    return claims().stream()
        .filter(claim -> clientId.equals(claim.get("clientId")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no claim for " + clientId));
  }

  private List<Map<String, Object>> claims() {
    return given()
        .contentType(ContentType.JSON)
        .when()
        .get("/deployments/api/claims/idp-clients")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("claims");
  }
}
