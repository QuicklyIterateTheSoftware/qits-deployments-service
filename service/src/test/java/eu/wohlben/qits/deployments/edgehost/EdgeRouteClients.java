package eu.wohlben.qits.deployments.edgehost;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/** {@link HttpEdgeRouteClient} outside CDI, every edge at one address — for the pact and its test. */
public final class EdgeRouteClients {

  public static final String BEARER = "test-deployments-machine-token";

  private EdgeRouteClients() {}

  public static HttpEdgeRouteClient at(String url) {
    return at(url, url);
  }

  public static HttpEdgeRouteClient at(String publicUrl, String privateUrl) {
    return at(publicUrl, privateUrl, privateUrl);
  }

  public static HttpEdgeRouteClient at(String publicUrl, String privateUrl, String overlayUrl) {
    HttpEdgeRouteClient client = new HttpEdgeRouteClient();
    client.publicUrl = publicUrl;
    client.privateUrl = privateUrl;
    client.overlayUrl = overlayUrl;
    client.timeoutSeconds = 5;
    client.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    client.bearer = () -> Optional.of(BEARER);
    return client;
  }
}
