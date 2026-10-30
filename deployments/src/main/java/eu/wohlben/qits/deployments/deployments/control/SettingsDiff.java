package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.dto.PdSettingsPreviewDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The settings preview's arithmetic, with no bean and no I/O in it: two environments in, the
 * difference out, masked.
 *
 * <p><b>Masking is decided per KEY and applies to both sides.</b> A key is masked when the settings
 * store holds it as a secret, or when its name looks like one ({@link #looksSecret}) — the second
 * rule covers what the deployment config and the resource family carry, which have no secret flag
 * of their own ({@code QITS_RESOURCE_DB_PASSWORD}, an {@code *_CLIENT_SECRET}). A masked change
 * still counts as a change: the preview says THAT a secret moves, never what to.
 */
public final class SettingsDiff {

  private static final List<String> SECRET_WORDS =
      List.of("SECRET", "TOKEN", "PASSWORD", "CREDENTIAL");

  private SettingsDiff() {}

  /** Whether a variable's NAME says its value is a secret. */
  public static boolean looksSecret(String key) {
    String upper = key == null ? "" : key.toUpperCase(Locale.ROOT);
    return upper.endsWith("_AUTH") || SECRET_WORDS.stream().anyMatch(upper::contains);
  }

  /**
   * {@code KEY=VALUE} entries as a map, the LAST entry of a key winning — the argv's own precedence,
   * where a later {@code --env} of a key replaces an earlier one. An entry with no {@code =} names a
   * key with an empty value, which is how docker reads one too.
   */
  public static Map<String, String> parse(List<String> variables) {
    Map<String, String> parsed = new LinkedHashMap<>();
    for (String variable : variables == null ? List.<String>of() : variables) {
      if (variable == null || variable.isEmpty()) {
        continue;
      }
      int equals = variable.indexOf('=');
      if (equals == 0) {
        continue;
      }
      parsed.put(
          equals < 0 ? variable : variable.substring(0, equals),
          equals < 0 ? "" : variable.substring(equals + 1));
    }
    return parsed;
  }

  /**
   * The difference between what the next deployment would state ({@code next}) and what the live
   * service carries ({@code running} — empty when it is not running, which makes everything
   * added). {@code secretKeys} are the keys the settings store holds as secret. {@code
   * keptWhenUnstated} is the update's own exemption ({@code DeploymentDriver#keptWhenUnstated}): a
   * live key it answers true for, and the next deployment does not state, is {@code kept} rather
   * than {@code removed}, because the update leaves it in place.
   */
  public static PdSettingsPreviewDto diff(
      String application,
      String environment,
      boolean extrasIncluded,
      Map<String, String> next,
      Map<String, String> running,
      Set<String> secretKeys,
      Predicate<String> keptWhenUnstated) {
    List<PdSettingsPreviewDto.PreviewEntry> added = new ArrayList<>();
    List<PdSettingsPreviewDto.PreviewEntry> removed = new ArrayList<>();
    List<PdSettingsPreviewDto.PreviewChange> changed = new ArrayList<>();
    List<PdSettingsPreviewDto.PreviewEntry> kept = new ArrayList<>();
    int unchanged = 0;
    Set<String> keys = new TreeSet<>(next.keySet());
    keys.addAll(running.keySet());
    for (String key : keys) {
      boolean secret = secretKeys.contains(key) || looksSecret(key);
      boolean inNext = next.containsKey(key);
      boolean inRunning = running.containsKey(key);
      if (inNext && !inRunning) {
        added.add(new PdSettingsPreviewDto.PreviewEntry(key, mask(next.get(key), secret), secret));
      } else if (!inNext && keptWhenUnstated.test(key)) {
        kept.add(new PdSettingsPreviewDto.PreviewEntry(key, mask(running.get(key), secret), secret));
      } else if (!inNext) {
        removed.add(new PdSettingsPreviewDto.PreviewEntry(key, mask(running.get(key), secret), secret));
      } else if (next.get(key).equals(running.get(key))) {
        unchanged++;
      } else {
        changed.add(
            new PdSettingsPreviewDto.PreviewChange(
                key, mask(running.get(key), secret), mask(next.get(key), secret), secret));
      }
    }
    return new PdSettingsPreviewDto(
        application,
        environment,
        extrasIncluded,
        List.copyOf(added),
        List.copyOf(removed),
        List.copyOf(changed),
        List.copyOf(kept),
        unchanged);
  }

  private static String mask(String value, boolean secret) {
    return secret ? null : value;
  }
}
