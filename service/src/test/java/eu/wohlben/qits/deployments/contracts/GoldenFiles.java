package eu.wohlben.qits.deployments.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * <b>The one compare-or-rewrite switch every golden file in this module goes through</b> — {@code
 * docs/openapi.yml} and the provider golden masters under {@code golden-masters/}. A copy of
 * qits-projects-service's, the first provider.
 *
 * <p>By default a test <em>compares</em>: the file it would write is checked against the committed
 * one and a difference fails with a unified diff. It rewrites only when asked, with {@code
 * -Dgolden.update=true} or {@code QITS_GOLDEN_UPDATE=true} — and then the diff in {@code git diff}
 * is the review.
 */
public final class GoldenFiles {

  private static final int CONTEXT = 3;
  private static final int MAX_DIFF_LINES = 400;

  private GoldenFiles() {}

  /** Whether this run rewrites golden files instead of comparing against them. */
  public static boolean updating() {
    return "true".equalsIgnoreCase(System.getenv("QITS_GOLDEN_UPDATE"))
        || Boolean.getBoolean("golden.update");
  }

  /**
   * The repository root — the directory holding {@code pom.xml} and {@code service/pom.xml} —
   * found by walking up from the working directory, so it does not matter whether surefire runs in
   * {@code service/} (it does) or anywhere below the root.
   */
  public static Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isRegularFile(candidate.resolve("service").resolve("pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "No repository root (a directory with pom.xml and service/pom.xml) above " + dir);
  }

  /** {@link #compareOrWrite(Path, String, boolean, UnaryOperator)} on the global switch. */
  public static void compareOrWrite(Path golden, String actual) {
    compareOrWrite(golden, actual, updating(), UnaryOperator.identity());
  }

  /** As {@link #compareOrWrite(Path, String)}, comparing both sides through {@code comparable}. */
  public static void compareOrWrite(Path golden, String actual, UnaryOperator<String> comparable) {
    compareOrWrite(golden, actual, updating(), comparable);
  }

  /**
   * Writes {@code actual} to {@code golden} when {@code update}, otherwise fails unless the file
   * exists and equals it. {@code comparable} maps both sides before the comparison (identity for a
   * byte-for-byte golden); what is written is always {@code actual} unchanged.
   */
  public static void compareOrWrite(
      Path golden, String actual, boolean update, UnaryOperator<String> comparable) {
    String failure = check(golden, actual, update, comparable);
    if (failure != null) {
      throw new AssertionError(failure);
    }
  }

  /** The non-throwing form: {@code null} when the golden matches (or was written), else why not. */
  public static String check(
      Path golden, String actual, boolean update, UnaryOperator<String> comparable) {
    try {
      if (update) {
        Files.createDirectories(golden.toAbsolutePath().getParent());
        Files.writeString(golden, actual);
        return null;
      }
      if (!Files.exists(golden)) {
        return "No golden at "
            + golden.toAbsolutePath()
            + " — run once with -Dgolden.update=true (or QITS_GOLDEN_UPDATE=true) and review what"
            + " it wrote.";
      }
      String expected = comparable.apply(Files.readString(golden));
      String recorded = comparable.apply(actual);
      if (expected.equals(recorded)) {
        return null;
      }
      return golden.toAbsolutePath()
          + " differs from what this run produced. If the change is intended, rerun with"
          + " -Dgolden.update=true (or QITS_GOLDEN_UPDATE=true) and review the diff.\n"
          + unifiedDiff(
              "committed/" + golden.getFileName(),
              "recorded/" + golden.getFileName(),
              expected,
              recorded);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- unified diff --------------------------------------------------------------------------

  /**
   * A unified diff of two texts, {@value #CONTEXT} lines of context, capped at {@value
   * #MAX_DIFF_LINES} lines. The common prefix and suffix are trimmed before the LCS, so a large
   * file with a small change (openapi.yml) costs only the changed middle.
   */
  public static String unifiedDiff(String fromName, String toName, String from, String to) {
    String[] a = lines(from);
    String[] b = lines(to);
    List<char[]> ops = new ArrayList<>(); // {op} per line: ' ', '-', '+'
    List<String> text = new ArrayList<>();
    int prefix = 0;
    while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < a.length - prefix
        && suffix < b.length - prefix
        && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) {
      suffix++;
    }
    for (int i = 0; i < prefix; i++) {
      ops.add(new char[] {' '});
      text.add(a[i]);
    }
    middle(a, prefix, a.length - suffix, b, prefix, b.length - suffix, ops, text);
    for (int i = a.length - suffix; i < a.length; i++) {
      ops.add(new char[] {' '});
      text.add(a[i]);
    }
    return hunks(fromName, toName, ops, text);
  }

  /** The lines of a text; a final newline ends the last line rather than starting an empty one. */
  private static String[] lines(String text) {
    String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    return body.isEmpty() ? new String[0] : body.split("\n", -1);
  }

  private static void middle(
      String[] a, int a0, int a1, String[] b, int b0, int b1, List<char[]> ops, List<String> text) {
    int n = a1 - a0;
    int m = b1 - b0;
    if ((long) n * m > 4_000_000L) {
      for (int i = a0; i < a1; i++) {
        ops.add(new char[] {'-'});
        text.add(a[i]);
      }
      for (int j = b0; j < b1; j++) {
        ops.add(new char[] {'+'});
        text.add(b[j]);
      }
      return;
    }
    int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] =
            a[a0 + i].equals(b[b0 + j])
                ? lcs[i + 1][j + 1] + 1
                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    int i = 0;
    int j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && a[a0 + i].equals(b[b0 + j])) {
        ops.add(new char[] {' '});
        text.add(a[a0 + i]);
        i++;
        j++;
      } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
        ops.add(new char[] {'-'});
        text.add(a[a0 + i]);
        i++;
      } else {
        ops.add(new char[] {'+'});
        text.add(b[b0 + j]);
        j++;
      }
    }
  }

  private static String hunks(String fromName, String toName, List<char[]> ops, List<String> text) {
    StringBuilder out = new StringBuilder();
    out.append("--- ").append(fromName).append('\n');
    out.append("+++ ").append(toName).append('\n');
    int lines = 0;
    int k = 0;
    int size = ops.size();
    while (k < size) {
      while (k < size && ops.get(k)[0] == ' ') {
        k++;
      }
      if (k == size) {
        break;
      }
      int start = Math.max(0, k - CONTEXT);
      int end = k;
      // extend the hunk while the next change is within 2*CONTEXT unchanged lines
      while (end < size) {
        if (ops.get(end)[0] != ' ') {
          end++;
          continue;
        }
        int run = end;
        while (run < size && ops.get(run)[0] == ' ') {
          run++;
        }
        if (run < size && run - end <= 2 * CONTEXT) {
          end = run;
        } else {
          end = Math.min(size, end + CONTEXT);
          break;
        }
      }
      int fromLine = 1;
      int toLine = 1;
      for (int x = 0; x < start; x++) {
        if (ops.get(x)[0] != '+') fromLine++;
        if (ops.get(x)[0] != '-') toLine++;
      }
      int fromCount = 0;
      int toCount = 0;
      for (int x = start; x < end; x++) {
        if (ops.get(x)[0] != '+') fromCount++;
        if (ops.get(x)[0] != '-') toCount++;
      }
      out.append("@@ -")
          .append(fromLine)
          .append(',')
          .append(fromCount)
          .append(" +")
          .append(toLine)
          .append(',')
          .append(toCount)
          .append(" @@\n");
      for (int x = start; x < end; x++) {
        if (++lines > MAX_DIFF_LINES) {
          out.append("... (diff truncated)\n");
          return out.toString();
        }
        out.append(ops.get(x)[0]).append(text.get(x)).append('\n');
      }
      k = end;
    }
    return out.toString();
  }
}
