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
import eu.wohlben.qits.deployments.environments.error.PdException;
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
 * carries now (qits-1390).
 *
 * <p><b>"Next" is today's computation plus the settings store.</b> The driver renders the
 * environment exactly as a deployment's argv would ({@link DeploymentDriver#stagedEnvironment}:
 * this component's own variables, the {@code QITS_RESOURCE_*} family, then the deployment config's
 * {@code env}), and the settings rows for the place are overlaid on top — a setting wins over an
 * extras key of the same name. Nothing deploys from the settings store yet, so this is the preview
 * of switching that read on, not of the deployment that would run today.
 *
 * <p><b>Three approximations, each because the true value only exists mid-deployment:</b>
 *
 * <ul>
 *   <li>the release is the newest one recorded for the place — its version is what the identity
 *       variables are stamped with; a new release would stamp its own, so those show as changed
 *       only when the live service runs something else;
 *   <li>the resource family is rendered from the registry rows ({@link
 *       ResourceProvisioning#recordedBindings}) rather than provisioned, which is what the next
 *       deployment hands out on its self-heal arm;
 *   <li>the deployment config is read VERSION-LESS (as a release that seeded no declaration reads
 *       it), because which release comes next — and whether it declares itself — is not known yet.
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
            false,
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

    Map<String, String> next;
    try {
      next = SettingsDiff.parse(driver.stagedEnvironment(spec));
    } catch (ServiceExtras.Refused e) {
      throw new PdException(
          502, "the deployment config of " + app + " in " + env + " cannot be read: " + e.getMessage());
    }
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

    return SettingsDiff.diff(app, env, next, running, secretKeys);
  }
}
