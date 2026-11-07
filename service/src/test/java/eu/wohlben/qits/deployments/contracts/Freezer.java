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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Freezes one state's answer</b> (a copy of qits-projects-service's) so a re-recording against fresh ids is byte-identical — the
 * golden-master format's freezing rules, and nothing else:
 *
 * <ul>
 *   <li><b>Ids.</b> Every distinct UUID, wherever it sits in a string value, becomes {@code
 *       00000000-0000-4000-8000-00000000000N} (N in hex, the last group 12 digits). The mapping is
 *       seeded with the state's params first ({@link #seed(Map, Map)}), which is how a state's
 *       first {@code repositoryId} is {@code …0001}; every UUID met after that takes the highest
 *       number assigned so far plus one. A param's numbers are <b>sticky to the committed
 *       index</b>: a UUID in a param value — the whole value, or embedded in it, as in a cursor's
 *       {@code <instant>,<uuid>} — keeps the n of the {@code frozenId(n)} at the same place in the
 *       committed frozen value, the other params number above every number taken (in key order),
 *       and the answer's UUIDs above those. So removing a param leaves a gap rather than
 *       renumbering every param after it (qits-1908 removed one in qits-projects-service and
 *       shifted them all, qits-1915, qits-1917). With no committed index the numbering is by first
 *       appearance, params in key order first. <b>Known limit:</b> an id that only the answer
 *       holds is not pinned — when a param is ADDED it takes a number above the pinned ones, and
 *       every answer-only id moves up past it. A consumer therefore joins only on param ids, never
 *       on an answer-only frozen id.
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

  private static final Pattern FROZEN_ID = Pattern.compile("00000000-0000-4000-8000-[0-9a-f]{12}");

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final Map<String, String> ids = new HashMap<>();

  /** The highest id number assigned so far: the next new UUID takes one above it. */
  private int highestId;
  private final Map<String, String> tokens = new LinkedHashMap<>();
  private int tokenCount;
  private final Set<String> idPaths = new LinkedHashSet<>();
  private final Set<String> instantPaths = new LinkedHashSet<>();
  private final Set<String> stringPaths = new LinkedHashSet<>();

  /**
   * Numbers each UUID in {@code values}, in order, ahead of anything the answer holds — {@link
   * #seed(Map, Map)} with no committed index to pin to.
   */
  public Freezer seed(Collection<String> values) {
    for (String value : values) {
      freezeIds(value);
    }
    return this;
  }

  /**
   * Numbers the state's params ahead of anything the answer holds, sticky to the committed index.
   *
   * <ol>
   *   <li>For each param in key order: if its raw value holds k UUIDs — the whole value being one
   *       (k = 1), or embedded in it, as in a cursor's {@code <instant>,<uuid>} — and its {@code
   *       committedParams} entry holds exactly k UUIDs, each a {@code frozenId(n)}, the i-th raw
   *       UUID keeps the i-th n. A raw UUID already numbered (two params naming one entity) keeps
   *       that first number; an n another raw UUID already holds (the committed index pinned one
   *       number to two params that now name different entities) is not pinned, and that UUID
   *       numbers as a new one below. When the counts differ, nothing in that param is pinned.
   *   <li>Every param's remaining UUIDs — new params, unpinned ones — then number in key order
   *       above the highest number taken so far, pinned ones included.
   * </ol>
   *
   * <p>With no committed params (no index, or a state it does not hold yet) this is exactly {@link
   * #seed(Collection)} over the values in key order.
   *
   * @param params the state's raw params
   * @param committedParams the same state's frozen params in the committed {@code index.json}
   */
  public Freezer seed(Map<String, String> params, Map<String, String> committedParams) {
    Map<String, String> sorted = new TreeMap<>(params);
    for (Map.Entry<String, String> param : sorted.entrySet()) {
      List<String> raw = uuidsIn(param.getValue());
      List<Integer> committed = frozenNumbers(committedParams.get(param.getKey()));
      if (raw.isEmpty() || raw.size() != committed.size()) {
        continue;
      }
      for (int i = 0; i < raw.size(); i++) {
        String original = raw.get(i).toLowerCase(Locale.ROOT);
        int n = committed.get(i);
        if (ids.containsKey(original) || ids.containsValue(frozenId(n))) {
          continue;
        }
        ids.put(original, frozenId(n));
        highestId = Math.max(highestId, n);
      }
    }
    for (String value : sorted.values()) {
      freezeIds(value);
    }
    return this;
  }

  /** The UUIDs in a value, in reading order; empty for {@code null}. */
  private static List<String> uuidsIn(String value) {
    List<String> out = new ArrayList<>();
    if (value != null) {
      Matcher m = UUID.matcher(value);
      while (m.find()) {
        out.add(m.group());
      }
    }
    return out;
  }

  /**
   * The n of each UUID in a committed value, in reading order — empty when there is none, or when
   * any of them is not exactly some {@code frozenId(n)}, n > 0 (nothing in that value pins).
   */
  private static List<Integer> frozenNumbers(String committed) {
    List<Integer> out = new ArrayList<>();
    for (String id : uuidsIn(committed)) {
      if (!FROZEN_ID.matcher(id).matches()) {
        return List.of();
      }
      long n = Long.parseLong(id.substring(id.length() - 12), 16);
      if (n <= 0 || n > Integer.MAX_VALUE || !frozenId((int) n).equals(id)) {
        return List.of();
      }
      out.add((int) n);
    }
    return out;
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
      String original = m.group().toLowerCase(Locale.ROOT);
      String frozen = ids.computeIfAbsent(original, k -> frozenId(++highestId));
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
