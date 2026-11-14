package eu.wohlben.qits.deployments.deployments.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * One database move as the read surface serves it (qits-2175): where from, where to, how far it got,
 * what both sides counted, and when the kept copy goes.
 *
 * <p>{@code sourceRows}/{@code targetRows} are the manifests' totals, null until that side was read —
 * the two numbers the move's own log line names, so a reader can compare them without summing.
 *
 * <p>{@code sourceBytes}, {@code targetFreeBytes} and {@code volumeFreeBytes} are what the space check
 * measured before anything stopped (qits-2243): the source's size, and the free bytes of the target
 * data directory's filesystem and of the move volume's. Null on a move that measured nothing.
 */
public record PdDatabaseMoveDto(
    String id,
    String batchId,
    String applicationName,
    String environmentName,
    String resourceName,
    String sourceServer,
    String sourceDatabase,
    String targetServer,
    String targetDatabase,
    String specServer,
    String specDatabase,
    String keptDatabase,
    String origin,
    String requestedBy,
    String state,
    Map<String, Long> sourceCounts,
    Map<String, Long> targetCounts,
    Long sourceRows,
    Long targetRows,
    Long sourceBytes,
    Long targetFreeBytes,
    Long volumeFreeBytes,
    String detail,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Instant dropAfter,
    Instant droppedAt) {

  private static final TypeReference<TreeMap<String, Long>> COUNTS = new TypeReference<>() {};

  public static PdDatabaseMoveDto of(PdDatabaseMove row, ObjectMapper json) {
    Map<String, Long> source = counts(json, row, "source_counts", row.sourceCounts);
    Map<String, Long> target = counts(json, row, "target_counts", row.targetCounts);
    return new PdDatabaseMoveDto(
        row.id,
        row.batchId,
        row.applicationName,
        row.environmentName,
        row.resourceName,
        row.sourceServer,
        row.sourceDatabase,
        row.targetServer,
        row.targetDatabase,
        row.specServer,
        row.specDatabase,
        row.keptDatabase,
        row.origin == null ? null : row.origin.name(),
        row.requestedBy,
        row.state == null ? null : row.state.name(),
        source,
        target,
        total(source),
        total(target),
        row.sourceBytes,
        row.targetFreeBytes,
        row.volumeFreeBytes,
        row.detail,
        row.createdAt,
        row.startedAt,
        row.finishedAt,
        row.dropAfter,
        row.droppedAt);
  }

  private static Long total(Map<String, Long> counts) {
    return counts == null ? null : counts.values().stream().mapToLong(Long::longValue).sum();
  }

  private static Map<String, Long> counts(
      ObjectMapper json, PdDatabaseMove row, String column, String text) {
    if (text == null) {
      return null;
    }
    try {
      return json.readValue(text, COUNTS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "pd_database_move " + row.id + " holds a " + column + " column that is not its manifest",
          e);
    }
  }
}
