package eu.wohlben.qits.deployments.deployments.mapper;

import eu.wohlben.qits.deployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.deployments.deployments.dto.PdDeploymentRequestDto;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.environments.control.ApplicationKeys;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The deployment wire shape. Hand-written rather than a MapStruct interface for one reason: {@code
 * applicationId} is not a column. It is derived from the row's {@code (environmentId,
 * applicationName)} through {@link ApplicationKeys}, which is the same definition the applications
 * listing derives its own id from — that shared derivation is what lets a client keep joining the
 * two listings when neither side has a row to take an id from.
 *
 * <p><b>The plane came out of the key with the plane itself.</b> It was there because the two sides
 * of the join disagreed about the tier — a platform deployment named one and the catalogue row it
 * joined to carried no link — and now both sides read the tier off the same links. A client that
 * cached a {@code platform:<name>} id across the change fails to join, which is the cutover this
 * key pays for once.
 */
@ApplicationScoped
public class DeploymentMapper {

  public PdDeploymentDto toDto(PdDeployment deployment) {
    return new PdDeploymentDto(
        deployment.id,
        ApplicationKeys.of(deployment.environmentId, deployment.applicationName),
        deployment.applicationName,
        deployment.version,
        deployment.commitSha,
        deployment.runId,
        deployment.status,
        deployment.containerName,
        deployment.detail,
        deployment.createdAt,
        deployment.finishedAt,
        retiredSettings(deployment.retiredSettings));
  }

  /**
   * The {@code retired_settings} column as the list it is: comma-separated keys in the file's
   * order, and null — not an empty list — when the row retired nothing. A setting key cannot hold a
   * comma ({@code Settings.requireKey}), so the split is exact.
   */
  private static List<String> retiredSettings(String column) {
    if (column == null || column.isBlank()) {
      return null;
    }
    return List.of(column.split(","));
  }

  /**
   * The deployment REQUEST's wire shape — a field-for-field copy of the row, plus one field that is
   * not on it.
   *
   * <p>No {@code applicationId} is computed. The derivation needs the PLANE, a request has no column
   * for one, and inventing {@code platform:} or {@code <tier>:} from the tier alone is exactly the
   * silent mis-join this class's header describes. The name is the join key, and {@link
   * eu.wohlben.qits.deployments.deployments.dto.PdDeploymentRequestDto} says so.
   *
   * <p><b>The deployment is a parameter and is nullable, and both halves of that are the point.</b>
   * A request the gate refused points at nothing, so there is no row to take a status from and the
   * field is null — the honest answer, and one a client reads as "nothing ran" rather than as "not
   * yet". And the row arrives as an argument rather than being fetched here, because every caller is
   * mapping a LIST: the join is one batch query at the caller, and a mapper that loaded per row
   * would turn a listing into a query per line.
   *
   * @param deployment the deployment {@code request.deploymentId} names, or {@code null}
   */
  public PdDeploymentRequestDto toDto(PdDeploymentRequest request, PdDeployment deployment) {
    return new PdDeploymentRequestDto(
        request.id,
        request.applicationName,
        request.variant,
        request.version,
        request.environmentId,
        request.packageName,
        request.priority,
        request.repoId,
        request.projectId,
        request.qualityGate,
        request.gateDetail,
        request.deploymentId,
        request.createdAt,
        request.gateSettledAt,
        deployment == null ? null : deployment.status);
  }
}
