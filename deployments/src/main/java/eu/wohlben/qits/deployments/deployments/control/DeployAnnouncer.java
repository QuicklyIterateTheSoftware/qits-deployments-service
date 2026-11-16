package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.events.DeploymentActive;
import eu.wohlben.qits.deployments.events.DeploymentFailed;
import eu.wohlben.qits.deployments.events.DeploymentQueued;
import eu.wohlben.qits.deployments.events.DeploymentRequestDeclined;
import eu.wohlben.qits.deployments.events.DeploymentStarted;
import java.util.UUID;

/**
 * The port {@link DeployService} announces a deployment's lifecycle to the <b>platform at large</b>
 * through — the seam the event bus hangs off. This component has consumed the bus since wave 3 and
 * produced nothing; these four methods are the other direction.
 *
 * <p>An interface rather than a call so this module stays free of the bus and its transport: the
 * sole production implementation is {@code service/…/bus/DeployEventAnnouncer}. It is resolved via
 * {@code Instance} and <b>absent is a supported configuration</b> — a deployment with no qits-events
 * deploys exactly as before and announces nothing at all.
 *
 * <p><b>One port with four methods rather than four ports</b>, which is where this departs from
 * qits-ci. There, {@code RunAnnouncer} and {@code ReleaseAnnouncer} are separate because they make
 * different statements about different subjects — "a build passed" and "this package is installable"
 * — and a consumer may reasonably want one implementation and not the other. These four are one
 * statement made four times about <b>one deployment</b>, in a fixed order, and nothing could
 * implement three of them sensibly.
 *
 * <p><b>The methods take the event record, which is the other departure.</b> {@code RunAnnouncer}
 * takes six loose fields because it announces one event of six fields; four announcements of eight
 * to ten fields each would be thirty-odd positional strings across one interface, which is exactly
 * the shape a wrong-order bug hides in. The records are a plain data jar over {@code eventstream}
 * with no publish, no subscribe and no wire in them — the same narrowing that already lets this
 * module hold {@code CausedRow} — so building one here does not put the bus in a domain module.
 *
 * <p><b>{@code cause} is a parameter rather than an ambient value, and that is the whole reason it
 * is here.</b> {@code CausationScope} is a ThreadLocal and every one of these calls happens on
 * {@code pd-deploy-worker}, behind the queue hop the intake made, so the scope an implementation
 * could read would be empty. The cause therefore travels as data the way {@code runId} always has:
 * it is the {@code BuildSuccessful} that caused the deployment, read off the deployment's own
 * {@code causation_id}, and the implementation hands it to {@code publish(event, parent)} — where an
 * explicit non-null argument outranks the ambient context by design, precisely for this case. Null
 * is a rootless deployment (a hand-made bootstrap POST), which is a real answer rather than a gap.
 *
 * <p><b>An implementation must not throw and must not block the caller for long.</b> Every call site
 * wraps it in a try/catch as the belt, because a deployment's outcome must never change because an
 * announcement failed. The bus implementation's {@code publish()} is synchronous and never throws,
 * but it is not free — it is bounded by the publish timeout when qits-events is unreachable, after
 * which the outbox owns the event. That cost is paid on the deploy worker, between one deployment
 * and the next; anything slower than that does not belong behind this port.
 *
 * <p><b>What deliberately does NOT announce, yet.</b> {@link DeploymentObserver}'s later corrections
 * (an {@code ACTIVE} row demoted to {@code GONE}, a demoted row whose container is healthy after
 * all) and the startup sweep's verdicts — {@code ACTIVE} or {@code SUPERSEDED} — publish nothing:
 * they
 * restate a deployment's outcome minutes or hours later, and a consumer would need to know that the
 * second statement supersedes the first before either is useful. That is a second design and it is
 * not this one.
 */
public interface DeployAnnouncer {

  /** A deployment row was created. One call per row a build-succeeded event queued. */
  void onQueued(DeploymentQueued event, UUID cause);

  /** The worker picked the deployment up and moved it to {@code STARTING}. */
  void onStarted(DeploymentStarted event, UUID cause);

  /** The cutover was recorded: this deployment is what serves now. */
  void onActive(DeploymentActive event, UUID cause);

  /**
   * The deployment did not go live — {@code FAILED}, {@code IMAGE_MISSING} or {@code ROLLED_BACK},
   * carried on the event as a string. Nothing was cut over, and on a rollback the predecessor is
   * serving still.
   */
  void onFailed(DeploymentFailed event, UUID cause);

  /**
   * A person declined a held deployment REQUEST (qits-2271): its gate settled {@code UNMET} and
   * nothing was queued. The one method here whose subject is a request rather than a deployment —
   * a held request never had one. Called once per row the decline settled, after the commit.
   */
  void onDeclined(DeploymentRequestDeclined event, UUID cause);
}
