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
import eu.wohlben.qits.deployments.deployments.entity.PdSetting;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The egress secret hand-over (epic qits-2523): once a route of the trigger application injecting
 * the trigger secret is in force in a tier, the source setting moves to the target application as
 * a secret in one transaction, and both the target's and the source's live versions are
 * redeployed — a restart would never carry the moved setting, since it replaces the tasks on the
 * unchanged swarm spec rather than rebuilding the argv — and nothing happens again once the source
 * row is gone.
 *
 * <p>Driven through the release path and the reconcile it ends in, with entries of this suite's
 * own ({@link EgressSecretHandOvers#declare}) so the names are unique to each test: the suite shares
 * one database, and an application named {@code qits-edge} would bring the edges' own machinery
 * in. {@link #theDeclaredEntryIsTheEdgesHetznerToken} holds the one real entry.
 */
@QuarkusTest
class PdEgressSecretHandOverTest {

  private static final String VERSION = "2026.1201.111111";
  private static final String FROM_KEY = "HO_EDGE_TOKEN";
  private static final String TO_KEY = "HO_EGRESS_SECRET";
  private static final String VALUE = "hand-over-value-must-never-be-logged";

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
  @Inject EgressSecretHandOvers handOvers;
  @Inject Settings settings;
  @Inject PdDeploymentRepository deployments;

  private String tier;
  private String environmentId;
  private String from;
  private String to;

  @BeforeEach
  void reset() throws Exception {
    deployService.awaitIdle();
    driver.reset();
    specs.reset();
    egress.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdEgressDeclaration.deleteAll();
              PdEgressGrantTable.deleteAll();
            });
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    tier = "handover-" + token;
    environmentId = createEnvironment(tier);
    from = "ho-edge-" + token;
    to = "ho-egress-" + token;
    handOvers.declare(
        List.of(
            new EgressSecretHandOvers.HandOver(from, FROM_KEY, to, TO_KEY, from, "HETZNER_DNS")));
  }

  @AfterEach
  void restore() throws Exception {
    settle();
    handOvers.declare(EgressSecretHandOvers.DECLARED);
  }

  @Test
  void theDeclaredEntryIsTheEdgesHetznerToken() {
    assertEquals(
        List.of(
            new EgressSecretHandOvers.HandOver(
                "qits-edge",
                "QITS_EDGE_ACME_HETZNER_TOKEN",
                "qits-egress",
                "QITS_EGRESS_SECRET_HETZNER_DNS",
                "qits-edge",
                "HETZNER_DNS")),
        EgressSecretHandOvers.DECLARED);
  }

  @Test
  void theRouteInForceMovesTheSecretAndRedeploysBothTargetAndSource() throws Exception {
    release(to);
    settings.put(from, tier, FROM_KEY, VALUE, false, "an operator");
    List<String> logs = new ArrayList<>();

    logs.addAll(logsOf(() -> releaseWithRoute(from)));

    PdSetting moved = row(to, TO_KEY).orElseThrow();
    assertEquals(VALUE, moved.value);
    assertTrue(moved.secret, "the target is a secret whatever the source was");
    assertEquals("egress hand-over from " + from, moved.updatedBy);
    assertTrue(row(from, FROM_KEY).isEmpty(), "the source row is deleted");

    List<PdDeployment> toRows = rows(to);
    assertEquals(2, toRows.size(), "the target's live version was redeployed");
    assertEquals(PdDeploymentStatus.ACTIVE, toRows.get(0).status);
    assertEquals(VERSION, toRows.get(0).version);
    List<PdDeployment> fromRows = rows(from);
    assertEquals(2, fromRows.size(), "the source's live version was redeployed");
    assertEquals(PdDeploymentStatus.ACTIVE, fromRows.get(0).status);
    assertEquals(VERSION, fromRows.get(0).version);

    List<String> lines =
        logs.stream().filter(line -> line.contains("egress hand-over in")).toList();
    assertEquals(1, lines.size(), logs.toString());
    assertTrue(lines.get(0).contains(FROM_KEY) && lines.get(0).contains(TO_KEY), lines.get(0));
    assertTrue(lines.get(0).contains(tier) && lines.get(0).contains(from), lines.get(0));
    assertFalse(logs.stream().anyMatch(line -> line.contains(VALUE)), "no line carries the value");
  }

  @Test
  void aSecondTickIsANoOp() throws Exception {
    release(to);
    settings.put(from, tier, FROM_KEY, VALUE, true, "an operator");
    releaseWithRoute(from);
    int toRows = rows(to).size();
    int fromRows = rows(from).size();
    assertEquals(2, toRows);

    deployService.enqueueObservation();
    settle();

    assertEquals(toRows, rows(to).size(), "no second redeploy of the target");
    assertEquals(fromRows, rows(from).size(), "no second redeploy of the source");
    assertEquals(VALUE, row(to, TO_KEY).orElseThrow().value);
  }

  @Test
  void nothingHappensWithoutTheRoute() throws Exception {
    release(to);
    settings.put(from, tier, FROM_KEY, VALUE, true, "an operator");
    release(from); // no egress.yml: no route

    deployService.enqueueObservation();
    settle();

    assertEquals(VALUE, row(from, FROM_KEY).orElseThrow().value, "the source stays");
    assertTrue(row(to, TO_KEY).isEmpty());
    assertEquals(1, rows(to).size());
    assertEquals(1, rows(from).size());
  }

  @Test
  void nothingHappensWhenTheSourceRowIsAbsent() throws Exception {
    release(to);
    releaseWithRoute(from);

    deployService.enqueueObservation();
    settle();

    assertTrue(row(to, TO_KEY).isEmpty());
    assertEquals(1, rows(to).size());
    assertEquals(1, rows(from).size());
  }

  @Test
  void anExistingTargetIsKeptAndTheSourceIsStillDeleted() throws Exception {
    release(to);
    settings.put(to, tier, TO_KEY, "already-there", true, "an operator");
    settings.put(from, tier, FROM_KEY, VALUE, true, "an operator");

    releaseWithRoute(from);

    PdSetting kept = row(to, TO_KEY).orElseThrow();
    assertEquals("already-there", kept.value);
    assertEquals("an operator", kept.updatedBy);
    assertTrue(row(from, FROM_KEY).isEmpty());
    assertEquals(2, rows(to).size(), "the target is still redeployed, even keeping its own value");
    assertEquals(2, rows(from).size());
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** The worker until idle: a job it runs may queue the two redeploys behind it. */
  private void settle() throws Exception {
    for (int i = 0; i < 4; i++) {
      deployService.awaitIdle();
    }
  }

  private void releaseWithRoute(String application) {
    specs.scriptText(application, SpecSource.EGRESS_PATH, ROUTE);
    try {
      release(application);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void release(String application) throws Exception {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-handover", "repoId", application, "version", VERSION))
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

  private Optional<PdSetting> row(String application, String key) {
    return settings.rowsOf(application, tier).stream()
        .filter(row -> key.equals(row.settingKey))
        .findFirst();
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
