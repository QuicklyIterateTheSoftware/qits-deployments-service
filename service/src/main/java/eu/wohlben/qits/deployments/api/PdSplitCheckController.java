package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.control.SplitCheck;
import eu.wohlben.qits.deployments.deployments.dto.SplitCheckDto;
import eu.wohlben.qits.deployments.environments.error.PdException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /deployments/api/split-check} — the last split check (epic qits-2174, task qits-2215):
 * which services are bridged or misplaced, and what the public edge answered for every private
 * application. It never runs a check; see {@link SplitCheck} for when one runs. A read of memory,
 * so it needs no {@link PdReadPatience}.
 */
@Path("/split-check")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({
  "qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:agent"
})
public class PdSplitCheckController {

  @Inject SplitCheck check;

  @GET
  @Operation(
      operationId = "getSplitCheck",
      summary = "The last check that public and private are split: networks and the public edge")
  @APIResponse(responseCode = "200", description = "The last result")
  @APIResponse(responseCode = "503", description = "No split check has run since this process started")
  public SplitCheckDto get() {
    return check
        .last()
        .orElseThrow(() -> new PdException(503, "No split check has run since this process started"));
  }
}
