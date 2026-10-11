package eu.wohlben.qits.deployments.confighost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * A qits-configuration that answers whatever a test scripts, on a real socket.
 *
 * <p><b>It is an HTTP stub rather than a fake at the seam</b>, and that is deliberate for these
 * tests alone: what is under test here IS the request — the url it is built at, the headers it
 * carries and the patience it spends — and a fake {@code DeploymentExtrasSource} would assert the
 * test's own model of a client. Everything ABOVE the seam uses a scripted lambda instead, which is
 * the repo's ordinary fake doctrine.
 *
 * <p><b>It answers BOTH directions</b>, because qits-configuration is one peer: the resolved read a
 * deployment makes, and the declaration a release seeds. The answer is scripted the same way for
 * both — this stub has no idea which is which, and does not need one. What tells them apart in an
 * assertion is the method and the address, which is exactly what tells them apart on a platform.
 *
 * <p>The JDK's own server, so nothing arrives on the classpath and no docker is involved.
 */
public final class ExtrasStub implements AutoCloseable {

  /** One scripted answer: what the service says this time. */
  public record Answer(int status, String body) {}

  private static final Answer EMPTY = new Answer(200, "{\"headRevision\":1,\"properties\":{}}");

  private final HttpServer server;

  /** One-shot answers, consumed in order; when they run out the standing answer repeats. */
  private final Deque<Answer> scripted = new ArrayDeque<>();

  private final List<String> paths = new ArrayList<>();

  /**
   * One entry per request: the raw path with the query string on it, which is the whole address.
   *
   * <p>It is separate from {@link #paths()} rather than replacing it because the two answer
   * different questions. The resolved read is addressed by a path AND a version parameter, so a
   * test of that url has to see both; the seed is addressed by a path alone plus one parameter, and
   * a test that only cares which resource was asked for should not have to spell a query.
   */
  private final List<String> targets = new ArrayList<>();

  /** One entry per request, the method — the seed is a POST where every other call here is a GET. */
  private final List<String> methods = new ArrayList<>();

  /** One entry per request, the Content-Type it carried, or null. */
  private final List<String> contentTypes = new ArrayList<>();

  /** One entry per request, the body bytes as UTF-8 — empty for a GET. */
  private final List<String> bodies = new ArrayList<>();

  /** One entry per request, the Authorization header or null — the absence is an assertion too. */
  private final List<String> authorizations = new ArrayList<>();

  private volatile Answer standing = EMPTY;

  public ExtrasStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start a stub qits-configuration", e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  private void handle(HttpExchange exchange) throws IOException {
    // Read before anything is answered, and read WHOLE: a request whose body a test asserts on has
    // to be drained here, because the JDK's server discards what the handler leaves behind.
    String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Answer answer;
    synchronized (this) {
      String query = exchange.getRequestURI().getRawQuery();
      paths.add(exchange.getRequestURI().getRawPath());
      targets.add(
          exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query));
      methods.add(exchange.getRequestMethod());
      contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
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
  public ExtrasStub answers(int status, String body) {
    standing = new Answer(status, body);
    return this;
  }

  /** The resolved document for one application, in the shape the service publishes. */
  public ExtrasStub resolves(long headRevision, String... keysAndValues) {
    StringBuilder json = new StringBuilder("{\"headRevision\":").append(headRevision).append(",\"properties\":{");
    for (int i = 0; i < keysAndValues.length; i += 2) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(keysAndValues[i]).append("\":\"").append(keysAndValues[i + 1]).append('"');
    }
    return answers(200, json.append("}}").toString());
  }

  /** One answer that is used before the standing one — the failure a retry gets past. */
  public synchronized ExtrasStub then(int status, String body) {
    scripted.add(new Answer(status, body));
    return this;
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public synchronized List<String> paths() {
    return List.copyOf(paths);
  }

  /** The whole address of each request — the raw path plus its query string. */
  public synchronized List<String> targets() {
    return List.copyOf(targets);
  }

  public synchronized List<String> methods() {
    return List.copyOf(methods);
  }

  public synchronized List<String> contentTypes() {
    return new ArrayList<>(contentTypes);
  }

  /** What each request carried as a body — for the seed, the declaration bytes themselves. */
  public synchronized List<String> bodies() {
    return List.copyOf(bodies);
  }

  /** A null entry is a request that carried no Authorization header, which is an assertion here. */
  public synchronized List<String> authorizations() {
    return new ArrayList<>(authorizations);
  }

  /**
   * A source pointed at this stub, with the retry pause zeroed so a refusal costs a test no seconds.
   * Here rather than in a test of its own because the source's fields are package-private and this
   * is the package they are visible in.
   */
  public ConfigHostExtrasSource source(Config boot, String extrasFile, ExtrasBearer bearer) {
    return source(boot, extrasFile, bearer, url());
  }

  /** The same, aimed anywhere — an address nothing listens on is a test of its own. */
  public static ConfigHostExtrasSource source(
      Config boot, String extrasFile, ExtrasBearer bearer, String extrasUrl) {
    ConfigHostExtrasSource source = new ConfigHostExtrasSource();
    source.config = boot;
    source.extrasFile = extrasFile;
    source.extrasUrl = Optional.ofNullable(extrasUrl);
    source.timeoutSeconds = 2;
    source.attempts = 1;
    source.bearer = bearer;
    source.retryPauseMillis = 0;
    source.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    return source;
  }

  /**
   * A declaration seed pointed at this stub, retry pause zeroed for the same reason the source's is.
   *
   * <p><b>Here, beside the read's factory, because they are one peer</b> — the seed writes to the
   * base url the read reads from, and a stub that served one and not the other would be describing
   * a platform this component cannot be configured into.
   */
  public ConfigHostDeclarationSeed seed(ExtrasBearer bearer) {
    return seed(bearer, url());
  }

  /** The same, aimed anywhere — including at nothing, and at no url at all. */
  public static ConfigHostDeclarationSeed seed(ExtrasBearer bearer, String extrasUrl) {
    ConfigHostDeclarationSeed seed = new ConfigHostDeclarationSeed();
    seed.extrasUrl = Optional.ofNullable(extrasUrl);
    seed.timeoutSeconds = 2;
    seed.attempts = 1;
    seed.bearer = bearer;
    seed.retryPauseMillis = 0;
    seed.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    return seed;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
