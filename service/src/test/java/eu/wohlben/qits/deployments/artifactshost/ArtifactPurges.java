package eu.wohlben.qits.deployments.artifactshost;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/**
 * A hand-built {@link HttpArtifactPurge} aimed at one address with a fixed bearer, for the unit test
 * and the consumer pact — {@code GitHostSources}' arrangement.
 */
public final class ArtifactPurges {

  public static final String BEARER = "test-deployments-machine-token";

  private ArtifactPurges() {}

  public static HttpArtifactPurge at(String artifactsUrl) {
    HttpArtifactPurge purge = new HttpArtifactPurge();
    purge.artifactsUrl = Optional.of(artifactsUrl);
    purge.timeoutSeconds = 5;
    purge.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    purge.bearer = () -> Optional.of(BEARER);
    return purge;
  }
}
