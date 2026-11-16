package eu.wohlben.qits.deployments.deployments.entity;

import java.util.Set;

/**
 * Where one database move is (qits-2175) — {@code pd_database_move.state}, a {@code varchar(32)}
 * with no check constraint, V1's parity rule: the vocabulary is this enum's.
 *
 * <p><b>The line that matters is {@link #SWITCHING}.</b> Everything before it is undone by an abort —
 * the target dropped, the source re-opened, the application scaled back up on data nothing touched.
 * From it on the claim already points at the target, and a restart finishes the switch rather than
 * unwinding it half-way.
 *
 * <p><b>A switched move is not a moved one until its deployment is live</b> (qits-2249, the owner's
 * ruling of 2026-11-14: a deployment with a database move ends with the service running — live on the
 * target, or rolled back onto the source). So the switch ends in {@link #SWITCHED}, which is not
 * terminal: the cutover (or the startup sweep adopting the deployment as {@code ACTIVE}) writes
 * {@link #MOVED}; a deployment that ends any other way — or a deployer that restarts before it is
 * live — rolls it back through {@link #ROLLING_BACK} to {@link #ABORTED}, the service started again on
 * its source. Neither of the two is terminal, so every settle pass (boot and every observation tick)
 * sees a row a failure or a restart left in them and drives it on.
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
   * Switched (qits-2249): the claim is the target and the source is put aside, and the deployment
   * that moved it ({@code deployment_id}) is not live yet. Its cutover writes {@link #MOVED}; any
   * other end of it rolls the move back.
   */
  SWITCHED,
  /**
   * Done: the claim is the target and its deployment went live; the source is kept closed until
   * {@code drop_after} — or, for a move renamed in place, there is no copy to keep ({@code
   * kept_database} and {@code drop_after} null): the way back is a rename back.
   */
  MOVED,
  /**
   * Being undone after its switch (qits-2249), because the deployment that moved it did not go live:
   * the claim is put back onto the source (renamed back, or the kept source restored), then the
   * services the move stopped are started again on it. The claim names whichever side the data is on
   * at every moment, so a pass that finds the row here — a restart, a step that failed — resumes it
   * from where the claim says. {@code detail} names what is still owed.
   */
  ROLLING_BACK,
  /**
   * Undone: the target dropped, the source re-opened, the service scaled back — before the switch, or
   * rolled back after it with the service started again on the source. {@code detail} says why.
   */
  ABORTED,
  /** Refused before anything stopped — or an operator's move whose redeploy never reached it. */
  REFUSED;

  /** The words a move never leaves. */
  public static final Set<PdDatabaseMoveState> TERMINAL = Set.of(MOVED, ABORTED, REFUSED);

  /**
   * The words of a move whose deployment has not settled it yet, past its switch (qits-2249): what
   * the cutover confirms and what a pass rolls back when that deployment did not go live.
   */
  public static final Set<PdDatabaseMoveState> AWAITING_DEPLOYMENT = Set.of(SWITCHED, ROLLING_BACK);

  /** Whether the claim may already point at the target — the restart's resume-or-abort line. */
  public boolean switched() {
    return this == SWITCHING || this == SWITCHED || this == MOVED || this == ROLLING_BACK;
  }
}
