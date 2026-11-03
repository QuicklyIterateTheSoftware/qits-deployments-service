package eu.wohlben.qits.deployments.edge;

import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.http.CallerHeader;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.http.RetryPolicy;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * THE BREAK-GLASS (epic qits-428, exception E3): the one place a call of this component may leave
 * the public edge for a peer's internal address on qits-net.
 *
 * <p><b>Why only here.</b> Every service-to-service call goes through the edge — {@code
 * https://<label>.qits.<domain>} — and qits-http-core's {@link RetryPolicy} rides an edge
 * switchover out for its thirty seconds. That is enough for every service but this one: the deployer
 * is what redeploys the edge, the idp and qits-events, so an edge that is down for longer than a
 * switchover (a failed rollout, an image that is gone) would otherwise leave the one component that
 * can repair it unable to read the spec it needs to do so. So a peer call that STILL fails after the
 * retry window gets exactly one more try, on {@code http://<env>-<application>:8080} plus the same
 * path and query, and a WARN {@code edge unavailable; using internal address for <application>}.
 *
 * <p><b>What "still fails" means, and nothing else does.</b> A connection-class failure (refused, a
 * connect timeout, a TLS handshake failure, a reset or EOF before an answer, a name that does not
 * resolve) or a {@code 502}/{@code 503}/{@code 504} — an edge that answered for a peer it could not
 * reach. Any other answer, a 404 or a 403 included, came from the peer and is returned as it is; a
 * request that timed out after it was sent is not a connection failure and is not repeated.
 *
 * <p><b>The second try is not retried again.</b> It goes out on a client of this class's own,
 * wrapped with {@link RetryPolicy#none()}: the break-glass is ONE try, and a second thirty-second
 * window on the internal address would make a down platform twice as slow to report itself. It
 * keeps the request's method, headers (its bearer or Basic pair included — the peer validates the
 * same credential on either door) and timeout.
 *
 * <p><b>Which calls carry it</b>: exactly those the deployer needs to redeploy the edge, the idp or
 * qits-events with no edge — {@code GitHostSpecSource}'s spec read, {@code
 * HttpIdpClientProvisioner}'s four service-client calls, and {@code IdpGitHostBearer}'s token mint
 * (through {@link #internalBase} for the oidc client's address). The configuration reads and the
 * projects lookups the epic lists do not exist in this component (qits-1393 removed the first;
 * nothing here calls qits-projects).
 *
 * <p><b>A call already on its internal address is not tried twice</b>, so an operator who points a
 * peer at qits-net by hand gets no break-glass and no WARN.
 */
@ApplicationScoped
public class EdgeBreakGlass {

  private static final Logger LOG = Logger.getLogger(EdgeBreakGlass.class);

  /** The port every application listens on inside qits-net. */
  static final int INTERNAL_PORT = 8080;

  /** The answers an edge gives for a peer it could not reach. */
  private static final Set<Integer> EDGE_UNAVAILABLE = Set.of(502, 503, 504);

  private static final int MAX_CAUSE_DEPTH = 16;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** The tier this component runs in, which is the tier of every peer it calls. */
  @ConfigProperty(name = "QITS_ENVIRONMENT", defaultValue = "dev")
  String environment = "dev";

  @Inject Instance<OpenTelemetry> openTelemetry;

  /**
   * The suite's seam, and nothing else: an internal base by application, so a test can point the
   * break-glass at a stub on a real socket. No config key exists for this on purpose — the internal
   * address is derived like every other wire alias, and a configured one would be a second spelling
   * free to drift.
   */
  private final Map<String, String> redirects = new ConcurrentHashMap<>();

  private volatile HttpClient client;

  /** {@code http://<env>-<application>:8080} — the peer's wire alias on qits-net. */
  public String internalBase(String application) {
    String redirected = redirects.get(application);
    if (redirected != null) {
      return redirected;
    }
    return "http://" + PdNetworks.alias(environment, application) + ":" + INTERNAL_PORT;
  }

  /** The suite's seam (see {@link #redirects}): the internal base for one application. */
  public void redirect(String application, String internalBase) {
    redirects.put(application, internalBase);
  }

  /** The suite's seam: every redirect gone, so derived addresses apply again. */
  public void clearRedirects() {
    redirects.clear();
  }

  /**
   * {@code request} through the edge on {@code edgeClient}, then — if that still failed (see the
   * class javadoc) — once on {@code application}'s internal address.
   *
   * <p>If the internal try throws too, that failure is what is thrown, carrying the edge's as a
   * suppressed exception, so the message the caller records names the last address tried.
   */
  public <T> HttpResponse<T> send(
      String application,
      HttpClient edgeClient,
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    boolean breakGlass = leavesTheEdge(application, request.uri());
    HttpResponse<T> answer;
    try {
      answer = edgeClient.send(request, handler);
    } catch (IOException failure) {
      if (!breakGlass || !connectionClass(failure)) {
        throw failure;
      }
      return internally(application, request, handler, failure.toString(), failure);
    }
    if (breakGlass && EDGE_UNAVAILABLE.contains(answer.statusCode())) {
      return internally(
          application, request, handler, "it answered " + answer.statusCode(), null);
    }
    return answer;
  }

  /** Whether {@code request} names a different authority than {@code application}'s internal one. */
  boolean leavesTheEdge(String application, URI uri) {
    URI internal = URI.create(internalBase(application));
    return !(sameText(internal.getScheme(), uri.getScheme())
        && sameText(internal.getHost(), uri.getHost())
        && port(internal) == port(uri));
  }

  private <T> HttpResponse<T> internally(
      String application,
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      String edgeOutcome,
      IOException edgeFailure)
      throws IOException, InterruptedException {
    URI internal = internalUri(application, request.uri());
    LOG.warnf(
        "edge unavailable; using internal address for %s (%s %s %s; trying %s)",
        application, request.method(), request.uri(), edgeOutcome, internal);
    HttpRequest copy = HttpRequest.newBuilder(request, (name, value) -> true).uri(internal).build();
    try {
      return client().send(copy, handler);
    } catch (IOException failure) {
      if (edgeFailure != null) {
        failure.addSuppressed(edgeFailure);
      }
      throw failure;
    }
  }

  /** The internal base, plus the original path and query untouched. */
  URI internalUri(String application, URI edge) {
    String base = internalBase(application);
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    String path = edge.getRawPath() == null ? "" : edge.getRawPath();
    String query = edge.getRawQuery() == null ? "" : "?" + edge.getRawQuery();
    return URI.create(base + path + query);
  }

  /**
   * A failure that says the peer was never reached, or the connection died before it answered —
   * the same families qits-http-core retries, read off the cause chain and each link's suppressed
   * exceptions (a failure the break-glass's own second try adds the edge's to, for one). A request
   * timeout after the request was sent is not one: the peer may be working on it.
   */
  public static boolean connectionClass(Throwable thrown) {
    return connectionClass(thrown, 0);
  }

  private static boolean connectionClass(Throwable thrown, int depth) {
    Throwable t = thrown;
    for (; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (t instanceof ConnectException
          || t instanceof HttpConnectTimeoutException
          || t instanceof SSLHandshakeException
          || t instanceof UnknownHostException
          || t instanceof UnresolvedAddressException
          || t instanceof SocketException
          || t instanceof EOFException
          || closedConnection(t)) {
        return true;
      }
      for (Throwable suppressed : t.getSuppressed()) {
        if (connectionClass(suppressed, depth + 1)) {
          return true;
        }
      }
      if (t.getCause() == t) {
        break;
      }
      t = t.getCause();
    }
    return false;
  }

  private static boolean closedConnection(Throwable t) {
    if (!(t instanceof IOException) || t.getMessage() == null) {
      return false;
    }
    String message = t.getMessage().toLowerCase(Locale.ROOT);
    return message.contains("header parser received no bytes")
        || message.contains("connection reset")
        || message.contains("connection closed")
        || message.contains("connection was closed");
  }

  /**
   * The break-glass's own client: traced and attributed like every other, and NOT retried — the
   * second try is one try.
   */
  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          OpenTelemetry otel =
              openTelemetry != null && openTelemetry.isResolvable()
                  ? openTelemetry.get()
                  : OpenTelemetry.noop();
          existing =
              QitsHttpClients.of(otel, CallerHeader.value().orElse(null), RetryPolicy.none())
                  .wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
          client = existing;
        }
      }
    }
    return existing;
  }

  private static boolean sameText(String a, String b) {
    return a == null ? b == null : a.equalsIgnoreCase(b);
  }

  private static int port(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
