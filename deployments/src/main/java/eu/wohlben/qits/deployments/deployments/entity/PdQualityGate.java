package eu.wohlben.qits.deployments.deployments.entity;

/**
 * Whether a {@link PdDeploymentRequest} may become a deployment.
 *
 * <p><b>Two words, and the gate writes both.</b> {@link #MET} is every release that asked for
 * nothing more, the moment it is written — a released version has already passed qits-ci's own
 * pipeline. {@link #UNMET} is a release that declared no spec (refused, settled) and one whose
 * release request asked to be HELD for a person's approval (qits-2268: pending, {@code
 * gate_settled_at} null until {@code POST /deployment-requests/{id}/approve} answers it).
 *
 * <p><b>It is a varchar with no check constraint</b> (V1's rule for every enum column in this
 * schema), so a third word costs no migration. Whoever adds one adds it here first: this enum owns
 * validity at every Java write path, and the database deliberately enumerates nothing.
 */
public enum PdQualityGate {

  /**
   * Nothing has decided yet, or something decided no. The request stands and no deployment was
   * queued for it; {@code gate_detail} says which of the two and why, and {@code gate_settled_at}
   * tells them apart — null while a held request waits for its approval.
   */
  UNMET,

  /**
   * The request may proceed, and a deployment was queued for it — immediately, or when a person
   * approved a held request.
   */
  MET
}
