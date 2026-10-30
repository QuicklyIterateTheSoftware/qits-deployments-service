package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.dto.PdSettingsPreviewDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The settings preview's arithmetic: added, removed, changed, unchanged — and the mask. */
class SettingsDiffTest {

  /** No key is exempt — every live-only key is a removal. */
  private static PdSettingsPreviewDto diff(
      String application,
      String environment,
      Map<String, String> next,
      Map<String, String> running,
      Set<String> secretKeys) {
    return SettingsDiff.diff(
        application, environment, false, next, running, secretKeys, key -> false);
  }

  @Test
  void aLiveKeyTheUpdateKeepsIsKeptNotRemoved() {
    // The update never --env-rm's the deployer's own variables or the resource family, so a
    // preview listing them as removed would be a false alarm about the switch.
    PdSettingsPreviewDto diff =
        SettingsDiff.diff(
            "app",
            "dev",
            false,
            Map.of("QITS_APPLICATION", "app"),
            Map.of(
                "QITS_APPLICATION", "app",
                "QITS_RESOURCE_DB_URL", "jdbc:postgresql://h/db",
                "QITS_RESOURCE_DB_PASSWORD", "p",
                "FROM_EXTRAS", "x"),
            Set.of(),
            key -> key.startsWith("QITS_RESOURCE_"));

    assertFalse(diff.extrasIncluded());
    assertEquals(List.of(new PdSettingsPreviewDto.PreviewEntry("FROM_EXTRAS", "x", false)), diff.removed());
    assertEquals(
        List.of(
            new PdSettingsPreviewDto.PreviewEntry("QITS_RESOURCE_DB_PASSWORD", null, true),
            new PdSettingsPreviewDto.PreviewEntry("QITS_RESOURCE_DB_URL", "jdbc:postgresql://h/db", false)),
        diff.kept());
    assertEquals(1, diff.unchanged());
  }

  @Test
  void aKeptKeyTheNextDeploymentStatesIsComparedNormally() {
    PdSettingsPreviewDto diff =
        SettingsDiff.diff(
            "app",
            "dev",
            true,
            Map.of("QITS_RESOURCE_DB_URL", "new"),
            Map.of("QITS_RESOURCE_DB_URL", "old"),
            Set.of(),
            key -> true);

    assertTrue(diff.extrasIncluded());
    assertTrue(diff.kept().isEmpty());
    assertEquals(
        List.of(new PdSettingsPreviewDto.PreviewChange("QITS_RESOURCE_DB_URL", "old", "new", false)),
        diff.changed());
  }

  @Test
  void addedRemovedChangedAndUnchangedAreSortedByKey() {
    PdSettingsPreviewDto diff =
        diff(
            "app",
            "dev",
            Map.of("B_NEW", "1", "A_NEW", "2", "SAME", "x", "MOVED", "new"),
            Map.of("SAME", "x", "MOVED", "old", "GONE", "bye"),
            Set.of());

    assertEquals("app", diff.application());
    assertEquals("dev", diff.environment());
    assertEquals(
        List.of(
            new PdSettingsPreviewDto.PreviewEntry("A_NEW", "2", false),
            new PdSettingsPreviewDto.PreviewEntry("B_NEW", "1", false)),
        diff.added());
    assertEquals(List.of(new PdSettingsPreviewDto.PreviewEntry("GONE", "bye", false)), diff.removed());
    assertEquals(
        List.of(new PdSettingsPreviewDto.PreviewChange("MOVED", "old", "new", false)), diff.changed());
    assertEquals(1, diff.unchanged());
  }

  @Test
  void nothingRunningMakesEverythingAdded() {
    PdSettingsPreviewDto diff =
        diff("app", "dev", Map.of("A", "1", "B", "2"), Map.of(), Set.of());

    assertEquals(2, diff.added().size());
    assertTrue(diff.removed().isEmpty());
    assertTrue(diff.changed().isEmpty());
    assertEquals(0, diff.unchanged());
  }

  @Test
  void aSecretSettingIsMaskedOnBothSidesAndStillCountsAsAChange() {
    PdSettingsPreviewDto diff =
        diff(
            "app",
            "dev",
            Map.of("PLAIN_NAME", "new-value", "ADDED_SECRET_SETTING_X", "s"),
            Map.of("PLAIN_NAME", "old-value"),
            Set.of("PLAIN_NAME", "ADDED_SECRET_SETTING_X"));

    assertEquals(
        List.of(new PdSettingsPreviewDto.PreviewChange("PLAIN_NAME", null, null, true)), diff.changed());
    assertEquals(
        List.of(new PdSettingsPreviewDto.PreviewEntry("ADDED_SECRET_SETTING_X", null, true)),
        diff.added());
  }

  @Test
  void aKeyThatLooksSecretIsMaskedEvenWithoutASetting() {
    PdSettingsPreviewDto diff =
        diff(
            "app",
            "dev",
            Map.of(
                "QITS_RESOURCE_DB_PASSWORD", "p2",
                "API_TOKEN", "t",
                "QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH", "true"),
            Map.of("QITS_RESOURCE_DB_PASSWORD", "p1", "OLD_CREDENTIAL_FILE", "c"),
            Set.of());

    assertEquals(
        List.of(new PdSettingsPreviewDto.PreviewChange("QITS_RESOURCE_DB_PASSWORD", null, null, true)),
        diff.changed());
    assertEquals(
        List.of(
            new PdSettingsPreviewDto.PreviewEntry("API_TOKEN", null, true),
            new PdSettingsPreviewDto.PreviewEntry("QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH", null, true)),
        diff.added());
    assertEquals(
        List.of(new PdSettingsPreviewDto.PreviewEntry("OLD_CREDENTIAL_FILE", null, true)), diff.removed());
  }

  @Test
  void anEqualSecretIsUnchangedAndNeverListed() {
    PdSettingsPreviewDto diff =
        diff(
            "app", "dev", Map.of("X_SECRET", "same"), Map.of("X_SECRET", "same"), Set.of());

    assertEquals(1, diff.unchanged());
    assertTrue(diff.changed().isEmpty());
  }

  @Test
  void looksSecretReadsTheNameOnly() {
    assertTrue(SettingsDiff.looksSecret("QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET"));
    assertTrue(SettingsDiff.looksSecret("GITHUB_TOKEN"));
    assertTrue(SettingsDiff.looksSecret("db_password"));
    assertTrue(SettingsDiff.looksSecret("REGISTRY_AUTH"));
    assertFalse(SettingsDiff.looksSecret("AUTHOR"));
    assertFalse(SettingsDiff.looksSecret("QITS_DOMAIN"));
    assertFalse(SettingsDiff.looksSecret("AUTH_MODE"));
  }

  @Test
  void parseLetsTheLastEntryOfAKeyWinAsTheArgvDoes() {
    Map<String, String> parsed =
        SettingsDiff.parse(List.of("A=1", "B=x=y", "A=2", "EMPTY=", "BARE", "=junk"));

    assertEquals("2", parsed.get("A"));
    assertEquals("x=y", parsed.get("B"));
    assertEquals("", parsed.get("EMPTY"));
    assertEquals("", parsed.get("BARE"));
    assertNull(parsed.get(""));
    assertEquals(4, parsed.size());
  }
}
