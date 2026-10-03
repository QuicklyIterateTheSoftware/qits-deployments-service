package eu.wohlben.qits.deployments.confighost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code qits} oidc client and the neutralised {@code configuration} stub, resolved against the
 * environment the deployer's running container actually carries.
 *
 * <p><b>Why this cannot be a {@code @QuarkusTest}.</b> The statement is about the ENVIRONMENT
 * source: one {@code QUARKUS_OIDC_CLIENT_CONFIGURATION_*} variable mints the {@code configuration}
 * map key and outranks {@code application.properties} (300 over 250), and the {@code qits} client
 * reads two of those variables by their raw names inside {@code ${…}}. A surefire JVM cannot gain an
 * environment variable, and a test profile's overrides are a map-backed source that cannot show
 * which source a key resolved from. So this assembles the real {@link PropertiesConfigSource} over
 * the SHIPPED file and the real {@link EnvConfigSource} over the container's variables, at the
 * ordinals a deployed Quarkus gives them, and asks SmallRye Config directly — the qits-ci
 * {@code OidcClientNeutralisationTest} arrangement.
 *
 * <p><b>This is the deployer</b>, so the case that matters most is the one where the rename could
 * silently switch its credential off: the old client's switch,
 * {@code QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED}, no longer reaches the renamed client, so
 * the {@code qits} client has to be on with no variable deciding it.
 */
class QitsOidcClientEnvTest {

  /** Where a deployed Quarkus puts {@code application.properties} from the classpath. */
  private static final int APPLICATION_PROPERTIES_ORDINAL = 250;

  /** The file under test, found by walking up from the directory surefire started this module in. */
  private static Path shippedProperties() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve("service/src/main/resources/application.properties");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no shipped application.properties above " + Path.of("").toAbsolutePath());
  }

  /**
   * What dev-qits-deployments' container carries of this concern (its envKeys, read 2026-10-02):
   * the five {@code QUARKUS_OIDC_CLIENT_CONFIGURATION_*} extras and {@code
   * QITS_AUTH_MACHINE_AUDIENCE}, and NO {@code QITS_RESOURCE_IDP_*} — this component keeps its
   * bootstrap pair. Plus {@code QITS_ENVIRONMENT}, which the deployer injects into every container
   * including its own. The values are sentinels, so an assertion can tell which one won.
   */
  private static Map<String, String> liveEnvironment() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_ENVIRONMENT", "dev");
    env.put("QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID", "sentinel-live-client-id");
    env.put("QUARKUS_OIDC_CLIENT_CONFIGURATION_CREDENTIALS_SECRET", "sentinel-live-secret");
    env.put("QUARKUS_OIDC_CLIENT_CONFIGURATION_AUTH_SERVER_URL", "http://sentinel-live-idp:8080/idp");
    env.put(
        "QUARKUS_OIDC_CLIENT_CONFIGURATION_GRANT_OPTIONS_CLIENT_AUDIENCE", "sentinel-live-audience");
    env.put("QITS_AUTH_MACHINE_AUDIENCE", "sentinel-live-machine-audience");
    return env;
  }

  private static SmallRyeConfig config(Map<String, String> environment, String... profiles)
      throws IOException {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withProfiles(List.of(profiles))
        .withSources(
            new PropertiesConfigSource(
                shippedProperties().toUri().toURL(), APPLICATION_PROPERTIES_ORDINAL))
        .withSources(new EnvConfigSource(environment, EnvConfigSource.ORDINAL))
        .build();
  }

  private static String value(SmallRyeConfig config, String key) {
    return config.getConfigValue(key).getValue();
  }

  @Test
  void theQitsClientIsOnWithTheLiveEnvironmentAndNoVariableOfItsOwn() throws IOException {
    SmallRyeConfig config = config(liveEnvironment());

    assertEquals("true", value(config, "quarkus.oidc-client.qits.client-enabled"));
    assertNotEquals(
        EnvConfigSource.NAME,
        config.getConfigValue("quarkus.oidc-client.qits.client-enabled").getConfigSourceName(),
        "the shipped file decides it, not a variable");
    // ...and the old switch, off, does not reach it either.
    Map<String, String> oldSwitchOff = liveEnvironment();
    oldSwitchOff.put("QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED", "false");
    assertEquals(
        "true", value(config(oldSwitchOff), "quarkus.oidc-client.qits.client-enabled"));
    assertEquals("true", value(config(Map.of()), "quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theQitsClientPresentsTheLivePairAtThePlatformAudience() throws IOException {
    SmallRyeConfig config = config(liveEnvironment());

    assertEquals("sentinel-live-client-id", value(config, "quarkus.oidc-client.qits.client-id"));
    assertEquals(
        "sentinel-live-secret", value(config, "quarkus.oidc-client.qits.credentials.secret"));
    assertEquals(
        "qits-platform", value(config, "quarkus.oidc-client.qits.grant-options.client.audience"));
    assertEquals("client", value(config, "quarkus.oidc-client.qits.grant.type"));
    assertEquals("false", value(config, "quarkus.oidc-client.qits.discovery-enabled"));
    assertEquals("token", value(config, "quarkus.oidc-client.qits.token-path"));
    assertEquals("false", value(config, "quarkus.oidc-client.qits.early-tokens-acquisition"));
  }

  @Test
  void theQitsClientDerivesTheIdpAndIgnoresTheOldHandSetAddress() throws IOException {
    // The old entry named the idp by hand; the qits client derives <env>-qits-idp, the same address
    // ResourceProvisioning and HttpIdpClientProvisioner already dial.
    assertEquals(
        "http://dev-qits-idp:8080/idp",
        value(config(liveEnvironment()), "quarkus.oidc-client.qits.auth-server-url"));

    Map<String, String> prod = liveEnvironment();
    prod.put("QITS_ENVIRONMENT", "prod");
    assertEquals(
        "http://prod-qits-idp:8080/idp",
        value(config(prod), "quarkus.oidc-client.qits.auth-server-url"));
  }

  @Test
  void theProvisionerPairIsTheSameLivePair() throws IOException {
    SmallRyeConfig config = config(liveEnvironment());

    assertEquals("sentinel-live-client-id", value(config, "qits.deployments.idp.client-id"));
    assertEquals("sentinel-live-secret", value(config, "qits.deployments.idp.client-secret"));
  }

  @Test
  void anInjectedResourceTripleOutranksTheOldPair() throws IOException {
    // What a fresh bootstrap's seed stack hands this container.
    Map<String, String> env = liveEnvironment();
    env.put("QITS_RESOURCE_IDP_URL", "http://resource-idp:8080/idp");
    env.put("QITS_RESOURCE_IDP_CLIENT_ID", "resource-client-id");
    env.put("QITS_RESOURCE_IDP_CLIENT_SECRET", "resource-secret");
    SmallRyeConfig config = config(env);

    assertEquals("http://resource-idp:8080/idp", value(config, "quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("resource-client-id", value(config, "quarkus.oidc-client.qits.client-id"));
    assertEquals("resource-secret", value(config, "quarkus.oidc-client.qits.credentials.secret"));
    assertEquals("resource-client-id", value(config, "qits.deployments.idp.client-id"));
    assertEquals("resource-secret", value(config, "qits.deployments.idp.client-secret"));
  }

  @Test
  void theConfigurationStubIsNeutralisedAgainstTheLiveVariables() throws IOException {
    SmallRyeConfig config = config(liveEnvironment());

    // MEASURED, and the reason client-enabled=false is not the whole neutralisation: the live
    // variable outranks the shipped false, so the old name's client exists and is ENABLED...
    assertEquals("true", value(config, "quarkus.oidc-client.configuration.client-enabled"));
    assertEquals(
        EnvConfigSource.NAME,
        config.getConfigValue("quarkus.oidc-client.configuration.client-enabled").getConfigSourceName());
    // ...and what keeps it from dialling at runtime init, or failing the boot, is shipped here.
    assertEquals("false", value(config, "quarkus.oidc-client.configuration.discovery-enabled"));
    assertEquals("token", value(config, "quarkus.oidc-client.configuration.token-path"));
    // With no environment at all, the file switches it off.
    assertEquals(
        "false", value(config(Map.of()), "quarkus.oidc-client.configuration.client-enabled"));
  }

  @Test
  void theDefaultClientStaysOff() throws IOException {
    assertEquals(
        "false", value(config(liveEnvironment()), "quarkus.oidc-client.client-enabled"));
  }

  @Test
  void devAndTestSwitchTheQitsClientOff() throws IOException {
    assertEquals(
        "false",
        value(config(liveEnvironment(), "test"), "quarkus.oidc-client.qits.client-enabled"));
    assertEquals(
        "false",
        value(config(liveEnvironment(), "dev"), "quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void nothingStillNamesTheOldClientOrTheMachineAudience() throws IOException {
    String shipped = Files.readString(shippedProperties());
    assertFalse(
        shipped.lines()
            .filter(line -> !line.startsWith("#"))
            .anyMatch(line -> line.contains("qits.auth.machine.audience")),
        "qits-auth-core reads only its own qits.auth.machine.platform-audience");
    assertTrue(
        shipped.lines()
            .filter(line -> !line.startsWith("#"))
            .filter(line -> line.startsWith("quarkus.oidc-client.configuration."))
            .allMatch(
                line ->
                    line.equals("quarkus.oidc-client.configuration.client-enabled=false")
                        || line.equals("quarkus.oidc-client.configuration.discovery-enabled=false")
                        || line.equals("quarkus.oidc-client.configuration.token-path=token")),
        "the old name is the three neutralising keys and nothing else");
  }
}
