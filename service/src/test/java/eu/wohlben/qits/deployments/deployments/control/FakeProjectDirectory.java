package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The suite's stand-in for qits-projects' project and repository reads (qits-2659), the tenth fake:
 * {@code @Mock}, so no {@code @QuarkusTest} lists a real project. What the real doors are asked and
 * answer is the pact's ({@code Contracts.PROJECTS}); what {@link ProjectEgressGrants} makes of the
 * answers is held here. Unscripted, there are no projects — so every existing test's table carries
 * no project route. A project's wrapper file is scripted on {@link FakeSpecSource#scriptText} under
 * the wrapper's NAME.
 *
 * <p>Application-scoped and shared: reset in {@code @BeforeEach}.
 */
@Mock
@ApplicationScoped
public class FakeProjectDirectory implements ProjectDirectory {

  private record Scripted(Project project, Wrapper wrapper) {}

  private final Map<String, Scripted> projects = new LinkedHashMap<>();
  private final Map<String, String> names = new LinkedHashMap<>();
  private volatile String failure;
  private volatile String wrapperFailure;

  public synchronized void reset() {
    projects.clear();
    names.clear();
    failure = null;
    wrapperFailure = null;
  }

  /**
   * A project whose wrapper is {@code wrapperId}, named {@code wrapperName} in the listing (null:
   * the name is answered by {@link #repositoryName} alone).
   */
  public synchronized void project(String id, String slug, String wrapperId, String wrapperName) {
    projects.put(
        id,
        new Scripted(
            new Project(id, slug), wrapperId == null ? null : new Wrapper(wrapperId, wrapperName)));
  }

  /** {@link #repositoryName} answers this for the id. */
  public synchronized void repository(String id, String name) {
    names.put(id, name);
  }

  /** The listing throws with this message, until reset or cleared (null). */
  public void failListingWith(String message) {
    failure = message;
  }

  /** Every wrapper read throws with this message, until reset or cleared (null). */
  public void failWrappersWith(String message) {
    wrapperFailure = message;
  }

  @Override
  public synchronized List<Project> projects() {
    if (failure != null) {
      throw new ProjectDirectoryException(failure);
    }
    List<Project> all = new ArrayList<>();
    projects.values().forEach(scripted -> all.add(scripted.project()));
    return all;
  }

  @Override
  public synchronized Optional<Wrapper> wrapperOf(String projectId) {
    if (wrapperFailure != null) {
      throw new ProjectDirectoryException(wrapperFailure);
    }
    Scripted scripted = projects.get(projectId);
    return scripted == null ? Optional.empty() : Optional.ofNullable(scripted.wrapper());
  }

  @Override
  public synchronized String repositoryName(String repositoryId) {
    String name = names.get(repositoryId);
    if (name == null) {
      throw new ProjectDirectoryException("no repository " + repositoryId);
    }
    return name;
  }
}
