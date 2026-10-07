package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.DeployService.RequestWithDeployment;
import eu.wohlben.qits.deployments.deployments.control.EnvironmentOperations;
import eu.wohlben.qits.deployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.deployments.deployments.dto.PdDeploymentRequestDto;
import eu.wohlben.qits.deployments.deployments.mapper.DeploymentMapper;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The deployment REQUEST read surface: what a released version asked this platform for, what the
 * quality gate said about it, and what became of it.
 *
 * <p><b>Why it is not the deployment listing.</b> A request that the gate refused produced no
 * deployment at all — no container, no row, no status word — so it exists nowhere in {@code GET
 * /deployments} and a client reading only that listing would show a release as having simply not
 * happened. This is the listing in which "asked for and declined, because …" is a thing that can be
 * seen. Today the placeholder gate says yes to everything, which is precisely why the surface goes
 * in now: the readers of a refusal have to exist before the first gate has an opinion.
 *
 * <p><b>Three filters, asked in order, and the order is the API.</b> A request is one row reached by
 * three different questions, and each of them belongs to a different screen:
 *
 * <ol>
 *   <li><b>{@code ?environmentId=}</b> — the tier listing, unchanged. Everything asked for here,
 *       newest first, optionally narrowed by {@code &applicationName=}. A missing tier is a 404
 *       rather than an empty list, and the word {@code platform} is not accepted (below).
 *   <li><b>{@code ?projectId=}</b> — one project's releases: every request still moving, plus the
 *       ten most recent that are not. The cap is the server's (see {@code
 *       DeployService.projectDeploymentRequests}).
 *   <li><b>{@code ?repoId=&version=}</b> — the exact-match join a release page follows the other
 *       way. <b>Both or neither</b>: half the pair is a 400, because a lone {@code repoId} would
 *       silently answer with a repository's whole history and a lone {@code version} with every
 *       repository's.
 * </ol>
 *
 * <p>With none of them, 400 — an unscoped listing would return every request on the instance.
 *
 * <p><b>An unknown PROJECT is an empty 200 and a missing TIER is a 404, and the asymmetry is the
 * honest one.</b> A tier is this component's own row: it either exists here or the caller named
 * something that does not, and saying so is a real answer. A project is qits-projects' row and this
 * component holds none — {@code project_id} is a foreign identity stored beside {@code repo_id} and
 * resolved by nobody — so "no request here carries that project" is all this surface can truthfully
 * say, and it is exactly what an empty list says. A 404 would be claiming knowledge of a catalogue
 * this service does not read.
 *
 * <p><b>There is no {@code ?environmentId=platform}.</b> Its sibling accepts that word because a
 * deployment records which plane it is on; a request does not — it is written before the catalogue
 * is consulted — and a platform service's request names the main environment exactly as its
 * deployment does. So the plane's requests come back in that tier's listing, which is where a reader
 * asking "what has been asked for in dev" wants them, and a word this table cannot answer for is not
 * accepted rather than answered emptily.
 *
 * <p><b>{@code GET /deployment-requests/{id}} inlines the deployment.</b> The detail screen needs
 * the request and the row it handed off to, and this component deliberately has no
 * deployment-by-id endpoint — the deployments listing has always been tier-scoped, and adding a
 * second door onto {@code pd_deployment} to serve one screen would be a wider surface than the
 * screen is. So the deployment travels inside this answer, nullable for the refusal that produced
 * none.
 *
 * <p><b>Read-only, and asked by three kinds of caller: {@code qits:admin}, {@code qits:agent} and
 * — since qits-projects drew the pipeline — {@code qits:system}.</b> The first is the role
 * qits-gateway forwards for a person's session, like every other listing the web client polls; the
 * second is an agent's own bearer. The third is a platform PEER: qits-projects draws a release
 * request as one pipeline of three phases — QA (a qits-ci run), publish (a qits-ci run), and
 * deploy, which is this service's deployment request for a (repoId, version) pair — and it draws
 * the third phase by reading {@code ?repoId=&version=}, the filter above, unchanged. That service
 * is not a person and not an agent: its service client carries {@code qits:system}, so without the
 * grant its only way in would be to forward a person's {@code X-Qits-User}/{@code X-Qits-Roles}
 * session headers — a service impersonating a user to read a listing it is itself entitled to.
 *
 * <p><b>It changes who may ask and nothing about what is answered.</b> The non-overlap the two
 * role sets keep is that no token holds both: a machine bearer still never carries {@code
 * qits:admin} and a browser session still never carries {@code qits:system}. Nothing here grants a
 * machine token the person's role, and the operator's levers stay exactly where they were —
 * {@code POST /applications/{id}/scale}, {@code /restart} and {@code /decommission} on {@code
 * PdApplicationController} are {@code qits:admin}-only, because nothing on the platform should be
 * able to stop or retire an application as a side effect of holding a service token. What this
 * listing does is join {@code GET /pins} as a guarded READ a machine peer may also ask.
 *
 * <p>{@code qits:admin-agent} is admitted too (qits-628 follow-up): an ADMIN workspace's coding
 * agent carries it alongside {@code qits:agent}, and for now it may use everything {@code
 * qits:admin} may use.
 */
@Path("/deployment-requests")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent", "qits:system"})
public class PdDeploymentRequestController {

  @Inject DeployService deployService;
  @Inject EnvironmentOperations environments;
  @Inject DeploymentMapper mapper;
  @Inject PdReadPatience reads;

  public record ListDeploymentRequestsResponse(List<PdDeploymentRequestDto> deploymentRequests) {}

  /**
   * One request and the deployment it produced, in one answer.
   *
   * <p>{@code deployment} is null for a request that queued nothing — a refusal, or a row an
   * environment teardown has since forgotten. It is inlined here rather than fetched from a
   * deployment-by-id endpoint because there is no such endpoint and minting one to serve this screen
   * would put a second, unscoped door onto the deployment table.
   */
  public record DeploymentRequestResponse(
      PdDeploymentRequestDto deploymentRequest, PdDeploymentDto deployment) {}

  @GET
  @Operation(
      summary =
          "Deployment requests, newest-first — by environment, by project, or by (repository,"
              + " version)")
  @APIResponse(responseCode = "200", description = "The requests")
  @APIResponse(responseCode = "400", description = "No filter, or half of the repoId/version pair")
  @APIResponse(responseCode = "404", description = "No such environment")
  public ListDeploymentRequestsResponse list(
      @QueryParam("environmentId") String environmentId,
      @QueryParam("applicationName") String applicationName,
      @QueryParam("projectId") String projectId,
      @QueryParam("repoId") String repoId,
      @QueryParam("version") String version) {
    if (stated(environmentId)) {
      // Ordered, and held through a short database outage for the deployment listing's reason: a
      // lost connection here would turn "which releases were asked for in this tier" into a 404 for
      // a tier that exists.
      reads.run("The tier check for " + environmentId, () -> environments.require(environmentId));
      return listOf(
          reads.call(
              "The deployment requests of " + environmentId,
              () -> deployService.deploymentRequestsFor(environmentId, applicationName)));
    }
    if (stated(projectId)) {
      // No existence check, and none is possible: this component holds no project rows. An
      // unknown project is an empty list rather than a 404 — see the class javadoc.
      return listOf(
          reads.call(
              "The deployment requests of project " + projectId,
              () -> deployService.projectDeploymentRequests(projectId)));
    }
    if (stated(repoId) || stated(version)) {
      if (!stated(repoId) || !stated(version)) {
        throw new BadRequestException(
            "repoId and version are asked together — a release is a (repository, version) pair");
      }
      return listOf(
          reads.call(
              "The deployment requests of " + repoId + "@" + version,
              () -> deployService.deploymentRequestsByRelease(repoId, version)));
    }
    throw new BadRequestException(
        "one of environmentId, projectId or the repoId/version pair is required");
  }

  @GET
  @Path("/{id}")
  @Operation(summary = "One deployment request, with the deployment it produced")
  @APIResponse(responseCode = "200", description = "The request")
  @APIResponse(responseCode = "404", description = "No such deployment request")
  public DeploymentRequestResponse byId(@PathParam("id") String id) {
    RequestWithDeployment found =
        reads
            .call("The deployment request " + id, () -> deployService.deploymentRequestById(id))
            .orElseThrow(() -> new NotFoundException("No deployment request " + id));
    return new DeploymentRequestResponse(
        mapper.toDto(found.request(), found.deployment()),
        found.deployment() == null ? null : mapper.toDto(found.deployment()));
  }

  private ListDeploymentRequestsResponse listOf(List<RequestWithDeployment> rows) {
    return new ListDeploymentRequestsResponse(
        rows.stream().map(row -> mapper.toDto(row.request(), row.deployment())).toList());
  }

  /** A query parameter a caller actually wrote — an empty one is not a filter. */
  private static boolean stated(String value) {
    return value != null && !value.isBlank();
  }
}
