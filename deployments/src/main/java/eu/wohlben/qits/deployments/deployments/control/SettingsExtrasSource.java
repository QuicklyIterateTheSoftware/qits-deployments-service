package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.entity.PdSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * The sole implementation of {@link DeploymentExtrasSource}: the settings store (qits-1393, epic
 * qits-765).
 *
 * <p>Every {@code pd_setting} row of the place — this application in this tier, and no other —
 * becomes {@code qits.deployments.extras.<application>.env.<KEY>=<value>}, layered over the boot
 * config by {@link ExtrasSnapshot#over}, which hides every extras key the boot config carries. So
 * {@link ServiceExtras} stays the one parser of the grammar, and the store is SOLE: a deleted row is
 * absent from the next argv, and the update's {@code --env-rm} diff removes it from the live
 * service.
 *
 * <p><b>Secret rows render verbatim.</b> {@code secret} decides how a value is SHOWN at the doors,
 * never whether a container gets it; the masking lives in {@link Settings}' DTOs, which this does
 * not use.
 *
 * <p><b>Only the {@code env} family can arrive</b>: a setting key is an environment-variable name
 * ({@code [A-Z_][A-Z0-9_]*}, held at the door), so mounts, publishes, aliases and groups are the
 * repository's {@code deployments.yml} alone now.
 *
 * <p>A store that cannot be read throws what the database threw, and the driver's refusal arm
 * records it on the deployment — the argv is never built from a partial read.
 */
@ApplicationScoped
public class SettingsExtrasSource implements DeploymentExtrasSource {

  private static final Logger LOG = Logger.getLogger(SettingsExtrasSource.class);

  @Inject Config config;

  @Inject Settings settings;

  @Override
  public Config forDeployment(String application, String environmentName) {
    List<PdSetting> rows;
    try {
      rows = settings.rowsOf(application, environmentName);
    } catch (RuntimeException e) {
      throw new ServiceExtras.Refused(
          "the settings of "
              + application
              + " in "
              + environmentName
              + " could not be read: "
              + e.getMessage());
    }
    // Keys only, never values: a secret row renders verbatim into the argv, and this line is read
    // by whoever reads the log.
    LOG.infof(
        "%d settings for %s in %s: %s",
        rows.size(),
        application,
        environmentName,
        rows.stream().map(row -> row.settingKey).sorted().toList());
    return ExtrasSnapshot.over(
        config, render(application, rows), "pd_setting " + application + "/" + environmentName);
  }

  /** The rows of one place in the extras grammar — public so the driver suite renders them alone. */
  public static Map<String, String> render(String application, Iterable<PdSetting> rows) {
    Map<String, String> stated = new HashMap<>();
    String prefix = DeploymentDriver.EXTRAS_PREFIX + application + ".env.";
    for (PdSetting row : rows) {
      stated.put(prefix + row.settingKey, row.value == null ? "" : row.value);
    }
    return stated;
  }
}
