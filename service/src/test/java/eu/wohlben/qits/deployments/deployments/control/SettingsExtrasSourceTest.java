package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The deployer's one extras source, against the real store (qits-1393): {@code pd_setting} rows of
 * one place become that application's {@code env} family, and nothing else does.
 *
 * <p>Every case uses names of its own, because the suite shares one database across classes.
 */
@QuarkusTest
class SettingsExtrasSourceTest {

  @Inject SettingsExtrasSource source;

  @Inject Settings settings;

  private static List<String> env(Config config, String application) {
    return ServiceExtras.of(config, application).env();
  }

  @Test
  void thePlacesRowsBecomeItsEnvAndNoOtherPlacesDo() {
    settings.put("ses-app", "ses-dev", "PEER_URL", "http://dev-peer:8080", false, "an operator");
    settings.put("ses-app", "ses-dev", "EMPTY_FLAG", "", false, "an operator");
    // The same application in another tier, and another application in the same tier: neither may
    // reach this place's argv.
    settings.put("ses-app", "ses-prod", "PEER_URL", "http://prod-peer:8080", false, "an operator");
    settings.put("ses-other", "ses-dev", "OTHER_ONLY", "x", false, "an operator");

    Config config = source.forDeployment("ses-app", "ses-dev");

    assertEquals(List.of("EMPTY_FLAG=", "PEER_URL=http://dev-peer:8080"), env(config, "ses-app"));
    assertEquals(List.of(), env(config, "ses-other"), "another application's rows are not rendered");
    assertEquals(
        "http://dev-peer:8080",
        config.getValue(DeploymentDriver.EXTRAS_PREFIX + "ses-app.env.PEER_URL", String.class),
        "the rows arrive in the full prefixed spelling ServiceExtras parses");
  }

  @Test
  void aSecretRowRendersVerbatim() {
    // `secret` decides how a value is SHOWN at the doors — never whether the container gets it.
    settings.put("ses-secret", "ses-dev", "SIGNING_KEY", "s3cr3t-value", true, "an operator");

    Config config = source.forDeployment("ses-secret", "ses-dev");

    assertEquals(List.of("SIGNING_KEY=s3cr3t-value"), env(config, "ses-secret"));
  }

  @Test
  void aDeletedRowIsAbsentFromTheNextSnapshot() {
    settings.put("ses-deleted", "ses-dev", "KEEP", "1", false, "an operator");
    settings.put("ses-deleted", "ses-dev", "GONE", "2", false, "an operator");
    Config before = source.forDeployment("ses-deleted", "ses-dev");

    settings.delete("ses-deleted", "ses-dev", "GONE");
    Config after = source.forDeployment("ses-deleted", "ses-dev");

    assertEquals(List.of("GONE=2", "KEEP=1"), env(before, "ses-deleted"), "one snapshot, fixed");
    assertEquals(List.of("KEEP=1"), env(after, "ses-deleted"));
  }

  @Test
  void aPlaceWithNoRowsHasNoExtrasAndStillAnswersTheBootConfig() {
    Config config = source.forDeployment("ses-nobody", "ses-dev");

    assertTrue(ServiceExtras.of(config, "ses-nobody").isEmpty());
    assertTrue(
        config.getOptionalValue("qits.deployments.legacy-network", String.class).isPresent(),
        "a non-extras key falls through to the boot config");
  }
}
