package eu.wohlben.qits.deployments.confighost;

import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.Settings;
import eu.wohlben.qits.deployments.deployments.dto.PdSettingDto;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The one-shot import of secrets and installation facts from qits-configuration into {@code
 * pd_setting} (qits-1392).
 *
 * <p><b>Why the deployer copies them itself.</b> A settings write is {@code qits:admin}/{@code
 * qits:system} only, and these values must never pass through an agent, a shell or a ticket. This
 * process already reads qits-configuration's resolved entries ({@link ConfigHostExtrasSource}, over
 * {@link ExtrasBearer}), so it is the one party that can move them without anybody seeing them.
 *
 * <p><b>What it does</b>, once per boot, on a daemon thread of its own so a slow or absent
 * qits-configuration never delays startup, and only when {@code qits.deployments.extras-url} is
 * set: for every entry of {@link #ALLOWLIST}, in this deployer's own environment —
 *
 * <ul>
 *   <li>a {@code pd_setting} row already there for (application, environment, key) → left alone,
 *       never overwritten: an operator's write wins over a copy, and a second boot imports nothing;
 *   <li>otherwise the served {@code env.<KEY>} of that application, if present and non-blank, is
 *       written through {@link Settings#put} — the PUT door's own validation — by {@link
 *       #UPDATED_BY}, with the allowlist's secret flag;
 *   <li>absent or blank → nothing.
 * </ul>
 *
 * <p>An application whose read fails is retried with the whole pass, a few times with a growing
 * pause; the rows already written make every later pass skip them. Then it gives up with a WARN —
 * never a failed boot.
 *
 * <p><b>Values never leave this class except into the store.</b> One INFO line per run carries the
 * counts and the imported keys' NAMES; nothing logs, returns or throws a value, and a failure is
 * reported by its exception CLASS only, because a message could quote what it failed on.
 *
 * <p>Self-contained on purpose: qits-1393 deletes it together with the rest of {@code
 * confighost}.
 */
@ApplicationScoped
public class SettingsImport {

  private static final Logger LOG = Logger.getLogger(SettingsImport.class);

  /** Who an imported row says wrote it. */
  static final String UPDATED_BY = "import:qits-configuration";

  /** One importable variable: whose it is, its name, and whether the store masks it. */
  record Entry(String application, String key, boolean secret) {}

  /**
   * THE list — nothing outside it is ever copied, whatever qits-configuration serves. Add a line to
   * import one more variable.
   *
   * <p>{@code QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH} feeds {@code qits.deployments.registry-auth},
   * a boolean that only toggles {@code --with-registry-auth} (the credential itself is a mounted
   * docker config), so it is not a secret.
   */
  static final List<Entry> ALLOWLIST =
      List.of(
          new Entry("qits-edge", "QITS_EDGE_ACME_EMAIL", false),
          new Entry("qits-edge", "QITS_EDGE_ACME_ENABLED", false),
          new Entry("qits-edge", "QITS_EDGE_ACME_MODE", false),
          new Entry("qits-edge", "QITS_EDGE_ACME_HETZNER_TOKEN", true),
          new Entry("qits-edge", "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT", false),
          new Entry("qits-edge", "QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY", false),
          new Entry("qits-edge", "QUARKUS_TLS_RELOAD_PERIOD", false),
          new Entry("qits-githost", "QITS_REPOSITORIES_GIT_PUSH_TOKEN", true),
          new Entry("qits-projects", "QITS_REPOSITORIES_GIT_PUSH_TOKEN", true),
          new Entry("qits-oci-postgresql", "POSTGRES_PASSWORD", true),
          new Entry("qits-deployments", "QITS_DOMAIN", false),
          new Entry("qits-deployments", "QITS_PORT", false),
          new Entry("qits-deployments", "QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD", true),
          new Entry("qits-deployments", "QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH", false));

  /** What one run did, by NAME only — never a value. */
  record Outcome(
      String environment,
      List<String> imported,
      int alreadySet,
      int absent,
      List<String> unreadable,
      int passes) {}

  @ConfigProperty(name = "qits.deployments.extras-url")
  Optional<String> extrasUrl;

  /** The deployer's own tier — injected into this container by the deployment that started it. */
  @ConfigProperty(name = "QITS_ENVIRONMENT")
  Optional<String> ownEnvironment;

  @Inject ConfigHostExtrasSource extras;

  @Inject Settings settings;

  @Inject EnvironmentService environments;

  /** Passes before giving up. */
  int passes = 4;

  /** The pause after the first failed pass; it doubles after each further one. */
  long firstPauseMillis = 15_000;

  void onStart(@Observes StartupEvent event) {
    start();
  }

  /** Starts the run on a daemon thread when an extras url is set; answers it, or null. */
  Thread start() {
    if (!configured()) {
      LOG.debug("Settings import skipped: qits.deployments.extras-url is not set");
      return null;
    }
    Thread worker = new Thread(this::runQuietly, "pd-settings-import");
    worker.setDaemon(true);
    worker.start();
    return worker;
  }

  private boolean configured() {
    return extrasUrl != null && extrasUrl.map(String::trim).filter(url -> !url.isEmpty()).isPresent();
  }

  private void runQuietly() {
    try {
      run();
    } catch (RuntimeException e) {
      LOG.warn(
          "Settings import from qits-configuration failed and is abandoned until the next boot: "
              + e.getClass().getName());
    }
  }

  /** The whole run, retries included; null when it did nothing. Package-private for the suite. */
  Outcome run() {
    if (!configured()) {
      return null;
    }
    String environment = environmentName();
    if (environment == null) {
      LOG.warn(
          "Settings import from qits-configuration skipped: QITS_ENVIRONMENT is unset and no"
              + " environment is designated the platform one");
      return null;
    }
    // "application/KEY" → what became of it. IMPORTED is sticky across passes (a later pass sees
    // that row as already set, which is this run's own doing, not an operator's); everything else
    // is what the latest pass found.
    Map<String, Status> statuses = new LinkedHashMap<>();
    Pass pass = null;
    int done = 0;
    long pause = firstPauseMillis;
    for (int attempt = 1; attempt <= Math.max(1, passes); attempt++) {
      pass = pass(environment);
      done = attempt;
      for (Map.Entry<String, Status> found : pass.statuses.entrySet()) {
        if (statuses.get(found.getKey()) != Status.IMPORTED) {
          statuses.put(found.getKey(), found.getValue());
        }
      }
      if (pass.unreadable.isEmpty() || attempt == Math.max(1, passes)) {
        break;
      }
      if (pause > 0) {
        try {
          Thread.sleep(pause);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      pause *= 2;
    }
    for (String application : pass.unreadable.keySet()) {
      statuses
          .entrySet()
          .removeIf(
              e -> e.getKey().startsWith(application + "/") && e.getValue() != Status.IMPORTED);
    }
    Outcome outcome =
        new Outcome(
            environment,
            keysWith(statuses, Status.IMPORTED),
            keysWith(statuses, Status.ALREADY_SET).size(),
            keysWith(statuses, Status.ABSENT).size(),
            List.copyOf(pass.unreadable.keySet()),
            done);
    LOG.info(
        "Settings import from qits-configuration into "
            + environment
            + ": imported="
            + outcome.imported().size()
            + " already-set="
            + outcome.alreadySet()
            + " absent="
            + outcome.absent()
            + " unreadable="
            + outcome.unreadable().size()
            + " passes="
            + done
            + " imported-keys="
            + outcome.imported());
    if (!pass.unreadable.isEmpty()) {
      LOG.warn(
          "Settings import from qits-configuration gave up after "
              + done
              + " pass(es) on "
              + pass.unreadable.entrySet().stream()
                  .map(e -> e.getKey() + " (" + e.getValue() + ")")
                  .collect(Collectors.joining(", "))
              + "; the next boot tries again");
    }
    return outcome;
  }

  private enum Status {
    IMPORTED,
    ALREADY_SET,
    ABSENT
  }

  private static List<String> keysWith(Map<String, Status> statuses, Status wanted) {
    return statuses.entrySet().stream()
        .filter(e -> e.getValue() == wanted)
        .map(Map.Entry::getKey)
        .toList();
  }

  /** One pass's findings. {@code unreadable} maps an application to its failure's class name. */
  private static final class Pass {
    final Map<String, Status> statuses = new LinkedHashMap<>();
    final Map<String, String> unreadable = new LinkedHashMap<>();

    void record(Entry entry, Status status) {
      statuses.put(entry.application() + "/" + entry.key(), status);
    }
  }

  private Pass pass(String environment) {
    Map<String, List<Entry>> byApplication = new LinkedHashMap<>();
    for (Entry entry : ALLOWLIST) {
      byApplication.computeIfAbsent(entry.application(), a -> new ArrayList<>()).add(entry);
    }
    Pass pass = new Pass();
    byApplication.forEach(
        (application, entries) -> {
          try {
            importApplication(application, environment, entries, pass);
          } catch (RuntimeException e) {
            pass.unreadable.put(application, e.getClass().getSimpleName());
          }
        });
    return pass;
  }

  private void importApplication(
      String application, String environment, List<Entry> entries, Pass pass) {
    Set<String> existing = existingKeys(application, environment);
    List<Entry> missing = new ArrayList<>();
    for (Entry entry : entries) {
      if (existing.contains(entry.key())) {
        pass.record(entry, Status.ALREADY_SET);
      } else {
        missing.add(entry);
      }
    }
    if (missing.isEmpty()) {
      // Nothing to copy, so nothing to ask: a second boot reaches no peer at all.
      return;
    }
    Map<String, String> served = extras.currentProperties(application, environment);
    for (Entry entry : missing) {
      String value = served(served, application, entry.key());
      if (value == null || value.isBlank()) {
        pass.record(entry, Status.ABSENT);
        continue;
      }
      // Re-checked right before the write, so an operator's PUT that landed during this run's read
      // still wins: Settings.put is an upsert and would replace it.
      if (existingKeys(application, environment).contains(entry.key())) {
        pass.record(entry, Status.ALREADY_SET);
        continue;
      }
      settings.put(application, environment, entry.key(), value, entry.secret(), UPDATED_BY);
      pass.record(entry, Status.IMPORTED);
    }
  }

  private Set<String> existingKeys(String application, String environment) {
    return settings.list(application, environment).stream()
        .map(PdSettingDto::key)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** {@code env.<KEY>} of one application in the served map, current spelling over the retired. */
  private static String served(Map<String, String> served, String application, String key) {
    String current = served.get(DeploymentDriver.EXTRAS_PREFIX + application + ".env." + key);
    return current != null
        ? current
        : served.get(DeploymentDriver.LEGACY_EXTRAS_PREFIX + application + ".env." + key);
  }

  /**
   * {@code QITS_ENVIRONMENT}, else the designated platform environment — {@code
   * BootResourceRegistration}'s resolution, for a container started before the variable was.
   */
  private String environmentName() {
    Optional<String> declared =
        ownEnvironment == null ? Optional.empty() : ownEnvironment.filter(v -> !v.isBlank());
    if (declared.isPresent()) {
      return declared.get().trim();
    }
    try {
      return QuarkusTransaction.requiringNew()
          .call(() -> environments.platformEnvironment().map(e -> e.name).orElse(null));
    } catch (RuntimeException e) {
      LOG.warn(
          "Settings import could not read the designated platform environment: "
              + e.getClass().getName());
      return null;
    }
  }
}
