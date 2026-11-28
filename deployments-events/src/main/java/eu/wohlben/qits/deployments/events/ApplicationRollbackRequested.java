package eu.wohlben.qits.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A person asked for ONE application in one tier to be put back on an earlier version that already
 * served there (qits-2779, {@code POST /deployments/api/applications/{id}/rollback}). Published when
 * the door ACCEPTS the request — every guard passed and the redeploy is queued on the deploy worker
 * — and never again: what the redeploy then does is told by the ordinary {@link DeploymentQueued},
 * {@link DeploymentStarted}, {@link DeploymentActive} or {@link DeploymentFailed} of the new row,
 * each of which names this event as its cause.
 *
 * <p><b>Its subject is the decision, not a deployment.</b> {@code fromVersion} is the version that
 * was serving when the request was accepted and {@code toVersion} the one asked for; {@code
 * deploymentId} is the {@code ACTIVE} deployment serving {@code fromVersion} — the row the rollback
 * replaces, which is the one id that exists at accept time (the new row is written on the worker).
 *
 * <p>{@code environmentName} is the tier's NAME, spelled as the four {@code Deployment*} events
 * spell it, and never {@code name} — {@link QitsEvent#name()} is the event's own label. {@code
 * requestedBy} is the caller's principal name and is nullable, as an operator stamp is; {@code
 * reason} is the caller's free text, required by the door, for a person and never to be parsed. A
 * null field is omitted from the canonical payload.
 *
 * <p>{@code occurredAt} is {@code requestedAt}.
 */
public record ApplicationRollbackRequested(
    UUID eventId,
    String applicationName,
    String environmentId,
    String environmentName,
    String fromVersion,
    String toVersion,
    String requestedBy,
    String reason,
    String deploymentId,
    Instant requestedAt)
    implements QitsEvent {

  public ApplicationRollbackRequested {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public ApplicationRollbackRequested(
      String applicationName,
      String environmentId,
      String environmentName,
      String fromVersion,
      String toVersion,
      String requestedBy,
      String reason,
      String deploymentId,
      Instant requestedAt) {
    this(
        null,
        applicationName,
        environmentId,
        environmentName,
        fromVersion,
        toVersion,
        requestedBy,
        reason,
        deploymentId,
        requestedAt);
  }

  @Override
  public Instant occurredAt() {
    return requestedAt;
  }
}
