package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.deployments.deployments.control.EnvironmentOperations;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.deployments.environments.dto.PdEnvironmentDto;
import eu.wohlben.qits.deployments.environments.dto.PdLinkedServiceDto;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.mapper.EnvironmentMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * The environment surface: creating a tier, renaming or retargeting it, tearing it down, the reads,
 * and the link query a reconciliation pulls.
 *
 * <p>A tier is created <b>deliberately</b>; what it holds is not. Service rows are derived from each
 * repository's {@code deployments.yml} on every green build, so this surface has no write for them
 * and gained none — {@link PdServiceController} is where the derived writes land.
 *
 * <p><b>The create and the delete have docker side effects, and that is why this component owns
 * them.</b> Creating a tier makes its bundle network; tearing one down reaps its containers and
 * removes its networks before the rows go. That composition lives in {@code EnvironmentOperations};
 * splitting it across two services is exactly what the merge undid.
 *
 * <p><b>The writes and the reads want different callers, and the roles say so.</b> The writer here
 * is a machine — the bootstrap and the deploy path — so the writes take {@code qits:system}, the
 * open calling model's "a service calling a service" role and the one qits-platform-idp puts in a
 * machine token's {@code groups} claim, and call {@link MachineAuth#require()} on top of it for the
 * audience. The reads are the opposite: a person drives them through qits-gateway's session and the
 * web client polls them, so they take {@code qits:admin}, which only a forwarded {@code
 * X-Qits-Roles} header carries. Neither caller can reach the other's half — {@code qits:system} and
 * {@code qits:admin} do not overlap. {@code MachineAuth} alone is gated off by {@code
 * qits.auth.machine.required} until qits-platform-idp grants this audience; the roles are not.
 * {@code qits:admin-agent} is admitted on the reads too (qits-628 follow-up): an ADMIN workspace's
 * coding agent carries it alongside {@code qits:agent}, and for now it may use everything {@code
 * qits:admin} may use.
 */
@Path("/environments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PdEnvironmentController {

  private static final Logger LOG = Logger.getLogger(PdEnvironmentController.class);

  @Inject MachineAuth machineAuth;
  @Inject EnvironmentOperations environments;
  @Inject ServiceCatalog catalog;
  @Inject EnvironmentMapper mapper;
  @Inject PdReadPatience reads;

  /**
   * One tracked application.
   *
   * @deprecated ignored — see {@link CreateEnvironmentRequest#applications()}.
   */
  @Deprecated
  public record ApplicationSpec(@NotBlank String repoId, @NotBlank String name, String healthPath) {}

  /**
   * The creation payload. {@code network} is a convention when omitted: {@code qits-env-<name>}.
   *
   * <p><b>There is no {@code branch}.</b> A tier listened to {@code environment/<name>} while a
   * green build was the deploy trigger; a release names a tag, so where a version lands is {@code
   * platform} and nothing else. An older sender's {@code branch} is simply not a field any more and
   * is ignored by the deserializer.
   *
   * <p>{@code applications} is <b>deprecated and ignored</b>, with a WARN so a sender finds out.
   * Service rows are derived from each repository's own {@code .config/qits/deployments.yml}, so
   * naming them here only pre-created what the next green build creates anyway — and the catalogue
   * holds one identity for a service (its name), so a {@code (repoId, name)} pair that disagrees
   * has nowhere to land. The field is still accepted so an older sender's payload keeps
   * deserializing; send nothing.
   *
   * <p>{@code platform} makes this the <b>platform environment</b> — the tier a release enters at,
   * and the tier the platform plane itself is deployed into. Omitted is false, and the bootstrap is
   * the sender that sets it: it creates the standing environment that {@code --platform-env} names.
   * Creating a tier without it on a database that has no platform environment yet is legal and logs
   * a warning, because a release would then deploy nowhere and say nothing.
   */
  public record CreateEnvironmentRequest(
      @NotBlank String name,
      String network,
      Boolean designated,
      @Deprecated Boolean platform,
      @Deprecated List<@Valid ApplicationSpec> applications) {

    /**
     * The designation, under either spelling, {@code designated} winning.
     *
     * <p>{@code platform} is the retired name and is still ACCEPTED because the senders are
     * released separately from this component — qits-bootstrap-cli asserts it on the tier it
     * creates at cold boot, and a bootstrap older than this build states nothing else. Refusing it
     * would make this component undeployable by the very thing that installs it.
     */
    Boolean effectiveDesignation() {
      return designated != null ? designated : platform;
    }
  }

  /**
   * The rename/designate payload — every field optional, an omitted one is left alone. This is how
   * an environment is renamed and how the platform designation moves between tiers.
   *
   * <p>{@code platform: true} moves the designation to this environment. {@code false} is refused
   * (409): the platform plane always has a tier to deploy into, so the designation is moved, never
   * dropped.
   */
  public record UpdateEnvironmentRequest(
      String name, Boolean designated, @Deprecated Boolean platform) {

    /** The designation, under either spelling — see {@link CreateEnvironmentRequest}. */
    Boolean effectiveDesignation() {
      return designated != null ? designated : platform;
    }
  }

  public record EnvironmentResponse(PdEnvironmentDto environment) {}

  public record ListEnvironmentsResponse(List<PdEnvironmentDto> environments) {}

  public record ListLinksResponse(List<PdLinkedServiceDto> services) {}

  @POST
  @Operation(summary = "Create an environment: a name and a bundle network")
  @APIResponse(responseCode = "201", description = "Created; a release may now be deployed into it")
  @APIResponse(responseCode = "400", description = "A name or network failed validation")
  @APIResponse(responseCode = "409", description = "An environment of that name already exists")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed({"qits:system"})
  public Response create(@Valid CreateEnvironmentRequest request) {
    machineAuth.require();
    if (request.applications() != null && !request.applications().isEmpty()) {
      LOG.warnf(
          "Ignoring %d declared application(s) on the creation of environment %s — applications are"
              + " derived from each repository's deployments.yml on its next green build",
          request.applications().size(), request.name());
    }
    PdEnvironment environment =
        environments.create(
            request.name(), request.network(), Boolean.TRUE.equals(request.effectiveDesignation()));
    return Response.status(Response.Status.CREATED).entity(toResponse(environment)).build();
  }

  /**
   * Rename an environment, or designate it the platform one. <b>No docker side effects</b> — a
   * rename that tore containers down would be a delete in disguise, and delete is the one thing
   * never to reach for on a live environment. The bundle network is not renamed either: dev's is
   * {@code qits-net} by design. The next deployment of each application moves it onto the networks
   * the new name derives; what runs now keeps running.
   *
   * <p>Moving the platform designation has none either, for the same reason plus one of its own: a
   * platform service keeps its bare wire alias, so a peer reaches it under the same name whichever
   * tier is designated. What the move changes is the tier the plane's next deployment names.
   */
  @PATCH
  @Path("/{environmentId}")
  @Operation(summary = "Rename an environment, or designate it the platform environment")
  @APIResponse(responseCode = "200", description = "The updated environment")
  @APIResponse(responseCode = "400", description = "A name failed validation")
  @APIResponse(responseCode = "404", description = "No such environment")
  @APIResponse(
      responseCode = "409",
      description = "Another environment already has that name, or the platform designation was cleared rather than moved")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed({"qits:system"})
  public EnvironmentResponse update(
      @PathParam("environmentId") String environmentId, UpdateEnvironmentRequest request) {
    machineAuth.require();
    PdEnvironment environment =
        environments.update(
            environmentId,
            request == null ? null : request.name(),
            request == null ? null : request.effectiveDesignation());
    return toResponse(environment);
  }

  /**
   * All environments, newest-first, without their applications (fetch one for the full shape).
   *
   * <p>Held through a short database outage rather than answering 500 — see {@link PdReadPatience}.
   * Every read on this surface is; no write is.
   */
  @GET
  @Operation(summary = "List environments")
  // The 200 is spelled out because declaring ANY response suppresses the generated one, and this
  // operation had only the generated one — leaving it off would drop the schema from the document.
  @APIResponse(responseCode = "200", description = "The environments")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  public ListEnvironmentsResponse list() {
    return new ListEnvironmentsResponse(
        reads.call("The environment listing", environments::list).stream()
            .map(mapper::toDto)
            .toList());
  }

  @GET
  @Path("/{environmentId}")
  @Operation(summary = "One environment with the applications it tracks")
  @APIResponse(responseCode = "200", description = "The environment")
  @APIResponse(responseCode = "404", description = "No such environment")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  public EnvironmentResponse get(@PathParam("environmentId") String environmentId) {
    // Both reads inside one bracket — the tier row and the applications it holds are one answer,
    // and a cutover between them would fail the half that ran second.
    return reads.call(
        "The read of environment " + environmentId,
        () -> toResponse(environments.require(environmentId)));
  }

  /**
   * <b>The pull query.</b> Every service present in this environment: the ones linked into it, then
   * every platform service — which is what a new environment picks up without anyone linking
   * anything.
   *
   * <p>It is what a reconciliation compares against the docker labels on the host — the shared
   * runtime truth — before connecting what is missing. It differs from the environment aggregate
   * above deliberately: that one is the tier's own services, this one composes the platform plane
   * in, and a reader that took the aggregate for the answer would silently miss qits-platform-idp.
   */
  @GET
  @Path("/{environmentId}/links")
  @Operation(summary = "Every service present in this environment: its links, plus every platform service")
  @APIResponse(responseCode = "200", description = "The services present in this environment")
  @APIResponse(responseCode = "404", description = "No such environment")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  public ListLinksResponse links(@PathParam("environmentId") String environmentId) {
    return new ListLinksResponse(
        reads
            .call(
                "The link query of environment " + environmentId,
                () -> catalog.linksOf(environmentId))
            .stream()
            .map(mapper::toLinkDto)
            .toList());
  }

  /**
   * Tear the environment down: its recorded deployments, its containers, its networks, and last the
   * tier itself. 204 — after this the tier holds nothing.
   *
   * <p>The platform environment is refused: a release would enter nowhere and the platform plane
   * would have no tier to deploy into. Designate another environment first.
   */
  @DELETE
  @Path("/{environmentId}")
  @Operation(summary = "Tear an environment down (rows, containers, networks)")
  @APIResponse(responseCode = "204", description = "Torn down")
  @APIResponse(responseCode = "404", description = "No such environment")
  @APIResponse(responseCode = "409", description = "This is the platform environment")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed({"qits:system"})
  public Response delete(@PathParam("environmentId") String environmentId) {
    machineAuth.require();
    environments.delete(environmentId);
    return Response.noContent().build();
  }

  private EnvironmentResponse toResponse(PdEnvironment environment) {
    return new EnvironmentResponse(
        mapper.toDto(environment, catalog.applicationsOf(environment)));
  }
}
