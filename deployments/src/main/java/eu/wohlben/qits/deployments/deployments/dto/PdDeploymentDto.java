package eu.wohlben.qits.deployments.deployments.dto;

import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import java.time.Instant;
import java.util.List;

/**
 * One recorded deployment attempt; {@code applicationName} denormalized for legible listings.
 *
 * <p>{@code applicationId} is DERIVED from {@code (environmentId, applicationName)} — the same
 * definition the applications listing uses on its side — because a deployment row points at no
 * service row and a client joins the two listings on that id.
 *
 * <p><b>{@code version} is the released coordinate and {@code commitSha} is the commit behind it.</b>
 * The version is what the image is tagged with and what a reader identifies the deployment by; the
 * sha is the commit the released tag resolved to, and it is NULLABLE — a repository carrying no
 * deployments.yml answers the spec read with a 404, which says nothing about where the tag points.
 * On rows written before releases became the trigger it is the other way round: {@code version} is
 * null and the sha was the whole coordinate.
 *
 * <p>{@code runId} is the qits-ci run that caused this deployment, and it may be null: a {@code
 * SoftwareRelease} carries none at all, so only a manual replay supplies one. A client renders it as
 * a link to {@code /ci/runs/<runId>} when it is set and omits it when it is not; there is no other
 * way to reach the run from here.
 *
 * <p>{@code retiredSettings} is the setting keys this deployment's {@code image_defaults:} retired
 * (qits-2048) — deleted from the settings store once it was {@code ACTIVE}, because each held
 * exactly the default the image now ships — in the file's order. Keys only, never a value. Null on
 * every deployment that retired nothing, which is nearly all of them: the field is the one place a
 * reader can see where such a row went, since the delete itself writes no event and no audit row.
 */
public record PdDeploymentDto(
    String id,
    String applicationId,
    String applicationName,
    String version,
    String commitSha,
    String runId,
    PdDeploymentStatus status,
    String containerName,
    String detail,
    Instant createdAt,
    Instant finishedAt,
    List<String> retiredSettings) {}
