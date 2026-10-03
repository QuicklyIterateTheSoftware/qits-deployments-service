package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link ResourceProvisioning#rotationRefused}, the D9 guard's decision: the id this process is
 * itself signed in as is never rotated, and nothing else is refused for being qits-deployments'.
 * The booted half — the refusal reaching {@code ensureAll} — is {@code IdpClientProvisioningTest}.
 */
class RotationGuardTest {

  private static final String DEPLOYER = BootResourceRegistration.APPLICATION;

  @Test
  void theIdThisProcessIsSignedInAsIsRefused() {
    assertTrue(
        ResourceProvisioning.rotationRefused(
            DEPLOYER, "qits-deployments", Optional.of("qits-deployments")));
    assertTrue(
        ResourceProvisioning.rotationRefused(
            DEPLOYER, "dev-qits-deployments", Optional.of(" dev-qits-deployments ")));
  }

  @Test
  void anotherClientOfQitsDeploymentsIsAllowed() {
    // The recovery path: running as the bootstrap client, the derived client is rotated.
    assertFalse(
        ResourceProvisioning.rotationRefused(
            DEPLOYER, "dev-qits-deployments", Optional.of("qits-deployments")));
  }

  @Test
  void anotherApplicationsClientIsAllowed() {
    assertFalse(
        ResourceProvisioning.rotationRefused(
            "qits-ci", "dev-qits-ci", Optional.of("qits-deployments")));
  }

  @Test
  void withNoKnownIdentityEveryClientOfQitsDeploymentsIsRefused() {
    // Not knowing who it is, it keeps the old, wider refusal rather than risk its own secret.
    assertTrue(ResourceProvisioning.rotationRefused(DEPLOYER, "dev-qits-deployments", Optional.empty()));
    assertTrue(ResourceProvisioning.rotationRefused(DEPLOYER, "qits-deployments", Optional.of("  ")));
    assertFalse(ResourceProvisioning.rotationRefused("qits-ci", "dev-qits-ci", Optional.empty()));
  }
}
