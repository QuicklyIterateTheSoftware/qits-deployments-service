package eu.wohlben.qits.deployments.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.deployments.deployments.control.IdpClientProvisioner;
import eu.wohlben.qits.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.deployments.deployments.control.SpecException;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.deployments.githost.GitHostSources;
import eu.wohlben.qits.deployments.githost.GitHostSpecSource;
import eu.wohlben.qits.deployments.idphost.IdpStub;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.http.RetryPolicy;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The edge break-glass (epic qits-428, E3) end to end over real sockets: the deployer's git host and
 * idp clients, sent through a stand-in "edge" that answers 503 with a {@code Retry-After} or refuses
 * the connection, reach the peer's stand-in internal address on ONE more try — after
 * qits-http-core's retry window, which is cut to a few hundred milliseconds here and allowed onto
 * the loopback so it actually runs — and log {@code edge unavailable; using internal address for
 * <app>}.
 *
 * <p>Plain JUnit, the {@code GitHostSpecSourceTest} shape: the clients are built and pointed at the
 * stubs directly, and the internal address is pointed at a stub through {@link
 * EdgeBreakGlass#redirect}, the suite's seam.
 */
class EdgeBreakGlassTest {

  private static final String VERSION = "2026.1103.120000";
  private static final String SHA = "b".repeat(40);
  private static final RepositoryRef REPOSITORY = new RepositoryRef("r-id", "proj", "repo");

  /** A short window, and on the loopback, so the policy really retries before the break-glass. */
  private static final QitsHttpClients CLIENTS =
      QitsHttpClients.of(
          OpenTelemetry.noop(),
          "qits-deployments",
          RetryPolicy.standard().withBudget(Duration.ofMillis(600)).withLoopbackRetries());

  private Stub edge;
  private Stub internal;
  private EdgeBreakGlass breakGlass;

  @BeforeEach
  void start() throws IOException {
    edge = new Stub();
    internal = new Stub();
    breakGlass = new EdgeBreakGlass();
    breakGlass.redirect("qits-githost", internal.url());
    breakGlass.redirect("qits-idp", internal.url());
  }

  @AfterEach
  void stop() {
    edge.close();
    internal.close();
  }

  @Test
  void aSpecReadTheEdgeAnswers503ForIsReadOnTheInternalAddress() {
    edge.answer(() -> new Answer(503, Map.of("Retry-After", "0"), "edge is switching over"));
    internal.answer(
        () -> new Answer(200, Map.of("Git-Commit-Sha", SHA), "update_order: stop-first\n"));
    GitHostSpecSource source = GitHostSources.at(edge.url(), CLIENTS, breakGlass);

    List<String> warnings = new ArrayList<>();
    SpecSource.SpecRead read =
        logsOf(warnings, () -> source.read(REPOSITORY, SpecSource.tagRev(VERSION)));

    assertTrue(read.declared(), "the internal answer is the spec");
    assertEquals(SHA, read.commitSha());
    assertTrue(
        edge.requests().size() >= 2,
        "qits-http-core's retry window ran before the break-glass: " + edge.requests());
    assertEquals(1, internal.requests().size(), "exactly ONE more try: " + internal.requests());
    Request second = internal.requests().get(0);
    assertEquals(edge.requests().get(0).path(), second.path(), "the same path, untouched");
    assertTrue(second.path().contains("refs%2Ftags%2F" + VERSION), second.path());
    assertEquals(
        "Bearer " + GitHostSources.BEARER, second.authorization(), "the same credential");
    assertTrue(
        warnings.stream()
            .anyMatch(
                line ->
                    line.contains("edge unavailable; using internal address for")
                        && line.contains("qits-githost")),
        warnings.toString());
  }

  @Test
  void aSpecReadTheEdgeRefusesIsReadOnTheInternalAddress() throws IOException {
    internal.answer(() -> new Answer(200, Map.of(), "update_order: stop-first\n"));
    GitHostSpecSource source = GitHostSources.at(refusedUrl(), CLIENTS, breakGlass);

    List<String> warnings = new ArrayList<>();
    SpecSource.SpecRead read =
        logsOf(warnings, () -> source.read(REPOSITORY, SpecSource.tagRev(VERSION)));

    assertTrue(read.declared());
    assertEquals(1, internal.requests().size());
    assertTrue(
        warnings.stream().anyMatch(line -> line.contains("qits-githost")), warnings.toString());
  }

  @Test
  void anAnswerFromThePeerIsBelievedAndNoSecondDoorIsTried() {
    edge.answer(() -> new Answer(404, Map.of(), "no such blob"));
    GitHostSpecSource source = GitHostSources.at(edge.url(), CLIENTS, breakGlass);

    List<String> warnings = new ArrayList<>();
    SpecSource.SpecRead read =
        logsOf(warnings, () -> source.read(REPOSITORY, SpecSource.tagRev(VERSION)));

    assertFalse(read.declared(), "a 404 is the git host's answer, not the edge failing");
    assertEquals(List.of(), internal.requests());
    assertTrue(
        warnings.stream().noneMatch(line -> line.contains("edge unavailable")),
        warnings.toString());
  }

  @Test
  void whenTheInternalAddressFailsTooTheReadStaysRetryable() throws IOException {
    edge.answer(() -> new Answer(503, Map.of("Retry-After", "0"), "down"));
    breakGlass.redirect("qits-githost", refusedUrl());
    GitHostSpecSource source = GitHostSources.at(edge.url(), CLIENTS, breakGlass);

    SpecException refused =
        assertThrows(
            SpecException.class, () -> source.read(REPOSITORY, SpecSource.tagRev(VERSION)));
    assertTrue(refused.retryable(), refused.getMessage());
  }

  @Test
  void aCallAlreadyOnTheInternalAddressIsNotTriedTwice() {
    edge.answer(() -> new Answer(503, Map.of(), "down"));
    breakGlass.redirect("qits-githost", edge.url());
    GitHostSpecSource source = GitHostSources.at(edge.url(), CLIENTS, breakGlass);

    List<String> warnings = new ArrayList<>();
    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> logsOf(warnings, () -> source.read(REPOSITORY, SpecSource.tagRev(VERSION))));
    assertTrue(refused.getMessage().contains("503"), refused.getMessage());
    assertEquals(1, edge.requests().size(), edge.requests().toString());
    assertTrue(
        warnings.stream().noneMatch(line -> line.contains("edge unavailable")),
        warnings.toString());
  }

  @Test
  void anIdpProvisioningTheEdgeAnswers503ForReachesTheInternalAddress() {
    edge.answer(() -> new Answer(503, Map.of("Retry-After", "1"), "edge is switching over"));
    internal.answer(
        () -> new Answer(201, Map.of(), "{\"clientId\":\"dev-qits-ci\",\"secret\":\"s3cret\"}"));
    var provisioner =
        IdpStub.adapterAt(
            edge.url() + "/idp/api/service-clients", "self", "pw", CLIENTS, breakGlass);

    List<String> warnings = new ArrayList<>();
    IdpClientProvisioner.Result result = logsOf(warnings, () -> provisioner.create("dev-qits-ci"));

    assertTrue(result.ok(), result.toString());
    assertEquals("s3cret", result.secret());
    assertEquals(1, internal.requests().size(), internal.requests().toString());
    Request second = internal.requests().get(0);
    assertEquals("POST", second.method());
    assertEquals("/idp/api/service-clients", second.path());
    assertEquals("{\"clientId\":\"dev-qits-ci\"}", second.body(), "the same body");
    assertTrue(second.authorization().startsWith("Basic "), second.authorization());
    assertTrue(
        warnings.stream().anyMatch(line -> line.contains("qits-idp")), warnings.toString());
  }

  @Test
  void theInternalAddressIsTheWireAliasOfThisTier() {
    EdgeBreakGlass derived = new EdgeBreakGlass();
    derived.environment = "prod";
    assertEquals("http://prod-qits-githost:8080", derived.internalBase("qits-githost"));
    assertEquals(
        URI.create("http://prod-qits-idp:8080/idp/api/service-clients/x?y=1"),
        derived.internalUri(
            "qits-idp", URI.create("https://idp.qits.wohlben.eu/idp/api/service-clients/x?y=1")));
  }

  // --- stubs -----------------------------------------------------------------------------------

  private static String refusedUrl() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }
  }

  record Answer(int status, Map<String, String> headers, String body) {}

  record Request(String method, String path, String authorization, String body) {}

  /** One JDK server that records every request and answers with whatever it was told. */
  static final class Stub implements AutoCloseable {

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private volatile Supplier<Answer> answer = () -> new Answer(500, Map.of(), "unscripted");

    Stub() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void answer(Supplier<Answer> answer) {
      this.answer = answer;
    }

    synchronized List<Request> requests() {
      return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      synchronized (this) {
        requests.add(
            new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
      }
      Answer reply = answer.get();
      reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
      byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** {@code body}'s result, with every record the break-glass logged appended to {@code lines}. */
  private static <T> T logsOf(List<String> lines, Supplier<T> body) {
    Logger logger = Logger.getLogger(EdgeBreakGlass.class.getName());
    Level level = logger.getLevel();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            lines.add(
                record.getLevel()
                    + " "
                    + record.getMessage()
                    + " "
                    + Arrays.toString(record.getParameters()));
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
}
