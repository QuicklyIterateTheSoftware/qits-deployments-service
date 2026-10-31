package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeIdpClientProvisioner;
import eu.wohlben.qits.deployments.deployments.control.FakeResourceProvisioner;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retired {@code qits.artifacts.registry-host} used to be a value a deployment could simply
 * carry; the derived replacement, {@code registry.qits.<domain>}, has nothing to build a host
 * out of when {@code qits.deployments.platform-domain} (the bare {@code QITS_DOMAIN}) is blank —
 * and a deploy must refuse loudly, naming it, rather than pull from {@code registry.qits./...}.
 *
 * <p>Its own class, the {@code ResourceProvisioningWithoutAdminPasswordTest} shape: the domain is
 * injected as an {@code Optional<String>} at bean creation, so taking it away needs a profile and a
 * Quarkus restart.
 */
@QuarkusTest
@TestProfile(MissingPlatformDomainProfile.class)
public class PdDeploymentNoDomainTest {

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
  }

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postRelease(String repoId, String version) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-1", "repoId", repoId, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private List<Map<String, Object>> awaitDeployments(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments =
          given()
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      boolean settled =
          deployments.size() == count
              && deployments.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        return deployments;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return fail("deployments of " + environmentId + " did not settle to " + count);
  }

  @Test
  public void aBlankDomainFailsTheDeployNamingQitsDomainRatherThanBuildAHostOnNothing() {
    String environmentId = createEnvironment("no-domain");
    postRelease("repo-no-domain", "2026.905.100000");

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    String detail = (String) deployments.get(0).get("detail");
    assertTrue(detail.contains("QITS_DOMAIN"), "names the missing fact: " + detail);
    assertTrue(
        !detail.contains("registry.qits./"), "never a reference built on nothing: " + detail);

    // No pull and no apply were ever attempted — the refusal is before anything docker-side.
    assertEquals(List.of(), driver.pulled());
    assertEquals(List.of(), driver.applied());
  }
}
