package eu.wohlben.qits.deployments.deployments.persistence;

import eu.wohlben.qits.deployments.deployments.entity.PdSetting;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link PdSetting}.
 *
 * <p>The attribute is {@code settingKey} rather than {@code key} because {@code key} is a function
 * in HQL; a query naming it would be parsed as a map-key accessor.
 */
@ApplicationScoped
public class PdSettingRepository implements PanacheRepositoryBase<PdSetting, String> {

  /** Application, tier, key — so a listing is the same listing twice. */
  private static final Sort ORDER = Sort.by("applicationName").and("environmentName").and("settingKey");

  /** The row's key: one variable of one application in one tier. */
  public Optional<PdSetting> findOne(
      String applicationName, String environmentName, String settingKey) {
    return find(
            "applicationName = ?1 and environmentName = ?2 and settingKey = ?3",
            applicationName,
            environmentName,
            settingKey)
        .firstResultOptional();
  }

  /** Every row matching the filters, each of which is optional — null means "any". */
  public List<PdSetting> listFiltered(String applicationName, String environmentName) {
    if (applicationName != null && environmentName != null) {
      return list(
          "applicationName = ?1 and environmentName = ?2", ORDER, applicationName, environmentName);
    }
    if (applicationName != null) {
      return list("applicationName = ?1", ORDER, applicationName);
    }
    if (environmentName != null) {
      return list("environmentName = ?1", ORDER, environmentName);
    }
    return listAll(ORDER);
  }
}
