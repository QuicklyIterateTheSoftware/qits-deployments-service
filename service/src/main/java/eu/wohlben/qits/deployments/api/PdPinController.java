package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.control.RollbackPins;
import eu.wohlben.qits.deployments.deployments.dto.PdPinDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The image shas that must survive a garbage collection, across every environment:
 * {@code {"pins":[{"applicationName","shas"}]}}.
 *
 * <p><b>Who asks.</b> qits-platform-artifacts' OCI image GC reads this when it plans a sweep — a
 * tag no pin names is eligible, and an unreachable answer aborts the plan with nothing deleted. It
 * is the same fail-closed shape qits-ci's {@code GET /ci/api/daemon} carries for the daemon
 * binaries.
 *
 * <p><b>Why this component answers it rather than the collector computing it.</b> The keep-set is
 * "which shas would a restart or a rollback pull", and this is the only service that knows. The
 * rule is {@link RollbackPins}, beside the code that performs the rollback — so the GC's keep-set
 * and the rollback target are one definition rather than two that drift, and drift here deletes an
 * image a container is about to pull.
 *
 * <p><b>Not a deployment listing.</b> {@code GET /deployments/api/deployments} is history,
 * scoped to one environment and reporting every attempt. This answers the smaller question a
 * collector asks: across the whole instance, what is serving and what would come back. It reads
 * nothing but the deployment rows, which is what keeps it answerable regardless of what the
 * topology says today.
 *
 * <p><b>Read-only, and a machine peer's rather than a person's.</b> It takes {@code qits:system} —
 * the role an idp-minted machine token carries — and not the {@code qits:admin} the environment and
 * deployment listings take, because its one caller is qits-platform-artifacts' collector and no
 * browser has business here. The collector's own idp client is granted the platform audience and
 * that role, so the credential exists to present. {@code qits:agent} is here too, for an agent's own
 * bearer; {@code qits:system} and {@code qits:admin} do not overlap.
 */
@Path("/pins")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
public class PdPinController {

  @Inject RollbackPins pins;

  public record ListPinsResponse(List<PdPinDto> pins) {}

  @GET
  @Operation(
      operationId = "listPins",
      summary = "The image shas deployments pin: what serves, and what a rollback restores")
  @APIResponse(
      responseCode = "200",
      description =
          "One entry per application name serving somewhere; an application with no ACTIVE"
              + " deployment pins nothing and is absent")
  public ListPinsResponse list() {
    return new ListPinsResponse(
        pins.pins().stream().map(pin -> new PdPinDto(pin.applicationName(), pin.shas())).toList());
  }
}
