package eu.wohlben.qits.deployments.deployments.control;

import java.util.List;

/**
 * The seam between a decommission and qits-artifacts' purge door (qits-1941): every published
 * artifact of a retired application, in every store type, removed.
 *
 * <pre>
 * POST &lt;artifacts-url&gt;/artifacts/api/applications/{application}/purge
 * {"repositories":["&lt;repository&gt;",…]}
 * </pre>
 *
 * <p>The {@link IdpClientProvisioner} arrangement again: this module owns the interface and the
 * decision to call it ({@link ApplicationRetirement#decommission}), {@code service/artifactshost}
 * owns the sole production implementation ({@code HttpArtifactPurge}, on this component's own
 * {@code qits:system} bearer), and the suite installs a scripted fake ({@code FakeArtifactPurge}),
 * so a clone's {@code mvn verify} reaches no registry.
 *
 * <p><b>Idempotent at the far end</b>: a second purge of the same application answers {@code
 * removed: []}. That is what lets a failed purge be retried by calling decommission again, whose
 * own relabel is idempotent too.
 */
public interface ArtifactPurge {

  /**
   * Purge one application's artifacts.
   *
   * @param applicationName the application NAME, never the {@code <env>:<name>} key: artifacts are
   *     published per application, not per tier
   * @param repositories the repositories whose artifacts are the application's too (docs, maven
   *     and npm packages are published under the repository name, not the application's)
   * @return what was removed; empty when nothing was left
   * @throws PurgeException when qits-artifacts could not be reached or answered anything but 2xx
   */
  List<RemovedArtifact> purge(String applicationName, List<String> repositories);

  /**
   * One package the purge removed, as qits-artifacts reports it.
   *
   * @param type the store type: {@code docs}, {@code maven}, {@code npm}, {@code oci} or {@code
   *     sbom}
   * @param repository the store it was removed from
   * @param name the package's name in that store
   * @param versions every version of it that went
   */
  record RemovedArtifact(String type, String repository, String name, List<String> versions) {}
}
