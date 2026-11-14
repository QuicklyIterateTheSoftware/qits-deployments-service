package eu.wohlben.qits.deployments.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.DatabaseMoves;
import eu.wohlben.qits.deployments.deployments.dto.PdDatabaseMoveDto;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The database moves (qits-2175): an operator's move of one application's database to another server
 * or name, and the record of every move — a deployment's and an operator's alike.
 *
 * <pre>
 * POST /deployments/api/database-moves   {"application","resource"?,"server"?,"database"?}  → 202
 * GET  /deployments/api/database-moves[?application=]                                       → 200
 * GET  /deployments/api/database-moves/{id}                                                 → 200
 * POST /deployments/api/database-moves/{id}/abort                                           → 202
 * </pre>
 *
 * <p><b>The POST is a redeploy with a target, not a move of its own.</b> It is decided on the request
 * thread — every refusal is an answer — and queued on the deploy worker as a redeploy of the version
 * that is live, whose provisioning step runs the move. That is the only way the running service is
 * handed the new address, and it is the path a deployment's own move already takes. So the answer is
 * 202 with the move's id, and its state is read back from the GET.
 *
 * <p><b>Roles: the operator's set and {@code qits:agent}</b>, on the POST too, which is the one write
 * on this surface an agent may make. qits-2187's agent runs it and the owner's rule allows no person
 * in that loop; what bounds it is that a move copies and verifies before anything switches, keeps the
 * source for the retention, and refuses the deployer's own databases. {@code qits:system} is not
 * granted: no service token should be able to stop an application as a side effect.
 *
 * <p><b>The abort is the operator levers' alone</b> (qits-2243): {@code qits:admin}, {@code
 * qits:admin-agent}, {@code qits:desk-admin}, {@code qits:desk} — not {@code qits:agent}, never
 * {@code qits:system}. It kills a running extract or ingest and the move ends {@code ABORTED} with its
 * service scaled back; 404 for an unknown id, 409 for a move that is queued, finished, or at its
 * switch or later.
 */
@Path("/database-moves")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({
  "qits:admin",
  "qits:admin-agent",
  "qits:desk-admin",
  "qits:desk",
  "qits:agent"
})
public class PdDatabaseMoveController {

  @Inject DatabaseMoves moves;
  @Inject PdReadPatience reads;
  @Inject ObjectMapper json;

  /**
   * What an operator asks for: the application, and optionally which of its databases ({@code
   * resource}, required when it holds more than one) and where to — {@code server} ({@code public} or
   * {@code private}) and {@code database}, each defaulting to what the live version's spec resolves.
   */
  public record MoveRequest(String application, String resource, String server, String database) {}

  /**
   * One move, and — on the POST, when the target overrides the spec — the note that the next
   * deployment of the application moves the database back to what its spec resolves to.
   */
  public record MoveResponse(PdDatabaseMoveDto move, String note) {}

  public record ListMovesResponse(List<PdDatabaseMoveDto> moves) {}

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "moveDatabase",
      summary =
          "Move an application's database to another server or name — queued as a redeploy of its"
              + " live version whose provisioning copies, verifies and switches it")
  @APIResponse(responseCode = "202", description = "Queued on the deploy worker; the move is named")
  @APIResponse(
      responseCode = "400",
      description =
          "No application, a malformed name, an unknown server, or no resource named for an"
              + " application holding several databases")
  @APIResponse(responseCode = "404", description = "The application holds no such database")
  @APIResponse(
      responseCode = "409",
      description =
          "Already there, the deployer's own database, a target another application holds, or nothing"
              + " serving to redeploy")
  @APIResponse(responseCode = "502", description = "The live version's spec could not be read")
  public Response move(MoveRequest request, @Context SecurityContext security) {
    if (request == null || request.application() == null || request.application().isBlank()) {
      throw new BadRequestException("application is required");
    }
    DatabaseMoves.Accepted accepted =
        moves.request(
            request.application(),
            blankToNull(request.resource()),
            blankToNull(request.server()),
            blankToNull(request.database()),
            actor(security));
    return Response.accepted()
        .entity(new MoveResponse(PdDatabaseMoveDto.of(accepted.move(), json), accepted.note()))
        .build();
  }

  @GET
  @Operation(
      operationId = "listDatabaseMoves",
      summary = "The database moves, newest first — of one application, or of every one")
  @APIResponse(responseCode = "200", description = "The moves, newest first; at most 200")
  @APIResponse(responseCode = "400", description = "A malformed application name")
  public ListMovesResponse list(@QueryParam("application") String application) {
    String name =
        application == null || application.isBlank()
            ? null
            : PdIdentifiers.requireName(application, "application name");
    return new ListMovesResponse(
        reads.call("The database move listing", () -> moves.list(name)).stream()
            .map(row -> PdDatabaseMoveDto.of(row, json))
            .toList());
  }

  @GET
  @Path("/{id}")
  @Operation(
      operationId = "getDatabaseMove",
      summary = "One database move: its state, both sides' counts, and when its kept copy goes")
  @APIResponse(responseCode = "200", description = "The move")
  @APIResponse(responseCode = "404", description = "No move of that id")
  public MoveResponse get(@PathParam("id") String id) {
    return reads
        .call("The database move " + id, () -> moves.find(id))
        .map(row -> new MoveResponse(PdDatabaseMoveDto.of(row, json), null))
        .orElseThrow(() -> new NotFoundException("no database move " + id));
  }

  @POST
  @Path("/{id}/abort")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:desk"
  })
  @Operation(
      operationId = "abortDatabaseMove",
      summary =
          "Abort a running database move — its extract or ingest killed, the target dropped, the"
              + " source re-opened and its service scaled back up")
  @APIResponse(
      responseCode = "202",
      description = "The abort is asked for; the move ends ABORTED once its running step is killed")
  @APIResponse(responseCode = "404", description = "No move of that id")
  @APIResponse(
      responseCode = "409",
      description =
          "The move is queued (nothing runs yet), already finished, or at its switch or later, where"
              + " an abort would strand the claim")
  public Response abort(@PathParam("id") String id, @Context SecurityContext security) {
    var row = moves.abort(id, actor(security));
    return Response.accepted()
        .entity(
            new MoveResponse(
                PdDatabaseMoveDto.of(row, json),
                "the abort is asked for; the move ends ABORTED once its running step is killed"))
        .build();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String actor(SecurityContext security) {
    return security == null || security.getUserPrincipal() == null
        ? null
        : security.getUserPrincipal().getName();
  }
}
