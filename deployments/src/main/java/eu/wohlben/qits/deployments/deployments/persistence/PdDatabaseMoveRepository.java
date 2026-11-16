package eu.wohlben.qits.deployments.deployments.persistence;

import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/** Panache DAO for {@link PdDatabaseMove}. Every listing is newest first, by creation. */
@ApplicationScoped
public class PdDatabaseMoveRepository implements PanacheRepositoryBase<PdDatabaseMove, String> {

  public List<PdDatabaseMove> listAll(int limit) {
    return find("order by createdAt desc, id asc").page(0, limit).list();
  }

  public List<PdDatabaseMove> listByApplication(String applicationName, int limit) {
    return find("applicationName = ?1 order by createdAt desc, id asc", applicationName)
        .page(0, limit)
        .list();
  }

  /** Every move a previous process left in a word it never leaves — the boot sweep's read. */
  public List<PdDatabaseMove> listUnfinished() {
    return list(
        "state not in ?1 order by createdAt asc, id asc", List.copyOf(PdDatabaseMoveState.TERMINAL));
  }

  /** Every move one deployment's run wrote (V24, qits-2249), oldest first. */
  public List<PdDatabaseMove> listByDeployment(String deploymentId) {
    return list("deploymentId = ?1 order by createdAt asc, id asc", deploymentId);
  }

  /**
   * The moves of an application in a tier that are under way and not an operator's {@code QUEUED}
   * one — a stop, a copy, a switch awaiting its deployment, a rollback owed (qits-2249). What a new
   * deployment of it and the start door wait behind.
   */
  public List<PdDatabaseMove> listUnderWay(String applicationName, String environmentName) {
    return list(
        "applicationName = ?1 and environmentName = ?2 and state not in ?3 order by createdAt asc, id asc",
        applicationName,
        environmentName,
        java.util.stream.Stream.concat(
                PdDatabaseMoveState.TERMINAL.stream(), java.util.stream.Stream.of(PdDatabaseMoveState.QUEUED))
            .toList());
  }

  /** The kept copies whose retention is over and that nothing has dropped yet. */
  public List<PdDatabaseMove> listExpired(Instant now) {
    return list(
        "state = ?1 and keptDatabase is not null and droppedAt is null and dropAfter <= ?2"
            + " order by dropAfter asc, id asc",
        PdDatabaseMoveState.MOVED,
        now);
  }

  /**
   * Whether an earlier ABORTED move of this claim left the same target behind — "this move's own
   * leftover", the one existing target database a move may replace.
   */
  public boolean abortedBefore(
      String applicationName,
      String environmentName,
      String resourceName,
      String targetServer,
      String targetDatabase) {
    return count(
            "applicationName = ?1 and environmentName = ?2 and resourceName = ?3"
                + " and targetServer = ?4 and targetDatabase = ?5 and state = ?6",
            applicationName,
            environmentName,
            resourceName,
            targetServer,
            targetDatabase,
            PdDatabaseMoveState.ABORTED)
        > 0;
  }
}
