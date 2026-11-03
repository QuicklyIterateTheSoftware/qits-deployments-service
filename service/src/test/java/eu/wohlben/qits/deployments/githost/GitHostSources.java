package eu.wohlben.qits.deployments.githost;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/** The shipped {@link GitHostSpecSource}, aimed at any git host — a pact mock server included. */
public final class GitHostSources {

  /** The machine bearer every source built here presents, in place of the {@code qits} client's. */
  public static final String BEARER = "test-deployments-machine-token";

  private GitHostSources() {}

  public static GitHostSpecSource at(String gitHostUrl) {
    GitHostSpecSource source = new GitHostSpecSource();
    source.gitHostUrl = gitHostUrl;
    source.timeoutSeconds = 5;
    source.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    source.bearer = () -> Optional.of(BEARER);
    return source;
  }
}
