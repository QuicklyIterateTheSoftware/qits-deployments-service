package eu.wohlben.qits.deployments.idphost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.IdpClientProvisioner;
import eu.wohlben.qits.deployments.deployments.control.ResourceException;
import eu.wohlben.qits.deployments.edge.EdgeBreakGlass;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link IdpClientProvisioner}: four calls against
 * qits-idp's service-client API, Basic-authenticated with this component's own client id and
 * secret — {@code ConfigHostDeclarationSeed}'s shape, over the JDK's own {@link HttpClient}, for the
 * same reason: no REST client generated against a peer's contract, no second HTTP library.
 *
 * <p><b>The address is derived, never configured</b> — {@code https://idp.qits.<domain>}, the idp's
 * public edge host (epic qits-428: every service-to-service call goes through the edge). There is
 * one idp per platform, and the platform project {@code qits} carries no environment label, so the
 * domain is the whole of the address and no caller of this class supplies it.
 *
 * <p><b>The edge is not the only door, because this component redeploys the edge.</b> Every call
 * goes through {@link EdgeBreakGlass}: one that still fails after qits-http-core's retry window
 * (a connection-class failure or a 502/503/504) gets one more try on {@code
 * http://<env>-qits-idp:8080}, the idp's wire alias on qits-net, with a WARN. The two POSTs survive
 * a second delivery: a create that already landed answers 409, which {@code ResourceProvisioning}
 * answers with a rotate, and a rotate twice leaves the secret of the second, which is the one
 * returned.
 *
 * <p><b>The SEAM still carries no tier.</b> The break-glass's internal address uses the environment
 * THIS component runs in, not the one the application being provisioned for runs in. Identical on a
 * single-environment estate; the day a second tier exists, the three verbs have to take it.
 *
 * <p><b>The credential is this component's own, never the target application's.</b> qits-idp's
 * service-client API takes Basic auth from a caller that must itself be a service client holding
 * {@code qits:system} — so this reads {@code QITS_RESOURCE_IDP_CLIENT_ID}/{@code
 * QITS_RESOURCE_IDP_CLIENT_SECRET}, falling back to {@code
 * QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID}/{@code
 * QUARKUS_OIDC_CLIENT_CONFIGURATION_CREDENTIALS_SECRET}, the names the pair has reached the
 * deployment under since qits-configuration's days (a setting of this component now). This component does not declare {@code
 * idp:client} for itself: it keeps its bootstrap pair (epic qits-540 dossier, 'Plan (as of
 * 2026-09-13)', D9). Neither is ever logged.
 *
 * <p><b>A returned secret is validated before it leaves this class</b>: at most 128 characters (the
 * registry column's own width) and no control character. A secret that fails either check is
 * refused rather than escaped — there is nowhere downstream that would un-escape it, and the value
 * is about to become an environment variable and a database column, neither of which parses
 * anything.
 */
@ApplicationScoped
public class HttpIdpClientProvisioner implements IdpClientProvisioner {

  private static final Logger LOG = Logger.getLogger(HttpIdpClientProvisioner.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  static final String IDP_APPLICATION = "qits-idp";
  static final int IDP_PORT = 8080;

  static final int SECRET_MAX_CHARS = 128;

  /**
   * The platform's one domain — {@code qits.deployments.platform-domain}, the bare {@code
   * QITS_DOMAIN} this component also hands every container. The idp is dialled on its public edge
   * host, {@code https://idp.qits.<domain>} (epic qits-428): the platform project {@code qits}
   * carries no environment label, so the tier never enters the address.
   */
  @ConfigProperty(name = "qits.deployments.platform-domain")
  Optional<String> platformDomain = Optional.empty();

  /**
   * The service-client API's base — derived by default, never configured. A plain field rather than
   * a method so {@code IdpStub} can point one instance at a real socket: there is no config key for
   * this, on purpose (see the class javadoc), so a test override is the only override there is.
   *
   * <p>Null until {@link #resolveBaseUrl} runs at startup, because the domain it carries comes from
   * an injected field and a field initialiser would run before injection. A test that sets it keeps
   * what it set: the resolve only fills a null.
   */
  String baseUrl;

  /**
   * THE EDGE HOST, derived from the domain (epic qits-428). It read {@code
   * http://<env>-qits-idp:8080} — the wire alias on qits-net — until every service-to-service call
   * moved to the public edge; that alias is now only the break-glass's second door ({@link
   * EdgeBreakGlass}), tried once when the edge cannot serve the call at all.
   *
   * <p>With no domain there is no address to derive, and the base stays null: every call then fails
   * naming {@code QITS_DOMAIN} (see {@link #base()}), which is the deployment's failure rather than
   * this component's boot failure — the service that would have to redeploy the idp must still start.
   */
  @PostConstruct
  void resolveBaseUrl() {
    if (baseUrl == null) {
      baseUrl =
          platformDomain
              .map(String::strip)
              .filter(domain -> !domain.isEmpty())
              .map(domain -> "https://idp.qits." + domain + "/idp/api/service-clients")
              .orElse(null);
    }
  }

  /** The base, or a refusal naming the fact that is missing. */
  private String base() {
    if (baseUrl == null) {
      throw new ResourceException(
          "QITS_DOMAIN (qits.deployments.platform-domain) is not set, so there is no idp address"
              + " to provision an idp client at");
    }
    return baseUrl;
  }

  /**
   * The edge's break-glass (epic qits-428, E3), for the idp provisioning a redeploy of the edge or
   * the idp itself needs. Null in a hand-built instance (the unit suites).
   */
  @Inject EdgeBreakGlass breakGlass;

  @ConfigProperty(name = "qits.deployments.idp-timeout-seconds")
  long timeoutSeconds;

  @ConfigProperty(name = "qits.deployments.idp-attempts")
  int attempts;

  @ConfigProperty(name = "qits.deployments.idp.client-id")
  Optional<String> ownClientId;

  @ConfigProperty(name = "qits.deployments.idp.client-secret")
  Optional<String> ownClientSecret;

  /** How long a failed attempt waits before the next — the declaration seed's arrangement. */
  long retryPauseMillis = 1000;

  @Inject QitsHttpClients clients;

  /**
   * One client for the life of the process, {@code GitHostSpecSource}'s arrangement and its why.
   * Wrapped through {@link QitsHttpClients} so this call to qits-idp opens a CLIENT span and
   * carries {@code X-Qits-Caller}, like every other internal client in this component.
   */
  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              clients.wrap(
                  HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build());
          client = existing;
        }
      }
    }
    return existing;
  }

  @Override
  public boolean databaseClientPresent(String clientId) {
    String url = base() + "/" + segment(clientId);
    Answer answer = send("GET", url, null, clientId, "read");
    if (answer.status() == 404) {
      return false;
    }
    if (answer.status() == 200) {
      String source = text(answer.body(), "source");
      return "database".equals(source) || "both".equals(source);
    }
    throw new ResourceException(
        "could not read the idp client " + clientId + " from " + url + ": it answered "
            + answer.status() + " — " + excerpt(answer.raw()));
  }

  @Override
  public Result create(String clientId) {
    String url = base();
    Answer answer =
        send("POST", url, "{\"clientId\":" + jsonString(clientId) + "}", clientId, "create");
    if (answer.status() == 201) {
      return new Result(true, false, validated(answer.body(), clientId), null);
    }
    if (answer.status() == 409) {
      return new Result(false, true, null, "a database row already exists for " + clientId);
    }
    return new Result(
        false,
        false,
        null,
        "creating the idp client "
            + clientId
            + " at "
            + url
            + " answered "
            + answer.status()
            + " — "
            + excerpt(answer.raw()));
  }

  @Override
  public Result rotate(String clientId) {
    String url = base() + "/" + segment(clientId) + "/secret";
    Answer answer = send("POST", url, "", clientId, "rotate");
    if (answer.status() == 200) {
      return new Result(true, false, validated(answer.body(), clientId), null);
    }
    if (answer.status() == 404) {
      return new Result(false, false, null, "no database row exists for " + clientId + " to rotate");
    }
    return new Result(
        false,
        false,
        null,
        "rotating the idp client "
            + clientId
            + " at "
            + url
            + " answered "
            + answer.status()
            + " — "
            + excerpt(answer.raw()));
  }

  /**
   * {@code DELETE /idp/api/service-clients/{id}}. A 2xx is done and so is a 404 — the client is
   * absent either way, which is what was asked. A 5xx that outlasted the budget, a transport
   * failure and any other status are a failed {@link Result} carrying the operator's sentence,
   * rather than a throw: the caller decides what a refused retirement means.
   */
  @Override
  public Result delete(String clientId) {
    String url = base() + "/" + segment(clientId);
    Answer answer;
    try {
      answer = send("DELETE", url, null, clientId, "delete");
    } catch (ResourceException e) {
      return new Result(false, false, null, e.getMessage());
    }
    if (answer.status() / 100 == 2 || answer.status() == 404) {
      return new Result(true, false, null, null);
    }
    return new Result(
        false,
        false,
        null,
        "deleting the idp client "
            + clientId
            + " at "
            + url
            + " answered "
            + answer.status()
            + " — "
            + excerpt(answer.raw()));
  }

  /** One HTTP call, retried on a transport failure or a 5xx within the budget — never on 2xx/4xx. */
  private Answer send(String method, String url, String body, String clientId, String what) {
    String lastFailure = null;
    int budget = Math.max(1, attempts);
    for (int attempt = 1; attempt <= budget; attempt++) {
      if (attempt > 1 && retryPauseMillis > 0) {
        try {
          Thread.sleep(retryPauseMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ResourceException(
              "could not " + what + " the idp client " + clientId + " at " + url + ": interrupted");
        }
      }
      int status;
      String raw;
      try {
        HttpRequest request = request(method, url, body);
        HttpResponse<String> response =
            breakGlass == null
                ? client().send(request, HttpResponse.BodyHandlers.ofString())
                : breakGlass.send(
                    IDP_APPLICATION, client(), request, HttpResponse.BodyHandlers.ofString());
        status = response.statusCode();
        raw = response.body();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ResourceException(
            "could not " + what + " the idp client " + clientId + " at " + url + ": interrupted");
      } catch (Exception e) {
        lastFailure = e.toString();
        LOG.warnf(
            "Could not %s the idp client %s at %s (attempt %d of %d): %s",
            what, clientId, url, attempt, budget, lastFailure);
        continue;
      }
      if (status / 100 == 5) {
        lastFailure = "it answered " + status;
        LOG.warnf(
            "Could not %s the idp client %s at %s (attempt %d of %d): %s",
            what, clientId, url, attempt, budget, lastFailure);
        continue;
      }
      return new Answer(status, parse(raw), raw);
    }
    throw new ResourceException(
        "could not " + what + " the idp client " + clientId + " at " + url + " after " + budget
            + " attempts: " + lastFailure);
  }

  private HttpRequest request(String method, String url, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Basic " + basic())
            .header("Content-Type", "application/json");
    request =
        switch (method) {
          case "GET" -> request.GET();
          case "DELETE" -> request.DELETE();
          case "POST" ->
              request.POST(
                  HttpRequest.BodyPublishers.ofString(
                      body == null ? "" : body, StandardCharsets.UTF_8));
          default -> throw new IllegalArgumentException("unsupported method " + method);
        };
    return request.build();
  }

  /** {@code <this component's own client id>:<its secret>}, base64 — never logged. */
  private String basic() {
    String id = ownClientId.map(String::strip).filter(v -> !v.isEmpty()).orElse("");
    String secret = ownClientSecret.map(String::strip).filter(v -> !v.isEmpty()).orElse("");
    return Base64.getEncoder()
        .encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
  }

  /** The client id as a path segment, refused rather than escaped if it is not one. */
  private static String segment(String clientId) {
    // Every clientId reaching here is PdNetworks.alias(...), already held to PdIdentifiers.requireName
    // by ResourceProvisioning before this class is ever called — this is the belt, not the buckle.
    if (clientId == null || !clientId.matches("[a-z0-9][a-z0-9-]{0,127}")) {
      throw new ResourceException("'" + clientId + "' is not an idp client id this component derived");
    }
    return clientId;
  }

  /** A returned secret, checked before it leaves this class — see the class javadoc. */
  private static String validated(JsonNode body, String clientId) {
    String secret = text(body, "secret");
    if (secret == null
        || secret.isEmpty()
        || secret.length() > SECRET_MAX_CHARS
        || secret.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      // Deliberately says nothing about the value itself.
      throw new ResourceException(
          "qits-idp answered a secret for " + clientId + " this component cannot use");
    }
    return secret;
  }

  private static JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      return null;
    }
  }

  private static String text(JsonNode body, String field) {
    if (body == null) {
      return null;
    }
    JsonNode value = body.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static String jsonString(String value) {
    // clientId is already held to the dns-label-ish charset above, which contains nothing JSON
    // needs escaped — but this is a belt, not a parser: quotes and backslashes are escaped in case
    // that charset ever widens.
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** As much of an unexpected body as belongs in a failure message. */
  private static String excerpt(String body) {
    if (body == null) {
      return "(no body)";
    }
    String single = body.strip().replaceAll("\\s+", " ");
    if (single.isEmpty()) {
      return "(no body)";
    }
    return single.length() <= 300 ? single : single.substring(0, 300) + "…";
  }

  private record Answer(int status, JsonNode body, String raw) {}
}
