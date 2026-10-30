package eu.wohlben.qits.deployments.confighost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.Settings;
import eu.wohlben.qits.deployments.deployments.dto.PdSettingDto;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1392's one-shot import, against a stub qits-configuration on a real socket and the suite's
 * real store. Every case runs in an environment of its own, because the allowlist's application
 * names are fixed and the suite shares one database across classes.
 */
@QuarkusTest
class SettingsImportTest {

  private static final String P = DeploymentDriver.EXTRAS_PREFIX;

  private static final String NO_FILE = "config/application.properties";

  private static final ExtrasBearer NONE = Optional::empty;

  private static final String TOKEN = "hetzner-token-value-that-must-never-be-logged";

  private static final String PG = "postgres-password-value-that-must-never-be-logged";

  private static final String EVENTS = "http://events-url-value-that-must-never-be-logged";

  private static final AtomicInteger ENVS = new AtomicInteger();

  @Inject Settings settings;

  @Inject Config config;

  @Inject EnvironmentService environments;

  private ExtrasStub stub;

  private String env;

  @BeforeEach
  void start() {
    stub = new ExtrasStub();
    env = "imp-" + ENVS.incrementAndGet() + "-" + System.nanoTime() % 100000;
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  private SettingsImport importer(String extrasUrl) {
    SettingsImport importer = new SettingsImport();
    importer.extrasUrl = Optional.ofNullable(extrasUrl);
    importer.ownEnvironment = Optional.of(env);
    importer.extras = ExtrasStub.source(config, NO_FILE, NONE, extrasUrl);
    importer.settings = settings;
    importer.environments = environments;
    importer.passes = 2;
    importer.firstPauseMillis = 0;
    return importer;
  }

  private PdSettingDto row(String application, String key) {
    return settings.list(application, env).stream()
        .filter(s -> s.key().equals(key))
        .findFirst()
        .orElse(null);
  }

  @Test
  void importsAllowlistedKeysOnlyWithTheirSecretFlagAndSkipsTheAbsent() {
    stub.resolves(
        4,
        P + "qits-edge.env.QITS_EDGE_ACME_EMAIL", "ops@example.test",
        P + "qits-edge.env.QITS_EDGE_ACME_HETZNER_TOKEN", TOKEN,
        P + "qits-edge.env.QITS_EDGE_ACME_MODE", "  ",
        P + "qits-edge.env.QITS_EVENTS_URL", EVENTS,
        P + "qits-oci-postgresql.env.POSTGRES_PASSWORD", PG);

    List<String> logged = new ArrayList<>();
    SettingsImport.Outcome outcome = capture(logged, () -> importer(stub.url()).run());

    // The stub answers every application with the same document, so only the keys named under
    // each application's own prefix can be found: qits-edge's three and qits-oci-postgresql's one.
    assertEquals(
        List.of(
            "qits-edge/QITS_EDGE_ACME_EMAIL",
            "qits-edge/QITS_EDGE_ACME_HETZNER_TOKEN",
            "qits-oci-postgresql/POSTGRES_PASSWORD"),
        outcome.imported());
    assertEquals(0, outcome.alreadySet());
    assertEquals(SettingsImport.ALLOWLIST.size() - 3, outcome.absent(), "blank counts as absent");
    assertTrue(outcome.unreadable().isEmpty());

    PdSettingDto email = row("qits-edge", "QITS_EDGE_ACME_EMAIL");
    assertEquals("ops@example.test", email.value());
    assertFalse(email.secret());
    assertEquals(SettingsImport.UPDATED_BY, email.updatedBy());
    assertTrue(row("qits-edge", "QITS_EDGE_ACME_HETZNER_TOKEN").secret());
    assertTrue(row("qits-oci-postgresql", "POSTGRES_PASSWORD").secret());
    assertNull(row("qits-edge", "QITS_EDGE_ACME_MODE"), "a blank value is not imported");
    assertNull(row("qits-edge", "QITS_EVENTS_URL"), "not on the allowlist");

    // The list door masks what the allowlist marked secret.
    given()
        .when()
        .get("/deployments/api/settings?application=qits-edge&environment=" + env)
        .then()
        .statusCode(200)
        .body("settings.find { it.key == 'QITS_EDGE_ACME_HETZNER_TOKEN' }.value", nullValue())
        .body("settings.find { it.key == 'QITS_EDGE_ACME_HETZNER_TOKEN' }.secret", equalTo(true))
        .body(
            "settings.find { it.key == 'QITS_EDGE_ACME_EMAIL' }.value",
            equalTo("ops@example.test"));

    // ONE info line, names and counts only.
    List<String> summaries =
        logged.stream().filter(l -> l.startsWith("Settings import")).toList();
    assertEquals(1, summaries.size(), String.join("\n", logged));
    assertEquals(
        "Settings import from qits-configuration into "
            + env
            + ": imported=3 already-set=0 absent="
            + (SettingsImport.ALLOWLIST.size() - 3)
            + " unreadable=0 passes=1 imported-keys=[qits-edge/QITS_EDGE_ACME_EMAIL,"
            + " qits-edge/QITS_EDGE_ACME_HETZNER_TOKEN, qits-oci-postgresql/POSTGRES_PASSWORD]",
        summaries.get(0));
    assertNoValue(logged);
  }

  @Test
  void neverOverwritesAnExistingRowAndASecondRunImportsNothingAndAsksNobody() {
    settings.put("qits-githost", env, "QITS_REPOSITORIES_GIT_PUSH_TOKEN", "operator", true, "op");
    stub.resolves(
        2,
        P + "qits-githost.env.QITS_REPOSITORIES_GIT_PUSH_TOKEN", TOKEN,
        P + "qits-projects.env.QITS_REPOSITORIES_GIT_PUSH_TOKEN", TOKEN);

    List<String> logged = new ArrayList<>();
    SettingsImport.Outcome first = capture(logged, () -> importer(stub.url()).run());

    assertEquals(List.of("qits-projects/QITS_REPOSITORIES_GIT_PUSH_TOKEN"), first.imported());
    assertEquals(1, first.alreadySet());
    PdSettingDto kept = row("qits-githost", "QITS_REPOSITORIES_GIT_PUSH_TOKEN");
    assertEquals("op", kept.updatedBy(), "an operator's row is never overwritten");

    // Fill every remaining allowlisted key, so a second run has nothing left to ask about.
    for (SettingsImport.Entry entry : SettingsImport.ALLOWLIST) {
      if (row(entry.application(), entry.key()) == null) {
        settings.put(entry.application(), env, entry.key(), "x", entry.secret(), "op");
      }
    }
    int requests = stub.paths().size();
    SettingsImport.Outcome second = capture(logged, () -> importer(stub.url()).run());

    assertTrue(second.imported().isEmpty());
    assertEquals(SettingsImport.ALLOWLIST.size(), second.alreadySet());
    assertEquals(requests, stub.paths().size(), "a fully imported place reaches no peer");
    assertNoValue(logged);
  }

  @Test
  void readsTheVersionlessResolvedDocumentOfTheDeployersOwnEnvironment() {
    importer(stub.url()).run();

    assertTrue(
        stub.targets().contains("/configuration/api/applications/qits-edge/envs/" + env + "/resolved"),
        String.valueOf(stub.targets()));
    assertTrue(stub.targets().stream().noneMatch(t -> t.contains("?")), "no version is asked for");
  }

  @Test
  void doesNothingWhenNoExtrasUrlIsSet() {
    SettingsImport importer = importer(null);

    assertNull(importer.start(), "no thread is started");
    assertNull(importer.run());
    assertTrue(settings.list(null, env).isEmpty());
    assertTrue(stub.paths().isEmpty());
  }

  @Test
  void anUnreachableConfigurationRetriesThenGivesUpWithoutFailingAndWithoutWriting()
      throws Exception {
    String dead;
    try (ServerSocket socket = new ServerSocket(0)) {
      dead = "http://127.0.0.1:" + socket.getLocalPort();
    }
    List<String> logged = new ArrayList<>();
    SettingsImport.Outcome outcome = capture(logged, () -> importer(dead).run());

    assertEquals(2, outcome.passes(), "retried before giving up");
    assertEquals(
        List.of("qits-edge", "qits-githost", "qits-projects", "qits-oci-postgresql", "qits-deployments"),
        outcome.unreadable());
    assertTrue(outcome.imported().isEmpty());
    assertTrue(settings.list(null, env).isEmpty());
    assertTrue(
        logged.stream().anyMatch(l -> l.contains("gave up after 2 pass(es)")),
        String.join("\n", logged));
  }

  @Test
  void theBootPathReturnsAtOnceWhileTheReadFails() throws Exception {
    stub.answers(500, "{}");
    SettingsImport importer = importer(stub.url());
    importer.firstPauseMillis = 200;

    long before = System.nanoTime();
    Thread worker = importer.start();
    long tookMillis = (System.nanoTime() - before) / 1_000_000;

    assertNotNull(worker);
    assertTrue(worker.isDaemon());
    assertTrue(tookMillis < 1000, "start() must not wait for the read: " + tookMillis + " ms");
    worker.join(30_000);
    assertFalse(worker.isAlive(), "the worker gives up rather than retrying forever");
    assertTrue(settings.list(null, env).isEmpty());
  }

  @Test
  void theRetiredPrefixIsReadToo() {
    stub.resolves(
        1, DeploymentDriver.LEGACY_EXTRAS_PREFIX + "qits-deployments.env.QITS_DOMAIN", "example.test");

    SettingsImport.Outcome outcome = importer(stub.url()).run();

    assertEquals(List.of("qits-deployments/QITS_DOMAIN"), outcome.imported());
    assertEquals("example.test", row("qits-deployments", "QITS_DOMAIN").value());
  }

  private static void assertNoValue(List<String> logged) {
    for (String line : logged) {
      for (String value : List.of(TOKEN, PG, EVENTS, "operator")) {
        assertFalse(line.contains(value), "a value was logged: " + line);
      }
    }
  }

  /** Everything logged under this component's package while {@code body} runs, formatted. */
  private static <T> T capture(List<String> lines, Supplier<T> body) {
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger("eu.wohlben.qits");
    Level level = logger.getLevel();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            Object[] parameters = record.getParameters();
            String message = String.valueOf(record.getMessage());
            if (parameters != null && parameters.length > 0) {
              message +=
                  " "
                      + java.util.Arrays.stream(parameters)
                          .map(String::valueOf)
                          .collect(Collectors.joining(" "));
            }
            if (record.getThrown() != null) {
              message += " " + record.getThrown();
            }
            synchronized (lines) {
              lines.add(message);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    logger.setLevel(Level.INFO);
    try {
      return body.get();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(level);
    }
  }
}
