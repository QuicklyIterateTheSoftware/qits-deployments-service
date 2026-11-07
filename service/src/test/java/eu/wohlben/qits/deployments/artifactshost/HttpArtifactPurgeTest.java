package eu.wohlben.qits.deployments.artifactshost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.deployments.deployments.control.ArtifactPurge.RemovedArtifact;
import eu.wohlben.qits.deployments.deployments.control.PurgeException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@link HttpArtifactPurge} sends and what it makes of each answer, against the JDK's own
 * {@code HttpServer} — {@code GitHostSpecSourceTest}'s arrangement: what is under test is the
 * request, so a fake at the seam would only assert this test's model of a client.
 */
public class HttpArtifactPurgeTest {

  private HttpServer server;
  private final List<String> requests = new ArrayList<>();
  private volatile int status = 200;
  private volatile String answer =
      "{\"application\":\"retired-app\",\"removed\":[{\"type\":\"maven\",\"repository\":\"maven\","
          + "\"name\":\"eu.wohlben.qits:retired-app-golden-masters\",\"versions\":[\"2026.1.0\"]}]}";

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(
        exchange.getRequestMethod()
            + " "
            + exchange.getRequestURI().getRawPath()
            + " "
            + exchange.getRequestHeaders().getFirst("Authorization")
            + " "
            + exchange.getRequestHeaders().getFirst("Content-Type")
            + " "
            + body);
    byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @Test
  void itPostsTheNameAndTheRepositoriesOnItsOwnBearerAndReadsTheRemovedList() {
    List<RemovedArtifact> removed =
        ArtifactPurges.at(url()).purge("retired-app", List.of("retired-app-service", "retired-docs"));

    assertEquals(
        List.of(
            "POST /artifacts/api/applications/retired-app/purge Bearer "
                + ArtifactPurges.BEARER
                + " application/json {\"repositories\":[\"retired-app-service\",\"retired-docs\"]}"),
        requests);
    assertEquals(
        List.of(
            new RemovedArtifact(
                "maven", "maven", "eu.wohlben.qits:retired-app-golden-masters", List.of("2026.1.0"))),
        removed);
  }

  @Test
  void anEmptyRemovedListIsAnAnswerNotAFailure() {
    answer = "{\"application\":\"retired-app\",\"removed\":[]}";
    assertEquals(List.of(), ArtifactPurges.at(url()).purge("retired-app", List.of()));
  }

  @Test
  void aNon2xxIsAFailureNamingTheStatusAndTheAddress() {
    status = 500;
    answer = "{\"message\":\"the store is down\"}";
    PurgeException failure =
        assertThrows(
            PurgeException.class, () -> ArtifactPurges.at(url()).purge("retired-app", List.of()));
    assertTrue(failure.getMessage().contains("answered 500"), failure.getMessage());
    assertTrue(failure.getMessage().contains("/artifacts/api/applications/retired-app/purge"));
    assertTrue(failure.getMessage().contains("the store is down"), failure.getMessage());
  }

  @Test
  void anUnreachableHostIsAFailure() {
    server.stop(0);
    PurgeException failure =
        assertThrows(
            PurgeException.class,
            () -> ArtifactPurges.at("http://127.0.0.1:1").purge("retired-app", List.of()));
    assertTrue(failure.getMessage().startsWith("could not POST"), failure.getMessage());
  }

  @Test
  void noBearerIsNoRequest() {
    HttpArtifactPurge purge = ArtifactPurges.at(url());
    purge.bearer = Optional::empty;
    PurgeException failure =
        assertThrows(PurgeException.class, () -> purge.purge("retired-app", List.of()));
    assertTrue(failure.getMessage().contains("quarkus.oidc-client.qits"), failure.getMessage());
    assertEquals(List.of(), requests);
  }

  @Test
  void thePurgePathHangsOffTheBaseWithOneSlash() {
    assertEquals(
        "https://registry.qits.example.test/artifacts/api/applications/a/purge",
        HttpArtifactPurge.purgeUrl("https://registry.qits.example.test/", "a"));
  }
}
