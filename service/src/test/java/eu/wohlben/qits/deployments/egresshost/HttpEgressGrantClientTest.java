package eu.wohlben.qits.deployments.egresshost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.deployments.deployments.control.EgressGrantClient;
import eu.wohlben.qits.deployments.deployments.control.EgressGrantException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the pact does not hold (epic qits-2523): the {@code 409} a newer held version answers, and
 * every answer the door does not promise. The promised answers are {@code Contracts.EGRESS}'s.
 */
class HttpEgressGrantClientTest {

  private HttpServer server;
  private final List<String> seen = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();

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
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
  void theTableIsPutVerbatimWithTheMachineBearer() throws IOException {
    String url = serve(204, "");
    String table = "{\"version\":7,\"routes\":[],\"refused\":[]}";

    assertEquals(new EgressGrantClient.Stored(), EgressGrantClients.at(url + "/egress/api/").push(table));

    assertEquals(
        List.of("PUT /egress/api/grant-table Bearer " + EgressGrantClients.BEARER), seen);
    assertEquals(List.of(table), bodies);
  }

  @Test
  void aNewerHeldVersionIsAConflictCarryingIt() throws IOException {
    String url = serve(409, "{\"error\":\"a newer grant table is held\",\"held\":9}");

    assertEquals(
        new EgressGrantClient.Newer(9),
        EgressGrantClients.at(url).push("{\"version\":7,\"routes\":[],\"refused\":[]}"));
  }

  @Test
  void aConflictWithoutTheHeldVersionIsAFailure() throws IOException {
    String url = serve(409, "{\"error\":\"no\"}");

    assertThrows(EgressGrantException.class, () -> EgressGrantClients.at(url).push("{}"));
  }

  @Test
  void aRefusedCallerOrAServerErrorIsAFailureNamingTheStatus() throws IOException {
    String url = serve(403, "{\"error\":\"not the deployer\"}");
    EgressGrantClient client = EgressGrantClients.at(url);

    EgressGrantException put = assertThrows(EgressGrantException.class, () -> client.push("{}"));
    assertTrue(put.getMessage().contains("403"), put.getMessage());
    EgressGrantException get = assertThrows(EgressGrantException.class, client::heldVersion);
    assertTrue(get.getMessage().contains("403"), get.getMessage());
  }

  @Test
  void aBadRequestIsAFailure() throws IOException {
    String url = serve(400, "{\"error\":\"version is required\"}");

    EgressGrantException e =
        assertThrows(EgressGrantException.class, () -> EgressGrantClients.at(url).push("{}"));
    assertTrue(e.getMessage().contains("400"), e.getMessage());
  }

  @Test
  void aNotFoundIsNoTableHeld() throws IOException {
    String url = serve(404, "{\"error\":\"no grant table is held\"}");

    assertEquals(OptionalLong.empty(), EgressGrantClients.at(url).heldVersion());
    assertEquals(List.of("GET /grant-table Bearer " + EgressGrantClients.BEARER), seen);
  }

  @Test
  void aHeldTableWithoutAVersionIsAFailure() throws IOException {
    String url = serve(200, "{\"routes\":[]}");

    assertThrows(EgressGrantException.class, () -> EgressGrantClients.at(url).heldVersion());
  }

  @Test
  void anUnreachableEgressOrNoAddressIsAFailure() {
    assertThrows(
        EgressGrantException.class, () -> EgressGrantClients.at("http://127.0.0.1:1").heldVersion());
    assertThrows(EgressGrantException.class, () -> EgressGrantClients.at(" ").push("{}"));
  }

  @Test
  void noBearerOpensNoSocket() throws IOException {
    String url = serve(204, "");

    assertThrows(EgressGrantException.class, () -> EgressGrantClients.at(url, "").push("{}"));
    assertEquals(List.of(), seen);
  }
}
