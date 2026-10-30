package eu.wohlben.qits.deployments.deployments.dto;

import java.util.List;

/**
 * What the next deployment of one application would change in its live environment.
 *
 * <p>{@code extrasIncluded}: whether the "next" side carries qits-configuration's extras {@code env}
 * (today's deployment) or not (the default: the deployment after the switch to settings). {@code
 * added}: keys the next deployment states and the live service lacks. {@code removed}: keys the
 * live service carries that the next deployment neither states nor keeps — what an update would
 * {@code --env-rm}. {@code changed}: keys on both sides with different values. {@code kept}: keys
 * the live service carries that the next deployment does not state but an update never removes
 * (the deployer's own identity variables and {@code QITS_RESOURCE_*}). {@code unchanged}: how many
 * keys are equal on both sides. Every list is sorted by key.
 *
 * <p>A value is null — on both sides of a change — whenever {@code secret} is true: the key is a
 * setting stored as secret, or its name looks like one (it contains {@code SECRET}, {@code TOKEN},
 * {@code PASSWORD} or {@code CREDENTIAL}, or ends with {@code _AUTH}).
 */
public record PdSettingsPreviewDto(
    String application,
    String environment,
    boolean extrasIncluded,
    List<PreviewEntry> added,
    List<PreviewEntry> removed,
    List<PreviewChange> changed,
    List<PreviewEntry> kept,
    int unchanged) {

  /** One variable present on one side only. */
  public record PreviewEntry(String key, String value, boolean secret) {}

  /** One variable present on both sides with different values. */
  public record PreviewChange(String key, String from, String to, boolean secret) {}
}
