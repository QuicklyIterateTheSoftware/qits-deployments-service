package eu.wohlben.qits.deployments.idphost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A qits-idp service-client API that answers whatever a test scripts, on a real socket — {@code
 * confighost/ExtrasStub}'s arrangement, for the same reason: what is under test here IS the
 * request, the url it is built at, the Basic header it carries and the patience it spends, and a
 * fake at the seam would assert this suite's own model of a client rather than the adapter's.
 *
 * <p>The JDK's own server, so nothing arrives on the classpath and no docker is involved.
 */
public final class IdpStub implements AutoCloseable {

  /** One scripted answer: what qits-idp says this time. */
  public record Answer(int status, String body) {}

  private static final Answer NOT_FOUND = new Answer(404, "");

  private final HttpServer server;

  /** One-shot answers, consumed in order; when they run out the standing answer repeats. */
  private final Deque<Answer> scripted = new ArrayDeque<>();

  private final List<String> methods = new ArrayList<>();
  private final List<String> paths = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();
  private final List<String> authorizations = new ArrayList<>();

  private volatile Answer standing = NOT_FOUND;

  public IdpStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start a stub qits-idp", e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Answer answer;
    synchronized (this) {
      methods.add(exchange.getRequestMethod());
      paths.add(exchange.getRequestURI().getRawPath());
      bodies.add(sent);
      authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      answer = scripted.poll();
    }
    if (answer == null) {
      answer = standing;
    }
    byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(answer.status(), body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /** What this stub answers once every scripted one-shot has been consumed. */
  public IdpStub answers(int status, String body) {
    standing = new Answer(status, body);
    return this;
  }

  /** One answer used before the standing one — the failure a retry gets past. */
  public synchronized IdpStub then(int status, String body) {
    scripted.add(new Answer(status, body));
    return this;
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/idp/api/service-clients";
  }

  public synchronized List<String> methods() {
    return List.copyOf(methods);
  }

  public synchronized List<String> paths() {
    return List.copyOf(paths);
  }

  public synchronized List<String> bodies() {
    return List.copyOf(bodies);
  }

  /** A null entry is a request that carried no Authorization header, which is an assertion too. */
  public synchronized List<String> authorizations() {
    return new ArrayList<>(authorizations);
  }

  public synchronized int requestCount() {
    return methods.size();
  }

  /**
   * An adapter pointed at this stub, with the retry pause zeroed so a refusal costs a test no
   * seconds. Here rather than in the adapter's own test because its fields are package-private and
   * this is the package they are visible in — {@code ExtrasStub.source(...)}'s arrangement.
   */
  public HttpIdpClientProvisioner adapter(String ownClientId, String ownClientSecret) {
    return adapterAt(url(), ownClientId, ownClientSecret);
  }

  /** The same, aimed at any base url — the pact mock server's, for one. */
  public static HttpIdpClientProvisioner adapterAt(
      String baseUrl, String ownClientId, String ownClientSecret) {
    HttpIdpClientProvisioner adapter = new HttpIdpClientProvisioner();
    adapter.baseUrl = baseUrl;
    adapter.timeoutSeconds = 2;
    adapter.attempts = 2;
    adapter.retryPauseMillis = 0;
    adapter.ownClientId = Optional.ofNullable(ownClientId);
    adapter.ownClientSecret = Optional.ofNullable(ownClientSecret);
    return adapter;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
