package eu.wohlben.qits.deployments.edgehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.EdgeRouteClient;
import eu.wohlben.qits.deployments.deployments.control.EdgeRouteException;
import eu.wohlben.qits.deployments.deployments.control.EdgeRouteTables;
import eu.wohlben.qits.deployments.githost.GitHostBearer;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * qits-edge's route-table door (epic qits-2174), over the JDK {@link HttpClient}: {@code PUT
 * <url>/edge-route-table} with the table and {@code GET <url>/edge-route-table} for the version the
 * edge holds, both with this component's own {@code qits} client bearer ({@link GitHostBearer}, a
 * {@code qits:system} JWT whose {@code azp} is {@code <env>-qits-deployments} — the publisher the
 * edge admits). The pact rows are {@code Contracts.EDGE}.
 *
 * <p>The public edge is dialled by its public name ({@code qits.deployments.edges.public-url}), the
 * private one on qits-private ({@code qits.deployments.edges.private-url}, port 80), and the overlay
 * edge (qits-2304) on qits-private too ({@code qits.deployments.edges.overlay-url}, port 80) — sent
 * the private table, whose body names {@code "edge":"private"}, which is the table the overlay edge
 * holds.
 */
@ApplicationScoped
public class HttpEdgeRouteClient implements EdgeRouteClient {

  static final String PATH = "/edge-route-table";

  static final int DETAIL_MAX_CHARS = 300;

  private static final ObjectMapper JSON = new ObjectMapper();

  @ConfigProperty(name = "qits.deployments.edges.public-url")
  String publicUrl;

  @ConfigProperty(name = "qits.deployments.edges.private-url")
  String privateUrl;

  @ConfigProperty(name = "qits.deployments.edges.overlay-url")
  String overlayUrl;

  @ConfigProperty(name = "qits.deployments.edges.timeout-seconds")
  long timeoutSeconds;

  @Inject QitsHttpClients clients;

  @Inject GitHostBearer bearer;

  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              clients.wrap(
                  HttpClient.newBuilder()
                      .version(HttpClient.Version.HTTP_1_1)
                      .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                      .build());
          client = existing;
        }
      }
    }
    return existing;
  }

  @Override
  public Pushed push(String edge, String table) {
    String url = url(edge);
    HttpResponse<String> response =
        send(
            "PUT",
            url,
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(table)));
    int status = response.statusCode();
    if (status == 204) {
      return new Stored();
    }
    if (status == 200) {
      JsonNode held = read(url, response.body()).path("held");
      if (!held.canConvertToLong()) {
        throw new EdgeRouteException(
            "the " + edge + " edge answered PUT " + url + " 200 without `held`" + detailOf(response.body()));
      }
      return new Held(held.asLong());
    }
    if (status == 422) {
      JsonNode error = read(url, response.body()).path("error");
      return new Refused(error.isTextual() ? error.asText() : detailOf(response.body()));
    }
    throw new EdgeRouteException(
        "the " + edge + " edge answered " + status + " to PUT " + url + detailOf(response.body()));
  }

  @Override
  public OptionalLong held(String edge) {
    String url = url(edge);
    HttpResponse<String> response = send("GET", url, HttpRequest.newBuilder(URI.create(url)).GET());
    if (response.statusCode() != 200) {
      throw new EdgeRouteException(
          "the "
              + edge
              + " edge answered "
              + response.statusCode()
              + " to GET "
              + url
              + detailOf(response.body()));
    }
    JsonNode version = read(url, response.body()).path("version");
    return version.canConvertToLong() && !version.isNull()
        ? OptionalLong.of(version.asLong())
        : OptionalLong.empty();
  }

  private HttpResponse<String> send(String method, String url, HttpRequest.Builder request) {
    String token =
        bearer
            .token()
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () ->
                    new EdgeRouteException(
                        "no machine bearer from the `qits` oidc client (quarkus.oidc-client.qits)"
                            + " to " + method + " " + url + " with"));
    try {
      return client()
          .send(
              request
                  .timeout(Duration.ofSeconds(timeoutSeconds))
                  .header("Authorization", "Bearer " + token)
                  .header("Accept", "application/json")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EdgeRouteException("interrupted while calling " + method + " " + url, e);
    } catch (Exception e) {
      throw new EdgeRouteException("could not " + method + " " + url + ": " + e, e);
    }
  }

  private String url(String edge) {
    String base =
        switch (edge) {
          case EdgeRouteTables.PUBLIC -> publicUrl;
          case EdgeRouteTables.PRIVATE -> privateUrl;
          case EdgeRouteTables.OVERLAY -> overlayUrl;
          default -> throw new EdgeRouteException("there is no `" + edge + "` edge");
        };
    if (base == null || base.isBlank()) {
      throw new EdgeRouteException("no address is configured for the " + edge + " edge");
    }
    return base.strip().replaceAll("/+$", "") + PATH;
  }

  private static JsonNode read(String url, String body) {
    try {
      JsonNode node = JSON.readTree(body == null ? "" : body);
      if (node == null || !node.isObject()) {
        throw new EdgeRouteException(url + " answered with no JSON object" + detailOf(body));
      }
      return node;
    } catch (EdgeRouteException e) {
      throw e;
    } catch (Exception e) {
      throw new EdgeRouteException(url + " answered with no JSON: " + e.getMessage(), e);
    }
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
