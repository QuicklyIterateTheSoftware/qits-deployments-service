package eu.wohlben.qits.deployments.api;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * A deployment that was never told the platform's domain — {@code qits.deployments.platform-domain}
 * ships with an empty default and SmallRye reads an empty value as ABSENT, the same shape {@code
 * MissingAdminPasswordProfile} overrides for its own key. It needs a profile, and therefore a
 * Quarkus restart, because the value is injected as an {@code Optional<String>} at bean creation
 * and nothing in the process can take it away later — see {@link
 * eu.wohlben.qits.deployments.deployments.control.RegistryHost}.
 */
public class MissingPlatformDomainProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.deployments.platform-domain", "");
  }
}
