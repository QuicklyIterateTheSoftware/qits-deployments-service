package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.control.EgressGrantTables;
import eu.wohlben.qits.deployments.deployments.dto.EgressGrantTableDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /deployments/api/egress-grants} — the egress grant table in force (epic qits-2523):
 * every route the {@code ACTIVE} deployments' {@code .config/qits/egress.yml} grant, and the
 * deployments whose file was refused. qits-egress polls it with its {@code qits} client bearer;
 * agents read it for live checks. See {@link EgressGrantTables} for what is in the table and how
 * its version moves.
 *
 * <p>{@code qits:system} for qits-egress, {@code qits:admin} and {@code qits:agent} for people and
 * agents. A read, so it is {@link PdReadPatience}-wrapped — the build it runs is idempotent, so a
 * second attempt after a lost connection stores nothing the first did not.
 */
@Path("/egress-grants")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:admin", "qits:agent"})
public class PdEgressGrantController {

  @Inject EgressGrantTables tables;

  @Inject PdReadPatience patience;

  @GET
  @Operation(
      operationId = "getEgressGrants",
      summary =
          "The egress grant table in force: the routes each ACTIVE deployment's egress.yml grants")
  @APIResponse(
      responseCode = "200",
      description = "The table, versioned; the version moves only when the content does")
  public EgressGrantTableDto get() {
    return patience.call("the egress grant table", () -> tables.current());
  }
}
