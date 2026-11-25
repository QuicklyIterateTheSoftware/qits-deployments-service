package eu.wohlben.qits.deployments.projectshost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.ProjectDirectory;
import eu.wohlben.qits.deployments.deployments.control.ProjectDirectoryException;
import eu.wohlben.qits.deployments.githost.GitHostBearer;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * qits-projects' project and repository reads (qits-2659), over the JDK {@link HttpClient}, with
 * this component's own {@code qits} client bearer ({@link GitHostBearer}, {@code qits:system}, which
 * all three doors admit). The pact rows are {@code Contracts.PROJECTS}.
 *
 * <pre>
 * GET &lt;url&gt;/projects                          → entries[].project.{id, slug}
 * GET &lt;url&gt;/projects/{projectId}/repositories → wrapper.repositoryId, entries[].repository.{id, name}
 * GET &lt;url&gt;/repositories/{repositoryId}       → repository.name
 * </pre>
 *
 * <p>Dialled at qits-projects' public name ({@code qits.deployments.projects.url}, {@code
 * https://projects.qits.${QITS_DOMAIN}/projects/api}). Read as a JSON tree, so no wire type joins
 * the native image's reflection list.
 */
@ApplicationScoped
public class HttpProjectDirectory implements ProjectDirectory {

  static final int DETAIL_MAX_CHARS = 300;

  private static final ObjectMapper JSON = new ObjectMapper();

  @ConfigProperty(name = "qits.deployments.projects.url")
  String url;

  @ConfigProperty(name = "qits.deployments.projects.timeout-seconds")
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
  public List<Project> projects() {
    JsonNode entries = get("/projects").path("entries");
    if (!entries.isArray()) {
      throw new ProjectDirectoryException("qits-projects answered GET /projects without `entries`");
    }
    List<Project> projects = new ArrayList<>();
    for (JsonNode entry : entries) {
      JsonNode project = entry.path("project");
      String id = text(project, "id");
      if (id != null) {
        projects.add(new Project(id, text(project, "slug")));
      }
    }
    return List.copyOf(projects);
  }

  @Override
  public Optional<Wrapper> wrapperOf(String projectId) {
    JsonNode answer = get("/projects/" + segment(projectId) + "/repositories");
    String repositoryId = text(answer.path("wrapper"), "repositoryId");
    if (repositoryId == null) {
      return Optional.empty();
    }
    String name = null;
    for (JsonNode entry : answer.path("entries")) {
      JsonNode repository = entry.path("repository");
      if (repositoryId.equals(text(repository, "id"))) {
        name = text(repository, "name");
        break;
      }
    }
    return Optional.of(new Wrapper(repositoryId, name));
  }

  @Override
  public String repositoryName(String repositoryId) {
    String name = text(get("/repositories/" + segment(repositoryId)).path("repository"), "name");
    if (name == null) {
      throw new ProjectDirectoryException(
          "qits-projects answered GET /repositories/" + repositoryId + " without `repository.name`");
    }
    return name;
  }

  private JsonNode get(String path) {
    String target = base() + path;
    String token =
        bearer
            .token()
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () ->
                    new ProjectDirectoryException(
                        "no machine bearer from the `qits` oidc client (quarkus.oidc-client.qits)"
                            + " to GET " + target + " with"));
    HttpResponse<String> response;
    try {
      response =
          client()
              .send(
                  HttpRequest.newBuilder(URI.create(target))
                      .timeout(Duration.ofSeconds(timeoutSeconds))
                      .header("Authorization", "Bearer " + token)
                      .header("Accept", "application/json")
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProjectDirectoryException("interrupted while calling GET " + target, e);
    } catch (Exception e) {
      throw new ProjectDirectoryException("could not GET " + target + ": " + e, e);
    }
    if (response.statusCode() != 200) {
      throw new ProjectDirectoryException(
          "qits-projects answered " + response.statusCode() + " to GET " + target
              + detailOf(response.body()));
    }
    try {
      JsonNode node = JSON.readTree(response.body() == null ? "" : response.body());
      if (node == null || !node.isObject()) {
        throw new ProjectDirectoryException(
            target + " answered with no JSON object" + detailOf(response.body()));
      }
      return node;
    } catch (ProjectDirectoryException e) {
      throw e;
    } catch (Exception e) {
      throw new ProjectDirectoryException(target + " answered with no JSON: " + e.getMessage(), e);
    }
  }

  private String base() {
    if (url == null || url.isBlank()) {
      throw new ProjectDirectoryException("no address is configured for qits-projects");
    }
    return url.strip().replaceAll("/+$", "");
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
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
