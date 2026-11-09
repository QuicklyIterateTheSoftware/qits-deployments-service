package eu.wohlben.qits.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The deployment succeeded: this container passed the health gate, the cutover was recorded, and
 * this commit of this application is what serves now. <b>The one event on this bus that says a
 * change is live.</b>
 *
 * <p>Its common fields are {@link DeploymentQueued}'s, argued there.
 *
 * <p><b>{@code containerName} is the fact a consumer cannot derive.</b> The name is built from the
 * deployment id rather than the sha — re-deploying one commit must never collide with the container
 * it replaces — so it is knowable only from here. It is what a person greps the host for.
 *
 * <p><b>{@code endpoints} is the complete, immutable routing snapshot.</b> It includes the resolved
 * wire alias rather than asking consumers to reimplement a deployer convention. An empty list is a
 * service that declares no public routes; consumers replace, never merge, the prior snapshot.
 *
 * <p><b>{@code browserHost} is the one DNS label this application is served at</b> — {@code ci} in
 * {@code ci.dev.wohlben.eu} — and null is a real answer: an application that asks for no host of
 * its own is reached under its path prefix, as every application was. The edge builds the authority
 * around the label; the label alone is what travels, so one deployment describes itself on every
 * environment and domain the platform has.
 *
 * <p><b>{@code navigation} is application-level and replaces the label an endpoint used to carry.</b>
 * One application can sit under several headings — a repository's Docs, CI and Workspaces are the
 * same three entries under seven categories — and a per-route label could say none of that. Empty is
 * an application that creates no navigation option at all, which is most of them.
 *
 * <p><b>{@code apiDocsPath} is where this application's browsable API document lives</b>, under one
 * of its published routes ({@code /ci/q/swagger-ui}), and null is a real answer: a service that
 * documents no HTTP surface. Like the routes it is a path and never an origin — the edge composes
 * the authority around it.
 *
 * <p><b>{@code exposure} is which edge may route this deployment</b>: {@code public}, {@code private}
 * or {@code both}, the repository's {@code exposure:} key. It is the last component because a
 * consumer reading the payload by name ignores what it does not know, and <b>a frame without it —
 * every frame recorded before it existed — reads as {@code public}</b>, which is also what a null
 * here becomes. An edge stores a frame outside its own class as an empty snapshot, so a move from
 * public to private withdraws the vhost from the public edge rather than leaving it stale.
 *
 * <p><b>{@code variant} says which of a release's deployments this is</b> (qits-1976): null for the
 * main deployment, the variant's name ({@code private}) for one a {@code variants:} entry put live
 * from the same image and release. A consumer that follows a RELEASE — qits-projects finalizing one
 * — reads a non-null variant as "not the release's own deployment". It goes last, after {@code
 * exposure}, and is absent from the payload when null.
 *
 * <p>{@code occurredAt} is {@code finishedAt}, the value the cutover bookkeeping wrote on the row.
 * The predecessor it decommissioned announces nothing: a deployment being replaced is this event
 * seen from the other side, and a consumer that wants "what is live" reads the newest event per
 * (application, tier). The one exception is a {@code renamed_from} predecessor, a different
 * application that will never deploy again: it is announced once per successor cutover, just before
 * the successor's own event, with an empty endpoint snapshot, no host and no version — its routes
 * withdrawn rather than replaced.
 */
public record DeploymentActive(
    UUID eventId,
    String deploymentId,
    String applicationName,
    String environmentId,
    String environmentName,
    String version,
    String commitSha,
    String runId,
    String containerName,
    Instant finishedAt,
    String browserHost,
    String apiDocsPath,
    List<NavigationEntry> navigation,
    List<DeploymentEndpoint> endpoints,
    String exposure,
    String variant)
    implements QitsEvent {

  /** What a frame that names no exposure means: every frame recorded before the field existed. */
  public static final String PUBLIC = "public";

  public DeploymentActive {
    exposure = exposure == null || exposure.isBlank() ? PUBLIC : exposure;
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    navigation = navigation == null ? List.of() : List.copyOf(navigation);
    endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public DeploymentActive(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt) {
    this(
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        containerName,
        finishedAt,
        List.of());
  }

  /** The constructor a publisher uses when this deployment exposes public endpoints. */
  public DeploymentActive(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt,
      List<DeploymentEndpoint> endpoints) {
    this(
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        containerName,
        finishedAt,
        null,
        null,
        List.of(),
        endpoints);
  }

  /**
   * The whole statement: the routes, the host they are also served at, where the application asks
   * to appear in the navigation, and where its API document lives.
   */
  public DeploymentActive(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation,
      List<DeploymentEndpoint> endpoints) {
    this(
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        containerName,
        finishedAt,
        browserHost,
        apiDocsPath,
        navigation,
        endpoints,
        null);
  }

  /** The publisher's constructor with the deployment's exposure class, {@code public} when null. */
  public DeploymentActive(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation,
      List<DeploymentEndpoint> endpoints,
      String exposure) {
    this(
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        containerName,
        finishedAt,
        browserHost,
        apiDocsPath,
        navigation,
        endpoints,
        exposure,
        null);
  }

  /** The publisher's whole constructor: the exposure class and, for a variant, its name. */
  public DeploymentActive(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation,
      List<DeploymentEndpoint> endpoints,
      String exposure,
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
        containerName,
        finishedAt,
        browserHost,
        apiDocsPath,
        navigation,
        endpoints,
        exposure,
        variant);
  }

  /** The shape before {@code exposure}, identity included: the class is {@code public}. */
  public DeploymentActive(
      UUID eventId,
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation,
      List<DeploymentEndpoint> endpoints) {
    this(
        eventId,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        version,
        commitSha,
        runId,
        containerName,
        finishedAt,
        browserHost,
        apiDocsPath,
        navigation,
        endpoints,
        null,
        null);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
