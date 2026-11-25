package eu.wohlben.qits.deployments.deployments.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The projects' egress grants</b> (qits-2659): the routes each project's WRAPPER repository
 * declares in {@code .config/qits/egress.yml} at {@code refs/heads/main}, granted to {@code
 * project:<projectId>} — every agent of the project. The wrapper's {@code main} moves only at
 * FINALIZED, after its release request passed the {@code .config/qits} approval, so the file there
 * is the reviewed one.
 *
 * <p><b>When</b>: {@link #refreshIfDue} on every reconcile tick ({@link EgressGrantPusher}), at most
 * every {@code qits.deployments.egress.project-poll} (60 s). A read lists the projects ({@link
 * ProjectDirectory}), finds each one's wrapper and its name, and reads the blob through the same
 * {@link SpecSource} the release path reads {@code deployments.yml} through, name-addressed: {@code
 * /git/<projectId>/<wrapperName>/blob/refs%2Fheads%2Fmain/.config/qits/egress.yml}.
 *
 * <ul>
 *   <li>A 404, or a project without a wrapper, is no routes.
 *   <li>A file {@link EgressDeclarationParser#parseProject} refuses is that project's refusal,
 *       listed under {@code refused}; every other project's routes are unaffected.
 *   <li>A read that never answered keeps what that project granted at the last read, and a listing
 *       that never answered keeps every project's — so a qits-projects or git-host outage never
 *       revokes a grant. A project that is no longer listed loses its routes.
 * </ul>
 *
 * <p><b>Held in memory, not in a table</b>: it is a cache of two peers' answers, re-read every
 * minute, and nothing about it is history. Until the first listing has answered, {@link #answered}
 * is false, and {@link EgressGrantPusher} holds its push so a fresh process never pushes a table
 * that silently dropped every project's routes.
 */
@ApplicationScoped
public class ProjectEgressGrants {

  private static final Logger LOG = Logger.getLogger(ProjectEgressGrants.class);

  /** The branch the reviewed file is read at. */
  static final String WRAPPER_BRANCH = "main";

  /** How many characters of the wrapper's {@code main} commit name the version. */
  static final int VERSION_CHARS = 12;

  @ConfigProperty(name = "qits.deployments.egress.project-poll", defaultValue = "60s")
  Duration poll;

  @Inject ProjectDirectory directory;

  @Inject SpecSource specs;

  /**
   * What one project grants.
   *
   * @param deployedVersion the wrapper's {@code main} commit, {@value #VERSION_CHARS} characters;
   *     null when the git host named none
   * @param routes the parsed routes, secrets resolved; empty when refused
   * @param refused why the file was refused, or null
   */
  public record Grants(
      String projectId,
      String deployedVersion,
      List<EgressDeclarationParser.Route> routes,
      String refused) {

    public Grants {
      routes = routes == null ? List.of() : List.copyOf(routes);
    }
  }

  private volatile Map<String, Grants> byProject = Map.of();

  private volatile Instant lastRead;

  private volatile boolean answered;

  private boolean listingFailing;

  private final Set<String> failingProjects = new HashSet<>();

  /** Every project's grants, by project id. */
  public Collection<Grants> current() {
    return byProject.values();
  }

  /** Whether the project listing has answered at least once in this process. */
  public boolean answered() {
    return answered;
  }

  /** {@link #refresh} when the last read is at least one poll interval old. Never throws. */
  public void refreshIfDue() {
    refreshIfDue(Instant.now());
  }

  synchronized void refreshIfDue(Instant now) {
    Instant last = lastRead;
    if (last != null && now.isBefore(last.plus(poll))) {
      return;
    }
    refresh(now);
  }

  /** Read every project's wrapper file now, whenever it was read last. Never throws. */
  public synchronized void refresh() {
    refresh(Instant.now());
  }

  private void refresh(Instant now) {
    lastRead = now;
    List<ProjectDirectory.Project> projects;
    try {
      projects = directory.projects();
    } catch (RuntimeException e) {
      if (!listingFailing) {
        listingFailing = true;
        LOG.warnf(
            "could not list the projects for their egress grants; keeping what was read last: %s",
            reason(e));
      }
      return;
    }
    if (listingFailing) {
      listingFailing = false;
      LOG.infof("the project listing for the egress grants answers again");
    }
    Map<String, Grants> previous = byProject;
    Map<String, Grants> next = new TreeMap<>();
    for (ProjectDirectory.Project project : projects) {
      if (project == null || project.id() == null) {
        continue;
      }
      try {
        Grants grants = read(project);
        if (grants != null) {
          next.put(project.id(), grants);
        }
        if (failingProjects.remove(project.id())) {
          LOG.infof("the egress grants of project %s read again", project.slug());
        }
      } catch (RuntimeException e) {
        Grants kept = previous.get(project.id());
        if (kept != null) {
          next.put(project.id(), kept);
        }
        if (failingProjects.add(project.id())) {
          LOG.warnf(
              "could not read the egress grants of project %s; %s: %s",
              project.slug(),
              kept == null ? "it grants nothing until a read answers" : "keeping what it granted",
              reason(e));
        }
      }
    }
    failingProjects.retainAll(next.keySet());
    byProject = java.util.Collections.unmodifiableMap(next);
    answered = true;
  }

  /**
   * One project's grants: null when it has no wrapper or no file.
   *
   * @throws RuntimeException when a peer did not answer — the caller keeps the previous grants
   */
  private Grants read(ProjectDirectory.Project project) {
    Optional<ProjectDirectory.Wrapper> wrapper = directory.wrapperOf(project.id());
    if (wrapper.isEmpty() || wrapper.get().repositoryId() == null) {
      return null;
    }
    String name =
        wrapper.get().name() != null
            ? wrapper.get().name()
            : directory.repositoryName(wrapper.get().repositoryId());
    String source =
        SpecSource.EGRESS_PATH + " of project " + project.slug() + " (" + name + "@main)";
    RepositoryRef ref;
    try {
      ref = new RepositoryRef(wrapper.get().repositoryId(), project.id(), name).validated();
    } catch (RuntimeException e) {
      return new Grants(project.id(), null, List.of(), source + ": " + reason(e));
    }
    if (!ref.nameAddressed()) {
      return new Grants(project.id(), null, List.of(), source + ": the wrapper has no name");
    }
    Optional<SpecSource.TextRead> file;
    try {
      file =
          specs.readTextAt(ref, SpecSource.branchRev(WRAPPER_BRANCH), SpecSource.EGRESS_PATH);
    } catch (SpecException e) {
      if (e.retryable()) {
        throw e;
      }
      return new Grants(project.id(), null, List.of(), reason(e));
    }
    if (file.isEmpty()) {
      return null;
    }
    String version = shortSha(file.get().commitSha());
    try {
      return new Grants(
          project.id(),
          version,
          EgressDeclarationParser.parseProject(file.get().text(), source, project.slug()),
          null);
    } catch (SpecException e) {
      LOG.warnf("%s grants no egress routes: %s", source, e.getMessage());
      return new Grants(project.id(), version, List.of(), reason(e));
    }
  }

  static String shortSha(String sha) {
    if (sha == null || sha.isBlank()) {
      return null;
    }
    String stripped = sha.strip();
    return stripped.length() <= VERSION_CHARS ? stripped : stripped.substring(0, VERSION_CHARS);
  }

  private static String reason(RuntimeException e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}
