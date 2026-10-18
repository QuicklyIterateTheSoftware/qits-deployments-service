package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.deployments.confighost.IdpExtrasBearer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * <b>The client-credentials token mint, as quarkus-oidc-client makes it</b> (qits-1149): the {@code
 * qits} named client, switched on and pointed at a pact mock server, asked for a token through the
 * one bean that spends it ({@link IdpExtrasBearer}). The row is {@link Contracts#TOKEN_MINT}; it runs
 * here because the oidc client only exists inside the application, on a port this profile picks
 * before the application starts, because the client reads its idp from config.
 *
 * <p>The client authenticates with Basic as the state's client and sends {@code
 * grant_type=client_credentials&audience=qits-platform}, which is what qits-idp's {@code a service
 * client calling its peers} records. Skipped, naming that state, while the pinned golden masters do
 * not hold it.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
class IdpTokenMintPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /**
   * The port the pact mock server listens on, chosen free once per JVM and kept in a system
   * property: a {@code QuarkusTestProfile} is instantiated in two classloaders, and a static field
   * is not shared between them (the {@code PdPackagedSurfaceIT} note). Not a {@code qits.*} name:
   * the application's {@code qits} config mapping would refuse an unknown key (SRCFG00050).
   */
  static final int PORT = port();

  private static int port() {
    synchronized (System.class) {
      String chosen = System.getProperty(PORT_PROPERTY);
      if (chosen == null) {
        chosen = Integer.toString(freePort());
        System.setProperty(PORT_PROPERTY, chosen);
      }
      return Integer.parseInt(chosen);
    }
  }

  private static final String PORT_PROPERTY = "pact.idp-token-mint-port";

  /** The {@code qits} client on, as the state's caller, against the mock server. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      String[] caller = basicPair();
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + PORT + "/idp",
          "quarkus.oidc-client.qits.client-id", caller[0],
          "quarkus.oidc-client.qits.credentials.secret", caller[1]);
    }
  }

  @Inject IdpExtrasBearer bearer;

  @Test
  void theBearerIsMintedFromTheIdp() {
    var pact = Contracts.IDP.pact();
    Assumptions.assumeTrue(
        pact.recorded().contains(Contracts.TOKEN_MINT), pact.needs(Contracts.TOKEN_MINT));
    String expected =
        Contracts.IDP_MASTERS.json(Contracts.A_PEER_CALLER, "issueToken").path("access_token").asText();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact.pact(List.of(Contracts.TOKEN_MINT)),
            MockProviderConfig.httpConfig("127.0.0.1", PORT, PactSpecVersion.V4),
            (mockServer, context) -> {
              assertEquals(Optional.of(expected), bearer.token(), "the idp's access_token is the bearer");
              return null;
            });
    if (result instanceof PactVerificationResult.Error error
        && error.getError() instanceof AssertionError assertion) {
      throw assertion;
    }
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(Contracts.TOKEN_MINT.description() + ": " + result);
    }
  }

  /**
   * The client id and secret the state's {@code authorization} param is the Basic header of, or a
   * placeholder pair while the pinned golden masters do not hold the state (the test then skips).
   */
  private static String[] basicPair() {
    if (!Contracts.IDP_MASTERS.recorded(Contracts.A_PEER_CALLER, "issueToken")) {
      return new String[] {"unrecorded", "unrecorded"};
    }
    String basic =
        Contracts.IDP_MASTERS.params(Contracts.A_PEER_CALLER).get("authorization").substring("Basic ".length());
    return new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":", 2);
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
