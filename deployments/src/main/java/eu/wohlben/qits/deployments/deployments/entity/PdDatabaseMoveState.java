package eu.wohlben.qits.deployments.deployments.entity;

import java.util.Set;

/**
 * Where one database move is (qits-2175) — {@code pd_database_move.state}, a {@code varchar(32)}
 * with no check constraint, V1's parity rule: the vocabulary is this enum's.
 *
 * <p><b>The line that matters is {@link #SWITCHING}.</b> Everything before it is undone by an abort —
 * the target dropped, the source re-opened, the application scaled back up on data nothing touched.
 * From it on the claim already points at the target, so the only safe direction is forward, and a
 * restart that finds a row there finishes the move rather than unwinding it.
 */
public enum PdDatabaseMoveState {
  /** An operator's move, written by the door and waiting for its redeploy to reach provisioning. */
  QUEUED,
  /** The application's service is being scaled to zero and the source closed to connections. */
  STOPPING,
  /**
   * A move between two names on one server (qits-2193): the source database and its role being
   * renamed to the target in place — no extract, no ingest. An abort renames it back.
   */
  RENAMING,
  /** {@code pg_dump} of the source, and its per-table counts, into the move's volume. */
  EXTRACTING,
  /** The target role and database created, the dump restored, the target's counts read. */
  INGESTING,
  /** The two manifests agreed; waiting for the batch's other moves before anything switches. */
  VERIFIED,
  /** The claim points at the target; the source is being renamed aside and the volume removed. */
  SWITCHING,
  /**
   * Done: the claim is the target, the source is kept closed until {@code drop_after} — or, for a
   * move renamed in place, there is no copy to keep ({@code kept_database} and {@code drop_after}
   * null): the way back is a rename back.
   */
  MOVED,
  /** Undone: the target dropped, the source re-opened, the service scaled back. {@code detail} says why. */
  ABORTED,
  /** Refused before anything stopped — or an operator's move whose redeploy never reached it. */
  REFUSED;

  /** The words a move never leaves. */
  public static final Set<PdDatabaseMoveState> TERMINAL = Set.of(MOVED, ABORTED, REFUSED);

  /** Whether the claim may already point at the target — the restart's resume-or-abort line. */
  public boolean switched() {
    return this == SWITCHING || this == MOVED;
  }
}
