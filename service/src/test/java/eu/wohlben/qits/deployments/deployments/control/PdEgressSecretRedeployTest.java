package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressDeclaration;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressGrantTable;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A changed egress secret redeploys qits-egress (qits-2666): a create, change or delete of a
 * {@code QITS_EGRESS_SECRET_*} setting of {@code qits-egress} queues one redeploy of its live
 * version in that tier — a new deployment row of the same version, never a restart — once the
 * debounce has passed; nothing else does.
 *
 * <p>The application is the real {@code qits-egress}, each test in a tier of its own (the suite
 * shares one database). The debounce is driven through {@link EgressSecretRedeploys#settle(Instant)}
 * with a clock past it, so nothing here sleeps.
 */
@QuarkusTest
class PdEgressSecretRedeployTest {

  private static final String EGRESS = "qits-egress";
  private static final String VERSION = "2026.1202.111111";
  private static final String SECRET = "QITS_EGRESS_SECRET_X";
  private static final String VALUE = "egress-secret-value-must-never-be-logged";
  private static final Duration PAST_DEBOUNCE = Duration.ofSeconds(11);

  private static final String ROUTE =
      """
      version: 1
      routes:
        - name: hetzner
          target: https://api.hetzner.cloud
          secret: HETZNER_DNS
          inject: bearer
          methods: [GET, PUT]
      """;

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeEgressGrantClient egress;
  @Inject DeployService deployService;
  @Inject EgressSecretRedeploys redeploys;
  @Inject EgressSecretHandOvers handOvers;
  @Inject PdDeploymentRepository deployments;

  private String tier;
  private String environmentId;
  private String token;

  @BeforeEach
  void reset() throws Exception {
    settle();
    driver.reset();
    specs.reset();
    egress.reset();
    redeploys.queueWith(null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdEgressDeclaration.deleteAll();
              PdEgressGrantTable.deleteAll();
            });
    token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    tier = "egsecret-" + token;
    environmentId = createEnvironment(tier);
  }

  @AfterEach
  void restore() throws Exception {
    settle();
    redeploys.queueWith(null);
    handOvers.declare(EgressSecretHandOvers.DECLARED);
  }

  @Test
  void aPutOfASecretQueuesExactlyOneRedeployOfTheLiveVersionAndNoRestart() throws Exception {
    release(EGRESS, VERSION);
    putSetting(EGRESS, SECRET, VALUE);

    redeploys.settle(Instant.now());
    settle();
    assertEquals(1, rows(EGRESS).size(), "nothing is queued inside the debounce");

    List<String> logs = logsOf(this::settlePastDebounce);

    List<PdDeployment> rows = rows(EGRESS);
    assertEquals(2, rows.size(), "the live version was redeployed once");
    assertEquals(PdDeploymentStatus.ACTIVE, rows.get(0).status);
    assertEquals(VERSION, rows.get(0).version);
    assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("restart:")),
        "a redeploy, never a restart: " + driver.calls());
    List<String> lines =
        logs.stream()
            .filter(line -> line.startsWith("egress secret ") && line.contains(" changed in "))
            .toList();
    assertEquals(1, lines.size(), logs.toString());
    assertTrue(
        lines.get(0).contains(SECRET)
            && lines.get(0).contains(tier)
            && lines.get(0).contains(VERSION),
        lines.get(0));
    assertFalse(logs.stream().anyMatch(line -> line.contains(VALUE)), "no line carries the value");

    settlePastDebounce();
    assertEquals(2, rows(EGRESS).size(), "a second tick queues nothing more");
  }

  @Test
  void twoPutsWithinTheDebounceGiveOneRedeploy() throws Exception {
    release(EGRESS, VERSION);
    putSetting(EGRESS, SECRET, VALUE);
    putSetting(EGRESS, "QITS_EGRESS_SECRET_Y", "another-value");
    putSetting(EGRESS, SECRET, VALUE + "-changed");

    settlePastDebounce();
    settlePastDebounce();

    assertEquals(2, rows(EGRESS).size(), "one redeploy for three writes");
  }

  @Test
  void aWriteAfterTheRedeployStartedQueuesOneMore() throws Exception {
    release(EGRESS, VERSION);
    putSetting(EGRESS, SECRET, VALUE);
    settlePastDebounce();
    assertEquals(2, rows(EGRESS).size());

    putSetting(EGRESS, SECRET, VALUE + "-again");
    settlePastDebounce();

    assertEquals(3, rows(EGRESS).size(), "the later write is not carried by the earlier redeploy");
  }

  @Test
  void aDeploymentAfterTheWriteAbsorbsIt() throws Exception {
    release(EGRESS, VERSION);
    putSetting(EGRESS, SECRET, VALUE);
    release(EGRESS, "2026.1202.222222"); // an ordinary release reads the store fresh

    settlePastDebounce();

    assertEquals(2, rows(EGRESS).size(), "the release carried the write; no redeploy on top");
    assertTrue(redeploys.owed().get(tier) == null);
  }

  @Test
  void aDeleteRedeploys() throws Exception {
    putSetting(EGRESS, SECRET, VALUE);
    release(EGRESS, VERSION);
    settlePastDebounce(); // the write predates the first deployment, which carried it
    assertEquals(1, rows(EGRESS).size());

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .delete("/deployments/api/settings/" + EGRESS + "/" + tier + "/" + SECRET)
        .then()
        .statusCode(204);
    settlePastDebounce();

    assertEquals(2, rows(EGRESS).size(), "the delete redeployed the live version");
  }

  @Test
  void anotherKeyOrAnotherApplicationRedeploysNothing() throws Exception {
    String other = "egsecret-other-" + token;
    release(EGRESS, VERSION);
    release(other, VERSION);
    putSetting(EGRESS, "QITS_EGRESS_LOG_LEVEL", "DEBUG");
    putSetting(other, SECRET, VALUE);

    assertTrue(redeploys.owed().get(tier) == null, "nothing is owed: " + redeploys.owed());
    settlePastDebounce();

    assertEquals(1, rows(EGRESS).size());
    assertEquals(1, rows(other).size());
  }

  @Test
  void aFailedQueueIsOwedAndRetriedOnTheNextTick() throws Exception {
    release(EGRESS, VERSION);
    putSetting(EGRESS, SECRET, VALUE);
    AtomicInteger attempts = new AtomicInteger();
    redeploys.queueWith(
        (application, environment, description) -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("the worker is shutting down");
        });

    settlePastDebounce();

    assertEquals(1, attempts.get());
    assertEquals(1, rows(EGRESS).size(), "nothing was queued");
    assertTrue(redeploys.owed().containsKey(tier), "the redeploy is OWED");

    redeploys.queueWith(null);
    settlePastDebounce();

    assertEquals(2, rows(EGRESS).size(), "the next tick queued it");
    assertFalse(redeploys.owed().containsKey(tier));
  }

  @Test
  void theHandOversOwnMoveQueuesNoExtraRedeploy() throws Exception {
    String from = "egsecret-edge-" + token;
    handOvers.declare(
        List.of(
            new EgressSecretHandOvers.HandOver(
                from, "HO_EDGE_TOKEN", EGRESS, "QITS_EGRESS_SECRET_HO", from, "HETZNER_DNS")));
    release(EGRESS, VERSION);
    putSetting(from, "HO_EDGE_TOKEN", VALUE);
    // A door write pending in the tier when the hand-over moves rides the hand-over's redeploy.
    putSetting(EGRESS, SECRET, VALUE);

    specs.scriptText(from, SpecSource.EGRESS_PATH, ROUTE);
    release(from, VERSION);
    assertEquals(2, rows(EGRESS).size(), "the hand-over redeployed qits-egress");

    settlePastDebounce();
    settlePastDebounce();

    assertEquals(2, rows(EGRESS).size(), "no second redeploy");
    assertFalse(redeploys.owed().containsKey(tier));
  }

  // --- helpers -----------------------------------------------------------------------------------

  private void settlePastDebounce() {
    redeploys.settle(Instant.now().plus(PAST_DEBOUNCE));
    try {
      settle();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The worker until idle: a job it runs may queue another behind it. */
  private void settle() throws Exception {
    for (int i = 0; i < 4; i++) {
      deployService.awaitIdle();
    }
  }

  private void putSetting(String application, String key, String value) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("value", value, "secret", true))
        .when()
        .put("/deployments/api/settings/" + application + "/" + tier + "/" + key)
        .then()
        .statusCode(200);
  }

  private void release(String application, String version) throws Exception {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-egsecret", "repoId", application, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    settle();
    assertEquals(PdDeploymentStatus.ACTIVE, rows(application).get(0).status, application);
  }

  private List<PdDeployment> rows(String application) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.listForPlaceNewestFirst(application, environmentId));
  }

  private static String createEnvironment(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  /** Every log line under this component's root while {@code body} runs, with its parameters. */
  private static List<String> logsOf(Runnable body) {
    List<String> lines = Collections.synchronizedList(new ArrayList<>());
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger("eu.wohlben.qits");
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            String thrown = record.getThrown() == null ? "" : " " + record.getThrown();
            lines.add(
                record.getMessage()
                    + " "
                    + java.util.Arrays.toString(record.getParameters())
                    + thrown);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      body.run();
    } finally {
      logger.removeHandler(handler);
    }
    return List.copyOf(lines);
  }
}
