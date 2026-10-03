package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.dto.PdIdpClientClaimDto;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Every {@code idp-client} row this component holds, across every application and tier:
 * {@code {"claims":[{"clientId","applicationName","environmentName","createdAt"}]}}.
 *
 * <p><b>Who asks.</b> qits-orchestrator's GC reads this and hands it, unchanged, to qits-idp, which
 * deletes every service client no {@code pd_resource} row here claims — the same "the owner of the fact answers the
 * question" shape {@code GET /deployments/api/pins} carries for the image GC.
 *
 * <p><b>No secret, ever.</b> {@link PdResource} carries the credential itself — generated here for a
 * postgres row, issued by qits-idp for an idp-client row — and {@link PdIdpClientClaimDto} omits
 * it: this door answers "which client ids are claimed", never "what is the client's password".
 *
 * <p><b>The deployer's own boot row is in here too.</b> {@code BootResourceRegistration} writes a
 * platform-plane {@code idp-client} row for this very service's own client at startup, with no
 * environment to report — so its entry carries a null {@code environmentName}, exactly as any other
 * platform-plane row does.
 *
 * <p><b>Read-only, and a machine peer's rather than a person's.</b> Same two roles as the pins door,
 * and the same reason: {@code qits:system} for the one caller with real business here, and
 * {@code qits:agent} for an agent's own bearer. {@code qits:admin} is not among them — this is not
 * the read surface a browser session reaches.
 */
@Path("/claims/idp-clients")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
public class PdIdpClientClaimController {

  private static final String IDP_RESOURCE_TYPE = "idp-client";

  @Inject PdResourceRepository resources;

  public record ListIdpClientClaimsResponse(List<PdIdpClientClaimDto> claims) {}

  @GET
  @Operation(
      summary =
          "Every idp-client row this component holds, ordered by clientId — qits-idp's read for"
              + " telling a claimed client id apart from an orphaned one")
  @APIResponse(
      responseCode = "200",
      description =
          "One entry per pd_resource row of type idp-client, including the deployer's own boot"
              + " row; never a secret")
  public ListIdpClientClaimsResponse list() {
    return new ListIdpClientClaimsResponse(
        resources.listByResourceType(IDP_RESOURCE_TYPE).stream()
            .map(
                resource ->
                    new PdIdpClientClaimDto(
                        resource.clientId,
                        resource.applicationName,
                        resource.environmentName,
                        resource.createdAt))
            .toList());
  }
}
