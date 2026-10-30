package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.control.Settings;
import eu.wohlben.qits.deployments.deployments.control.SettingsPreview;
import eu.wohlben.qits.deployments.deployments.dto.PdSettingDto;
import eu.wohlben.qits.deployments.deployments.dto.PdSettingsPreviewDto;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
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
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The deployer's own settings store (qits-1390, epic qits-765): per-application, per-tier
 * environment variables — secrets and installation facts moving out of qits-configuration.
 *
 * <p><b>No deployment reads this store yet.</b> The preview door shows what switching the read over
 * would change on a live service; until then a write here changes nothing that runs.
 *
 * <p><b>A secret's value never leaves this service.</b> Every answer — the listing, the preview and
 * the answer to the very {@code PUT} that stored it — carries {@code "value": null} beside
 * {@code "secret": true}. There is no door that reads one back.
 *
 * <p><b>Roles.</b> The reads take the read set ({@code qits:admin}, {@code qits:admin-agent},
 * {@code qits:desk-admin} by {@code DeskAdminParityTest}'s rule, {@code qits:system} and {@code
 * qits:agent}). The writes take {@code qits:admin} and {@code qits:system} — an operator or a
 * machine stating a value — and nothing an agent holds. {@code MachineAuth.require()} is
 * deliberately NOT called on the writes: they admit a person's forwarded {@code qits:admin} as well
 * as a machine bearer, and the guard would refuse the person.
 */
@Path("/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PdSettingController {

  @Inject Settings settings;
  @Inject SettingsPreview preview;

  /** The body of a write: the value is required, {@code secret} defaults to false. */
  public record PutSettingRequest(String value, Boolean secret) {}

  public record ListSettingsResponse(List<PdSettingDto> settings) {}

  @GET
  @Operation(
      operationId = "listSettings",
      summary = "Every setting, optionally filtered by application and environment; secret values are null")
  @APIResponse(responseCode = "200", description = "The settings, ordered by application, environment and key")
  @APIResponse(responseCode = "400", description = "A filter is not a valid name")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"
  })
  public ListSettingsResponse list(
      @QueryParam("application") String application,
      @QueryParam("environment") String environment) {
    return new ListSettingsResponse(settings.list(application, environment));
  }

  @PUT
  @Path("/{application}/{environment}/{key}")
  @Operation(
      operationId = "putSetting",
      summary = "Insert or replace one setting; answers the stored row, its value null if secret")
  @APIResponse(responseCode = "200", description = "The stored setting")
  @APIResponse(responseCode = "400", description = "The key is not an environment variable name, a name is invalid, or no value")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  public PdSettingDto put(
      @PathParam("application") String application,
      @PathParam("environment") String environment,
      @PathParam("key") String key,
      PutSettingRequest request,
      @Context SecurityContext security) {
    return settings.put(
        application,
        environment,
        key,
        request == null ? null : request.value(),
        request != null && Boolean.TRUE.equals(request.secret()),
        actor(security));
  }

  @DELETE
  @Path("/{application}/{environment}/{key}")
  @Operation(operationId = "deleteSetting", summary = "Remove one setting")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "400", description = "The key or a name is invalid")
  @APIResponse(responseCode = "404", description = "No such setting")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  public Response delete(
      @PathParam("application") String application,
      @PathParam("environment") String environment,
      @PathParam("key") String key) {
    settings.delete(application, environment, key);
    return Response.noContent().build();
  }

  @GET
  @Path("/preview/{application}/{environment}")
  @Operation(
      operationId = "previewSettings",
      summary = "What the next deployment would change on the live service",
      description =
          "Compares the environment the next deployment would set with the live service's: the"
              + " variables the deployer injects itself (QITS_ENVIRONMENT, QITS_APPLICATION,"
              + " QITS_DOMAIN, the OTEL identity, QITS_RESOURCE_*) plus the settings rows, which are"
              + " the deployer's only source of an application's environment."
              + " The extras parameter is accepted for compatibility and changes nothing: include,"
              + " exclude and absent answer identically (qits-configuration, which include used to"
              + " read, is gone), and extrasIncluded is always false."
              + " added/removed/changed compare the two sides; kept lists live keys the next"
              + " deployment does not state but an update never removes (the deployer's own"
              + " variables and QITS_RESOURCE_*), so removed is exactly what an update would"
              + " --env-rm. A value is null on both sides when the key is a secret setting or its"
              + " name contains SECRET, TOKEN, PASSWORD or CREDENTIAL, or ends with _AUTH. A"
              + " service that is not running makes everything added.")
  @APIResponse(responseCode = "200", description = "The difference")
  @APIResponse(responseCode = "400", description = "A name is invalid, or extras is neither include nor exclude")
  @APIResponse(responseCode = "404", description = "No such environment, or an application the deployer does not know")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"
  })
  public PdSettingsPreviewDto preview(
      @PathParam("application") String application,
      @PathParam("environment") String environment,
      @Parameter(description = "Accepted for compatibility; include and exclude answer identically")
          @QueryParam("extras")
          String extras) {
    if (extras != null
        && !extras.isBlank()
        && !"exclude".equals(extras)
        && !"include".equals(extras)) {
      throw new BadRequestException("extras is 'include' or 'exclude', not '" + extras + "'");
    }
    return preview.preview(application, environment);
  }

  private static String actor(SecurityContext security) {
    return security == null || security.getUserPrincipal() == null
        ? null
        : security.getUserPrincipal().getName();
  }
}
