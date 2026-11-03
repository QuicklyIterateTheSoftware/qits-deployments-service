package eu.wohlben.qits.deployments.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.deployments.edge.EdgeBreakGlass;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The token mint's break-glass (epic qits-428, E3), against the REAL quarkus-oidc-client: the
 * {@code qits} client is switched on and pointed at a stand-in edge address.
 *
 * <ul>
 *   <li>With nothing listening there — an edge that is down — {@link IdpGitHostBearer} still hands
 *       out a bearer, minted by a copy of the same client on the idp's (stand-in) internal address,
 *       with the WARN.
 *   <li>With the edge answering {@code 503} + {@code Retry-After}, there is NO break-glass, and this
 *       pins why: quarkus-oidc-client's failure carries the response body and no status, so a 503
 *       from the edge cannot be told from the idp's own refusal without guessing at prose. The day
 *       that changes this test goes red and the gap can be closed.
 * </ul>
 *
 * <p>There is no qits-http-core retry window to shorten here: the mint is quarkus-oidc-client's own
 * Vert.x client, not a {@code QitsHttpClients} one.
 */
@QuarkusTest
@TestProfile(IdpTokenBreakGlassTest.EdgeDown.class)
class IdpTokenBreakGlassTest {

  /** A port chosen once per JVM and kept in a system property — the two-classloader note. */
  static final int EDGE_PORT = port("pd.test.token-break-glass-edge-port");

  public static class EdgeDown implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + EDGE_PORT + "/idp",
          "quarkus.oidc-client.qits.client-id", "dev-qits-deployments",
          "quarkus.oidc-client.qits.credentials.secret", "not-a-real-secret");
    }
  }

  @Inject IdpGitHostBearer bearer;
  @Inject EdgeBreakGlass breakGlass;

  private HttpServer edge;
  private HttpServer internal;

  private final List<String> edgePaths = new ArrayList<>();
  private final List<String> internalRequests = new ArrayList<>();

  @BeforeEach
  void start() throws IOException {
    internal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    internal.createContext(
        "/",
        exchange -> {
          String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          synchronized (internalRequests) {
            internalRequests.add(exchange.getRequestURI().getPath() + " " + form);
          }
          reply(
              exchange,
              200,
              "application/json",
              "{\"access_token\":\"minted-on-qits-net\",\"token_type\":\"Bearer\",\"expires_in\":300}");
        });
    internal.start();
    breakGlass.redirect("qits-idp", "http://127.0.0.1:" + internal.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    breakGlass.clearRedirects();
    if (edge != null) {
      edge.stop(0);
      edge = null;
    }
    internal.stop(0);
  }

  @Test
  void aMintTheEdgeRefusesIsMintedOnTheInternalAddress() {
    List<String> warnings = new ArrayList<>();
    Optional<String> token = logsOf(warnings, () -> bearer.token());

    assertEquals(Optional.of("minted-on-qits-net"), token, warnings.toString());
    assertEquals(1, internalRequests.size(), internalRequests.toString());
    assertTrue(internalRequests.get(0).startsWith("/idp/token "), internalRequests.toString());
    assertTrue(
        internalRequests.get(0).contains("grant_type=client_credentials"),
        "the same grant: " + internalRequests);
    assertTrue(
        internalRequests.get(0).contains("audience=qits-platform"),
        "the same audience: " + internalRequests);
    assertTrue(
        warnings.stream()
            .anyMatch(
                line ->
                    line.contains("edge unavailable; using internal address for")
                        && line.contains("qits-idp")),
        warnings.toString());
  }

  @Test
  void anEdges503IsNotRecognisableOnTheTokenEndpointAndTakesNoBreakGlass() throws IOException {
    edge = HttpServer.create(new InetSocketAddress("127.0.0.1", EDGE_PORT), 0);
    edge.createContext(
        "/",
        exchange -> {
          synchronized (edgePaths) {
            edgePaths.add(exchange.getRequestURI().getPath());
          }
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Retry-After", "1");
          reply(exchange, 503, "text/plain", "edge is switching over");
        });
    edge.start();

    List<String> warnings = new ArrayList<>();
    Optional<String> token = logsOf(warnings, () -> bearer.token());

    assertEquals(Optional.empty(), token);
    assertEquals(List.of("/idp/token"), edgePaths);
    assertEquals(List.of(), internalRequests, "no second door");
    assertTrue(
        warnings.stream().anyMatch(line -> line.contains("edge is switching over")),
        "the failure is the body: " + warnings);
    assertTrue(
        warnings.stream().noneMatch(line -> line.contains("503")),
        "and carries no status: " + warnings);
  }

  private static void reply(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static <T> T logsOf(List<String> lines, java.util.function.Supplier<T> body) {
    Logger logger = Logger.getLogger(IdpGitHostBearer.class.getName());
    Level level = logger.getLevel();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            lines.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    logger.setLevel(Level.ALL);
    try {
      return body.get();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(level);
    }
  }

  private static int port(String property) {
    synchronized (System.class) {
      String chosen = System.getProperty(property);
      if (chosen == null) {
        try (ServerSocket socket = new ServerSocket(0)) {
          chosen = Integer.toString(socket.getLocalPort());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        System.setProperty(property, chosen);
      }
      return Integer.parseInt(chosen);
    }
  }
}
