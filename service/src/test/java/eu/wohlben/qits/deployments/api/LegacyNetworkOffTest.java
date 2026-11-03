package eu.wohlben.qits.deployments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What emptying {@code qits.deployments.legacy-network} changes: the membership a
 * deployment declares, and nothing else.
 *
 * <p>The claim survived the docker path it was written for. There it was a join after the start;
 * here it is one entry in the network list a service is created with — the same key, the same
 * decision, read one layer up in {@link
 * eu.wohlben.qits.deployments.deployments.control.DeployService}, which is where it always
 * was.
 */
@QuarkusTest
@TestProfile(LegacyNetworkOffProfile.class)
public class LegacyNetworkOffTest {

  private static final String VERSION = "2026.903.193059";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void anEmptyLegacyNetworkDropsItFromTheDeclaredMembership() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "flip", "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("repoId", "repo-flip", "version", VERSION))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);

    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.applied().isEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (driver.applied().isEmpty()) {
      fail("nothing was applied");
    }

    // The service is declared on its own network and nothing else: no environment names it a public
    // node, and there is no legacy network left to dual-home onto.
    DeploymentDriver.ServiceSpec applied = driver.applied().get(0);
    assertEquals("qits-env-flip-repo-flip", applied.primaryNetwork());
    assertEquals(1, applied.networks().size(), applied.networks().toString());
    assertFalse(applied.networks().contains("qits-net"), applied.networks().toString());
  }
}
