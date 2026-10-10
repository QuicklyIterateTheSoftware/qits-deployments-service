package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The committed consumer pacts, {@code pacts/qits-deployments-service_<provider>.json}</b>
 * (ticket qits-1149), one per provider with at least one ready row. A copy of
 * qits-maintenance-service's {@code ProjectsPactFileTest}.
 *
 * <p><b>Compare by default.</b> The test normalises what pact-jvm writes (interactions sorted by
 * description then provider state, pact-jvm's own version stripped, 2-space indentation, a trailing
 * newline) and compares it byte for byte with the committed file; {@code -Dgolden.update=true} (or
 * {@code QITS_GOLDEN_UPDATE=true}) rewrites it instead. A provider with no ready row has no file:
 * an empty pact would be published for nothing.
 */
class PactFileTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> theCommittedPactIsWhatTheContractWrites() {
    return Contracts.all().stream()
        .map(contract -> DynamicTest.dynamicTest(contract.file(), () -> check(contract)));
  }

  private static void check(Contract contract) throws IOException {
    Path committed = GoldenFiles.repositoryRoot().resolve("pacts").resolve(contract.file());
    if (contract.readyRows().isEmpty()) {
      if (GoldenFiles.updating()) {
        Files.deleteIfExists(committed);
      }
      assertFalse(
          Files.exists(committed),
          committed + " is committed, but no row of " + contract.provider() + " is ready");
      return;
    }
    String raw = written(contract);
    Path scratch = Path.of("target", "pacts", contract.file());
    Files.createDirectories(scratch.getParent());
    Files.writeString(scratch, raw);
    String normalised = normalise(raw);
    GoldenFiles.compareOrWrite(committed, normalised);
    references(contract, MAPPER.readTree(normalised));
  }

  /**
   * Both references on every interaction, as the platform reads them: {@code qits-call} naming the
   * provider operation, {@code qits-trigger} naming the kind and the kind's own key, every value a
   * string.
   */
  private static void references(Contract contract, JsonNode pact) {
    assertEquals(Contract.CONSUMER, pact.path("consumer").path("name").asText());
    assertEquals(contract.provider(), pact.path("provider").path("name").asText());
    assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());
    JsonNode interactions = pact.path("interactions");
    assertEquals(contract.readyRows().size(), interactions.size(), "one interaction per ready row");
    Map<String, String> keyOfKind =
        Map.of("operation", "operationId", "event", "event", "schedule", "schedule");
    Set<String> unique = new HashSet<>();
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode references = interaction.path("comments").path("references");
      JsonNode call = references.path("qits-call");
      assertStrings(description, call, "app", "operationId");
      assertEquals(contract.provider(), call.path("app").asText(), description);
      JsonNode trigger = references.path("qits-trigger");
      String kind = trigger.path("kind").asText();
      assertTrue(keyOfKind.containsKey(kind), description + ": unknown trigger kind '" + kind + "'");
      assertStrings(description, trigger, "kind", "app", keyOfKind.get(kind));
      assertEquals(Contract.CONSUMER, trigger.path("app").asText(), description);
      assertTrue(
          description.startsWith(trigger.path(keyOfKind.get(kind)).asText() + ": "),
          description + ": the description leads with the trigger");
      String state = interaction.path("providerStates").path(0).path("name").asText();
      assertTrue(unique.add(description + "\u0000" + state), "(description, state) repeats: " + description);
    }
  }

  private static void assertStrings(String description, JsonNode group, String... keys) {
    assertTrue(group.isObject(), description + ": reference group missing");
    assertEquals(keys.length, group.size(), description + ": " + group + " holds other keys");
    for (String key : keys) {
      JsonNode value = group.path(key);
      assertTrue(
          value.isTextual() && !value.asText().isBlank(),
          description + ": " + key + " must be a non-blank string, got " + value);
    }
  }

  /** The pact as pact-jvm's own writer serialises it. */
  static String written(Contract contract) {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(contract.pact(), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline. */
  static String normalise(String raw) throws IOException {
    ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
    JsonNode metadata = pact.path("metadata");
    if (metadata instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(
          Comparator.comparing((JsonNode i) -> i.path("description").asText())
              .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    StringBuilder out = new StringBuilder();
    print(pact, "", out);
    return out.append('\n').toString();
  }

  /** {@code JSON.stringify(value, null, 2)}: no space before a colon, empty containers as {} / []. */
  private static void print(JsonNode node, String indent, StringBuilder out) throws IOException {
    String inner = indent + "  ";
    if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
        print(field.getValue(), inner, out);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        out.append(inner);
        print(node.get(i), inner, out);
        out.append(i < node.size() - 1 ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else {
      out.append(MAPPER.writeValueAsString(node));
    }
  }
}
