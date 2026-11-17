package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.control.EdgeRouteTables;
import eu.wohlben.qits.deployments.deployments.dto.EdgeRouteTableDto;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import eu.wohlben.qits.deployments.environments.error.PdException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /deployments/api/edge-routes/{edge}} — the route table this component built for one
 * edge (epic qits-2174), exactly the table it pushes to that edge's {@code PUT /edge-route-table}.
 * The private edge reads it at its own start with its {@code qits} client bearer; agents read it
 * for live checks. See {@link EdgeRouteTables} for what is in a table and how its version moves.
 *
 * <p>{@code qits:system} for the edge, {@code qits:admin} and {@code qits:agent} for people and
 * agents. A read, so it is {@link PdReadPatience}-wrapped.
 */
@Path("/edge-routes")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:admin", "qits:agent"})
public class PdEdgeRouteController {

  @Inject EdgeRouteTables tables;

  @Inject PdReadPatience patience;

  @GET
  @Path("/{edge}")
  @Operation(
      operationId = "getEdgeRoutes",
      summary = "The route table built for one edge: `public` (qits-edge) or `private`")
  @APIResponse(responseCode = "200", description = "The stored table, as it is pushed to the edge")
  @APIResponse(responseCode = "404", description = "No edge of that name")
  @APIResponse(responseCode = "503", description = "No table has been built for that edge yet")
  public EdgeRouteTableDto get(@PathParam("edge") String edge) {
    if (!EdgeRouteTables.TABLES.contains(edge)) {
      throw new NotFoundException(
          "No edge `" + edge + "`; the edges are " + String.join(" and ", EdgeRouteTables.TABLES));
    }
    return patience
        .call("the " + edge + " route table", () -> tables.storedRow(edge))
        .map(row -> EdgeRouteTableDto.of(row, EdgeRouteTables.mapper()))
        .orElseThrow(
            () ->
                new PdException(
                    503, "No route table has been built for the " + edge + " edge yet"));
  }
}
