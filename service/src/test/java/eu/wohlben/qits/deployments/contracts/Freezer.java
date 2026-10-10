package eu.wohlben.qits.deployments.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Freezes one state's answer</b> (a copy of qits-projects-service's) so a re-recording against fresh ids is byte-identical — the
 * golden-master format's freezing rules, and nothing else:
 *
 * <ul>
 *   <li><b>Ids.</b> Every distinct UUID, wherever it sits in a string value, becomes {@code
 *       00000000-0000-4000-8000-00000000000N} (N in hex, the last group 12 digits), numbered by first
 *       appearance. The mapping is seeded with the state's params first ({@link #seed}), which is
 *       how a state's first {@code repositoryId} is {@code …0001}.
 *   <li><b>Instants.</b> Every string value that is an ISO-8601 instant becomes {@value
 *       #FROZEN_INSTANT} — by shape, not by a list of field names.
 *   <li><b>Unique tokens.</b> A random token a state had to put into a name to keep it unique becomes the same-length hex counter {@code 0…0N},
 *       numbered by first appearance.
 * </ul>
 *
 * <p>Every value it changes is recorded as a JSONPath, by what the WHOLE value was: a UUID goes in
 * {@link #idPaths}, an instant in {@link #instantPaths}, and a string that merely contains a frozen
 * id or token (a message, a url, a slug) in {@link #stringPaths}. Paths are {@code $.a.b}, array
 * elements as {@code [*]}, in order of first appearance and de-duplicated — the index's {@code
 * frozen.ids}, {@code frozen.instants} and {@code frozen.strings}.
 */
public final class Freezer {

  static final Pattern UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final Map<String, String> ids = new HashMap<>();
  private final Map<String, String> tokens = new LinkedHashMap<>();
  private int tokenCount;
  private final Set<String> idPaths = new LinkedHashSet<>();
  private final Set<String> instantPaths = new LinkedHashSet<>();
  private final Set<String> stringPaths = new LinkedHashSet<>();

  /** Numbers each UUID in {@code values}, in order, ahead of anything the answer holds. */
  public Freezer seed(Collection<String> values) {
    for (String value : values) {
      freezeIds(value);
    }
    return this;
  }

  /**
   * Registers the random tokens a state put into names. Numbered when first met in the answer, not
   * here: a token the answer never shows takes no number.
   */
  public Freezer uniqueTokens(Collection<String> values) {
    for (String token : values) {
      if (token == null || token.isEmpty()) {
        throw new IllegalArgumentException("an empty unique token would match everywhere");
      }
      tokens.putIfAbsent(token, null);
    }
    return this;
  }

  /** A state param, frozen as an answer's string is: its ids and its unique tokens. */
  public String freezeParam(String value) {
    return freezeTokens(freezeIds(value));
  }

  public JsonNode freeze(JsonNode node) {
    return freeze(node, "$");
  }

  public List<String> idPaths() {
    return new ArrayList<>(idPaths);
  }

  public List<String> instantPaths() {
    return new ArrayList<>(instantPaths);
  }

  public List<String> stringPaths() {
    return new ArrayList<>(stringPaths);
  }

  /** {@code 00000000-0000-4000-8000-} plus {@code n} as 12 hex digits. */
  public static String frozenId(int n) {
    return String.format("00000000-0000-4000-8000-%012x", n);
  }

  private JsonNode freeze(JsonNode node, String path) {
    if (node == null || node.isNull()) {
      return node;
    }
    if (node.isTextual()) {
      return TextNode.valueOf(freezeText(node.asText(), path));
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode element : node) {
        out.add(freeze(element, path + "[*]"));
      }
      return out;
    }
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String key = field.getKey();
        if (UUID.matcher(key).find()) {
          // A JSONPath cannot name "whatever this key is frozen to"; fail loudly rather than
          // record a file no consumer could match.
          throw new IllegalStateException("A UUID in a key cannot be frozen: " + path + " / " + key);
        }
        out.set(key, freeze(field.getValue(), path + segment(key)));
      }
      return out;
    }
    return node;
  }

  private String freezeText(String value, String path) {
    if (INSTANT.matcher(value).matches()) {
      instantPaths.add(path);
      return FROZEN_INSTANT;
    }
    String result = freezeTokens(freezeIds(value));
    if (result.equals(value)) {
      return result;
    }
    // A consumer puts a uuid matcher on every frozen.ids path, so only a value that IS a UUID goes
    // there; a string that merely contains a frozen id or token is type-matched, in frozen.strings.
    if (UUID.matcher(value).matches()) {
      idPaths.add(path);
    } else {
      stringPaths.add(path);
    }
    return result;
  }

  private String freezeIds(String value) {
    if (value == null) {
      return null;
    }
    Matcher m = UUID.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String original = m.group().toLowerCase(java.util.Locale.ROOT);
      String frozen = ids.computeIfAbsent(original, k -> frozenId(ids.size() + 1));
      m.appendReplacement(out, Matcher.quoteReplacement(frozen));
    }
    m.appendTail(out);
    return out.toString();
  }

  private String freezeTokens(String value) {
    // One left-to-right pass over the original, so two tokens in one string are numbered in reading
    // order and a replacement is never scanned again.
    StringBuilder out = new StringBuilder();
    int from = 0;
    while (true) {
      String earliest = null;
      int at = Integer.MAX_VALUE;
      for (String token : tokens.keySet()) {
        int i = value.indexOf(token, from);
        if (i >= 0 && i < at) {
          at = i;
          earliest = token;
        }
      }
      if (earliest == null) {
        return out.append(value, from, value.length()).toString();
      }
      String frozen = tokens.get(earliest);
      if (frozen == null) {
        frozen = String.format("%0" + earliest.length() + "x", ++tokenCount);
        tokens.put(earliest, frozen);
      }
      out.append(value, from, at).append(frozen);
      from = at + earliest.length();
    }
  }

  private static String segment(String key) {
    if (PLAIN_KEY.matcher(key).matches()) {
      return "." + key;
    }
    return "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
  }
}
