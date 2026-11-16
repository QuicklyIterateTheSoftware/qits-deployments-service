package eu.wohlben.qits.deployments.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.deployments.deployments.control.DeployAnnouncer;
import eu.wohlben.qits.deployments.events.DeploymentActive;
import eu.wohlben.qits.deployments.events.DeploymentFailed;
import eu.wohlben.qits.deployments.events.DeploymentQueued;
import eu.wohlben.qits.deployments.events.DeploymentRequestDeclined;
import eu.wohlben.qits.deployments.events.DeploymentStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Hands a deployment's four lifecycle events to {@link QitsEventBus}. The <b>producing</b> end of
 * this service's event-bus wiring; {@link PdBuildSuccessfulSubscriber} is the consuming one, and
 * until this class existed there was only the second.
 *
 * <p>It lives in {@code bus/} because the {@code deployments} module knows nothing of the bus; the
 * seam it implements is {@link DeployAnnouncer} in {@code deployments/control}, and zero
 * implementations is a supported configuration.
 *
 * <p><b>The chain this closes.</b> A push publishes {@code SCMPublishCommit}, qits-ci publishes
 * {@code BuildSuccessful} naming it, this component consumes that and records the causing event id
 * on the deployment row — and there the chain stopped, because a deployment announced nothing. The
 * {@code cause} each method takes is that recorded id, handed to {@code publish(event, parent)} as
 * an explicit argument, so a commit and the container it ended up in are one walk through the log.
 *
 * <p><b>It is handed a {@code UUID} and parses nothing</b>, which is the one place this differs from
 * qits-ci's {@code CausingEvent}. Over there the trigger id is a {@code varchar} on the run row and
 * a bad value has to cost the edge and nothing else. Here the column is a {@code uuid} and the
 * leniency already happened one layer up — {@code PdBuildSuccessfulSubscriber} parses the frame id
 * defensively before the value ever reaches a row — so a second defensive parse would guard against
 * nothing the type allows.
 *
 * <p><b>It does not throw and does not block for long.</b> {@link QitsEventBus#publish} never throws
 * and attempts the idempotent PUT inline, bounded by {@code qits.eventstream.publish-timeout};
 * anything past that belongs to the outbox. The caller is {@code pd-deploy-worker}, so an
 * unreachable qits-events costs a deploy slot those few seconds per announcement and nothing after.
 * {@code DeployService} wraps every call anyway — see {@link DeployAnnouncer}.
 */
@ApplicationScoped
public class DeployEventAnnouncer implements DeployAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  @ActivateRequestContext
  public void onQueued(DeploymentQueued event, UUID cause) {
    publish(event, cause);
  }

  @Override
  @ActivateRequestContext
  public void onStarted(DeploymentStarted event, UUID cause) {
    publish(event, cause);
  }

  @Override
  @ActivateRequestContext
  public void onActive(DeploymentActive event, UUID cause) {
    publish(event, cause);
  }

  @Override
  @ActivateRequestContext
  public void onFailed(DeploymentFailed event, UUID cause) {
    publish(event, cause);
  }

  @Override
  @ActivateRequestContext
  public void onDeclined(DeploymentRequestDeclined event, UUID cause) {
    publish(event, cause);
  }

  /**
   * Every method is a {@code @ActivateRequestContext} around this one line, and the annotation is
   * the point.
   *
   * <p>A deployment runs on {@code pd-deploy-worker}, a bare daemon thread with no request context
   * bound — the same shape {@code ScmEventAnnouncer} carries for a Vert.x worker. The outbox opens
   * its own transaction when the inline PUT does not land, and this is what gives it a context to
   * open one in. Inside a {@code @QuarkusTest} the context is already active and the annotation is a
   * no-op, which is why its absence would not be caught by a suite that drives the REST door.
   *
   * <p>It sits on the four methods rather than on this private one because an interceptor binding
   * only applies to a call that crosses the bean's proxy, and a {@code this.} invocation never does.
   */
  private void publish(QitsEvent event, UUID cause) {
    bus.publish(event, cause);
  }
}
