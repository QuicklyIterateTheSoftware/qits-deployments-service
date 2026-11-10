package eu.wohlben.qits.deployments.deployments.control;

/**
 * What a deployed container is told about itself, in OpenTelemetry's own vocabulary — three
 * attributes, each a value this component genuinely holds at deploy time and none invented:
 *
 * <ul>
 *   <li>{@code service.version} — the deployment's commit sha. This component deploys sha-addressed
 *       images, so the sha IS the released identity; it is not a version number and is not dressed
 *       up as one.
 *   <li>{@code deployment.environment.name} — the environment this container belongs to. Every
 *       container has one now; {@value #NO_ENVIRONMENT} is a fallback that marks a bug rather than
 *       the platform plane it was named for.
 *   <li>{@code service.instance.id} — the name this component assigned, which is unique per
 *       deployment and stable for the process' lifetime.
 * </ul>
 *
 * <p>Nothing else. In particular no {@code qits.workspace.id} or {@code qits.repository.id}: a
 * platform service has neither, and stamping a fake one to fit an old query model is what the
 * log-streaming plan forbids.
 *
 * <p><b>{@code service.name} is named by the deployer, not by the image, and it is carried
 * separately from the three above.</b> It used to be left alone — each image set it from its own
 * {@code quarkus.application.name}, baked in at build time — which is exactly right for a repository
 * deployed once under its own name and exactly wrong for a <b>variant</b> (qits-1976): a deployment
 * that runs its MAIN application's image under a second, distinct application name (the private
 * edge, {@code qits-edge-private}, running qits-edge's own image) exported telemetry under
 * {@code qits-edge}'s name, because nothing told the container otherwise. The deployer already
 * knows the application it is deploying, so it is the one place that can set the name correctly with
 * no per-repository setting (qits-2054): {@link #serviceName} turns that application name into the
 * value, and {@link #OTEL_SERVICE_NAME_VARIABLE}/{@link #QUARKUS_OTEL_SERVICE_NAME_VARIABLE} carry
 * it to the container. A variant then exports under its own name, {@code _service/qits-edge-private},
 * never the main application's.
 *
 * <p>It rides its own pair of variables rather than a fourth {@code service.name=} entry in the
 * resource-attribute list, because of the same precedence order the next paragraph measures:
 * Quarkus' build-time {@code service.name} is merged OVER whatever {@code OTEL_RESOURCE_ATTRIBUTES}
 * states, so a {@code service.name} written only there would be silently overridden by the image's
 * own stamp — exactly the failure this class exists to prevent for the three attributes above. The
 * dedicated variables are a config source of their own and outrank the stamp the same way the
 * {@code QUARKUS_OTEL_RESOURCE_ATTRIBUTES} twin does, below.
 *
 * <p><b>Why two variables carry one value, for both the resource-attribute list and the service
 * name.</b> {@code OTEL_RESOURCE_ATTRIBUTES} is the vendor-neutral spelling every OpenTelemetry SDK
 * reads, and it is the contract; it is written first and alone would be the whole of this class. But
 * it does not win everywhere, and the one place it loses is the one attribute that matters most
 * here. Measured against the platform's Quarkus 3.34.6, the SDK resource is assembled in this order
 * (lowest precedence first):
 *
 * <ol>
 *   <li>the SDK's autoconfigured environment resource — where {@code OTEL_RESOURCE_ATTRIBUTES}
 *       lands;
 *   <li>Quarkus' own build-time attributes ({@code service.name}, {@code service.version} from the
 *       pom stamp, {@code webengine.*}), merged OVER the previous;
 *   <li>an env var sits at ordinal 300, above {@code application.properties}' 250 and any custom
 *       config source below 300 — which is what lets {@code QUARKUS_OTEL_SERVICE_NAME} (i.e.
 *       {@code quarkus.otel.service.name}) and {@code QUARKUS_OTEL_RESOURCE_ATTRIBUTES} outrank the
 *       build-time stamp merged in the step above.
 * </ol>
 *
 * <p>So the environment name and the instance id arrive from the neutral variable (Quarkus stamps
 * neither), while a {@code service.version} — or a {@code service.name} — written only there is
 * silently replaced by what Quarkus baked into the image at build time. The Quarkus-spelled
 * variable is the layer that outranks the stamp. Both carry the same string, built once here, so the
 * two cannot disagree; a non-Quarkus image simply ignores the second name.
 *
 * <p><b>It is a class of its own because there are two drivers now</b>, and this is a belt as much
 * as a formatter: a comma or an equals sign in any of these values would forge an extra attribute,
 * so each is re-validated at the last line before an argv. Spelled twice, one copy would eventually
 * be loosened.
 */
public final class DeployedIdentity {

  /**
   * The sentinel {@code deployment.environment.name} falls back to when a deployment reaches here
   * with no environment.
   *
   * <p><b>It used to mean something and now marks a bug.</b> A PLATFORM service belonged to every
   * environment rather than one, so it genuinely had no tier and {@code platform} was "the one true
   * thing there is to say". That plane is deleted: every application is an ordinary service in one
   * environment, so a null here is not a kind of service, it is a deployment that lost its tier
   * somewhere upstream.
   *
   * <p>The guard stays, because stamping nothing would be worse than stamping a word. But the word
   * is now a signal: {@code deployment.environment.name=platform} appearing in telemetry is
   * something to chase, not a service class to look up.
   */
  public static final String NO_ENVIRONMENT = "platform";

  /** The vendor-neutral variable every OpenTelemetry SDK reads. */
  public static final String OTEL_VARIABLE = "OTEL_RESOURCE_ATTRIBUTES";

  /** The Quarkus-spelled twin, which is the layer that outranks the image's own pom stamp. */
  public static final String QUARKUS_OTEL_VARIABLE = "QUARKUS_OTEL_RESOURCE_ATTRIBUTES";

  /** The vendor-neutral {@code service.name} variable every OpenTelemetry SDK reads. */
  public static final String OTEL_SERVICE_NAME_VARIABLE = "OTEL_SERVICE_NAME";

  /**
   * The Quarkus-spelled twin ({@code quarkus.otel.service.name}). An env var's ordinal is 300,
   * above {@code application.properties}' 250 and any custom config source below 300, so it is the
   * layer that outranks the build-time stamp — exactly {@link #QUARKUS_OTEL_VARIABLE}'s argument,
   * for the one attribute Quarkus itself sets at build time.
   */
  public static final String QUARKUS_OTEL_SERVICE_NAME_VARIABLE = "QUARKUS_OTEL_SERVICE_NAME";

  private DeployedIdentity() {}

  /**
   * The {@code k=v,k=v} list both variables carry.
   *
   * @param environmentName the environment this deployment belongs to; null is not expected any
   *     more and falls back to {@value #NO_ENVIRONMENT} — see that constant for why that is a bug
   *     marker rather than a service class
   * @param instanceName the container or service name this deployment assigned
   */
  public static String resourceAttributes(
      String commitSha, String environmentName, String instanceName) {
    // Belt at the argv, the health-path stance: each value is already validated at the boundary as
    // a sha or a dns label, and this is what makes loosening one of those checks a failed
    // deployment instead of a container stamped with attributes nobody wrote.
    String version = DeploymentIdentifiers.requireAttributeValue(commitSha, "commit sha");
    String environment =
        DeploymentIdentifiers.requireAttributeValue(
            environmentName == null ? NO_ENVIRONMENT : environmentName, "environment name");
    String instance = DeploymentIdentifiers.requireAttributeValue(instanceName, "container name");
    return "service.version="
        + version
        + ",deployment.environment.name="
        + environment
        + ",service.instance.id="
        + instance;
  }

  /**
   * The {@code service.name} both variables carry — the deployed APPLICATION's name, never the
   * image's. For a main deployment that is the repository's own application name; for a variant
   * (qits-1976) it is the variant's distinct application name (for example
   * {@code qits-edge-private}), so a variant exports under its own name even though it runs its
   * main application's image.
   *
   * @param applicationName the application this deployment is for, exactly as the rest of the argv
   *     names it
   */
  public static String serviceName(String applicationName) {
    return DeploymentIdentifiers.requireAttributeValue(applicationName, "application name");
  }
}
