package eu.wohlben.qits.deployments.api;

import eu.wohlben.qits.deployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.deployments.deployments.dto.PdDeploymentRequestDto;
import eu.wohlben.qits.deployments.deployments.dto.PdPinDto;
import eu.wohlben.qits.deployments.deployments.dto.PdSettingDto;
import eu.wohlben.qits.deployments.deployments.dto.PdSettingsPreviewDto;
import eu.wohlben.qits.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.deployments.environments.dto.PdApplicationDto;
import eu.wohlben.qits.deployments.environments.dto.PdEnvironmentDto;
import eu.wohlben.qits.deployments.environments.dto.PdLinkedServiceDto;
import eu.wohlben.qits.deployments.environments.dto.PdServiceDto;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API. Several
 * controllers return {@code Response.entity(...)}, which hides the entity types from the build-time
 * analysis — so in the native binary, serialization fails at runtime with a 500 while every JVM
 * test stays green. Measured, not theoretical: qits-serviceregistry's first live {@code PUT
 * /services/{name}} answered 500 on exactly this. Some of these types happen to be reachable today
 * through other paths; they are all listed anyway, because which ones the analysis finds is an
 * implementation detail no test guards.
 */
@RegisterForReflection(
    targets = {
      PdEnvironmentController.CreateEnvironmentRequest.class,
      PdEnvironmentController.UpdateEnvironmentRequest.class,
      PdEnvironmentController.ApplicationSpec.class,
      PdEnvironmentController.EnvironmentResponse.class,
      PdEnvironmentController.ListEnvironmentsResponse.class,
      PdEnvironmentController.ListLinksResponse.class,
      PdServiceController.UpsertServiceRequest.class,
      PdServiceController.ServiceResponse.class,
      PdServiceController.ListServicesResponse.class,
      PdApplicationController.ListApplicationsResponse.class,
      PdApplicationController.ScaleRequest.class,
      PdApplicationController.OperationResponse.class,
      PdApplicationController.RetirementResponse.class,
      PdApplicationController.RetireResponse.class,
      PdDeploymentController.ListDeploymentsResponse.class,
      PdDeploymentRequestController.ListDeploymentRequestsResponse.class,
      PdDeploymentRequestController.DeploymentRequestResponse.class,
      PdPinController.ListPinsResponse.class,
      PdSettingController.PutSettingRequest.class,
      PdSettingController.ListSettingsResponse.class,
      PdEventController.SoftwareReleasedEvent.class,
      PdEnvironmentDto.class,
      PdApplicationDto.class,
      PdServiceDto.class,
      PdLinkedServiceDto.class,
      PdDeploymentDto.class,
      PdDeploymentRequestDto.class,
      PdPinDto.class,
      PdSettingDto.class,
      PdSettingsPreviewDto.class,
      PdSettingsPreviewDto.PreviewEntry.class,
      PdSettingsPreviewDto.PreviewChange.class,
      PdQualityGate.class
    })
final class ApiWireReflection {

  private ApiWireReflection() {}
}
