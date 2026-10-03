package eu.wohlben.qits.deployments.deployments.persistence;

import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link PdResource}.
 *
 * <p><b>Null is tested as a value, never compared.</b> A platform-plane resource has no environment
 * name, and {@code environmentName = null} matches nothing at all in SQL — the same trap
 * {@code PdDeploymentRepository} documents, and with the same consequence if it is missed: the row
 * would never be found, the provisioner would take the reconcile arm on every deployment, and a
 * working credential would be rotated out from under a running application each time.
 */
@ApplicationScoped
public class PdResourceRepository implements PanacheRepositoryBase<PdResource, String> {

  /** The registry's key: one resource of one application in one tier (null tier = the plane). */
  public Optional<PdResource> findOne(
      String applicationName, String environmentName, String resourceName) {
    return environmentName == null
        ? find(
                "applicationName = ?1 and environmentName is null and resourceName = ?2",
                applicationName,
                resourceName)
            .firstResultOptional()
        : find(
                "applicationName = ?1 and environmentName = ?2 and resourceName = ?3",
                applicationName,
                environmentName,
                resourceName)
            .firstResultOptional();
  }

  /**
   * Everything already claiming this database name — the cross-check that turns two repositories
   * naming one database into a refused deployment rather than a silent takeover. Unscoped by
   * application on purpose: the question is exactly "whose is this".
   */
  public List<PdResource> listByDatabase(String databaseName) {
    return list("databaseName = ?1", databaseName);
  }

  /**
   * Everything already claiming this idp client id — the {@code database_name} cross-check's
   * mirror, for the resource type that has no database. Unscoped by application for the same
   * reason: two applications deriving the same client id is a derivation bug, not a legitimate
   * share, and this is what a caller would ask to notice it.
   */
  public List<PdResource> listByClientId(String clientId) {
    return list("clientId = ?1", clientId);
  }

  /**
   * Every {@code idp-client} row, across every application and tier — the claims listing a
   * garbage collector reads to tell "issued and claimed" apart from "issued and orphaned". Ordered
   * by {@code clientId} so a reader gets a deterministic answer without reaching for a second
   * field to break ties.
   */
  public List<PdResource> listByResourceType(String resourceType) {
    return list("resourceType = ?1 order by clientId", resourceType);
  }
}
