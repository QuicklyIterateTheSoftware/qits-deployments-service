package eu.wohlben.qits.deployments.githost;

import java.util.Optional;

/**
 * The bearer {@link GitHostSpecSource} presents to qits-githost: this component's own machine
 * token, from the {@code qits} named oidc client (epic qits-543, qits-1331).
 *
 * <p>A port of one method so {@code GitHostSpecSourceTest} and the consumer pact can hand the reader
 * a fixed token without an idp. <b>An empty answer is not an anonymous fallback</b>: the reader
 * refuses the read before it opens a socket, the posture qits-projects' {@code
 * HttpGitHostRepositories} takes over its own {@code GitHostBearer}.
 */
@FunctionalInterface
public interface GitHostBearer {

  /** A current access token for qits-githost, or empty when none could be obtained. */
  Optional<String> token();
}
