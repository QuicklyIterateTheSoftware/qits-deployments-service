package eu.wohlben.qits.deployments.idphost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.IdpClientProvisioner;
import eu.wohlben.qits.deployments.deployments.control.ResourceException;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
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
 * The sole production implementation of {@link IdpClientProvisioner}: three calls against
 * qits-idp's service-client API, Basic-authenticated with this component's own client id and
 * secret — {@code ConfigHostDeclarationSeed}'s shape, over the JDK's own {@link HttpClient}, for the
 * same reason: no REST client generated against a peer's contract, no second HTTP library.
 *
 * <p><b>The address is derived, like the postgres host</b> — the application name plus the container
 * port. There is one idp per platform, so there is nothing to configure, and no caller of this class
 * supplies it.
 *
 * <p><b>The address carries the tier, and the SEAM still does not.</b> Every derived address in this
 * component carries it now that the platform plane is deleted ({@code PdNetworks.alias}), including
 * the {@code QITS_RESOURCE_IDP_URL} injected into the containers this component starts ({@code
 * ResourceProvisioning.idpUrl}). This one was the last exception: it read a bare
 * {@code qits-platform-idp} and so resolved only while that plane-era swarm service was still up,
 * which made retiring that service break every {@code idp:client} provisioning. It now derives
 * {@code <env>-qits-idp} from this component's OWN environment (see {@link
 * #resolveBaseUrl}). The debt that remains is narrower and unchanged in kind: {@link
 * IdpClientProvisioner} is a courier of three requests carrying no tier, so this uses the
 * environment this component runs in rather than the one the application being provisioned for runs
 * in. Identical on a single-environment estate; the day a second tier exists, the three verbs have
 * to take it.
 *
 * <p><b>The credential is this component's own, never the target application's.</b> qits-idp's
 * service-client API takes Basic auth from a caller that must itself be a service client holding
 * {@code qits:system} — so this reads {@code QITS_RESOURCE_IDP_CLIENT_ID}/{@code
 * QITS_RESOURCE_IDP_CLIENT_SECRET}, falling back to {@code
 * QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID}/{@code
 * QUARKUS_OIDC_CLIENT_CONFIGURATION_CREDENTIALS_SECRET} — today's extras pair, since this component
 * does not declare {@code idp:client} for itself yet (D10). Neither is ever logged.
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
   * The environment this component is deployed into, which is the tier whose idp it provisions
   * against. Injected into every container by this very component
   * ({@code BootResourceRegistration.ENVIRONMENT_VARIABLE}), so it is always present on the estate
   * and falls back to {@code dev} for a clone-alone run.
   */
  @ConfigProperty(name = "QITS_ENVIRONMENT", defaultValue = "dev")
  String environment;

  /**
   * The service-client API's base — derived by default, the postgres host's own arrangement. A
   * plain field rather than a method so {@code IdpStub} can point one instance at a real socket,
   * the {@code ExtrasStub.source(...)} shape: there is no config key for this, on purpose (see the
   * class javadoc), so a test override is the only override there is.
   *
   * <p>Null until {@link #resolveBaseUrl} runs at startup, because the tier it carries comes from
   * an injected field and a field initialiser would run before injection. A test that sets it keeps
   * what it set: the resolve only fills a null.
   */
  String baseUrl;

  /**
   * THE TIER IS DERIVED HERE, and this is what retires the last bare address in this component.
   *
   * <p>It used to read {@code http://qits-platform-idp:8080/...} — bare, because a platform-tier
   * service was one process for the whole estate. That plane is deleted, the idp is an ordinary
   * application addressed {@code <env>-<application>}, and its bare-named swarm service is retired
   * as part of the rollout — at which point a bare address here would fail every {@code idp:client}
   * provisioning with an unresolvable host.
   *
   * <p><b>The seam is still not widened, and that debt is unchanged.</b> {@link
   * IdpClientProvisioner} carries no tier, so this uses the environment THIS component runs in
   * rather than the environment of the application being provisioned for. Those are the same string
   * on a single-environment estate and would diverge the day a second tier exists — at which point
   * the three verbs have to take it, which is the fix the class javadoc describes. What has changed
   * is that the address now resolves at all.
   */
  @PostConstruct
  void resolveBaseUrl() {
    if (baseUrl == null) {
      baseUrl =
          "http://"
              + PdNetworks.alias(environment, IDP_APPLICATION)
              + ":"
              + IDP_PORT
              + "/idp/api/service-clients";
    }
  }

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

  /** One client for the life of the process, {@code GitHostSpecSource}'s arrangement and its why. */
  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
          client = existing;
        }
      }
    }
    return existing;
  }

  @Override
  public boolean databaseClientPresent(String clientId) {
    String url = baseUrl + "/" + segment(clientId);
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
    String url = baseUrl;
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
    String url = baseUrl + "/" + segment(clientId) + "/secret";
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
        HttpResponse<String> response =
            client().send(request(method, url, body), HttpResponse.BodyHandlers.ofString());
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
