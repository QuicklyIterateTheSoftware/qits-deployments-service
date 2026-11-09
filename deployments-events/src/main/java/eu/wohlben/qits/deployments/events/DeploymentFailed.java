package eu.wohlben.qits.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The deployment ended badly: nothing was cut over, and whatever was serving before still is. Every
 * terminal outcome that is not {@link DeploymentActive} arrives as this event.
 *
 * <p>Its common fields are {@link DeploymentQueued}'s, argued there.
 *
 * <p><b>{@code status} is a string carrying the deployer's own terminal state</b> — {@code FAILED},
 * {@code IMAGE_MISSING} or {@code ROLLED_BACK} — rather than one event type per outcome or a
 * boolean. They are one thing to a consumer ("this did not go live") and different things to a
 * person: an image the registry does not have yet is the expected outcome for an application
 * nothing publishes, and a rollback means the predecessor is still serving. Folding either into
 * {@code FAILED} would page somebody for it.
 *
 * <p><b>That the field is a string is what let the vocabulary grow without a fifth event.</b> A
 * word added here reaches every consumer as data; one that switches on the value learns a new one
 * and one that only cares "not live" is untouched. An enum on the wire would have been a shared
 * type, and cross-context references here are plain values.
 *
 * <p>Two of the deployer's words never arrive on this event, deliberately: {@code SUPERSEDED} is
 * written by the startup sweep and {@code GONE} by the periodic observation, and neither announces
 * anything — both restate an outcome minutes or hours after the fact, which a consumer would first
 * have to know supersedes the earlier statement.
 *
 * <p><b>{@code detail} is docker's own output or this component's own sentence, and it is
 * nullable.</b> It is a diagnosis for a person, never something to parse — the wording is whatever
 * the failing tool said and is not a contract. A null field is omitted from the canonical payload
 * rather than written as an explicit null.
 *
 * <p>{@code containerName} is absent, unlike on {@link DeploymentActive}: most failures happen
 * before a container exists, and the ones that do not have already removed it by the time this is
 * published. A name that resolves to nothing is worse than no name.
 *
 * <p>{@code occurredAt} is {@code finishedAt}, the value the outcome bookkeeping wrote on the row.
 */
public record DeploymentFailed(
    UUID eventId,
    String deploymentId,
    String applicationName,
    String environmentId,
    String environmentName,
    String version,
    String commitSha,
    String runId,
    String status,
    String detail,
    Instant finishedAt,
    String variant)
    implements QitsEvent {

  public DeploymentFailed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public DeploymentFailed(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String status,
      String detail,
      Instant finishedAt) {
    this(
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        status,
        detail,
        finishedAt,
        null);
  }

  /**
   * The publisher's whole constructor: {@code variant} is null for a release's main deployment and
   * the variant's name ({@code private}) for one a {@code variants:} entry asked for (qits-1976). It
   * goes last and is absent from the payload when null, so a consumer that follows a release can
   * tell a variant's failure from the release's own.
   */
  public DeploymentFailed(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String status,
      String detail,
      Instant finishedAt,
      String variant) {
    this(
        null,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        status,
        detail,
        finishedAt,
        variant);
  }

  /** The shape before {@code variant}, identity included: a release's main deployment. */
  public DeploymentFailed(
      UUID eventId,
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String status,
      String detail,
      Instant finishedAt) {
    this(
        eventId,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        status,
        detail,
        finishedAt,
        null);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
