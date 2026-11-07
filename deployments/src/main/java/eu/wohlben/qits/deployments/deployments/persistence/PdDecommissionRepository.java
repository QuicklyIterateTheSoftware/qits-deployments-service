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
}
