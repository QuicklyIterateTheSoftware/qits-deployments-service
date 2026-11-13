package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shipped state: {@code qits.deployments.postgres.admin-password} has no default, so a
 * deployment nobody configured it for cannot provision anything.
 *
 * <p>It is its own class because the value is injected as an {@code Optional<String>} at bean
 * creation — taking it away needs a profile, and a profile needs a Quarkus restart.
 */
@QuarkusTest
@TestProfile(MissingAdminPasswordProfile.class)
public class ResourceProvisioningWithoutAdminPasswordTest {

  @Inject ResourceProvisioning provisioning;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeIdpClientProvisioner idpProvisioner;

  @BeforeEach
  void reset() {
    provisioner.reset();
    idpProvisioner.reset();
  }

  @Test
  public void aDeploymentThatDeclaresAResourceFailsNamingTheKeyNobodySet() {
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    "no-admin",
                    "no-admin-env",
                    List.of(new ResourceProvisioning.Resolved("db", "qits_no_admin"))));

    // The one actionable sentence there is: an authentication error from postgres would read like
    // anything but "nothing configured this".
    assertTrue(
        refused.getMessage().contains("qits.deployments.postgres.admin-password"),
        refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "and no connection was attempted");
  }

  @Test
  public void aDeploymentThatDeclaresNothingIsUnaffected() {
    // Which is every application on the platform today: the key is read only where it is needed.
    assertEquals(List.of(), provisioning.ensureAll("no-admin", "no-admin-env", List.of()));
  }

  @Test
  public void thePrivatePostgresFailsNamingTheKeyEvenDeclaringNothing() {
    // qits-2175: the deployer initialises qits-oci-postgresql-private with its own admin password
    // (POSTGRES_PASSWORD), so without one that application's deployment has nothing to start it
    // with — refused naming the key, like a declared database.
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    ResourceProvisioning.PRIVATE_POSTGRES_APPLICATION, "no-admin-env", List.of()));

    assertTrue(
        refused.getMessage().contains("qits.deployments.postgres.admin-password"),
        refused.getMessage());
  }

  @Test
  public void anIdpOnlyDeclarationNeedsNoAdminPasswordAtAll() {
    // The admin password is postgres' own credential; an idp:client resource never reads it. This
    // is the proof rather than an inference: the key is unset in this profile and the call still
    // succeeds.
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll(
            "no-admin-idp",
            "no-admin-env",
            List.of(new ResourceProvisioning.Resolved("idp", null, ResourceSpec.Type.IDP_CLIENT)));

    assertEquals(1, bindings.size());
    assertEquals("idp", bindings.get(0).name());
    assertEquals(List.of(), provisioner.requests(), "the postgres seam was never touched");
  }
}
