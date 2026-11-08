package eu.wohlben.qits.deployments.deployments.persistence;

import eu.wohlben.qits.deployments.deployments.entity.PdDecommission;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PdDecommissionRepository implements PanacheRepositoryBase<PdDecommission, UUID> {

  /** Every run for one application name, oldest first. */
  public List<PdDecommission> listByApplication(String applicationName) {
    return list("applicationName = ?1 order by decommissionedAt asc", applicationName);
  }

  /**
   * Every run for one application in one place — {@code (applicationName, environmentId)}, the pair
   * an application id names — oldest first, so a failed purge reads before the retry that settled
   * it (qits-1956).
   *
   * <p>The tier is part of the filter because a name is not an application: the same name on
   * another tier is another place with its own history. A null {@code environmentId} matches the
   * rows that carry none — the column is nullable, V1's rule — rather than every row, which is what
   * binding a null into {@code =} would quietly fail to do. An id that parsed never yields one
   * today ({@code ApplicationKeys.parse} refuses an empty tier), so that branch is the safe reading
   * of the column rather than a path the door takes.
   */
  public List<PdDecommission> listByPlace(String applicationName, String environmentId) {
    if (environmentId == null) {
      return list(
          "applicationName = ?1 and environmentId is null order by decommissionedAt asc, id asc",
          applicationName);
    }
    return list(
        "applicationName = ?1 and environmentId = ?2 order by decommissionedAt asc, id asc",
        applicationName,
        environmentId);
  }
}
