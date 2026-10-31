package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.VolumeSpec;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DeployService#resolveVolumes}, the one place that decides which owner a renamed
 * application's private volume has (qits-1772): the predecessor's only while the application's own
 * volume is absent and the predecessor's present, its own in every other case. Plain JUnit over the
 * fake driver, which answers {@code volumeExists} from what each test scripts.
 */
class VolumeResolutionTest {

  private static final String APP = "qits-edge";
  private static final String OLD = "qits-platform-edge";

  private static final VolumeSpec PRIVATE =
      new VolumeSpec(VolumeSpec.Scope.PRIVATE, "letsencrypt", "/work/.letsencrypt", false);
  private static final VolumeSpec SHARED =
      new VolumeSpec(VolumeSpec.Scope.SHARED, "qits_shared_m2", "/m2", true);

  private FakeDeploymentDriver driver;

  @BeforeEach
  void setUp() {
    driver = new FakeDeploymentDriver();
  }

  private DeployService.VolumeResolution resolve(String renamedFrom, VolumeSpec... declared) {
    return DeployService.resolveVolumes(APP, renamedFrom, List.of(declared), driver);
  }

  private static List<DeploymentDriver.VolumeMount> mounting(String source) {
    return List.of(new DeploymentDriver.VolumeMount(source, "/work/.letsencrypt", false));
  }

  @Test
  void ownVolumePresentMountsItsOwnAndNeverAsksAboutThePredecessors() {
    // The edge/stt case: the rename completed before the pin existed and the application has run
    // on its own volume since — a stale predecessor volume beside it changes nothing.
    driver.scriptExistingVolume(APP + "-letsencrypt");
    driver.scriptExistingVolume(OLD + "-letsencrypt");

    DeployService.VolumeResolution resolved = resolve(OLD, PRIVATE);

    assertNull(resolved.failure());
    assertEquals(mounting(APP + "-letsencrypt"), resolved.mounts());
    assertEquals(List.of(), resolved.pinned());
    assertEquals(List.of("volumeExists:" + APP + "-letsencrypt"), driver.calls());
  }

  @Test
  void ownAbsentAndPredecessorsPresentIsPinnedToThePredecessor() {
    driver.scriptExistingVolume(OLD + "-letsencrypt");

    DeployService.VolumeResolution resolved = resolve(OLD, PRIVATE);

    assertNull(resolved.failure());
    assertEquals(mounting(OLD + "-letsencrypt"), resolved.mounts());
    assertEquals(List.of(OLD + "-letsencrypt"), resolved.pinned());
    assertEquals(
        List.of("volumeExists:" + APP + "-letsencrypt", "volumeExists:" + OLD + "-letsencrypt"),
        driver.calls());
  }

  @Test
  void neitherPresentMountsItsOwn() {
    DeployService.VolumeResolution resolved = resolve(OLD, PRIVATE);

    assertNull(resolved.failure());
    assertEquals(mounting(APP + "-letsencrypt"), resolved.mounts());
    assertEquals(List.of(), resolved.pinned());
  }

  @Test
  void aRenameBackFindsTheOriginalNamesOwnVolume() {
    // A -> B -> A: the file now declares renamed_from: B, and A's own volume is still there.
    driver.scriptExistingVolume(APP + "-letsencrypt");
    driver.scriptExistingVolume("qits-edge-b-letsencrypt");

    DeployService.VolumeResolution resolved = resolve("qits-edge-b", PRIVATE);

    assertEquals(mounting(APP + "-letsencrypt"), resolved.mounts());
    assertEquals(List.of(), resolved.pinned());
  }

  @Test
  void noRenameResolvesItsOwnWithoutAskingTheDriver() {
    for (String noRename : new String[] {null, "", "  ", APP}) {
      driver.reset();
      DeployService.VolumeResolution resolved = resolve(noRename, PRIVATE, SHARED);

      assertNull(resolved.failure());
      assertEquals(
          List.of(
              new DeploymentDriver.VolumeMount(APP + "-letsencrypt", "/work/.letsencrypt", false),
              new DeploymentDriver.VolumeMount("qits_shared_m2", "/m2", true)),
          resolved.mounts(),
          "renamedFrom=" + noRename);
      assertEquals(List.of(), resolved.pinned());
      assertEquals(List.of(), driver.calls(), "renamedFrom=" + noRename);
    }
  }

  @Test
  void aSharedVolumeIsUnaffectedByARenameAndNeverAskedAbout() {
    driver.scriptExistingVolume(OLD + "-letsencrypt");

    DeployService.VolumeResolution resolved = resolve(OLD, SHARED, PRIVATE);

    assertEquals(
        List.of(
            new DeploymentDriver.VolumeMount("qits_shared_m2", "/m2", true),
            new DeploymentDriver.VolumeMount(
                OLD + "-letsencrypt", "/work/.letsencrypt", false)),
        resolved.mounts());
    assertEquals(List.of(OLD + "-letsencrypt"), resolved.pinned());
    assertTrue(
        driver.calls().stream().noneMatch(call -> call.contains("qits_shared_m2")),
        driver.calls().toString());
  }

  @Test
  void anUnknownOwnVolumeFailsNamingIt() {
    driver.scriptUnknownVolume(APP + "-letsencrypt", "Cannot connect to the Docker daemon");

    DeployService.VolumeResolution resolved = resolve(OLD, PRIVATE);

    assertTrue(resolved.failure().contains(APP + "-letsencrypt"), resolved.failure());
    assertTrue(
        resolved.failure().contains("Cannot connect to the Docker daemon"), resolved.failure());
    assertEquals(List.of(), resolved.mounts(), "nothing resolved on a guess");
  }

  @Test
  void anUnknownPredecessorVolumeFailsNamingIt() {
    driver.scriptUnknownVolume(OLD + "-letsencrypt", "context deadline exceeded");

    DeployService.VolumeResolution resolved = resolve(OLD, PRIVATE);

    assertTrue(resolved.failure().contains(OLD + "-letsencrypt"), resolved.failure());
    assertTrue(resolved.failure().contains("context deadline exceeded"), resolved.failure());
  }
}
