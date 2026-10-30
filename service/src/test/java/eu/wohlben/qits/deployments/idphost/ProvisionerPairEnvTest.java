package eu.wohlben.qits.deployments.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pair {@link HttpIdpClientProvisioner} presents, resolved against the environment the
 * deployer's running container actually carries.
 *
 * <p>It was {@code confighost/QitsOidcClientEnvTest}'s second half; the first, about the {@code
 * qits} oidc client and the neutralised {@code configuration} stub, went with both (qits-1393). The
 * {@code QUARKUS_OIDC_CLIENT_CONFIGURATION_*} NAMES outlived the client: they are where the pair has
 * reached this container since qits-configuration's days, and the provisioner still falls back to
 * them behind {@code QITS_RESOURCE_IDP_*}.
 *
 * <p><b>Why this cannot be a {@code @QuarkusTest}.</b> A surefire JVM cannot gain an environment
 * variable, so this assembles the real {@link PropertiesConfigSource} over the SHIPPED file and the
 * real {@link EnvConfigSource} over the container's variables, at the ordinals a deployed Quarkus
 * gives them, and asks SmallRye Config directly.
 */
class ProvisionerPairEnvTest {

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

  /** The bootstrap pair under its old names, and no {@code QITS_RESOURCE_IDP_*}; sentinel values. */
  private static Map<String, String> liveEnvironment() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_ENVIRONMENT", "dev");
    env.put("QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID", "sentinel-live-client-id");
    env.put("QUARKUS_OIDC_CLIENT_CONFIGURATION_CREDENTIALS_SECRET", "sentinel-live-secret");
    return env;
  }

  private static SmallRyeConfig config(Map<String, String> environment) throws IOException {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
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
  void theProvisionerFallsBackToTheBootstrapPair() throws IOException {
    SmallRyeConfig config = config(liveEnvironment());

    assertEquals("sentinel-live-client-id", value(config, "qits.deployments.idp.client-id"));
    assertEquals("sentinel-live-secret", value(config, "qits.deployments.idp.client-secret"));
  }

  @Test
  void anInjectedResourcePairOutranksTheBootstrapPair() throws IOException {
    Map<String, String> env = liveEnvironment();
    env.put("QITS_RESOURCE_IDP_CLIENT_ID", "resource-client-id");
    env.put("QITS_RESOURCE_IDP_CLIENT_SECRET", "resource-secret");
    SmallRyeConfig config = config(env);

    assertEquals("resource-client-id", value(config, "qits.deployments.idp.client-id"));
    assertEquals("resource-secret", value(config, "qits.deployments.idp.client-secret"));
  }

  @Test
  void noVariableOfItsOwnCanShadowThePair() throws IOException {
    // The retired QITS_PLATFORM_DEPLOYMENTS_IDP_* spelling, set by a stray entry next to the
    // injected resource pair, must not shadow it: the rotate guard reads this id as "who am I".
    Map<String, String> env = liveEnvironment();
    env.put("QITS_RESOURCE_IDP_CLIENT_ID", "resource-client-id");
    env.put("QITS_PLATFORM_DEPLOYMENTS_IDP_CLIENT_ID", "stray-client-id");
    env.put("QITS_PLATFORM_DEPLOYMENTS_IDP_CLIENT_SECRET", "stray-secret");
    SmallRyeConfig config = config(env);

    assertEquals("resource-client-id", value(config, "qits.deployments.idp.client-id"));
    assertEquals("sentinel-live-secret", value(config, "qits.deployments.idp.client-secret"));
  }
}
