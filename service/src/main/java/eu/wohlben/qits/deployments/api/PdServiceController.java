package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.deployments.environments.dto.PdServiceDto;
import eu.wohlben.qits.deployments.environments.mapper.EnvironmentMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The service catalogue: one {@code PUT} that states a service whole, and the flat read.
 *
 * <p><b>Why an upsert and not a create/update pair.</b> What is written is derived — a green build
 * reads a repository's {@code .config/qits/deployments.yml} at that sha and states the shape it
 * found there. A caller holding the whole file has nothing partial to say, so the name in the path
 * is the key and the body is the entire row. That also makes the write idempotent, which matters
 * because a green build fans out over every environment tracking a branch.
 *
 * <p><b>The usual writer is in-process.</b> Derived registration calls {@code ServiceCatalog}
 * directly — it is a local transaction now, not an HTTP round trip onto another service. This
 * surface stays because it is the operator's door onto the same rules: the remediation the refused
 * plane flip points at is a {@code DELETE} here, and reading the catalogue is what the web client
 * and a person do.
 *
 * <p><b>Both writes take {@code qits:system} and call {@link MachineAuth#require()}; the read takes
 * {@code qits:admin} and does not.</b> The writes are a machine's, so the role is one an idp-minted
 * token carries — under the open calling model {@code qits:system} means "a service calling a
 * service"; the read is driven by people through the client, so the role is the one the edge asserts
 * for an admin session. {@code qits:system} and {@code qits:admin} do not overlap. See the class
 * javadoc of {@link PdEnvironmentController} for the whole split. {@code qits:admin-agent} is
 * admitted on the read too (qits-628 follow-up): an ADMIN workspace's coding agent carries it
 * alongside {@code qits:agent}, and for now it may use everything {@code qits:admin} may use.
 */
@Path("/services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PdServiceController {

  @Inject MachineAuth machineAuth;
  @Inject ServiceCatalog catalog;
  @Inject EnvironmentMapper mapper;
  @Inject PdReadPatience reads;

  /**
   * The whole of a service.
   *
   * <p>{@code environmentIds} <b>replaces</b> the link set — it is not a delta, and an empty or
   * absent list unlinks the service everywhere, which since the platform plane was deleted means it
   * runs nowhere rather than everywhere.
   *
   * <p><b>{@code deploymentTarget} is gone from this payload.</b> An older sender's value
   * deserializes into nothing rather than failing the write, which is the only compatibility owed —
   * the same answer {@code branch} got when {@code pd_environment.branch} left the create payload.
   *
   * <p>{@code branch} is <b>vestigial</b>: nothing decides a deployment on it any more. It is still
   * accepted and stored so an operator's write round-trips; derived registration writes null. See
   * {@code PdService.branch}.
   */
  public record UpsertServiceRequest(
      String branch,
      boolean availableOnEnv,
      String healthPath,
      List<String> environmentIds) {}

  public record ServiceResponse(PdServiceDto service) {}

  public record ListServicesResponse(List<PdServiceDto> services) {}

  /**
   * Register or update one service, whole. 201 the first time a name is seen, 200 afterwards.
   *
   * <p><b>There is one shape of service and therefore no flip to arbitrate.</b> This door used to
   * convert a service onto the platform plane (dropping its links) and refuse the way back with a
   * 409; the plane is deleted, so an upsert states a link set and the row takes it.
   */
  @PUT
  @Path("/{name}")
  @Operation(operationId = "upsertService", summary = "Register or update one service, replacing its environment links")
  @APIResponse(responseCode = "200", description = "The updated service")
  @APIResponse(responseCode = "201", description = "The newly registered service")
  @APIResponse(responseCode = "400", description = "Validation failed")
  @APIResponse(responseCode = "404", description = "An environmentId names no environment")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed({"qits:system"})
  public Response upsert(@PathParam("name") String name, UpsertServiceRequest request) {
    machineAuth.require();
    UpsertServiceRequest body =
        request == null ? new UpsertServiceRequest(null, false, null, null) : request;
    ServiceCatalog.UpsertResult result =
        catalog.upsert(
            new ServiceCatalog.Upsert(
                name,
                body.branch(),
                body.availableOnEnv(),
                body.healthPath(),
                body.environmentIds()));
    PdServiceDto dto = mapper.toDto(result.service().service(), result.service().environmentIds());
    return Response.status(result.created() ? Response.Status.CREATED : Response.Status.OK)
        .entity(new ServiceResponse(dto))
        .build();
  }

  /**
   * Every service, oldest first, each flattened with the environments it is linked into.
   *
   * <p>Flat because the listing has one entry per (service, tier) and a service the catalogue links
   * nowhere still has to appear — a row nothing has registered into a tier is exactly the row a
   * reader goes looking for.
   *
   * <p>Held through a short database outage rather than answering 500 — see {@link PdReadPatience}.
   * The write above is deliberately not: a retried insert whose commit the connection died before
   * reporting would be a second row.
   */
  @GET
  @Operation(operationId = "listServices", summary = "Every service, with the environments each is linked into")
  @APIResponse(responseCode = "200", description = "The services")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  public ListServicesResponse list() {
    return new ListServicesResponse(
        reads.call("The service catalogue listing", catalog::list).stream()
            .map(linked -> mapper.toDto(linked.service(), linked.environmentIds()))
            .toList());
  }

  /**
   * Remove a service and its links. This is the deliberate act the refused platform-to-environment
   * flip points at: remove the row, then let the next green build register the service afresh in
   * the shape its repository now declares.
   *
   * <p>It takes no deployment history with it, by design — the rows name a service by string with
   * no FK, so what ran stays readable and the rollback pins keep answering.
   */
  @DELETE
  @Path("/{name}")
  @Operation(operationId = "deleteService", summary = "Remove a service and its links")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "404", description = "No such service")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed({"qits:system"})
  public Response delete(@PathParam("name") String name) {
    machineAuth.require();
    catalog.delete(name);
    return Response.noContent().build();
  }
}
