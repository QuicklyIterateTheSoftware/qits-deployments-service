package eu.wohlben.qits.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A person declined a HELD deployment request (qits-2271): the release asked to wait for an
 * approval, and the answer was no. The request's gate settled {@code UNMET}, nothing was queued and
 * nothing will deploy that version from this request. <b>The decline is final</b> — there is no
 * un-decline; whoever wants the work back pushes a branch made from the release's tag and opens a new
 * release request.
 *
 * <p><b>Its subject is the deployment REQUEST, not a deployment</b>, which is why it does not share
 * {@link DeploymentQueued}'s fields: a held request never had a deployment, so there is no {@code
 * deploymentId}, no container and no run to name. {@code deploymentRequestId} is the row's id and
 * {@code releaseRequestQualifiedId} the release request it came from ({@code
 * qits-ci-service-rr-12}) — the key a consumer that follows release requests resolves by.
 *
 * <p><b>One event per settled row.</b> A decline settles every still-held request of the same
 * release request — the other applications and tiers of that release — in one transaction, and each
 * of those rows is announced on its own, so a consumer of any one row sees it. They share {@code
 * declinedBy}, {@code note} and {@code declinedAt}.
 *
 * <p>{@code environment} is the tier's NAME, the way a person reads it. {@code note} is the
 * decliner's free text and is nullable; a null field is omitted from the canonical payload rather
 * than written as an explicit null. It is for a person, never something to parse.
 *
 * <p>{@code occurredAt} is {@code declinedAt}, the value the decline wrote on the row.
 */
public record DeploymentRequestDeclined(
    UUID eventId,
    String deploymentRequestId,
    String releaseRequestQualifiedId,
    String appName,
    String environment,
    String version,
    String declinedBy,
    String note,
    Instant declinedAt)
    implements QitsEvent {

  public DeploymentRequestDeclined {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public DeploymentRequestDeclined(
      String deploymentRequestId,
      String releaseRequestQualifiedId,
      String appName,
      String environment,
      String version,
      String declinedBy,
      String note,
      Instant declinedAt) {
    this(
        null,
        deploymentRequestId,
        releaseRequestQualifiedId,
        appName,
        environment,
        version,
        declinedBy,
        note,
        declinedAt);
  }

  @Override
  public Instant occurredAt() {
    return declinedAt;
  }
}
