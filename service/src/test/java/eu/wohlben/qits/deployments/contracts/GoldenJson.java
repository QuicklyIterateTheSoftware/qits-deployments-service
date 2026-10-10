package eu.wohlben.qits.deployments.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/**
 * Renders a golden-master file: 2-space indentation, {@code "key": value}, {@code []}/{@code {}}
 * for empty containers, keys in the order the node holds them, and a trailing newline — the shape
 * {@code JSON.stringify(value, null, 2) + "\n"} produces, so a consumer in any language can
 * re-render a file byte for byte.
 *
 * <p>Hand-rolled rather than Jackson's {@code INDENT_OUTPUT}, whose default printer writes {@code
 * "key" : value} and {@code [ ]} and whose knobs to change that move between Jackson minors.
 */
public final class GoldenJson {

  private GoldenJson() {}

  public static String render(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out, "");
    return out.append('\n').toString();
  }

  private static void write(JsonNode node, StringBuilder out, String indent) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      out.append("null");
    } else if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      String inner = indent + "  ";
      out.append("{\n");
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.append(inner).append(quote(field.getKey())).append(": ");
        write(field.getValue(), out, inner);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      String inner = indent + "  ";
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        out.append(inner);
        write(node.get(i), out, inner);
        out.append(i + 1 < node.size() ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else {
      // Jackson's own value rendering: quoting and escaping for text, the literal for the rest.
      out.append(node.toString());
    }
  }

  private static String quote(String key) {
    return com.fasterxml.jackson.databind.node.TextNode.valueOf(key).toString();
  }
}
