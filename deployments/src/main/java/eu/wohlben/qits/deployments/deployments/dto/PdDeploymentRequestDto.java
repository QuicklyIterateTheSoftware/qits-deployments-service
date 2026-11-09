package eu.wohlben.qits.deployments.deployments.dto;

import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import java.time.Instant;

/**
 * One deployment request on the wire: <b>this version of this application was asked for here, the
 * gate said this, and this is what it became</b>.
 *
 * <p>It is the lifecycle a reader watches — request, gate, deployment — and the only shape in which
 * a release that shipped NOTHING is visible at all. A refused request has a null {@code
 * deploymentId} and a {@code gateDetail} that says why; there is no deployment row anywhere that
 * could carry that sentence.
 *
 * <p><b>The join key is {@code applicationName} and deliberately not a derived {@code
 * applicationId}.</b> {@link PdDeploymentDto} derives one from {@code (plane, tier, name)} because
 * a deployment row records its plane; a request records no plane — it is written before the
 * catalogue is consulted for one — so deriving a key here would mean guessing {@code platform:} or
 * {@code <tier>:} from a column that does not exist. A name is unique per tier by construction (the
 * catalogue holds one identity per service), so a client folds these into its application rows by
 * name, and that is honest rather than lossy.
 *
 * <p>{@code version} is the released CalVer coordinate and is never null: a request is about a
 * version, which is what makes two of them comparable. {@code environmentId} is where it was asked
 * for — a platform service's request names the main environment like its deployment does.
 *
 * <p>{@code gateSettledAt} is null while nothing has answered. Today's placeholder answers in the
 * same transaction that writes the row, so it is always set; the field exists because the first
 * real gate is the one that will leave it null for a while.
 *
 * <p><b>{@code priority} is what the release request declared and is display-only.</b> It arrives
 * from qits-projects through qits-ci and this component records it; nothing here orders anything by
 * it — the deploy worker is FIFO. Null is a complete answer and the common one: the release stated
 * none, which is every release cut before the field existed. A client renders a badge or nothing.
 *
 * <p><b>{@code variant} names the variant this request deploys</b> ({@code private} for {@code
 * qits-edge-private}), and is null for a release's main application — every request but a
 * variant's, and every one written before variants existed (qits-1976). A release's own listing
 * ({@code ?repoId=&version=}) carries only the null ones.
 *
 * <p><b>{@code deploymentStatus} is the one field here that is not this row's own</b> — it is
 * joined from the deployment {@code deploymentId} names, and it is on the request because the
 * question a reader asks of a request spans both rows: "is the platform still doing something about
 * this release" is answered by the gate and by the container together, and neither alone. Carrying
 * it means a client folding a listing into two sections makes no second request, and it is why the
 * listings batch-load rather than fetch per row. Null means there is no deployment to have a status:
 * a refusal queued nothing, and a teardown may since have forgotten the row a request points at.
 */
public record PdDeploymentRequestDto(
    String id,
    String applicationName,
    String variant,
    String version,
    String environmentId,
    String packageName,
    String priority,
    String repoId,
    String projectId,
    PdQualityGate qualityGate,
    String gateDetail,
    String deploymentId,
    Instant createdAt,
    Instant gateSettledAt,
    PdDeploymentStatus deploymentStatus) {}
