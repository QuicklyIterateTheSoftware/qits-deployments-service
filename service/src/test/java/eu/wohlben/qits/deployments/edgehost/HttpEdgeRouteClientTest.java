package eu.wohlben.qits.deployments.edgehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.deployments.deployments.control.EdgeRouteException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the pact does not hold (epic qits-2174): the answers the edge's door does not promise, and
 * which edge each name dials. The promised answers are {@code Contracts.EDGE}'s.
 */
class HttpEdgeRouteClientTest {

  private HttpServer server;
  private final List<String> seen = new ArrayList<>();

  private String serve(int status, String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seen.add(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI().getPath()
                  + " "
                  + exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void eachEdgeIsDialledAtItsOwnAddressWithTheMachineBearer() throws IOException {
    String url = serve(204, "");
    HttpEdgeRouteClient client = EdgeRouteClients.at("http://127.0.0.1:1", url + "/");

    client.push("private", "{\"edge\":\"private\",\"version\":1}");

    assertEquals(
        List.of("PUT /edge-route-table Bearer " + EdgeRouteClients.BEARER), seen);
    assertThrows(EdgeRouteException.class, () -> client.push("public", "{}"), "port 1 refuses");
    assertThrows(EdgeRouteException.class, () -> client.push("intranet", "{}"));
  }

  @Test
  void anAnswerTheDoorDoesNotPromiseIsAnException() throws IOException {
    String url = serve(503, "{\"error\":\"idp unreachable\"}");
    HttpEdgeRouteClient client = EdgeRouteClients.at(url);

    EdgeRouteException push = assertThrows(EdgeRouteException.class, () -> client.push("public", "{}"));
    assertTrue(push.getMessage().contains("503"), push.getMessage());
    assertThrows(EdgeRouteException.class, () -> client.held("public"));
  }

  @Test
  void anEdgeThatHoldsNoTableAnswersNoVersion() throws IOException {
    String url = serve(200, "{\"edge\":\"public\",\"version\":null,\"receivedAt\":null}");

    assertEquals(OptionalLong.empty(), EdgeRouteClients.at(url).held("public"));
  }
}
