package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.dto.SplitCheckDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The network half of the split check (qits-2215): {@code bridged}, {@code misplaced} and clean. */
class NetworkCheckTest {

  private static final String FLAT = "qits-net";
  private static final String PRIVATE = "qits-private";

  private static DeploymentDriver.LabelledService placed(String service, String placement) {
    Map<String, String> labels = new HashMap<>();
    labels.put(DeploymentDriver.APP_NAME_LABEL, service);
    if (placement != null) {
      labels.put(DeploymentDriver.PLACEMENT_LABEL, placement);
    }
    return new DeploymentDriver.LabelledService(service, labels);
  }

  @Test
  void everyServiceOnItsOnePlacementIsClean() {
    NetworkCheck.Findings findings =
        NetworkCheck.check(
            List.of(placed("dev-qits-ci", FLAT), placed("dev-qits-stt", PRIVATE)),
            Map.of("dev-qits-ci", Set.of(FLAT), "dev-qits-stt", Set.of(PRIVATE)),
            FLAT,
            PRIVATE);

    assertTrue(findings.clean(), findings.toString());
  }

  @Test
  void aServiceOnBothNetworksIsBridgedAndMisplacedWhateverItsLabel() {
    NetworkCheck.Findings findings =
        NetworkCheck.check(
            List.of(placed("dev-qits-edge", FLAT), placed("dev-qits-ci", FLAT)),
            Map.of("dev-qits-edge", Set.of(PRIVATE, FLAT), "dev-qits-ci", Set.of(FLAT)),
            FLAT,
            PRIVATE);

    assertEquals(
        List.of(new SplitCheckDto.Bridged("dev-qits-edge", List.of(FLAT, PRIVATE))),
        findings.bridged());
    assertEquals(
        List.of(new SplitCheckDto.Misplaced("dev-qits-edge", FLAT, List.of(FLAT, PRIVATE))),
        findings.misplaced());
  }

  @Test
  void aServiceOnAnotherNetworkThanItsLabelIsMisplaced() {
    NetworkCheck.Findings findings =
        NetworkCheck.check(
            List.of(placed("dev-qits-stt", PRIVATE), placed("dev-qits-docs", PRIVATE)),
            Map.of(
                "dev-qits-stt", Set.of(FLAT),
                // A leftover attachment beside the right one is a difference too.
                "dev-qits-docs", Set.of(PRIVATE, "qits-platform")),
            FLAT,
            PRIVATE);

    assertEquals(List.of(), findings.bridged());
    assertEquals(
        List.of(
            new SplitCheckDto.Misplaced("dev-qits-docs", PRIVATE, List.of("qits-platform", PRIVATE)),
            new SplitCheckDto.Misplaced("dev-qits-stt", PRIVATE, List.of(FLAT))),
        findings.misplaced(),
        "sorted by service");
  }

  @Test
  void aServiceWithoutAPlacementIsOnlyEverBridged() {
    NetworkCheck.Findings findings =
        NetworkCheck.check(
            List.of(placed("dev-unplaced", null), placed("dev-bridged", null)),
            Map.of("dev-unplaced", Set.of(PRIVATE), "dev-bridged", Set.of(FLAT, PRIVATE)),
            FLAT,
            PRIVATE);

    assertEquals(List.of("dev-bridged"), findings.bridged().stream().map(SplitCheckDto.Bridged::service).toList());
    assertEquals(List.of(), findings.misplaced());
  }

  @Test
  void aServiceWhoseNetworksCouldNotBeReadIsInNeitherList() {
    NetworkCheck.Findings findings =
        NetworkCheck.check(List.of(placed("dev-unread", FLAT)), Map.of(), FLAT, PRIVATE);

    assertTrue(findings.clean());
  }
}
