package eu.wohlben.qits.deployments.deployments.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.ArtifactPurge;
import eu.wohlben.qits.deployments.deployments.entity.PdDecommission;
import java.time.Instant;
import java.util.List;

/**
 * One decommission run and what its purge of the application's published artifacts came to — a
 * {@code pd_decommission} row on the wire (qits-1956). Written by {@code POST
 * /applications/{applicationId}/decommission} (qits-1941), read by {@code GET
 * /applications/{applicationId}/decommissions}.
 *
 * <p><b>One per run, never updated</b>, so a failed purge followed by a successful retry is two
 * entries — {@code FAILED} then {@code PURGED} — and the listing says the purge failed and then
 * succeeded rather than only that it succeeded.
 *
 * <p><b>{@code removed} is null exactly when the purge FAILED, and that is not the same answer as
 * an empty list.</b> An empty list is a purge that found nothing left — the second run of a
 * decommission, say; null is a purge that never got an answer, and {@code failureDetail} says why.
 * A client that folded the two into "nothing removed" would read a failed purge as a clean one.
 *
 * <p>{@code actor} is who asked, null for "an operator" — the same reading the deployment row's
 * stamp gives it. {@code environmentId} is the tier of the row the run settled, copied rather than
 * linked: history outlives the tier.
 *
 * @param repositories the repositories the purge named: the caller's and the release ledger's,
 *     deduplicated and sorted
 * @param removed what qits-artifacts reported it removed; empty when nothing was left, null when
 *     the purge failed
 * @param failureDetail why the purge failed; null when it did not
 */
public record PdDecommissionDto(
    String id,
    String applicationName,
    String environmentId,
    String actor,
    Instant decommissionedAt,
    List<String> repositories,
    PdDecommission.PurgeOutcome purgeOutcome,
    List<ArtifactPurge.RemovedArtifact> removed,
    String failureDetail) {

  private static final TypeReference<List<String>> NAMES = new TypeReference<>() {};
  private static final TypeReference<List<ArtifactPurge.RemovedArtifact>> REMOVED =
      new TypeReference<>() {};

  /**
   * The row's wire shape. The two {@code jsonb} columns hold the JSON text {@code
   * ApplicationRetirement.record} wrote, so they are read back with the same kind of mapper — the
   * inverse of that write, and nothing more: a null {@code removed} column stays null.
   *
   * <p>A factory on the DTO taking the entity, rather than a mapper bean, so qits-1945's exposure
   * analysis sees the link from {@link PdDecommission} to this type in one place.
   *
   * @param json the application's mapper; only ever used to read
   * @throws IllegalStateException when a stored column is not the JSON the write produced — a row
   *     somebody edited by hand, and worth a 500 that names it rather than a silently empty list
   */
  public static PdDecommissionDto of(PdDecommission row, ObjectMapper json) {
    return new PdDecommissionDto(
        row.id == null ? null : row.id.toString(),
        row.applicationName,
        row.environmentId,
        row.actor,
        row.decommissionedAt,
        row.repositories == null ? List.of() : read(json, row, "repositories", row.repositories, NAMES),
        row.purgeOutcome,
        row.removed == null ? null : read(json, row, "removed", row.removed, REMOVED),
        row.failureDetail);
  }

  private static <T> T read(
      ObjectMapper json, PdDecommission row, String column, String text, TypeReference<T> type) {
    try {
      return json.readValue(text, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "pd_decommission " + row.id + " holds a " + column + " column that is not its JSON list",
          e);
    }
  }
}
