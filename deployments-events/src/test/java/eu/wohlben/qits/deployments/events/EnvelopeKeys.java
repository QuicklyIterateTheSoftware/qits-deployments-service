package eu.wohlben.qits.deployments.events;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * The envelope's key set, as the plan fixes it.
 *
 * <p>qits-eventstream-javalib 2026.1126.192104 (qits-2701) added the nullable envelope fields
 * {@code onBehalfOf} and {@code originTraceId}. Both key sets are the plan's shape, so the contract
 * holds on either side of that bump and never depends on the order it is folded in (qits-2730).
 * Any other key set still fails.
 */
final class EnvelopeKeys {

  /** The envelope before qits-2701. */
  static final List<String> BEFORE =
      List.of("description", "environment", "name", "occurredAt", "parentId", "payload", "publisher");

  /** The envelope from qits-2701 on: the same keys plus onBehalfOf and originTraceId. */
  static final List<String> AFTER =
      List.of(
          "description",
          "environment",
          "name",
          "occurredAt",
          "onBehalfOf",
          "originTraceId",
          "parentId",
          "payload",
          "publisher");

  private EnvelopeKeys() {}

  /** Asserts the envelope's keys are exactly {@link #BEFORE} or exactly {@link #AFTER}, in order. */
  static void assertThePlansKeys(JsonNode envelope) {
    List<String> keys = envelope.properties().stream().map(Map.Entry::getKey).toList();
    assertTrue(keys.equals(BEFORE) || keys.equals(AFTER), "envelope keys were " + keys);
  }
}
