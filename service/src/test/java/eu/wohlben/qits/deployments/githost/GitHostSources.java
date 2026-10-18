package eu.wohlben.qits.deployments.githost;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;

/** The shipped {@link GitHostSpecSource}, aimed at any git host — a pact mock server included. */
public final class GitHostSources {

  private GitHostSources() {}

  public static GitHostSpecSource at(String gitHostUrl) {
    GitHostSpecSource source = new GitHostSpecSource();
    source.gitHostUrl = gitHostUrl;
    source.timeoutSeconds = 5;
    source.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    return source;
  }
}
