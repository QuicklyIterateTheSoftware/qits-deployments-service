package eu.wohlben.qits.deployments.deployments.control;

import java.io.IOException;

/**
 * The seam to the public edge as an internet client meets it (epic qits-2174, task qits-2215): the
 * split check's probe that a private application is NOT served there. Its implementation is {@code
 * edgehost/TlsPublicEdgeProbe}; the suite installs {@code FakePublicEdgeProbe}.
 *
 * <p><b>Why not a plain request.</b> The deployer is private, and its resolvers ask the private
 * edge first (qits-2117), so {@code https://stt.qits.<domain>} from here reaches the PRIVATE edge,
 * which serves it. The probe has to enter where the internet enters: it connects to the address the
 * apex {@code qits.<domain>} resolves to — a name only the public edge serves — and asks for the
 * private host by SNI and {@code Host}.
 */
public interface PublicEdgeProbe {

  /**
   * The status line's code the public edge answers for {@code GET <path>} on {@code serverName}.
   *
   * @param apex {@code qits.<domain>}, possibly with a {@code :port}: where the connection goes
   * @param serverName {@code <host>.qits.<domain>}, possibly with a {@code :port}: SNI, the
   *     certificate's expected name and the {@code Host} header
   * @param path the request target, e.g. {@code /stt/q/health/ready}
   * @throws IOException when no status line could be read
   */
  int status(String apex, String serverName, String path) throws IOException;
}
