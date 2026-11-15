package eu.wohlben.qits.deployments.deployments.control;

import org.jboss.logging.Logger;

/**
 * What a release said about being HELD before it deploys (qits-2268): whether its release request
 * was flagged "hold deploy", and which release request that was.
 *
 * <p>qits-projects flags the request, qits-ci forwards {@code deployHold} and {@code
 * releaseRequestQualifiedId} on {@code SoftwareRelease}, and the quality gate ({@code
 * DeployService.gate}) answers {@code UNMET} for a held release until a person approves it through
 * {@code POST /deployment-requests/{id}/approve}.
 *
 * <p><b>Absence is "not held", forever.</b> {@code CanonicalJson} is {@code NON_NULL}, so every
 * release cut before the field existed, every replay of one, and every release that was not flagged
 * arrives with neither key — {@link #NONE}. Only an explicit {@code true} holds.
 *
 * <p><b>The qualified id is LENIENT, the {@link ReleasePriorities} stance</b>: it reaches a column
 * and a sentence, never an argv, so a value that will not fit is recorded as none with a WARN
 * rather than refusing the release. It is recorded whether or not the release is held — it says
 * which release request a deployment came from.
 */
public record DeployHold(boolean requested, String releaseRequestQualifiedId) {

  private static final Logger LOG = Logger.getLogger(DeployHold.class);

  /** The longest qualified id kept: the column is {@code text}, so this is a belt, not a width. */
  public static final int MAX_LENGTH = 255;

  /** A release that asked for no hold — every release before qits-2268. */
  public static final DeployHold NONE = new DeployHold(false, null);

  /**
   * The hold as a door read it: {@code deployHold} null or false is not held, and the qualified id
   * is trimmed, blank is none, and an overlong one is none with a WARN naming {@code what}.
   */
  public static DeployHold of(Boolean deployHold, String releaseRequestQualifiedId, String what) {
    String qid = releaseRequestQualifiedId == null ? null : releaseRequestQualifiedId.strip();
    if (qid != null && qid.isEmpty()) {
      qid = null;
    }
    if (qid != null && qid.length() > MAX_LENGTH) {
      LOG.warnf(
          "The release of %s names a release request of %d characters; it is recorded as none"
              + " rather than refusing the release",
          what, qid.length());
      qid = null;
    }
    boolean held = Boolean.TRUE.equals(deployHold);
    return !held && qid == null ? NONE : new DeployHold(held, qid);
  }

  /** The gate's sentence for a held release: {@code held for manual approval[ on <qid>]}. */
  public String detail() {
    return releaseRequestQualifiedId == null
        ? "held for manual approval"
        : "held for manual approval on " + releaseRequestQualifiedId;
  }
}
