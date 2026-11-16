package eu.wohlben.qits.deployments.edgehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The split check's public-edge probe (qits-2215) below the socket: the request it writes, the
 * status line it reads, and the {@code host[:port]} it is aimed with. The TLS handshake itself is
 * the JDK's; what the probe adds is which name it asks for and what it reads back.
 */
class TlsPublicEdgeProbeTest {

  @Test
  void theRequestAsksForTheHostWithTheBearerAndClosesTheConnection() {
    assertEquals(
        "GET /stt/q/health/ready HTTP/1.1\r\n"
            + "Host: stt.qits.example.org\r\n"
            + "Authorization: Bearer tok\r\n"
            + "Accept: */*\r\n"
            + "Connection: close\r\n"
            + "\r\n",
        TlsPublicEdgeProbe.request("stt.qits.example.org", "/stt/q/health/ready", "tok"));
  }

  @Test
  void theStatusLineIsReadForItsCode() throws IOException {
    assertEquals(404, TlsPublicEdgeProbe.statusOf("HTTP/1.1 404 Not Found"));
    assertEquals(421, TlsPublicEdgeProbe.statusOf("HTTP/1.1 421 Misdirected Request"));
    assertEquals(200, TlsPublicEdgeProbe.statusOf("HTTP/1.1 200"));
    assertEquals(502, TlsPublicEdgeProbe.statusOf("HTTP/2 502 Bad Gateway"));
  }

  @Test
  void anythingButAStatusLineIsNoAnswer() {
    assertThrows(IOException.class, () -> TlsPublicEdgeProbe.statusOf(null));
    assertThrows(IOException.class, () -> TlsPublicEdgeProbe.statusOf(""));
    assertThrows(IOException.class, () -> TlsPublicEdgeProbe.statusOf("SSH-2.0-OpenSSH"));
    assertThrows(IOException.class, () -> TlsPublicEdgeProbe.statusOf("HTTP/1.1 4o4 nope"));
  }

  @Test
  void aNameWithoutAPortIsOn443AndOneWithAPortKeepsIt() {
    TlsPublicEdgeProbe.HostPort plain = TlsPublicEdgeProbe.HostPort.parse("qits.example.org");
    assertEquals("qits.example.org", plain.host());
    assertEquals(443, plain.port());
    assertEquals("qits.example.org", plain.authority());

    TlsPublicEdgeProbe.HostPort dev = TlsPublicEdgeProbe.HostPort.parse("stt.qits.dev.localhost:8443");
    assertEquals("stt.qits.dev.localhost", dev.host());
    assertEquals(8443, dev.port());
    assertEquals("stt.qits.dev.localhost:8443", dev.authority());
  }

  @Test
  void noBearerIsNoProbe() {
    TlsPublicEdgeProbe probe = new TlsPublicEdgeProbe();
    probe.timeoutSeconds = 1;
    probe.bearer = Optional::empty;

    IOException refused =
        assertThrows(
            IOException.class, () -> probe.status("qits.invalid", "stt.qits.invalid", "/stt"));
    assertTrue(refused.getMessage().contains("bearer"), refused.getMessage());
  }
}
