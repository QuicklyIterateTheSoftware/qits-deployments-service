package eu.wohlben.qits.deployments.projectshost;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;

/** {@link HttpProjectDirectory} outside CDI, at one address — for the pact and its test. */
public final class ProjectDirectories {

  public static final String BEARER = "test-deployments-machine-token";

  private ProjectDirectories() {}

  /** @param url the doors' base, {@code …/projects/api} */
  public static HttpProjectDirectory at(String url) {
    HttpProjectDirectory directory = new HttpProjectDirectory();
    directory.url = url;
    directory.timeoutSeconds = 5;
    directory.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    directory.bearer = () -> Optional.of(BEARER);
    return directory;
  }
}
