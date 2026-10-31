package eu.wohlben.qits.deployments.deployments.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The OCI registry host the docker daemon pulls and pushes through, derived from the platform's
 * one domain rather than configured as a value of its own — {@code registry.qits.<domain>}, the
 * public edge vhost qits-ci publishes a release's image to. {@code qits.artifacts.registry-host}
 * used to configure this directly and is retired: a configured value is how a loopback machine
 * name ({@code registry.dev.localhost:8080}) ended up qualifying an image reference, as so many
 * times before, so there is nothing left to configure here.
 *
 * <p><b>One place, used by both callers that used to read the retired key.</b> {@link
 * DeployService} builds every image reference from {@link #require()} — a deploy must fail loudly,
 * naming {@code QITS_DOMAIN}, rather than build {@code registry.qits./...} on a blank one. {@link
 * DockerCredentialFile} is warn-only by design (see its own javadoc) and takes {@link #resolve()},
 * so a blank domain there is simply no credential written, exactly as an absent idp-client pair is
 * today.
 *
 * <p>The domain itself is {@code qits.deployments.platform-domain} (the bare {@code QITS_DOMAIN}) —
 * see that key's own comment for why it is one fact stated once and why its empty default is read
 * as absent rather than as a blank hostname.
 */
@ApplicationScoped
public class RegistryHost {

  private static final String PREFIX = "registry.qits.";

  @ConfigProperty(name = "qits.deployments.platform-domain")
  Optional<String> platformDomain;

  /** Empty when the domain is blank or absent — never a host built on nothing. */
  public Optional<String> resolve() {
    return derive(platformDomain);
  }

  /**
   * The derived host, or a refusal naming {@code QITS_DOMAIN} — for a caller that must fail a
   * deployment rather than silently build a reference nothing resolves.
   */
  public String require() {
    return require(platformDomain);
  }

  static Optional<String> derive(Optional<String> domain) {
    return domain.map(String::strip).filter(value -> !value.isEmpty()).map(value -> PREFIX + value);
  }

  static String require(Optional<String> domain) {
    return derive(domain)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "QITS_DOMAIN is not set, so this deployment cannot build a registry host to"
                        + " pull or push an image through"));
  }
}
