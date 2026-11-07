package eu.wohlben.qits.deployments.artifactshost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.deployments.deployments.control.ArtifactPurge;
import eu.wohlben.qits.deployments.deployments.control.PurgeException;
import eu.wohlben.qits.deployments.deployments.control.RegistryHost;
import eu.wohlben.qits.deployments.githost.GitHostBearer;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The sole production implementation of {@link ArtifactPurge}: one {@code POST} to qits-artifacts'
 * machine-only purge door (qits-1941).
 *
 * <pre>
 * POST &lt;artifacts-url&gt;/artifacts/api/applications/{application}/purge
 * {"repositories":["&lt;repository&gt;",…]}
 * → 200 {"application","removed":[{"type","repository","name","versions"}]}
 * </pre>
 *
 * <p><b>The address is the registry's edge host</b>, {@code https://registry.qits.<domain>} — the
 * edge application that serves qits-artifacts is {@code registry} — derived through {@link
 * RegistryHost} from the platform's one domain, so it is the same host the docker daemon pulls
 * through. {@code qits.deployments.artifacts-url} overrides it, and is otherwise empty.
 *
 * <p><b>The bearer is this component's own</b>, the {@code qits} named oidc client's ({@link
 * GitHostBearer}, implemented by {@code IdpGitHostBearer}): a qits-idp service client, so its token
 * carries {@code qits:system} — the one role the purge door admits — and {@code aud qits-platform}.
 * The name says git host because the spec read was its first caller; the token is not the git
 * host's. No token, no request: a missing one fails the purge before a socket opens, as the spec
 * read does.
 *
 * <p><b>Every failure is a {@link PurgeException}</b> — a transport failure, a non-2xx, an answer
 * that is not the documented shape — and none is retried here: the decommission door answers 502
 * and calling it again is the retry, which the purge's own idempotence makes safe.
 */
@ApplicationScoped
public class HttpArtifactPurge implements ArtifactPurge {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The most of an error body a failure message carries. */
  static final int DETAIL_MAX_CHARS = 300;

  @ConfigProperty(name = "qits.deployments.artifacts-url")
  Optional<String> artifactsUrl = Optional.empty();

  @ConfigProperty(name = "qits.deployments.artifacts-timeout-seconds")
  long timeoutSeconds;

  @Inject RegistryHost registryHost;

  @Inject QitsHttpClients clients;

  @Inject GitHostBearer bearer;

  /** One client for the life of the process; see {@code GitHostSpecSource#client} for why lazily. */
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
  public List<RemovedArtifact> purge(String applicationName, List<String> repositories) {
    String url = purgeUrl(base(), applicationName);
    String token =
        bearer
            .token()
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () ->
                    new PurgeException(
                        "could not POST "
                            + url
                            + ": no machine bearer from the `qits` oidc client"
                            + " (quarkus.oidc-client.qits)"));
    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body(repositories)))
              .build();
      response = client().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PurgeException("interrupted while POSTing " + url, e);
    } catch (Exception e) {
      throw new PurgeException("could not POST " + url + ": " + e, e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new PurgeException(
          "qits-artifacts answered "
              + response.statusCode()
              + " to POST "
              + url
              + detailOf(response.body()));
    }
    return removedIn(url, response.body());
  }

  /** The base address: the override when set, the registry's edge host otherwise. */
  private String base() {
    Optional<String> configured =
        artifactsUrl.map(String::strip).filter(value -> !value.isEmpty());
    if (configured.isPresent()) {
      return configured.get();
    }
    try {
      return "https://" + registryHost.require();
    } catch (IllegalStateException e) {
      throw new PurgeException(
          "no qits-artifacts address: qits.deployments.artifacts-url is empty and " + e.getMessage(),
          e);
    }
  }

  /**
   * Package-private so the tests can spell the expected url. The name was checked by the door's
   * own slug rule ({@code PdIdentifiers.requireName}), so it cannot leave the segment it is in.
   */
  static String purgeUrl(String base, String applicationName) {
    String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    return trimmed + "/artifacts/api/applications/" + applicationName + "/purge";
  }

  private static String body(List<String> repositories) {
    ObjectNode body = JSON.createObjectNode();
    ArrayNode names = body.putArray("repositories");
    repositories.forEach(names::add);
    return body.toString();
  }

  private static List<RemovedArtifact> removedIn(String url, String body) {
    JsonNode answer;
    try {
      answer = JSON.readTree(body);
    } catch (Exception e) {
      throw new PurgeException("qits-artifacts answered POST " + url + " with no JSON: " + e, e);
    }
    JsonNode removed = answer == null ? null : answer.get("removed");
    if (removed == null || !removed.isArray()) {
      throw new PurgeException(
          "qits-artifacts answered POST " + url + " without a `removed` list" + detailOf(body));
    }
    List<RemovedArtifact> all = new ArrayList<>();
    for (JsonNode entry : removed) {
      List<String> versions = new ArrayList<>();
      entry.path("versions").forEach(version -> versions.add(version.asText()));
      all.add(
          new RemovedArtifact(
              entry.path("type").asText(null),
              entry.path("repository").asText(null),
              entry.path("name").asText(null),
              List.copyOf(versions)));
    }
    return List.copyOf(all);
  }

  private static String detailOf(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String stripped = body.strip();
    return ": "
        + (stripped.length() > DETAIL_MAX_CHARS
            ? stripped.substring(0, DETAIL_MAX_CHARS) + "…"
            : stripped);
  }
}
