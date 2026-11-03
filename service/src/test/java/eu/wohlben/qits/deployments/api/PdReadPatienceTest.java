package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.environments.entity.PdService;
import eu.wohlben.qits.deployments.environments.persistence.PdServiceRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;

/**
 * The read seam holds through a lost connection, and still fails when the database stays gone.
 *
 * <p>Both claims are driven through the REST surface, because the surface is what {@link
 * PdReadPatience} exists for: the wrap sits at the controller rather than inside {@code
 * ServiceCatalog}, so a test of the catalogue would prove nothing about it.
 *
 * <p>The failure is injected at the repository the read goes through, in the suite's own idiom — a
 * hand-written stand-in installed for the one test, not a mocking framework. It throws what a
 * cutover throws ({@code 57P01}, wrapped as Hibernate's {@code JDBCConnectionException}) so the
 * classification under test is the real one; a business failure would be rethrown on the first
 * attempt and neither test would mean anything.
 */
@QuarkusTest
@TestProfile(DbPatienceShortProfile.class)
public class PdReadPatienceTest {

  private static final String SERVICES = "/deployments/api/services";

  /**
   * The real repository with a scripted number of connection failures in front of one read.
   *
   * <p>It extends the bean rather than delegating to it: the injected reference is a CDI client
   * proxy, and once this stands in for the type a delegating call through that proxy would come
   * straight back here. {@code super} is the only way out.
   */
  static final class FlakyServiceRepository extends PdServiceRepository {

    private final int failures;
    private int attempts;

    FlakyServiceRepository(int failures) {
      this.failures = failures;
    }

    @Override
    public List<PdService> listOldestFirst() {
      attempts++;
      if (attempts <= failures) {
        throw new JDBCConnectionException(
            "This connection has been closed",
            new SQLException("terminating connection due to administrator command", "57P01"));
      }
      return super.listOldestFirst();
    }
  }

  @Test
  void aReadThatLosesItsConnectionOnceIsAnsweredOnTheSecondAttempt() {
    registerPlatformService("read-patience-recovers");

    FlakyServiceRepository flaky = new FlakyServiceRepository(1);
    QuarkusMock.installMockForType(flaky, PdServiceRepository.class);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(SERVICES)
        .then()
        .statusCode(200)
        // The catalogue is shared across test classes, so assert about this test's own row.
        .body("services.name", hasItem("read-patience-recovers"));

    assertEquals(2, flaky.attempts, "the read should have been retried exactly once");
  }

  @Test
  void aDatabaseThatStaysGoneIsStillAnError() {
    FlakyServiceRepository flaky = new FlakyServiceRepository(Integer.MAX_VALUE);
    QuarkusMock.installMockForType(flaky, PdServiceRepository.class);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(SERVICES).then().statusCode(500);

    assertTrue(
        flaky.attempts > 1,
        "the deadline should have been spent retrying, not skipped — attempts: " + flaky.attempts);
  }

  private void registerPlatformService(String name) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("""
            {"deploymentTarget":"PLATFORM","availableOnEnv":false}
            """)
        .when()
        .put(SERVICES + "/" + name)
        .then()
        .statusCode(201);
  }
}
