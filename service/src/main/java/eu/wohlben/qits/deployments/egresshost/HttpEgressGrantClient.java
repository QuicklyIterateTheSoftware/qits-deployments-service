package eu.wohlben.qits.deployments.egresshost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.EgressGrantClient;
import eu.wohlben.qits.deployments.deployments.control.EgressGrantException;
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
 * qits-egress's grant-table door (epic qits-2523), over the JDK {@link HttpClient}: {@code PUT
 * <url>/grant-table} with the table and {@code GET <url>/grant-table} for the version qits-egress
 * holds, both with this component's own {@code qits} client bearer ({@link GitHostBearer}, a {@code
 * qits:system} JWT whose {@code sub} is {@code <env>-qits-deployments} — the one caller the door
 * admits). The pact rows are {@code Contracts.EGRESS}.
 *
 * <p>qits-egress is public and this service private, so this side PUSHES: qits-egress cannot
 * reach {@code GET /deployments/api/egress-grants}. It is dialled at its public name ({@code
 * qits.deployments.egress.url}, {@code https://egress.qits.${QITS_DOMAIN}/egress/api}), the way the
 * public edge is in {@code HttpEdgeRouteClient}.
 */
@ApplicationScoped
public class HttpEgressGrantClient implements EgressGrantClient {

  static final String PATH = "/grant-table";

  static final int DETAIL_MAX_CHARS = 300;

  private static final ObjectMapper JSON = new ObjectMapper();

  @ConfigProperty(name = "qits.deployments.egress.url")
  String url;

  @ConfigProperty(name = "qits.deployments.egress.timeout-seconds")
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
  public Pushed push(String tableJson) {
    String url = url();
    HttpResponse<String> response =
        send(
            "PUT",
            url,
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(tableJson)));
    int status = response.statusCode();
    if (status == 204) {
      return new Stored();
    }
    if (status == 409) {
      JsonNode held = read(url, response.body()).path("held");
      if (!held.canConvertToLong() || held.isNull()) {
        throw new EgressGrantException(
            "qits-egress answered PUT " + url + " 409 without `held`" + detailOf(response.body()));
      }
      return new Newer(held.asLong());
    }
    throw new EgressGrantException(
        "qits-egress answered " + status + " to PUT " + url + detailOf(response.body()));
  }

  @Override
  public OptionalLong heldVersion() {
    String url = url();
    HttpResponse<String> response = send("GET", url, HttpRequest.newBuilder(URI.create(url)).GET());
    int status = response.statusCode();
    if (status == 404) {
      return OptionalLong.empty();
    }
    if (status != 200) {
      throw new EgressGrantException(
          "qits-egress answered " + status + " to GET " + url + detailOf(response.body()));
    }
    JsonNode version = read(url, response.body()).path("version");
    if (!version.canConvertToLong() || version.isNull()) {
      throw new EgressGrantException(
          "qits-egress answered GET " + url + " 200 without `version`" + detailOf(response.body()));
    }
    return OptionalLong.of(version.asLong());
  }

  private HttpResponse<String> send(String method, String url, HttpRequest.Builder request) {
    String token =
        bearer
            .token()
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () ->
                    new EgressGrantException(
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
      throw new EgressGrantException("interrupted while calling " + method + " " + url, e);
    } catch (Exception e) {
      throw new EgressGrantException("could not " + method + " " + url + ": " + e, e);
    }
  }

  private String url() {
    if (url == null || url.isBlank()) {
      throw new EgressGrantException("no address is configured for qits-egress");
    }
    return url.strip().replaceAll("/+$", "") + PATH;
  }

  private static JsonNode read(String url, String body) {
    try {
      JsonNode node = JSON.readTree(body == null ? "" : body);
      if (node == null || !node.isObject()) {
        throw new EgressGrantException(url + " answered with no JSON object" + detailOf(body));
      }
      return node;
    } catch (EgressGrantException e) {
      throw e;
    } catch (Exception e) {
      throw new EgressGrantException(url + " answered with no JSON: " + e.getMessage(), e);
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
