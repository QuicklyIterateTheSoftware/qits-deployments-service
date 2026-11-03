package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.persistence.PdEnvironmentRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;

/**
 * A tier WRITE holds through a lost connection, and it lands exactly once.
 *
 * <p>The read seam next door ({@link PdReadPatienceTest}) can retry anything, because re-running a
 * read is not a second effect. A write cannot, and that is the whole reason {@code DbRetry.inNewTx}
 * exists: it owns the transaction, so an attempt that failed inside the body is an attempt that was
 * rolled back and certainly never committed. These two tests are the two halves of that claim on
 * this component's own write path.
 *
 * <p><b>The failure is injected AFTER the insert, on purpose.</b> {@code EnvironmentService.create}
 * persists the row and then asks whether any tier is the platform one; the stand-in throws there, so
 * the first attempt really does write and really is rolled back. A second attempt then writes again,
 * and "exactly one environment of that name exists" is the assertion that says the retry cost the
 * database nothing — which a failure injected before the insert could never show.
 *
 * <p>The stand-in is the suite's own idiom: the real repository, extended rather than delegated to,
 * because the injected reference is a CDI client proxy and a delegating call through it would come
 * straight back here. It throws what a cutover throws ({@code 57P01} under Hibernate's {@code
 * JDBCConnectionException}), so the classification under test is the real one.
 */
@QuarkusTest
@TestProfile(DbPatienceShortProfile.class)
public class PdWritePatienceTest {

  private static final String ENVIRONMENTS = "/deployments/api/environments";

  /** The real repository, failing a scripted number of times at the read that follows the insert. */
  static final class FlakyEnvironmentRepository extends PdEnvironmentRepository {

    private final int failures;
    private final RuntimeException failure;
    private int attempts;

    FlakyEnvironmentRepository(int failures, RuntimeException failure) {
      this.failures = failures;
      this.failure = failure;
    }

    @Override
    public List<PdEnvironment> listPlatform() {
      attempts++;
      if (attempts <= failures) {
        throw failure;
      }
      return super.listPlatform();
    }
  }

  private static JDBCConnectionException cutover() {
    return new JDBCConnectionException(
        "This connection has been closed",
        new SQLException("terminating connection due to administrator command", "57P01"));
  }

  @Test
  void aWriteThatLosesItsConnectionOnceLandsOnceOnTheSecondAttempt() {
    FlakyEnvironmentRepository flaky = new FlakyEnvironmentRepository(1, cutover());
    QuarkusMock.installMockForType(flaky, PdEnvironmentRepository.class);

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "write-patience-recovers"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201);

    assertEquals(2, flaky.attempts, "the write should have been retried exactly once");

    // The point of the whole exercise: the rolled-back attempt left nothing behind.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS)
        .then()
        .statusCode(200)
        .body(
            "environments.name.findAll { it == 'write-patience-recovers' }.size()", equalTo(1));
  }

  @Test
  void aFailureThatIsNotTheConnectionIsReportedOnTheFirstAttempt() {
    FlakyEnvironmentRepository flaky =
        new FlakyEnvironmentRepository(
            Integer.MAX_VALUE, new IllegalStateException("the row is wrong, and it stays wrong"));
    QuarkusMock.installMockForType(flaky, PdEnvironmentRepository.class);

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "write-patience-reports"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(500);

    assertEquals(
        1,
        flaky.attempts,
        "a failure that would fail identically next time must not spend the deadline");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS)
        .then()
        .statusCode(200)
        .body("environments.name.findAll { it == 'write-patience-reports' }.size()", equalTo(0));
  }
}
