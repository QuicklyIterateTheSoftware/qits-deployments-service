package eu.wohlben.qits.deployments.edgehost;

import eu.wohlben.qits.deployments.deployments.control.PublicEdgeProbe;
import eu.wohlben.qits.deployments.githost.GitHostBearer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The split check's probe of the public edge (epic qits-2174, task qits-2215), over a bare {@link
 * SSLSocket} — no new dependency, and nothing in between that would resolve the name itself:
 *
 * <ol>
 *   <li>the apex {@code idp.qits.<domain>}, which only the public edge serves, is resolved with
 *       {@link InetAddress#getAllByName}; each address is tried in turn until one answers. Not the
 *       bare {@code qits.<domain>} apex — that is the landing app's own door and, since qits-2360,
 *       {@code exposure: private}, so public DNS answers it with the private tunnel address rather
 *       than the public edge. qits-idp stays public, and the edge answers {@code
 *       /edge-route-table} on every vhost regardless of which application it names, so this is a
 *       host only the public edge can be reached at;
 *   <li>a TLS socket to that address on 443 (or the apex's own port), with SNI {@code
 *       <host>.qits.<domain>} and endpoint identification {@code HTTPS}, so the certificate must be
 *       the private name's — a probe that reached the wrong server fails its handshake rather than
 *       passing;
 *   <li>{@code GET <path> HTTP/1.1} with {@code Host}, the deployer's own {@code qits:system}
 *       bearer ({@link GitHostBearer}, the {@code qits} oidc client, as the route-table push uses)
 *       — so a 401 or 403 can only mean the edge routed the request to a gate — and {@code
 *       Connection: close};
 *   <li>the status line's code, and nothing else of the answer.
 * </ol>
 */
@ApplicationScoped
public class TlsPublicEdgeProbe implements PublicEdgeProbe {

  static final int HTTPS_PORT = 443;

  /** The longest status line read; anything longer is not HTTP. */
  static final int STATUS_LINE_MAX = 512;

  @ConfigProperty(name = "qits.deployments.split-check.timeout-seconds")
  long timeoutSeconds;

  @Inject GitHostBearer bearer;

  @Override
  public int status(String apex, String serverName, String path) throws IOException {
    String token =
        bearer
            .token()
            .orElseThrow(() -> new IOException("no qits:system bearer could be obtained"));
    HostPort target = HostPort.parse(apex);
    HostPort name = HostPort.parse(serverName);
    InetAddress[] addresses = InetAddress.getAllByName(target.host());
    IOException last = null;
    for (InetAddress address : addresses) {
      try {
        return ask(address, target.port(), name, path, token);
      } catch (IOException e) {
        last = e;
      }
    }
    throw last != null ? last : new IOException(target.host() + " resolved to no address");
  }

  private int ask(InetAddress address, int port, HostPort name, String path, String token)
      throws IOException {
    int timeout = (int) Math.min(Integer.MAX_VALUE, timeoutSeconds * 1000);
    Socket raw = new Socket();
    try {
      raw.connect(new InetSocketAddress(address, port), timeout);
      raw.setSoTimeout(timeout);
      SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
      try (SSLSocket socket = (SSLSocket) factory.createSocket(raw, name.host(), port, true)) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName(name.host())));
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        OutputStream out = socket.getOutputStream();
        out.write(request(name.authority(), path, token).getBytes(StandardCharsets.US_ASCII));
        out.flush();
        BufferedReader in =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        return statusOf(readLine(in));
      }
    } finally {
      raw.close();
    }
  }

  /** The request, byte for byte. Package-private for the test. */
  static String request(String host, String path, String token) {
    return "GET "
        + path
        + " HTTP/1.1\r\n"
        + "Host: "
        + host
        + "\r\n"
        + "Authorization: Bearer "
        + token
        + "\r\n"
        + "Accept: */*\r\n"
        + "Connection: close\r\n"
        + "\r\n";
  }

  /** The code of a status line ({@code HTTP/1.1 404 Not Found}). Package-private for the test. */
  static int statusOf(String line) throws IOException {
    if (line == null) {
      throw new IOException("the public edge closed the connection without a status line");
    }
    String[] parts = line.split(" ", 3);
    if (parts.length < 2 || !parts[0].startsWith("HTTP/") || parts[1].length() != 3) {
      throw new IOException("not an HTTP status line: " + abbreviate(line));
    }
    try {
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("not an HTTP status line: " + abbreviate(line));
    }
  }

  private static String readLine(BufferedReader in) throws IOException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        break;
      }
      if (c != '\r') {
        line.append((char) c);
      }
      if (line.length() > STATUS_LINE_MAX) {
        throw new IOException("the public edge's status line is longer than " + STATUS_LINE_MAX);
      }
    }
    return c == -1 && line.isEmpty() ? null : line.toString();
  }

  private static String abbreviate(String line) {
    return line.length() > 80 ? line.substring(0, 80) + "…" : line;
  }

  /** A {@code host[:port]}, 443 when it names none. Package-private for the test. */
  record HostPort(String host, int port) {

    static HostPort parse(String value) {
      int colon = value.lastIndexOf(':');
      if (colon > 0 && colon < value.length() - 1 && value.indexOf(']') < colon) {
        try {
          return new HostPort(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        } catch (NumberFormatException e) {
          return new HostPort(value, HTTPS_PORT);
        }
      }
      return new HostPort(value, HTTPS_PORT);
    }

    /** What {@code Host} carries: the name, and the port only when it is not 443. */
    String authority() {
      return port == HTTPS_PORT ? host : host + ":" + port;
    }
  }
}
