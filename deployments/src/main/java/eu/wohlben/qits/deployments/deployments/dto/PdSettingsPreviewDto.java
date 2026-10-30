package eu.wohlben.qits.deployments.deployments.dto;

import java.util.List;

/**
 * What the next deployment of one application would change in its live environment.
 *
 * <p>{@code added}: keys the next deployment states and the live service lacks. {@code removed}:
 * keys the live service carries and the next deployment does not state. {@code changed}: keys on
 * both sides with different values. {@code unchanged}: how many keys are equal on both sides. Every
 * list is sorted by key.
 *
 * <p>A value is null — on both sides of a change — whenever {@code secret} is true: the key is a
 * setting stored as secret, or its name looks like one (it contains {@code SECRET}, {@code TOKEN},
 * {@code PASSWORD} or {@code CREDENTIAL}, or ends with {@code _AUTH}).
 */
public record PdSettingsPreviewDto(
    String application,
    String environment,
    List<PreviewEntry> added,
    List<PreviewEntry> removed,
    List<PreviewChange> changed,
    int unchanged) {

  /** One variable present on one side only. */
  public record PreviewEntry(String key, String value, boolean secret) {}

  /** One variable present on both sides with different values. */
  public record PreviewChange(String key, String from, String to, boolean secret) {}
}
