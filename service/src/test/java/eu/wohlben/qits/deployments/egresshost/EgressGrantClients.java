package eu.wohlben.qits.deployments.egresshost;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/** {@link HttpEgressGrantClient} outside CDI, at one address — for the pact and its test. */
public final class EgressGrantClients {

  public static final String BEARER = "test-deployments-machine-token";

  private EgressGrantClients() {}

  /** @param url the door's base, {@code …/egress/api} */
  public static HttpEgressGrantClient at(String url) {
    return at(url, BEARER);
  }

  /** @param bearer the token presented, without its {@code Bearer } prefix */
  public static HttpEgressGrantClient at(String url, String bearer) {
    HttpEgressGrantClient client = new HttpEgressGrantClient();
    client.url = url;
    client.timeoutSeconds = 5;
    client.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    client.bearer = () -> Optional.of(bearer);
    return client;
  }
}
