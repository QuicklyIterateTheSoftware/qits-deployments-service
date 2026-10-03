package eu.wohlben.qits.deployments.confighost;

import java.util.Optional;

/**
 * The machine credential this component presents to qits-configuration, or none.
 *
 * <p><b>None is a supported answer and not a degraded one.</b> A token that cannot be minted leaves
 * the read with no Authorization header at all, and qits-configuration answers that for itself; and
 * {@code quarkus.oidc-client.qits.client-enabled} is false under {@code %dev} and {@code %test},
 * because a clone-alone build has no idp to mint anything against and must stay green.
 *
 * <p>It is a seam of its own rather than a call inside {@link ConfigHostExtrasSource} so that source
 * stays plain-JUnit testable: the suite scripts a bearer and asserts the header on a recorded
 * request, with no OIDC extension involved.
 */
@FunctionalInterface
public interface ExtrasBearer {

  /** The access token to present, or empty when this deployment holds no credential. */
  Optional<String> token();
}
