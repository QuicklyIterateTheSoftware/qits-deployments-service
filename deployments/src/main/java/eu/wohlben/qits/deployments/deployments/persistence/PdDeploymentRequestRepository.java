package eu.wohlben.qits.deployments.deployments.persistence;

import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link PdDeploymentRequest}.
 *
 * <p>Every listing orders by {@code seq desc} — V6's identity column — for {@link
 * PdDeploymentRepository}'s reason: {@code createdAt} is not unique and the requests of one release
 * land in one tick, so a {@code createdAt desc, id desc} order would swap them at random.
 */
@ApplicationScoped
public class PdDeploymentRequestRepository
    implements PanacheRepositoryBase<PdDeploymentRequest, String> {

  /** One application's requests, newest-first — its whole release history on this instance. */
  public List<PdDeploymentRequest> listByApplicationNewestFirst(String applicationName) {
    return list("applicationName = ?1 order by seq desc", applicationName);
  }

  /**
   * One tier's requests across every application asked for in it, newest-first — the read surface's
   * listing, and the platform plane's requests are in it for the reason its deployments are: a
   * platform service is deployed into the main environment and its request names that tier.
   *
   * <p>{@code environment_id} is nullable and the null is never matched here, exactly as on {@link
   * PdDeploymentRepository#listByEnvironmentNewestFirst}: a request written before V8 on an install
   * with no designated tier carries one, and {@code environment_id = null} matches nothing in SQL
   * anyway.
   */
  public List<PdDeploymentRequest> listByEnvironmentNewestFirst(String environmentId) {
    return list("environmentId = ?1 order by seq desc", environmentId);
  }

  /** The same listing narrowed to one application — "what has this service been asked for here". */
  public List<PdDeploymentRequest> listByEnvironmentAndApplicationNewestFirst(
      String environmentId, String applicationName) {
    return list(
        "environmentId = ?1 and applicationName = ?2 order by seq desc",
        environmentId,
        applicationName);
  }

  /**
   * One PROJECT's requests across every application and every tier, newest-first — the listing the
   * project-scoped screen reads.
   *
   * <p><b>{@code project_id} is a foreign identity and is not resolved here</b>, exactly like {@code
   * repo_id} beside it: this component holds no project rows and never asks qits-projects whether
   * one exists. So a project nothing was ever released for answers with an empty list, which is the
   * honest answer — "no release of this project reached this platform" — rather than a 404 about a
   * row this schema does not have.
   *
   * <p>It is unscoped by tier on purpose. A project's releases enter at whatever tier the platform
   * designates, and the designation moves; a listing narrowed to today's entry tier would silently
   * lose everything asked for before it moved.
   */
  public List<PdDeploymentRequest> listByProjectNewestFirst(String projectId) {
    return list("projectId = ?1 order by seq desc", projectId);
  }

  /**
   * Every request written for one released version of one repository, newest-first — the join a
   * release page follows to ask "what did this release deploy".
   *
   * <p>The pair is {@code (repo_id, version)} rather than {@code (applicationName, version)} because
   * the caller is holding a release request over in qits-projects, which knows a repository and a
   * version and nothing about the name this platform deploys under — the application name may be the
   * repository's, or the spec's {@code application:} override, and only this table knows which.
   *
   * <p>More than one row is an ordinary answer rather than a surprise: one version is asked for once
   * per place, and a redeploy of the same version writes a second request. Newest first, so the
   * first result is the one a reader means.
   *
   * <p><b>The main application's rows only</b>: a release whose spec names {@code variants:} also
   * writes rows for each variant's application under the same pair, and those are not the release's
   * own deployment (qits-1976). They are found by their application name instead.
   */
  public List<PdDeploymentRequest> listMainByRepoAndVersionNewestFirst(
      String repoId, String version) {
    return list(
        "repoId = ?1 and version = ?2 and variant is null order by seq desc", repoId, version);
  }

  /**
   * Whether this exact version of this application has already been asked for anywhere.
   *
   * <p><b>The idempotence guard of the acceptance ledger's re-drive</b> ({@code OwedReleaseSweep}):
   * an obligation whose request row already exists was met by the process that died holding it, and
   * announcing it again would mint a second request and a second deployment of one version. It is
   * asked of the requests rather than of the deployments for {@link #newestVersionOf}'s reason — a
   * request is written the moment a release is accepted for a place, so it records "this was done
   * here" even for a deployment that then failed.
   *
   * <p>Environment-blind on purpose: the question is about the release, and a release accepted for
   * several places wrote a row per place. One is enough to know it was not lost.
   */
  public boolean existsFor(String applicationName, String version) {
    return count("applicationName = ?1 and version = ?2", applicationName, version) > 0;
  }

  /**
   * How many of this exact version's requests reached a DECISION — the half of {@link #existsFor}
   * that a restart can make wrong (qits-1803).
   *
   * <p>A request is written at the start of the worker's {@code deploy()}, so a process replaced
   * between that write and the cutover leaves a request whose deployment the next boot's sweep
   * settles {@code FAILED} with {@code interrupted} set: nothing ever decided it. {@link
   * #existsFor} alone reads that as "met", and the release is never deployed. So a request counts
   * here unless it points at a deployment carrying that flag:
   *
   * <ul>
   *   <li><b>a deployment that is not interrupted</b> — {@code ACTIVE}, {@code DECOMMISSIONED},
   *       {@code SUPERSEDED}, an ordinary {@code FAILED} — is an outcome somebody can read;
   *   <li><b>no deployment at all</b> ({@code deployment_id} null) is a refusal — the gate's, or a
   *       release that declares no spec — and a refusal is a decision too;
   *   <li><b>a deployment that has since gone</b> counts, conservatively: nothing says it was
   *       interrupted, and re-driving on an absence is how a version deploys twice;
   *   <li><b>an interrupted row that is no longer {@code FAILED}</b> counts too. The flag is set
   *       beside that word and nothing clears it, so a row something later moved — an operator's
   *       scale on the newest row of a place writes a status — answers by its status, which is
   *       the newer statement.
   * </ul>
   *
   * <p>Zero with {@link #existsFor} true is therefore exactly "asked for, and every attempt was cut
   * off by a restart" — the one shape the owed sweep may re-drive. Environment-blind for {@link
   * #existsFor}'s reason: the question is about the release.
   */
  public long countDecidedFor(String applicationName, String version) {
    return getEntityManager()
        .createQuery(
            "select count(r) from PdDeploymentRequest r"
                + " where r.applicationName = :applicationName and r.version = :version"
                + " and not exists (select d.id from PdDeployment d"
                + "   where d.id = r.deploymentId and d.interrupted = true and d.status = :failed)",
            Long.class)
        .setParameter("applicationName", applicationName)
        .setParameter("version", version)
        .setParameter("failed", PdDeploymentStatus.FAILED)
        .getSingleResult();
  }

  /**
   * The newest version this application was ever asked for, whatever the gate said and whatever
   * became of it.
   *
   * <p><b>It is the cross-restart floor of the monotonic collapse</b> ({@code ReleaseTips}), and it
   * is asked of the REQUESTS rather than of the deployments on purpose: a request is written the
   * moment a release is accepted, before the spec read and before anything is queued, so it records
   * "this version was seen here" even for a release that then failed to deploy. A floor read off
   * deployment rows alone would let a stale catch-up replay a version whose newer sibling never got
   * as far as a container.
   */
  public Optional<String> newestVersionOf(String applicationName) {
    return find("applicationName = ?1 order by seq desc", applicationName)
        .firstResultOptional()
        .map(request -> request.version);
  }
}
