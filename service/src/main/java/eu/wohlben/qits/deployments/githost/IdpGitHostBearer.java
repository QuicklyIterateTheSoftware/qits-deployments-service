package eu.wohlben.qits.deployments.githost;

import eu.wohlben.qits.deployments.edge.EdgeBreakGlass;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.OidcClientException;
import io.quarkus.oidc.client.OidcClients;
import io.quarkus.oidc.client.runtime.OidcClientConfig;
import io.quarkus.oidc.client.runtime.OidcClientsConfig;
import io.quarkus.oidc.client.runtime.TokensHelper;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
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
 *
 * <p><b>The mint has the edge's break-glass too</b> (epic qits-428, E3), for a TRANSPORT failure.
 * The client's address is the idp's edge host ({@code https://idp.qits.<domain>/idp}), and the spec
 * read it authorises is the first call a redeploy of the edge makes — so a mint that never reached
 * anything is asked ONCE more of a second client, built at the first need from the {@code qits}
 * client's own configuration with only the address changed to the idp's wire alias ({@link
 * EdgeBreakGlass#internalBase}{@code ("qits-idp") + "/idp"}), through Quarkus' own {@link
 * OidcClients#newClient}. Same id, secret, grant and audience.
 *
 * <p><b>How "never reached" is recognised is the one place this leans on Quarkus' wording</b>:
 * quarkus-oidc-client logs the transport exception and throws {@code new
 * OidcClientException("OIDC Server is not available")} with no cause, so that exact literal ({@link
 * #TRANSPORT_FAILURE}) is the only signal there is. {@code IdpTokenBreakGlassTest} drives the real
 * client against a refused port, so an upgrade that rewords it fails a test.
 *
 * <p><b>A 502/503/504 from the edge does NOT take the break-glass here, and that is a known gap
 * rather than a choice.</b> A non-200 token answer is an {@code OidcClientException} whose message is
 * the response BODY and nothing else — no status (also pinned by that test) — so the edge's 503
 * cannot be told from the idp's own 401 without guessing at prose. Telling them apart cleanly needs a
 * token-endpoint {@code OidcResponseFilter} that records the status, or an edge that marks its own
 * unavailability; see the epic's report.
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

  /** The client name the break-glass copies, and the id its copy is registered under. */
  static final String CLIENT_NAME = "qits";

  static final String BREAK_GLASS_CLIENT_ID = "qits-break-glass";

  static final String IDP_APPLICATION = "qits-idp";

  /**
   * quarkus-oidc-client's whole report of a token request that could not be SENT — refused, reset,
   * unresolvable, timed out on connect. It is a fixed literal, thrown as {@code new
   * OidcClientException(TRANSPORT_FAILURE)} with the transport exception logged and dropped, so the
   * exact string is the only thing that says "the idp was never reached" (see the class javadoc).
   */
  static final String TRANSPORT_FAILURE = "OIDC Server is not available";

  @Inject OidcClients oidcClients;

  @Inject EdgeBreakGlass breakGlass;

  /** The internal-address copy of the {@code qits} client, built at the first mint that needs it. */
  private volatile OidcClient internalClient;

  private final TokensHelper internalTokens = new TokensHelper();

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
      if (edgeUnavailable(e)) {
        return internally(e);
      }
      LOG.warnf(
          "the `qits` oidc client (quarkus.oidc-client.qits) could not mint a machine token for"
              + " qits-githost: %s",
          e.toString());
      return Optional.empty();
    }
  }

  /** The one more try, on the idp's internal address. */
  private Optional<String> internally(RuntimeException edgeFailure) {
    try {
      OidcClient client = internalClient();
      LOG.warnf(
          "edge unavailable; using internal address for %s (token mint: %s; trying %s)",
          IDP_APPLICATION, edgeFailure.toString(), internalAuthServerUrl());
      return Optional.ofNullable(
              internalTokens.getTokens(client).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf(
          "the `qits` oidc client (quarkus.oidc-client.qits) could not mint a machine token for"
              + " qits-githost through the edge (%s) nor on the internal address: %s",
          edgeFailure.toString(), e.toString());
      return Optional.empty();
    }
  }

  private String internalAuthServerUrl() {
    return breakGlass.internalBase(IDP_APPLICATION) + "/idp";
  }

  private OidcClient internalClient() {
    OidcClient existing = internalClient;
    if (existing == null) {
      synchronized (this) {
        existing = internalClient;
        if (existing == null) {
          OidcClientConfig configured =
              ConfigProvider.getConfig()
                  .unwrap(SmallRyeConfig.class)
                  .getConfigMapping(OidcClientsConfig.class)
                  .namedClients()
                  .get(CLIENT_NAME);
          if (configured == null) {
            throw new IllegalStateException("no `qits` oidc client is configured");
          }
          OidcClientConfig copy =
              OidcClientConfig.builder(configured)
                  .id(BREAK_GLASS_CLIENT_ID)
                  .authServerUrl(internalAuthServerUrl())
                  .build();
          existing = oidcClients.newClient(copy).await().atMost(TOKEN_TIMEOUT);
          internalClient = existing;
        }
      }
    }
    return existing;
  }

  /**
   * A mint the edge could not serve: the idp never reached, as the cause chain says. See the class
   * javadoc for why an edge's 5xx is not recognised here.
   */
  static boolean edgeUnavailable(Throwable failure) {
    if (EdgeBreakGlass.connectionClass(failure)) {
      return true;
    }
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof OidcClientException && TRANSPORT_FAILURE.equals(t.getMessage())) {
        return true;
      }
    }
    return false;
  }
}
