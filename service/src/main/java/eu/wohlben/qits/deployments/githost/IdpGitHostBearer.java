package eu.wohlben.qits.deployments.githost;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@code qits} named oidc client, spent on the one call that needs it: the spec read against
 * qits-githost (epic qits-543, qits-1331).
 *
 * <p>The owner's ruling of 2026-10-18: a service calls another platform service on its own bearer,
 * from {@code quarkus.oidc-client.qits} ({@code qits:system}), and on nothing else — no forwarded
 * {@code X-Qits-User}/{@code X-Qits-Roles} pair beside it and none instead of it. The pair this read
 * sent until then was the edge's assertion for a person, spoken by a machine.
 *
 * <p><b>The switch is the extension's own</b>, {@code quarkus.oidc-client.qits.client-enabled}: true
 * wherever the deployer runs, false under {@code %dev} and {@code %test}. Off, this answers empty
 * and logs which client is missing; {@link GitHostSpecSource} then refuses the read without sending
 * it. A mint that fails is empty too, logged with its cause — the refusal belongs to the read, which
 * records the deployment {@code SPEC_UNREADABLE} and tries again.
 */
@ApplicationScoped
public class IdpGitHostBearer implements GitHostBearer {

  private static final Logger LOG = Logger.getLogger(IdpGitHostBearer.class);

  /** The mint is not the read: this bounds the hop to idp, not the hop to qits-githost. */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @Inject
  @NamedOidcClient("qits")
  OidcClient oidcClient;

  /** Caches and refreshes the token, so one read per deployment is not one token request. */
  private final TokensHelper tokens = new TokensHelper();

  @Override
  public Optional<String> token() {
    if (!enabled) {
      LOG.warn(
          "the `qits` oidc client (quarkus.oidc-client.qits) is not enabled: refusing to read from"
              + " qits-githost without a machine bearer");
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf(
          "the `qits` oidc client (quarkus.oidc-client.qits) could not mint a machine token for"
              + " qits-githost: %s",
          e.toString());
      return Optional.empty();
    }
  }
}
