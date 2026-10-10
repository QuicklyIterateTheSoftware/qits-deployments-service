package eu.wohlben.qits.deployments.pacts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>One provider's recorded answers, as this repository's consumer pacts read them</b> (epic
 * qits-546, ticket qits-1149). The provider publishes {@code golden-masters/index.json} plus one
 * JSON per (state, operation) as {@code eu.wohlben.qits:<app>-golden-masters}, a test-scoped pin in
 * the root pom. Modeled on qits-maintenance-service's {@code testing/contracts/GoldenMasters}, with
 * two changes:
 *
 * <ul>
 *   <li><b>One class for every provider.</b> Every golden-master jar puts its tree at the same
 *       classpath path, so the index is found by its {@code provider} field among all of them, and
 *       each recording is read beside that index.
 *   <li><b>The body is cut to what the consumer reads.</b> {@link #responseBody} keeps only the
 *       paths a row {@code consumes} (spelled {@code events[].id}); a path the recording does not
 *       hold is left out, as qits-landing-app's {@code heldBy} does. No path means status only.
 * </ul>
 *
 * <p>Matchers come from the index's {@code frozen} lists, never from a value's shape: {@code ids}
 * get a UUID regex, {@code instants} an ISO-8601 regex, the {@code listFilteredTo} array "contains"
 * ({@code minArrayLike}), every other non-empty array {@code minMaxArrayLike(n, n)}, every other
 * leaf a type match.
 */
public final class GoldenMasters {

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
  public static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final String INDEX = "golden-masters/index.json";
  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String indexProvider;
  private final URL indexUrl;
  private final JsonNode index;

  private GoldenMasters(String indexProvider, URL indexUrl, JsonNode index) {
    this.indexProvider = indexProvider;
    this.indexUrl = indexUrl;
    this.index = index;
  }

  /**
   * The golden masters of {@code indexProvider} (the application name, e.g. {@code qits-events}),
   * or empty when no jar on the test classpath carries them.
   */
  public static Optional<GoldenMasters> of(String indexProvider) {
    try {
      Enumeration<URL> found = GoldenMasters.class.getClassLoader().getResources(INDEX);
      for (URL url : Collections.list(found)) {
        JsonNode index = MAPPER.readTree(read(url));
        if (indexProvider.equals(index.path("provider").asText())) {
          if (index.path("formatVersion").asInt() != 1) {
            throw new IllegalStateException(
                url + " is formatVersion " + index.path("formatVersion") + "; this reads 1");
          }
          return Optional.of(new GoldenMasters(indexProvider, url, index));
        }
      }
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** One recorded (state, operation), as the index describes it. */
  public record Operation(
      String state,
      Map<String, String> params,
      String operationId,
      String method,
      String path,
      int status,
      String file,
      Set<String> ids,
      Set<String> instants,
      String listFilteredTo) {

    /** Whether the path template names a {@code {param}}. */
    public boolean hasPathParams() {
      return PARAM.matcher(path).find();
    }

    /** The path with every {@code {param}} replaced by the state's frozen example. */
    public String examplePath() {
      return substitute(name -> params.get(name));
    }

    /** The path as a provider-state expression: {@code {param}} becomes {@code ${param}}. */
    public String expressionPath() {
      return substitute(name -> "${" + name + "}");
    }

    private String substitute(java.util.function.Function<String, String> value) {
      Matcher m = PARAM.matcher(path);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String name = m.group(1);
        if (!params.containsKey(name)) {
          throw new IllegalStateException(
              "golden master " + state + "/" + operationId + ": {" + name + "} is not a param");
        }
        m.appendReplacement(out, Matcher.quoteReplacement(value.apply(name)));
      }
      m.appendTail(out);
      return out.toString();
    }
  }

  /** Whether the index records {@code operationId} in {@code state}. */
  public boolean records(String state, String operationId) {
    return find(state, operationId) != null;
  }

  /** The provider state's frozen example params. */
  public Map<String, String> params(String state) {
    Map<String, String> params = new LinkedHashMap<>();
    JsonNode node = stateNode(state);
    if (node != null) {
      node.path("params")
          .fields()
          .forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
    }
    return params;
  }

  /** The index entry for one (state, operation); fails naming both when there is none. */
  public Operation operation(String state, String operationId) {
    JsonNode op = find(state, operationId);
    if (op == null) {
      throw new IllegalArgumentException(
          indexProvider + "' golden masters record no " + operationId + " in state '" + state + "'");
    }
    JsonNode frozen = op.path("frozen");
    JsonNode filtered = frozen.path("listFilteredTo");
    return new Operation(
        state,
        params(state),
        operationId,
        op.path("method").asText(),
        op.path("path").asText(),
        op.path("status").asInt(),
        op.path("file").asText(),
        strings(frozen.path("ids")),
        strings(frozen.path("instants")),
        filtered.isTextual() ? filtered.asText() : null);
  }

  /** The recorded JSON for one (state, operation), parsed — a fresh tree each call. */
  public JsonNode json(String state, String operationId) {
    try {
      // A jar: url is opaque to URI#resolve, so the sibling is spelled by hand.
      String at = indexUrl.toString();
      String base = at.substring(0, at.length() - "index.json".length());
      URL file = URI.create(base + operation(state, operationId).file()).toURL();
      return MAPPER.readTree(read(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- the body --------------------------------------------------------------------------------

  /**
   * The recording cut to {@code consumes}, with the index's matchers; {@code null} when nothing the
   * consumer reads is in it (the row is then status only).
   */
  public DslPart responseBody(String state, String operationId, List<String> consumes) {
    Operation op = operation(state, operationId);
    JsonNode recorded = json(state, operationId);
    if (!recorded.isObject()) {
      throw new IllegalStateException(
          "golden master " + state + "/" + operationId + ": only an object body is supported");
    }
    JsonNode cut = cut(recorded, consumes);
    if (cut == null || cut.isEmpty()) {
      return null;
    }
    PactDslJsonBody root = new PactDslJsonBody();
    fillObject(root, Shape.of(cut), "$", op);
    return root;
  }

  /** The paths of {@code consumes} that {@code recorded} holds, and nothing else. */
  static JsonNode cut(JsonNode recorded, List<String> consumes) {
    JsonNode out = null;
    for (String path : consumes) {
      JsonNode kept = keep(recorded, steps(path), 0);
      if (kept != null) {
        out = out == null ? kept : merge(out, kept);
      }
    }
    return out;
  }

  /** {@code events[].id} as {@code [events, [], id]}. */
  private static List<String> steps(String path) {
    List<String> steps = new ArrayList<>();
    for (String part : path.split("\\.")) {
      String name = part.replaceAll("(\\[\\])+$", "");
      if (!name.isEmpty()) {
        steps.add(name);
      }
      for (int i = 0; i < (part.length() - name.length()) / 2; i++) {
        steps.add("[]");
      }
    }
    return steps;
  }

  private static JsonNode keep(JsonNode value, List<String> steps, int at) {
    if (at == steps.size()) {
      return value.deepCopy();
    }
    String step = steps.get(at);
    if ("[]".equals(step)) {
      if (!value.isArray()) {
        return null;
      }
      ArrayNode out = MAPPER.createArrayNode();
      for (JsonNode element : value) {
        JsonNode kept = keep(element, steps, at + 1);
        if (kept == null) {
          // An element not holding the path drops the path from the whole array, as a merged
          // template would demand it of every element.
          return null;
        }
        out.add(kept);
      }
      return out;
    }
    if (!value.isObject() || !value.has(step)) {
      return null;
    }
    JsonNode kept = keep(value.get(step), steps, at + 1);
    if (kept == null) {
      return null;
    }
    ObjectNode out = MAPPER.createObjectNode();
    out.set(step, kept);
    return out;
  }

  private static JsonNode merge(JsonNode a, JsonNode b) {
    if (a.isObject() && b.isObject()) {
      ObjectNode out = ((ObjectNode) a).deepCopy();
      b.fields()
          .forEachRemaining(
              e -> out.set(e.getKey(), out.has(e.getKey()) ? merge(out.get(e.getKey()), e.getValue()) : e.getValue()));
      return out;
    }
    if (a.isArray() && b.isArray() && a.size() == b.size()) {
      ArrayNode out = MAPPER.createArrayNode();
      for (int i = 0; i < a.size(); i++) {
        out.add(merge(a.get(i), b.get(i)));
      }
      return out;
    }
    return b;
  }

  private static void fillObject(PactDslJsonBody target, Shape shape, String path, Operation op) {
    for (Map.Entry<String, Shape> field : shape.fields.entrySet()) {
      String name = field.getKey();
      Shape child = field.getValue();
      String childPath = path + "." + name;
      switch (child.kind) {
        case NULL -> target.nullValue(name);
        case LEAF -> leaf(target, name, child, childPath, op);
        case OBJECT -> {
          if (child.nullable) {
            throw unsupported(op, childPath, "an object that is null in some elements");
          }
          PactDslJsonBody nested = target.object(name);
          fillObject(nested, child, childPath, op);
          nested.closeObject();
        }
        case ARRAY -> array(target, name, child, childPath, op);
      }
    }
  }

  private static void leaf(
      PactDslJsonBody target, String name, Shape leaf, String path, Operation op) {
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      if (leaf.nullable) {
        target.or(name, example.asText(), new RegexMatcher(UUID_REGEX, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.uuid(name, example.asText());
      }
    } else if (op.instants().contains(path)) {
      if (leaf.nullable) {
        target.or(name, example.asText(), new RegexMatcher(ISO_INSTANT, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, example.asText());
      }
    } else if (leaf.nullable) {
      target.or(name, scalar(example), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (example.isTextual()) {
      target.stringType(name, example.asText());
    } else if (example.isNumber()) {
      target.numberType(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanType(name, example.asBoolean());
    } else {
      throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
    }
  }

  private static void array(
      PactDslJsonBody target, String name, Shape array, String path, Operation op) {
    if (array.nullable) {
      throw unsupported(op, path, "an array that is null in some elements");
    }
    boolean filtered = path.equals(op.listFilteredTo());
    int n = array.length;
    if (n == 0) {
      // The recording says "empty": an empty array with no rule is compared as exactly that.
      target.array(name).closeArray();
      return;
    }
    Shape element = array.element;
    String elementPath = path + "[*]";
    switch (element.kind) {
      case OBJECT -> {
        PactDslJsonBody template =
            filtered ? target.minArrayLike(name, n, n) : target.minMaxArrayLike(name, n, n, n);
        fillObject(template, element, elementPath, op);
        DslPart closed = template.closeObject();
        ((PactDslJsonArray) closed).closeArray();
      }
      case LEAF -> {
        PactDslJsonRootValue value = rootLeaf(element, elementPath, op);
        if (filtered) {
          target.minArrayLike(name, n, value, n);
        } else {
          target.minMaxArrayLike(name, n, n, value, n);
        }
      }
      default -> throw unsupported(op, elementPath, "an array of " + element.kind);
    }
  }

  private static PactDslJsonRootValue rootLeaf(Shape leaf, String path, Operation op) {
    if (leaf.nullable) {
      throw unsupported(op, path, "an array holding nulls");
    }
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      return PactDslJsonRootValue.uuid(example.asText());
    }
    if (op.instants().contains(path)) {
      return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
    }
    if (example.isTextual()) {
      return PactDslJsonRootValue.stringType(example.asText());
    }
    if (example.isNumber()) {
      return PactDslJsonRootValue.numberType(example.numberValue());
    }
    if (example.isBoolean()) {
      return PactDslJsonRootValue.booleanType(example.asBoolean());
    }
    throw unsupported(op, path, "a " + example.getNodeType() + " array element");
  }

  private static Object scalar(JsonNode example) {
    if (example.isTextual()) {
      return example.asText();
    }
    if (example.isNumber()) {
      return example.numberValue();
    }
    if (example.isBoolean()) {
      return example.asBoolean();
    }
    throw new IllegalStateException("not a scalar: " + example);
  }

  private static IllegalStateException unsupported(Operation op, String path, String what) {
    return new IllegalStateException(
        "golden master " + op.state() + "/" + op.operationId() + ": " + path + " is " + what
            + ", which GoldenMasters cannot express as a pact matcher yet");
  }

  /**
   * A recorded value's structure, with an array's elements merged into one template: field union, a
   * leaf's first non-null example, and {@code nullable} wherever an element held null or lacked the
   * field.
   */
  private static final class Shape {
    enum Kind {
      NULL,
      LEAF,
      OBJECT,
      ARRAY
    }

    Kind kind;
    boolean nullable;
    JsonNode example;
    final LinkedHashMap<String, Shape> fields = new LinkedHashMap<>();
    Shape element;
    int length;

    static Shape of(JsonNode node) {
      Shape shape = new Shape();
      if (node == null || node.isNull() || node.isMissingNode()) {
        shape.kind = Kind.NULL;
        shape.nullable = true;
      } else if (node.isObject()) {
        shape.kind = Kind.OBJECT;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
          Map.Entry<String, JsonNode> e = it.next();
          shape.fields.put(e.getKey(), of(e.getValue()));
        }
      } else if (node.isArray()) {
        shape.kind = Kind.ARRAY;
        shape.length = node.size();
        for (JsonNode e : node) {
          shape.element = shape.element == null ? of(e) : merge(shape.element, of(e));
        }
      } else {
        shape.kind = Kind.LEAF;
        shape.example = node;
      }
      return shape;
    }

    static Shape merge(Shape a, Shape b) {
      if (a.kind == Kind.NULL) {
        b.nullable = true;
        return b;
      }
      if (b.kind == Kind.NULL) {
        a.nullable = true;
        return a;
      }
      if (a.kind != b.kind) {
        throw new IllegalStateException("golden master array elements disagree: " + a.kind + " and " + b.kind);
      }
      a.nullable |= b.nullable;
      if (a.kind == Kind.OBJECT) {
        List<String> keys = new ArrayList<>(a.fields.keySet());
        for (String key : b.fields.keySet()) {
          if (!keys.contains(key)) {
            keys.add(key);
          }
        }
        LinkedHashMap<String, Shape> merged = new LinkedHashMap<>();
        for (String key : keys) {
          Shape left = a.fields.get(key);
          Shape right = b.fields.get(key);
          merged.put(
              key,
              left == null ? merge(of(null), right) : right == null ? merge(left, of(null)) : merge(left, right));
        }
        a.fields.clear();
        a.fields.putAll(merged);
      } else if (a.kind == Kind.ARRAY) {
        a.length = Math.min(a.length, b.length);
        a.element = a.element == null ? b.element : b.element == null ? a.element : merge(a.element, b.element);
      }
      return a;
    }
  }

  // --- reading the jar -------------------------------------------------------------------------

  private JsonNode stateNode(String state) {
    for (JsonNode node : index.path("states")) {
      if (state.equals(node.path("name").asText())) {
        return node;
      }
    }
    return null;
  }

  private JsonNode find(String state, String operationId) {
    JsonNode node = stateNode(state);
    if (node == null) {
      return null;
    }
    for (JsonNode op : node.path("operations")) {
      if (operationId.equals(op.path("operationId").asText())) {
        return op;
      }
    }
    return null;
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode e : array) {
      out.add(e.asText());
    }
    return Set.copyOf(out);
  }

  private static String read(URL url) throws IOException {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
