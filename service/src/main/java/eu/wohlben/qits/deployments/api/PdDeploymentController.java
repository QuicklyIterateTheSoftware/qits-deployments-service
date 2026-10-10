package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.EnvironmentOperations;
import eu.wohlben.qits.deployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.mapper.DeploymentMapper;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The deployment read surface. The deployment is the entity and the environment is a required
 * <b>filter</b> ({@code ?environmentId=}), the ci-runs shape: an unscoped listing would return
 * every deployment on the instance, and a missing environment must say so (404) rather than answer
 * with an empty list.
 *
 * <p><b>{@code ?environmentId=platform} is GONE and answers 404 like any other unknown tier.</b> It
 * named the platform plane, reusing the {@code platform:} stand-in from {@code ApplicationKeys} so
 * the word at the front of an application's id was the word a client filtered with. The plane is
 * deleted; its rows already named the designated tier (V8), so the tier's own listing is where they
 * are, which is the answer an operator asking "what is running in dev" was always after. The two
 * filters overlapped for exactly that reason, and one of them was the redundant one.
 */
@Path("/deployments")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class PdDeploymentController {

  @Inject DeployService deployService;
  @Inject EnvironmentOperations environments;
  @Inject DeploymentMapper mapper;
  @Inject PdReadPatience reads;

  public record ListDeploymentsResponse(List<PdDeploymentDto> deployments) {}

  @GET
  @Operation(
      operationId = "listDeployments",
      summary = "One tier's recorded deployments, newest-first")
  @APIResponse(responseCode = "200", description = "The deployments")
  @APIResponse(responseCode = "400", description = "environmentId was not given")
  @APIResponse(responseCode = "404", description = "No such environment")
  public ListDeploymentsResponse list(@QueryParam("environmentId") String environmentId) {
    if (environmentId == null || environmentId.isBlank()) {
      throw new BadRequestException("environmentId is required");
    }
    // Ordered: a tier that does not exist is a 404 rather than an empty list — which is now also
    // the answer to the retired `platform` filter value, and the right one: it names no tier.
    //
    // Held through a short database outage (PdReadPatience): a lost connection here would turn
    // "which deployments does this tier have" into a 404 for a tier that exists.
    reads.run("The tier check for " + environmentId, () -> environments.require(environmentId));
    List<PdDeployment> rows = deployService.deploymentsFor(environmentId);
    return new ListDeploymentsResponse(rows.stream().map(mapper::toDto).toList());
  }
}
