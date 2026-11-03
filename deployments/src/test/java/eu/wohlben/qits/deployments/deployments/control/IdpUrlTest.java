package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@code QITS_RESOURCE_IDP_URL} every provisioned container is told (epic qits-428): the idp's
 * edge host on the platform's domain, the internal wire alias for qits-edge alone (E1), and a refusal
 * naming {@code QITS_DOMAIN} when there is no dotted domain to derive it from. Plain JUnit over the
 * static derivation, the {@link RegistryHostTest} shape.
 */
class IdpUrlTest {

  @Test
  void anApplicationIsToldTheIdpsEdgeHost() {
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        ResourceProvisioning.idpUrl("qits-ci", "dev", Optional.of("wohlben.eu")));
  }

  @Test
  void theTierNeverEntersThePublicAddress() {
    assertEquals(
        ResourceProvisioning.idpUrl("qits-ci", "dev", Optional.of("wohlben.eu")),
        ResourceProvisioning.idpUrl("qits-ci", "prod", Optional.of("wohlben.eu")));
  }

  @Test
  void surroundingWhitespaceInTheDomainIsNotPartOfTheHost() {
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        ResourceProvisioning.idpUrl("qits-ci", "dev", Optional.of("  wohlben.eu ")));
  }

  @Test
  void theEdgeKeepsTheInternalAddress() {
    assertEquals(
        "http://dev-qits-idp:8080/idp",
        ResourceProvisioning.idpUrl("qits-edge", "dev", Optional.of("wohlben.eu")));
  }

  @Test
  void theEdgesInternalAddressNeedsNoDomain() {
    assertEquals(
        "http://prod-qits-idp:8080/idp",
        ResourceProvisioning.idpUrl("qits-edge", "prod", Optional.empty()));
  }

  @Test
  void noDomainRefusesNamingQitsDomain() {
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> ResourceProvisioning.idpUrl("qits-ci", "dev", Optional.empty()));
    assertTrue(refused.getMessage().contains("QITS_DOMAIN"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("qits.deployments.platform-domain"), refused.getMessage());
  }

  @Test
  void aBlankDomainRefusesLikeAnAbsentOne() {
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> ResourceProvisioning.idpUrl("qits-ci", "dev", Optional.of("   ")));
    assertTrue(refused.getMessage().contains("QITS_DOMAIN"), refused.getMessage());
  }

  @Test
  void aDomainWithNoDotIsACloneAndRefuses() {
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> ResourceProvisioning.idpUrl("qits-ci", "dev", Optional.of("localhost")));
    assertTrue(refused.getMessage().contains("QITS_DOMAIN"), refused.getMessage());
    assertTrue(refused.getMessage().contains("no dot"), refused.getMessage());
  }
}
