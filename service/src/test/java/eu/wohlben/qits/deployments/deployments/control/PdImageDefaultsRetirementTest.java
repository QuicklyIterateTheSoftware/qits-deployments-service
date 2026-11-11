package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The image-defaults retirement (qits-2009), end to end through the real intake and the real
 * worker: a spec declaring {@code image_defaults:} retires, once its deployment is {@code ACTIVE},
 * every {@code pd_setting} row of that place that restates one of the declared values exactly — and
 * nothing else, and never on a deployment that did not converge. {@link Settings#retireImageDefaults}
 * is driven directly for the compare-and-delete's own edges.
 *
 * <p>Every case uses application and environment names of its own, because the suite shares one
 * database across classes. The profile is {@link PdRenameCutoverTest}'s, so no extra Quarkus boot.
 */
@QuarkusTest
@TestProfile(PdSwarmDeployFlowTest.SwarmOrchestrator.class)
public class PdImageDefaultsRetirementTest {

  private static final String VERSION = "2026.1110.120000";

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver fake;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;
  @Inject PdDeploymentRepository deployments;
  @Inject Settings settings;

  @BeforeEach
  void reset() {
    specs.reset();
    provisioner.reset();
    idpProvisioner.reset();
    fake.reset();
  }

  @Test
  public void anActiveDeploymentRetiresTheRowsEqualToItsImageDefaultsAndNothingElse() {
    String env = "imgdef-dev";
    String environmentId = createEnvironment(env);
    String app = "imgdef-edge";
    // Equal to the declared defaults: these retire, the secret one by its clear value.
    put(app, env, "QUARKUS_TLS_RELOAD_PERIOD", "1m", false);
    put(app, env, "QITS_EDGE_ACME_ENABLED", "true", true);
    // A deliberate override of a declared key, and a near miss String.equals refuses: both stay.
    put(app, env, "QITS_EDGE_ACME_MODE", "staging", false);
    put(app, env, "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT", " /work/cert.crt", false);
    // A key nothing declares stays whatever it holds.
    put(app, env, "GREETING", "hello", false);
    // The same key and value for another application, and for this one in another tier: both stay.
    put("imgdef-other", env, "QUARKUS_TLS_RELOAD_PERIOD", "1m", false);
    put(app, "imgdef-prod", "QUARKUS_TLS_RELOAD_PERIOD", "1m", false);

    specs.script(
        app,
        DeploymentSpecParser.parse(
            "image_defaults: QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT=/work/cert.crt,"
                + " QUARKUS_TLS_RELOAD_PERIOD=1m, QITS_EDGE_ACME_ENABLED=true,"
                + " QITS_EDGE_ACME_MODE=production\n",
            "deployments.yml of " + app));
    List<String> logged =
        logsOf(
            () -> {
              postRelease("run-imgdef", app);
              awaitSettled(environmentId, 1);
            });

    assertEquals(PdDeploymentStatus.ACTIVE, newestStatus(app, environmentId));
    assertEquals(
        Map.of(
            "QITS_EDGE_ACME_MODE", "staging",
            "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT", " /work/cert.crt",
            "GREETING", "hello"),
        valuesOf(app, env));
    assertEquals(Map.of("QUARKUS_TLS_RELOAD_PERIOD", "1m"), valuesOf("imgdef-other", env));
    assertEquals(Map.of("QUARKUS_TLS_RELOAD_PERIOD", "1m"), valuesOf(app, "imgdef-prod"));

    // One INFO line naming the place, the version and the keys — in the file's order — never a value.
    List<String> retirements =
        logged.stream().filter(line -> line.startsWith("Retired ")).toList();
    assertEquals(1, retirements.size(), logged.toString());
    String line = retirements.get(0);
    assertTrue(line.contains(app) && line.contains(env) && line.contains(VERSION), line);
    assertTrue(line.contains("[QUARKUS_TLS_RELOAD_PERIOD, QITS_EDGE_ACME_ENABLED]"), line);
    assertFalse(line.contains("1m") || line.contains("true,") || line.contains("staging"), line);

    // qits-2048: the deployment's own spec carried the pairs, so its update stopped stating the
    // equal rows (SwarmDeploymentDriverTest holds the argv) — and the row records what retired, in
    // the file's order, keys only.
    DeploymentDriver.ServiceSpec applied = appliedSpecOf(app);
    assertEquals(
        List.of(
            "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT",
            "QUARKUS_TLS_RELOAD_PERIOD",
            "QITS_EDGE_ACME_ENABLED",
            "QITS_EDGE_ACME_MODE"),
        List.copyOf(applied.imageDefaults().keySet()));
    assertEquals("1m", applied.imageDefaults().get("QUARKUS_TLS_RELOAD_PERIOD"));
    assertEquals(
        "QUARKUS_TLS_RELOAD_PERIOD,QITS_EDGE_ACME_ENABLED",
        newestRetiredColumn(app, environmentId));
    assertEquals(
        List.of("QUARKUS_TLS_RELOAD_PERIOD", "QITS_EDGE_ACME_ENABLED"),
        servedRetired(environmentId, app).get(0));
  }

  @Test
  public void aFailedOrRolledBackDeploymentRetiresNothingAndTheNextActiveOneDoes() {
    String env = "imgdef-fail";
    String environmentId = createEnvironment(env);
    String app = "imgdef-fail-app";
    put(app, env, "QUARKUS_TLS_RELOAD_PERIOD", "1m", false);
    specs.script(
        app,
        DeploymentSpecParser.parse(
            "image_defaults: QUARKUS_TLS_RELOAD_PERIOD=1m\n", "deployments.yml of " + app));

    fake.scriptConvergence(DeploymentDriver.Convergence.failed("the task never went healthy"));
    List<String> logged =
        logsOf(
            () -> {
              postRelease("run-imgdef-fail-1", app);
              awaitSettled(environmentId, 1);
            });
    assertEquals(PdDeploymentStatus.FAILED, newestStatus(app, environmentId));
    assertEquals(Map.of("QUARKUS_TLS_RELOAD_PERIOD", "1m"), valuesOf(app, env));
    assertTrue(logged.stream().noneMatch(l -> l.startsWith("Retired ")), logged.toString());

    fake.scriptConvergence(DeploymentDriver.Convergence.rolledBack("rollback_completed"));
    postRelease("run-imgdef-fail-2", app);
    awaitSettled(environmentId, 2);
    assertEquals(PdDeploymentStatus.ROLLED_BACK, newestStatus(app, environmentId));
    assertEquals(Map.of("QUARKUS_TLS_RELOAD_PERIOD", "1m"), valuesOf(app, env));

    // A refused apply never reaches convergence at all. (The fake's scripts are sticky.)
    fake.scriptConvergence(DeploymentDriver.Convergence.converged(List.of()));
    fake.scriptApply(
        new DeploymentDriver.ApplyResult(DeploymentDriver.ApplyOutcome.REFUSED, "no such network"));
    postRelease("run-imgdef-fail-3", app);
    awaitSettled(environmentId, 3);
    assertEquals(PdDeploymentStatus.FAILED, newestStatus(app, environmentId));
    assertEquals(Map.of("QUARKUS_TLS_RELOAD_PERIOD", "1m"), valuesOf(app, env));

    fake.scriptApply(new DeploymentDriver.ApplyResult(DeploymentDriver.ApplyOutcome.APPLIED, null));
    postRelease("run-imgdef-fail-4", app);
    awaitSettled(environmentId, 4);
    assertEquals(PdDeploymentStatus.ACTIVE, newestStatus(app, environmentId));
    assertEquals(Map.of(), valuesOf(app, env));

    // Only the ACTIVE row records a retirement; the FAILED, ROLLED_BACK and refused ones carry
    // none.
    List<List<String>> served = servedRetired(environmentId, app);
    assertEquals(4, served.size(), served.toString());
    assertEquals(List.of("QUARKUS_TLS_RELOAD_PERIOD"), served.get(0), "newest first");
    assertEquals(
        java.util.Arrays.asList(null, null, null), served.subList(1, 4), served.toString());
  }

  @Test
  public void aSpecWithoutImageDefaultsRetiresNothing() {
    String env = "imgdef-none";
    String environmentId = createEnvironment(env);
    String app = "imgdef-none-app";
    put(app, env, "QUARKUS_TLS_RELOAD_PERIOD", "1m", false);
    specs.script(app, DeploymentSpecParser.parse("", "deployments.yml of " + app));

    postRelease("run-imgdef-none", app);
    awaitSettled(environmentId, 1);

    assertEquals(PdDeploymentStatus.ACTIVE, newestStatus(app, environmentId));
    assertEquals(Map.of("QUARKUS_TLS_RELOAD_PERIOD", "1m"), valuesOf(app, env));
    assertEquals(Map.of(), appliedSpecOf(app).imageDefaults());
    assertEquals(null, newestRetiredColumn(app, environmentId));
    assertEquals(java.util.Arrays.asList((List<String>) null), servedRetired(environmentId, app));
  }

  @Test
  public void aVariantRetiresItsOwnRowsAgainstItsOwnFile() {
    String env = "imgdef-var";
    String environmentId = createEnvironment(env);
    String app = "imgdef-var-app";
    String variantApp = app + "-private";
    put(app, env, "QITS_EDGE_ACME_ENABLED", "false", false);
    put(variantApp, env, "QITS_EDGE_ACME_ENABLED", "false", false);
    specs.script(app, DeploymentSpecParser.parse("variants: private\n", "deployments.yml"));
    specs.scriptAt(
        app,
        SpecSource.variantPath("private"),
        DeploymentSpecParser.parse(
            "application: " + variantApp + "\nimage_defaults: QITS_EDGE_ACME_ENABLED=false\n",
            "deployments.private.yml"));

    postRelease("run-imgdef-var", app);
    awaitSettled(environmentId, 2);

    assertEquals(PdDeploymentStatus.ACTIVE, newestStatus(variantApp, environmentId));
    assertEquals(Map.of(), valuesOf(variantApp, env), "the variant's file declares it");
    assertEquals(
        Map.of("QITS_EDGE_ACME_ENABLED", "false"),
        valuesOf(app, env),
        "the main file declares nothing");
  }

  @Test
  public void retireImageDefaultsComparesExactlyAndIsIdempotent() {
    String env = "imgdef-direct";
    String app = "imgdef-direct-app";
    put(app, env, "A", "x", false);
    put(app, env, "B", "X", false);
    put(app, env, "C", "secret-value", true);
    Map<String, String> declared = new LinkedHashMap<>();
    declared.put("C", "secret-value");
    declared.put("B", "x");
    declared.put("A", "x");
    declared.put("MISSING", "x");

    assertEquals(List.of("C", "A"), settings.retireImageDefaults(app, env, declared));
    assertEquals(Map.of("B", "X"), valuesOf(app, env), "case is a different value");
    assertEquals(List.of(), settings.retireImageDefaults(app, env, declared));
    assertEquals(List.of(), settings.retireImageDefaults(app, env, Map.of()));
    assertEquals(List.of(), settings.retireImageDefaults(app, env, null));
  }

  // --- helpers -----------------------------------------------------------------------------------

  private void put(String app, String env, String key, String value, boolean secret) {
    settings.put(app, env, key, value, secret, "an operator");
  }

  /** The place's rows, key to CLEAR value — the masked door would hide a secret's. */
  private Map<String, String> valuesOf(String app, String env) {
    return settings.rowsOf(app, env).stream()
        .collect(Collectors.toMap(row -> row.settingKey, row -> row.value));
  }

  /** The {@code retired_settings} column of the place's newest row, as stored. */
  private String newestRetiredColumn(String name, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.newestForPlace(name, environmentId).orElseThrow().retiredSettings);
  }

  /**
   * Each of the application's rows' {@code retiredSettings}, as {@code GET /deployments} serves.
   */
  @SuppressWarnings("unchecked")
  private List<List<String>> servedRetired(String environmentId, String name) {
    return deploymentsOf(environmentId).stream()
        .filter(row -> name.equals(row.get("applicationName")))
        .map(row -> (List<String>) row.get("retiredSettings"))
        .toList();
  }

  /** The last spec the fake driver applied for {@code name}. */
  private DeploymentDriver.ServiceSpec appliedSpecOf(String name) {
    List<DeploymentDriver.ServiceSpec> applied =
        fake.applied().stream().filter(spec -> name.equals(spec.applicationName())).toList();
    assertFalse(applied.isEmpty(), "nothing was applied for " + name);
    return applied.get(applied.size() - 1);
  }

  private PdDeploymentStatus newestStatus(String name, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.newestForPlace(name, environmentId).orElseThrow().status);
  }

  private static List<String> logsOf(Runnable body) {
    List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(DeployService.class.getName());
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            lines.add(
                record.getMessage() + " " + java.util.Arrays.toString(record.getParameters()));
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

  private String createEnvironment(String name) {
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

  private void postRelease(String runId, String repoId) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", runId, "repoId", repoId, "version", VERSION))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private List<Map<String, Object>> deploymentsOf(String environmentId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments");
  }

  private void awaitSettled(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows = deploymentsOf(environmentId);
      boolean settled =
          rows.size() == count
              && rows.stream()
                  .noneMatch(
                      row ->
                          "QUEUED".equals(row.get("status")) || "STARTING".equals(row.get("status")));
      if (settled) {
        try {
          deployService.awaitIdle();
        } catch (Exception e) {
          throw new IllegalStateException("the deploy worker did not drain", e);
        }
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    fail("the deployments of " + environmentId + " did not settle to " + count);
  }
}
