package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.deployments.deployments.control.ReleaseAnnouncements;
import eu.wohlben.qits.deployments.deployments.control.RepositoryRef;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The release intake — the <b>manual and bootstrap</b> door. {@code POST
 * /deployments/api/events/software-released} announces one released version of one
 * application, exactly as {@code bus/PdSoftwareReleaseSubscriber} does off qits-ci's {@code
 * SoftwareRelease}. The path carries no segment of its own because {@code
 * quarkus.rest.path=/deployments/api} already says it.
 *
 * <p><b>It replaces {@code /events/build-succeeded}, which is gone.</b> A green build is no longer a
 * reason to deploy anything, so a door that took {@code (branch, commitSha)} would be a second
 * coordinate system for the same cutover — and the one thing this epic must not leave behind is two
 * ways to put an application live. The old path now 404s; qits-ci's fire-and-forget POST to it was
 * already scheduled for retirement and this is that retirement.
 *
 * <p><b>What it is FOR, now that the bus is the ordinary door.</b> A bootstrap replays a release
 * nobody was listening for; an operator redeploys a version, or deliberately goes back one. That
 * last case is why this door is unguarded by the monotonic collapse the subscriber runs through:
 * an operator posting a version is choosing that version, and refusing a lower one would be
 * refusing the choice.
 *
 * <p><b>It is the {@link ReleaseAnnouncements.Door#MANUAL} door, and the same reasoning is why.</b>
 * A release whose tag carries no {@code .config/qits/deployments.yml} is recorded and not deployed
 * when the BUS announces it — that door hears every published image and a base image is not a
 * service. Here it still deploys with the defaults: one of the things this door is for is a tag cut
 * before the file existed anywhere, and somebody typed this version on purpose.
 *
 * <p>In the OpenAPI document as {@code softwareReleased}, so consumer pacts (qits-bootstrap-cli,
 * qits-projects-service) can name it. It is still a machine door: the role below says so.
 *
 * <p><b>{@code qits:system} and {@link MachineAuth#require()} are both here because nothing human
 * reaches this path</b> — its callers are machines and bootstraps, so a bearer is the only
 * credential one could ever hold, and the role is one an idp-minted token carries: under the open
 * calling model {@code qits:system} is a service calling a service. The reads next door take the
 * admin role no token has, and {@code qits:system} does not reach that role either — {@code
 * qits:system} and {@code qits:admin} do not overlap. That split is the rule, not a phasing.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:system"})
public class PdEventController {

  @Inject ReleaseAnnouncements announcements;
  @Inject MachineAuth machineAuth;

  /**
   * One released version of one application. The pair that matters is {@code (application,
   * version)} — the image is resolved from it by convention, the spec is read at the tag, and
   * everything after that is this component's.
   *
   * <p><b>{@code application} is optional and the fallback chain is the point.</b> The bus door
   * takes the application out of the released package ({@code qits/qits-ci} → {@code qits-ci});
   * this door lets a caller state it outright, and falls back to the repository's name and then to
   * its id — which is what a bootstrap that knows a repository and nothing else has to hand.
   *
   * <p><b>{@code projectId} and {@code repoName} are the repository's public address and both stay
   * OPTIONAL</b>: with both, the spec is read name-addressed; with either missing, through {@code
   * /git/<repoId>}, which is the route a release event itself takes.
   *
   * <p>{@code runId} is optional and drives nothing: it is recorded on each deployment this queues
   * so a reader can walk from a deployment row to {@code /ci/runs/<runId>}. A release event carries
   * none, so the ordinary path records null and this door exists to let a replay supply one.
   *
   * <p><b>{@code priority} is optional and drives nothing either</b> — it is the value the release
   * request declared, recorded on the request row for a reader and for the queue-ordering feature
   * that comes later. The deploy worker is FIFO, so naming a priority here reorders nothing and
   * omitting one refuses nothing: null is the honest answer for a replay of a release cut before
   * qits-projects had the field, which is most of what this door is used for.
   */
  public record SoftwareReleasedEvent(
      String runId,
      @NotBlank String repoId,
      String projectId,
      String repoName,
      String application,
      @NotBlank String version,
      String priority) {

    /**
     * What this release deploys under: what the caller stated, else the repository's name, else its
     * id — the pre-release fallback, byte for byte, for a bootstrap that has only a storage key.
     */
    String applicationName() {
      if (application != null && !application.isBlank()) {
        return application;
      }
      return repoName != null && !repoName.isBlank() ? repoName : repoId;
    }
  }

  /**
   * Accepts the release and returns immediately — deployments execute on the worker. 202 also when
   * no tier is designated to enter: that is a mid-bootstrap install rather than an error the
   * fire-and-forget sender could act on.
   *
   * <p>{@code require()} and not {@code requireProject(...)}: the event names a {@code repoId}, and
   * a repository is not a project. Holding a token minted for this component is the whole claim
   * this intake needs — it queues a deployment onto the tier this platform enters at, and which
   * tier that is is this component's own topology, not the caller's to name.
   *
   * <p>With the gate off this line returns at once and the endpoint accepts credential-free calls
   * from the platform's networks exactly as it did before.
   */
  @POST
  @Path("/software-released")
  @Operation(
      operationId = "softwareReleased",
      summary = "Announce one released version of one application; it deploys on the worker")
  @APIResponse(responseCode = "202", description = "Accepted; the deployment runs on the worker")
  @APIResponse(responseCode = "400", description = "No repoId or no version")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  public Response softwareReleased(@Valid SoftwareReleasedEvent event) {
    machineAuth.require();
    // The cause is read HERE, on the request thread, because that is the only place it exists:
    // CausationServerFilter restored it from the caller's X-Qits-Causation-Id before this method
    // ran, and everything after announce() is on pd-deploy-worker, where the ThreadLocal is gone.
    // Null for a hand-made bootstrap POST, which is a rootless deployment and not an error.
    announcements.announce(
        event.runId(),
        new RepositoryRef(event.repoId(), event.projectId(), event.repoName()),
        event.applicationName(),
        event.version(),
        null,
        event.priority(),
        CausationScope.current());
    return Response.accepted().build();
  }
}
