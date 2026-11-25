package eu.wohlben.qits.deployments.deployments.control;

import java.util.List;
import java.util.Optional;

/**
 * The seam to qits-projects' project and repository reads (qits-2659), which the project egress
 * grants need to find each project's WRAPPER repository: {@code GET /projects/api/projects}, {@code
 * GET /projects/api/projects/{id}/repositories} (its {@code wrapper.repositoryId}) and {@code GET
 * /projects/api/repositories/{id}}, presented with this component's own {@code qits:system} bearer.
 * Its implementation is {@code projectshost/HttpProjectDirectory}; the suite installs {@code
 * FakeProjectDirectory}.
 *
 * <p>Every call throws {@link ProjectDirectoryException} when qits-projects could not be reached or
 * answered what its door does not promise; {@link ProjectEgressGrants} keeps what it last read.
 */
public interface ProjectDirectory {

  /** One project: its id (the git host's address segment) and its slug (the secret namespace). */
  record Project(String id, String slug) {}

  /**
   * A project's wrapper repository.
   *
   * @param name the repository's name when the listing carried it, else null — {@link
   *     #repositoryName} then answers it
   */
  record Wrapper(String repositoryId, String name) {}

  /** Every project. */
  List<Project> projects();

  /** The project's wrapper, or empty when it has none (yet). */
  Optional<Wrapper> wrapperOf(String projectId);

  /** A repository's name. */
  String repositoryName(String repositoryId);
}
