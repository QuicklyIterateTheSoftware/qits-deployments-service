package eu.wohlben.qits.deployments.deployments.persistence;

import eu.wohlben.qits.deployments.deployments.entity.PdOwedRelease;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link PdOwedRelease}, the acceptance ledger.
 *
 * <p>Two questions and no more: "is this event already accepted" (the upsert key, so accepting is
 * idempotent across restarts) and "what is owed by somebody who is not here any more" (the sweep).
 * Both are index-backed by V10 — the unique {@code event_id} and the partial index over the unsettled
 * rows.
 *
 * <p><b>And one statement, which is not a third question.</b> {@link #renameRepository} corrects the
 * ADDRESS these rows record when qits-projects renames the repository underneath them; it reads
 * nothing and decides nothing, and its whole argument lives one layer up in {@code
 * ReleaseAcceptance}, which owns the transaction it must run in.
 */
@ApplicationScoped
public class PdOwedReleaseRepository implements PanacheRepositoryBase<PdOwedRelease, String> {

  /** The obligation this event already opened, if it opened one. */
  public Optional<PdOwedRelease> findByEventId(String eventId) {
    return find("eventId = ?1", eventId).firstResultOptional();
  }

  /**
   * Everything still owed that <b>this</b> process is not holding, oldest first.
   *
   * <p>The negation is the whole predicate and it is exact rather than heuristic: a row carrying
   * this process's id is on its worker queue — legitimately for an hour, since deployments are
   * serialized platform-wide — and a row carrying anybody else's belonged to a queue that did not
   * survive its JVM. A null {@code accepted_by} is the worker's own hand-back and is included, which
   * is what lets a failed discharge be retried without a restart.
   *
   * <p>{@code seq desc} is what every other listing here orders by; this one is the exception and
   * ascends, because owed work is re-driven in the order it arrived — so two releases of one
   * application replay in their real order and the monotonic collapse sees the newer one last.
   */
  public List<PdOwedRelease> listOwedByOthers(String instanceId) {
    return list(
        "settledAt is null and (acceptedBy is null or acceptedBy <> ?1) order by seq asc",
        instanceId);
  }

  /**
   * Every repository name a release of this package was accepted from, settled or not, distinct
   * and sorted; rows that carry no name are left out. What a decommission adds to the repositories
   * it asks qits-artifacts to purge (qits-1941).
   */
  public List<String> repositoryNamesOfPackage(String packageName) {
    return getEntityManager()
        .createQuery(
            "select distinct o.repoName from PdOwedRelease o where o.packageName = :packageName"
                + " and o.repoName is not null order by o.repoName",
            String.class)
        .setParameter("packageName", packageName)
        .getResultList();
  }

  /**
   * Move every obligation of one repository onto its new public name, and answer how many moved.
   *
   * <p><b>A bulk JPQL update rather than a load-and-set loop</b>, because it is a statement about a
   * column and not about a set of entities: nothing here reads a row, decides anything from it, or
   * needs it managed. That is also what makes it converge — replaying the same rename lands on the
   * same values — which is the property {@code PdRepositoryRenamedSubscriber.replayFromEpoch()}
   * rests on.
   *
   * <p><b>Two statements, chosen by whether the event carried a project id.</b> {@code project_id}
   * is filled with {@code coalesce}, so a row that already names one keeps it and only a null is
   * written — see {@code ReleaseAcceptance.renameRepository} for why a rename is not a move. An
   * event with no project id can fill nothing in, so it says only what it knows and the {@code
   * coalesce} is left out entirely rather than handed a null to infer a type from.
   */
  public int renameRepository(String repositoryId, String projectId, String newName) {
    if (projectId == null || projectId.isBlank()) {
      return getEntityManager()
          .createQuery("update PdOwedRelease set repoName = :newName where repoId = :repoId")
          .setParameter("newName", newName)
          .setParameter("repoId", repositoryId)
          .executeUpdate();
    }
    return getEntityManager()
        .createQuery(
            "update PdOwedRelease set repoName = :newName,"
                + " projectId = coalesce(projectId, :projectId) where repoId = :repoId")
        .setParameter("newName", newName)
        .setParameter("projectId", projectId)
        .setParameter("repoId", repositoryId)
        .executeUpdate();
  }
}
