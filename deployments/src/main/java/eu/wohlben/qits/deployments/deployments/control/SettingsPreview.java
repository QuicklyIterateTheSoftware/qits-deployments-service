package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.dto.PdSettingsPreviewDto;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdSetting;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import eu.wohlben.qits.deployments.environments.persistence.PdEnvironmentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the next deployment of one application would set, compared with what its live service
 * carries now (qits-1390, epic qits-765).
 *
 * <p><b>"Next" is the deployment as it runs now</b>, since the switch (qits-1393): the environment
 * this component injects itself ({@link DeploymentDriver#stagedEnvironment} without extras — the
 * identity variables, {@code QITS_DOMAIN}, {@code QITS_RESOURCE_*}) with the settings rows overlaid.
 * That is exactly what a deployment renders, because the settings store is its one extras source.
 * There used to be a second mode, today's deployment with qits-configuration's extras; with that
 * service gone the two are one, and the door answers both spellings identically.
 *
 * <p><b>A live key the update would keep is never "removed".</b> The update's {@code --env-rm}
 * diff exempts this component's own variables and the resource family; the preview asks the
 * driver the same question ({@link DeploymentDriver#keptWhenUnstated}) and lists such keys as
 * {@code kept}.
 *
 * <p><b>Approximations, each because the true value only exists mid-deployment:</b>
 *
 * <ul>
 *   <li>the release is the newest one recorded for the place — its version is what the identity
 *       variables are stamped with; a new release would stamp its own;
 *   <li>the resource family is rendered from the registry rows ({@link
 *       ResourceProvisioning#recordedBindings}) rather than provisioned, which is what the next
 *       deployment hands out on its self-heal arm;
 *   <li>with the extras, the deployment config is read VERSION-LESS (as a release that seeded no
 *       declaration reads it), because which release comes next is not known yet.
 * </ul>
 *
 * <p>It writes nothing and calls the orchestrator for one {@code service inspect}.
 */
@ApplicationScoped
public class SettingsPreview {

  @Inject PdEnvironmentRepository environments;
  @Inject PdDeploymentRepository deployments;
  @Inject ServiceCatalog catalog;
  @Inject Settings settings;
  @Inject ResourceProvisioning resources;
  @Inject DeploymentDriver driver;

  /** What the place is, as plain values read in one bracket. */
  private record Place(String environmentId, String version, String serviceName) {}

  public PdSettingsPreviewDto preview(String application, String environment) {
    String app = PdIdentifiers.requireName(application, "application");
    String env = PdIdentifiers.requireName(environment, "environment");
    Place place =
        QuarkusTransaction.joiningExisting()
            .call(
                () -> {
                  PdEnvironment tier =
                      environments
                          .findByName(env)
                          .orElseThrow(() -> new NotFoundException("no environment " + env));
                  PdDeployment newest = deployments.newestForPlace(app, tier.id).orElse(null);
                  if (newest == null && catalog.find(app).isEmpty()) {
                    throw new NotFoundException(
                        "the deployer knows no application " + app + " in " + env);
                  }
                  return new Place(
                      tier.id,
                      newest == null ? null : newest.imageTag(),
                      newest == null ? null : newest.containerName);
                });

    DeploymentDriver.ServiceSpec spec =
        new DeploymentDriver.ServiceSpec(
            place.environmentId(),
            env,
            ApplicationKeys.of(place.environmentId(), app),
            app,
            null,
            place.version(),
            place.version(),
            null,
            PdNetworks.alias(env, app),
            List.of(),
            null,
            null,
            null,
            false,
            null,
            null,
            resources.recordedBindings(app, env),
            List.of());

    // Without the extras: the settings rows ARE the extras now, and they are overlaid below with
    // their secret flags, which the extras grammar does not carry.
    Map<String, String> next = SettingsDiff.parse(driver.stagedEnvironment(spec, false));
    Set<String> secretKeys = new HashSet<>();
    for (PdSetting row : settings.rowsOf(app, env)) {
      next.put(row.settingKey, row.value);
      if (row.secret) {
        secretKeys.add(row.settingKey);
      }
    }

    // The row's own service name first: the deployer's own service may still carry the seed
    // stack's prefix, which only the row recorded.
    String serviceName =
        place.serviceName() == null || place.serviceName().isBlank()
            ? driver.nameOf(spec)
            : place.serviceName();
    Map<String, String> running =
        driver.liveEnvironment(serviceName).map(SettingsDiff::parse).orElse(Map.of());

    return SettingsDiff.diff(
        app, env, false, next, running, secretKeys, driver::keptWhenUnstated);
  }
}
