package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The derivation that replaced {@code qits.artifacts.registry-host}: {@code registry.qits.<domain>}
 * from {@code qits.deployments.platform-domain}, and the refusal a blank or absent domain takes.
 * Plain JUnit over the static halves, the {@link DockerCredentialFileTest} shape.
 */
class RegistryHostTest {

  @Test
  void theHostIsDerivedFromTheDomain() {
    assertEquals(
        Optional.of("registry.qits.wohlben.eu"), RegistryHost.derive(Optional.of("wohlben.eu")));
  }

  @Test
  void surroundingWhitespaceIsStripped() {
    assertEquals(
        Optional.of("registry.qits.wohlben.eu"),
        RegistryHost.derive(Optional.of("  wohlben.eu  ")));
  }

  @Test
  void aBlankOrAbsentDomainResolvesToNothing() {
    assertEquals(Optional.empty(), RegistryHost.derive(Optional.empty()));
    assertEquals(Optional.empty(), RegistryHost.derive(Optional.of("")));
    assertEquals(Optional.empty(), RegistryHost.derive(Optional.of("   ")));
  }

  @Test
  void requireReturnsTheDerivedHost() {
    assertEquals("registry.qits.wohlben.eu", RegistryHost.require(Optional.of("wohlben.eu")));
  }

  @Test
  void requireRefusesABlankOrAbsentDomainNamingQitsDomain() {
    IllegalStateException absent =
        assertThrows(IllegalStateException.class, () -> RegistryHost.require(Optional.empty()));
    assertTrue(absent.getMessage().contains("QITS_DOMAIN"), absent.getMessage());

    IllegalStateException blank =
        assertThrows(IllegalStateException.class, () -> RegistryHost.require(Optional.of("  ")));
    assertTrue(blank.getMessage().contains("QITS_DOMAIN"), blank.getMessage());
  }
}
