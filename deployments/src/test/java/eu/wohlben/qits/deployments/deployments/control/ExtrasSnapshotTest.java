package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The config a deployment reads: what one source stated, over a boot config that is not a clean
 * slate — the regression suite for qits-1468, where a key the authoritative source had stopped
 * stating came back out of the boot layer.
 */
class ExtrasSnapshotTest {

  private static final String P = DeploymentDriver.EXTRAS_PREFIX;

  /** The retired spelling {@code ServiceExtras.of} still reads, which the filter must hide too. */
  private static final String LEGACY_P = DeploymentDriver.LEGACY_EXTRAS_PREFIX;

  /** Where the snapshot names its own layer — unused by any assertion. */
  private static final String SOURCE = "pd_setting qits-ci/dev";

  /** The boot config as production's is: Quarkus' config-dir read at ordinal 260, extras included. */
  private static Config boot(Map<String, String> properties) {
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "boot", 260))
        .build();
  }

  private static List<String> env(Config config) {
    return ServiceExtras.of(config, "qits-ci").env();
  }

  @Test
  void aSnapshotHidesAnExtrasKeyTheSourceDidNotState() {
    // THE DEFECT ITSELF. A key the source stopped stating must not read back out of the boot layer.
    // Measured as QUARKUS_OIDC_AUTH_SERVER_URL reaching qits-ci with no file read anywhere.
    Config boot =
        boot(Map.of(P + "qits-ci.env.QUARKUS_OIDC_AUTH_SERVER_URL", "http://qits-platform-idp:8080/idp"));

    Config snapshot = ExtrasSnapshot.over(boot, Map.of(), SOURCE);

    assertEquals(List.of(), env(snapshot));
    assertTrue(
        snapshot
            .getOptionalValue(P + "qits-ci.env.QUARKUS_OIDC_AUTH_SERVER_URL", String.class)
            .isEmpty(),
        "the raw config must not answer for the key either, not merely ServiceExtras");
  }

  @Test
  void aSnapshotHidesTheLegacyPrefixTooNotJustTheCurrentOne() {
    Config boot =
        boot(
            Map.of(
                LEGACY_P + "qits-ci.env.QUARKUS_OIDC_AUTH_SERVER_URL",
                "http://qits-platform-idp:8080/idp"));

    Config snapshot = ExtrasSnapshot.over(boot, Map.of(), SOURCE);

    assertEquals(List.of(), env(snapshot));
    assertTrue(
        snapshot
            .getOptionalValue(LEGACY_P + "qits-ci.env.QUARKUS_OIDC_AUTH_SERVER_URL", String.class)
            .isEmpty());
  }

  @Test
  void aSnapshotLetsTheSourceStateAnExtrasKeyTheStaleBootLayerAlsoHad() {
    // The filter removes the boot layer's copy, not the key: a source that KEEPS stating a key
    // must still see it, from the layer that is actually authoritative.
    Config boot = boot(Map.of(P + "qits-ci.env.FOO", "stale-from-last-boot"));

    Config snapshot = ExtrasSnapshot.over(boot, Map.of(P + "qits-ci.env.FOO", "fresh"), SOURCE);

    assertEquals(List.of("FOO=fresh"), env(snapshot));
  }

  @Test
  void aSnapshotStillAnswersANonExtrasBootKey() {
    // The filter is scoped to the two extras prefixes and nothing wider.
    Config boot = boot(Map.of("qits.deployments.legacy-network", "qits-net"));

    Config snapshot = ExtrasSnapshot.over(boot, Map.of(), SOURCE);

    assertEquals(
        "qits-net",
        snapshot.getOptionalValue("qits.deployments.legacy-network", String.class).orElse(null));
  }

  @Test
  void oneSnapshotAnswersTheSameThingHoweverTheMapChangesUnderIt() {
    // ServiceExtras rests on "every reading agrees", and a caller reads more than once per
    // deployment. The stated map is copied, so a snapshot cannot answer two ways.
    Map<String, String> stated = new HashMap<>(Map.of(P + "qits-ci.env.QITS_EVENTS_URL", "a"));
    Config snapshot = ExtrasSnapshot.over(boot(Map.of()), stated, SOURCE);

    List<String> first = env(snapshot);
    stated.put(P + "qits-ci.env.QITS_EVENTS_URL", "b");

    assertEquals(first, env(snapshot));
    assertEquals(List.of("QITS_EVENTS_URL=a"), first);
  }
}
