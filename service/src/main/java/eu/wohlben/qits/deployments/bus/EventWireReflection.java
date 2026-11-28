package eu.wohlben.qits.deployments.bus;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.deployments.events.ApplicationRollbackRequested;
import eu.wohlben.qits.deployments.events.DeploymentActive;
import eu.wohlben.qits.deployments.events.DeploymentEndpoint;
import eu.wohlben.qits.deployments.events.DeploymentFailed;
import eu.wohlben.qits.deployments.events.DeploymentQueued;
import eu.wohlben.qits.deployments.events.DeploymentRequestDeclined;
import eu.wohlben.qits.deployments.events.DeploymentStarted;
import eu.wohlben.qits.deployments.events.EnvironmentChanged;
import eu.wohlben.qits.deployments.events.EnvironmentCreated;
import eu.wohlben.qits.deployments.events.EnvironmentDeleted;
import eu.wohlben.qits.deployments.events.NavigationEntry;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type the event bus binds JSON to. A class with no
 * code, the {@code ApiWireReflection} arrangement applied to the other wire this service has.
 *
 * <p><b>Nothing registers these automatically, and the reason is deliberate on the library's
 * side.</b> {@code CanonicalJson} builds its own {@code ObjectMapper} rather than injecting the CDI
 * one — the payload string is a byte-for-byte wire contract and a consuming application's
 * customizers must not be able to reach it — so the graph that mapper binds is invisible to the
 * build step that scans for what needs reflecting on. On a JVM these records bind whether anyone
 * registered them or not, which is exactly what makes the omission survive a green suite: the
 * failure is in the binary, at runtime, on the first frame.
 *
 * <p>The CONSUMING path is two channels rather than one:
 *
 * <ul>
 *   <li>{@link EventFrame} — a live frame off {@code /events/stream}, and also every row of the
 *       catch-up log, which binds to the same record.
 *   <li>{@code EventPage} — one page of {@code GET /events/api/events}, by string name because it
 *       is package-private in the library (no consumer holds one; the sweeper does). Without it the
 *       stream works in the binary and <b>catch-up alone</b> fails — the half that only matters
 *       after a cutover, which is the half nobody would be watching.
 *   <li>{@link PdSoftwareReleaseSubscriber.SoftwareReleasePayload} — the payload this component
 *       reads out of the frame.
 *   <li>{@link PdRepositoryRenamedSubscriber.RepositoryRenamedPayload} — qits-projects' rename, the
 *       second payload this component decodes. It is the one whose omission would be hardest to
 *       see: the consumer that reads it replays from the epoch, so a binary missing this entry
 *       fails on its very first catch-up page and then on every rename after it, correcting an
 *       address nobody is watching until a release holds sixty minutes at a URL nothing serves.
 * </ul>
 *
 * <p><b>The PUBLISHING path joined it when this component got events of its own to announce</b>, and
 * it is the four lifecycle records, their nested endpoint record, and a pair from the library:
 *
 * <ul>
 *   <li>{@link DeploymentQueued}, {@link DeploymentStarted}, {@link DeploymentActive}, {@link
 *       DeploymentEndpoint}, {@link NavigationEntry} and {@link DeploymentFailed} — what {@code
 *       DeployEventAnnouncer} serializes. Unregistered, the binary publishes an empty payload rather than failing.
 *   <li>{@link DeploymentRequestDeclined} — a held deployment REQUEST a person declined
 *       (qits-2271), also serialized by {@code DeployEventAnnouncer}.
 *   <li>{@link ApplicationRollbackRequested} — a person's rollback of one application, accepted
 *       (qits-2779), serialized by {@code DeployEventAnnouncer} on the door's request thread.
 *   <li>{@link EnvironmentCreated}, {@link EnvironmentChanged} and {@link EnvironmentDeleted} — a
 *       TIER's lifecycle rather than a deployment's, what {@code EnvironmentEventAnnouncer}
 *       serializes. They fail the way the four above would, and quieter: an environment is created
 *       once and renamed rarely, so an empty payload here is a frame nobody is watching at the
 *       moment it is published, read weeks later by a consumer that has no way to tell it apart
 *       from an environment that never existed.
 *   <li>{@link EventEnvelope} — the wrapper every publish is sent as.
 *   <li>The {@code CanonicalJson$QitsEventMixin}, by string name because it is a nested type inside
 *       the library. <b>This is the quiet one</b>, and qits-ci paid for it: the mix-in is what keeps
 *       {@code eventId} out of the payload, so its absence is a payload carrying an id it is
 *       contractually supposed to omit — no crash, no log, and every consumer reading a wire
 *       contract that silently changed.
 * </ul>
 */
@RegisterForReflection(
    targets = {
      EventFrame.class,
      PdSoftwareReleaseSubscriber.SoftwareReleasePayload.class,
      PdRepositoryRenamedSubscriber.RepositoryRenamedPayload.class,
      EventEnvelope.class,
      DeploymentQueued.class,
      DeploymentStarted.class,
      DeploymentActive.class,
      DeploymentEndpoint.class,
      NavigationEntry.class,
      DeploymentFailed.class,
      DeploymentRequestDeclined.class,
      ApplicationRollbackRequested.class,
      EnvironmentCreated.class,
      EnvironmentChanged.class,
      EnvironmentDeleted.class
    },
    classNames = {
      "eu.wohlben.qits.eventstream.control.EventPage",
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"
    })
final class EventWireReflection {

  private EventWireReflection() {}
}
