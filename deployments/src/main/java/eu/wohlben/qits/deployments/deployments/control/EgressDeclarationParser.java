package eu.wohlben.qits.deployments.deployments.control;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The strict reader of {@code .config/qits/egress.yml} (epic qits-2523): the routes an application
 * sends through qits-egress, each one a grant {@code (application, route, methods)}.
 *
 * <pre>
 * version: 1
 * routes:
 *   - name: hetzner                       # [a-z][a-z0-9-]{0,62}; the first path segment at the gateway
 *     target: https://api.hetzner.cloud   # https://&lt;host&gt;[:port][/base path], or platform:&lt;host label&gt;
 *     secret: HETZNER_DNS                 # [A-Z][A-Z0-9_]{0,63}; omitted for a platform target
 *     inject: bearer                      # bearer | header:&lt;Header-Name&gt; | cookie:&lt;name&gt;; omitted for a platform target
 *     methods: [GET, POST, PUT, DELETE]   # HTTP methods; CONNECT allowed too
 * </pre>
 *
 * <p><b>A line reader, like {@link DeploymentSpecParser}</b>, and for its reason: the platform
 * carries no YAML library, the file has exactly one shape, and a reader that knows that shape names
 * the line of every refusal. It reads the block form above, {@code routes: []}, and {@code methods}
 * either as a flow list ({@code [GET, POST]}) or as a block list of {@code - GET} lines under it.
 * Anchors, multi-line scalars, flow maps and tabs are refused.
 *
 * <p><b>Every rule refuses the whole file</b> — one broken route is never dropped while the others
 * are granted, because a file that says something this reader cannot vouch for is a file whose
 * author meant something else. What a refusal costs is {@link EgressDeclarations}' decision: the
 * release still deploys, and that version carries no routes.
 *
 * <ul>
 *   <li>{@code version} is required and must be {@code 1}; unknown keys are refused at every level,
 *       a key twice is refused.
 *   <li>{@code name} is unique within the file. Route names are scoped per application, so two
 *       applications may both have a {@code hetzner} route.
 *   <li>{@code target} is {@code https://…} (plain {@code http://} is refused; no user info, query or
 *       fragment) or {@code platform:<label>}.
 *   <li>A {@code platform:} route takes no {@code secret} and no {@code inject}: it is answered with
 *       the token-exchange bearer. An {@code https://} route needs both.
 *   <li>{@code methods} is a non-empty list of upper-case HTTP methods out of {@link #METHODS}, none
 *       twice; it is answered sorted.
 *   <li>{@code inject} is {@code bearer}, {@code header:<Header-Name>} or {@code cookie:<name>}
 *       (qits-2659: sends {@code Cookie: <name>=<secret>}, the name an RFC 6265 token of at most 64
 *       characters).
 * </ul>
 *
 * <p><b>Two owners, one grammar</b> (qits-2659). An APPLICATION's file ({@link #parse}) may not
 * name a secret starting {@value #PROJECT_SECRET_PREFIX}: that namespace is the projects', and
 * without the refusal any application's file could grant itself a project's credential. A
 * PROJECT's file — its wrapper's {@code .config/qits/egress.yml} at {@code main} — is read by
 * {@link #parseProject}: every {@code secret: X} is answered RESOLVED, as {@code
 * PROJECT_<SLUG>_X} (the slug upper-cased, {@code -} → {@code _}), so the table names the settings
 * key qits-egress reads ({@code QITS_EGRESS_SECRET_PROJECT_<SLUG>_X}) and qits-egress needs no
 * slug. A project file grants no {@code platform:} target: an agent already holds its own bearer
 * for the platform, and the token-exchange answer is an application's.
 */
public final class EgressDeclarationParser {

  /** The methods a route may grant. {@code CONNECT} tunnels to the target's host and port. */
  public static final Set<String> METHODS =
      new LinkedHashSet<>(
          List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT"));

  /** The one version this reader knows. */
  static final String VERSION = "1";

  /** The prefix of a target answered at {@code https://<label>.qits.${QITS_DOMAIN}}. */
  public static final String PLATFORM_PREFIX = "platform:";

  /** The plain bearer injection. */
  public static final String BEARER = "bearer";

  /** The prefix of a named-header injection, {@code header:<Header-Name>}. */
  public static final String HEADER_PREFIX = "header:";

  /** The prefix of a cookie injection, {@code cookie:<name>}: {@code Cookie: <name>=<secret>}. */
  public static final String COOKIE_PREFIX = "cookie:";

  /** The secret namespace of the projects' routes; an application's file may not name it. */
  public static final String PROJECT_SECRET_PREFIX = "PROJECT_";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final Pattern SECRET = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern HEADER = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");
  private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  /** An RFC 6265 cookie name: an RFC 7230 token, at most 64 characters. */
  private static final Pattern COOKIE = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}");
  private static final Pattern PROJECT_SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

  private static final Set<String> TOP_KEYS = Set.of("version", "routes");
  private static final Set<String> ROUTE_KEYS =
      new LinkedHashSet<>(List.of("name", "target", "secret", "inject", "methods"));

  /**
   * One route as the file grants it.
   *
   * @param secret the settings key whose VALUE the gateway injects; null for a platform target
   * @param inject {@code bearer}, {@code header:<Header-Name>} or {@code cookie:<name>}; null for
   *     a platform target
   * @param methods sorted, never empty
   */
  public record Route(
      String name, String target, String secret, String inject, List<String> methods) {

    public Route {
      methods = methods == null ? List.of() : List.copyOf(methods);
    }
  }

  private EgressDeclarationParser() {}

  /**
   * An APPLICATION's file: a secret in the {@value #PROJECT_SECRET_PREFIX} namespace is refused.
   *
   * @param source how the file is named back to a reader, in every refusal
   * @return the routes in file order — empty for {@code routes: []}, an absent {@code routes} or an
   *     empty file is refused for its missing {@code version}
   * @throws SpecException naming the file and, where there is one, the line
   */
  public static List<Route> parse(String yaml, String source) {
    return parse(yaml, source, null);
  }

  /**
   * A PROJECT's file (qits-2659), its wrapper's {@code egress.yml}: the same grammar, no {@code
   * platform:} target, and every secret answered resolved as {@link #projectSecret}.
   *
   * @param projectSlug the project's slug, {@code [a-z0-9][a-z0-9-]{0,62}}
   * @throws SpecException naming the file and, where there is one, the line
   */
  public static List<Route> parseProject(String yaml, String source, String projectSlug) {
    if (projectSlug == null || !PROJECT_SLUG.matcher(projectSlug).matches()) {
      throw new SpecException(
          source + ": the project's slug cannot name a secret namespace: " + projectSlug);
    }
    return parse(yaml, source, projectSlug);
  }

  /**
   * The settings name a project route's {@code secret: <name>} resolves to: {@code
   * PROJECT_<SLUG>_<name>}, the slug upper-cased and {@code -} → {@code _}. For project {@code qits}
   * and {@code GITHUB_READ}: {@code PROJECT_QITS_GITHUB_READ}, read by qits-egress as {@code
   * QITS_EGRESS_SECRET_PROJECT_QITS_GITHUB_READ}.
   */
  public static String projectSecret(String projectSlug, String secret) {
    return PROJECT_SECRET_PREFIX
        + projectSlug.toUpperCase(Locale.ROOT).replace('-', '_')
        + "_"
        + secret;
  }

  /** @param projectSlug null for an application's file */
  private static List<Route> parse(String yaml, String source, String projectSlug) {
    String version = null;
    boolean routesSeen = false;
    boolean routesClosed = false;
    Set<String> topSeen = new HashSet<>();
    List<Route> routes = new ArrayList<>();
    Set<String> names = new HashSet<>();

    RouteBuilder current = null;
    int itemIndent = -1;
    String[] lines = (yaml == null ? "" : yaml).split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      int lineNumber = i + 1;
      String raw = stripComment(lines[i]);
      if (raw.isBlank() || (raw.strip().equals("---") && i == firstContent(lines))) {
        continue;
      }
      int indent = indentOf(raw, source, lineNumber);
      String content = raw.strip();

      if (indent == 0) {
        if (current != null) {
          routes.add(current.build(source, names, projectSlug));
          current = null;
        }
        if (routesSeen && !routesClosed) {
          routesClosed = true;
        }
        KeyValue pair = keyValue(content, source, lineNumber);
        if (!TOP_KEYS.contains(pair.key())) {
          throw error(
              source,
              lineNumber,
              "unknown key `" + pair.key() + "` — this file knows version and routes");
        }
        if (!topSeen.add(pair.key())) {
          throw error(source, lineNumber, "duplicate key `" + pair.key() + "`");
        }
        if (pair.key().equals("version")) {
          version = pair.value();
          if (!VERSION.equals(version)) {
            throw error(
                source,
                lineNumber,
                "`version` must be 1, got: " + (version.isEmpty() ? "nothing" : version));
          }
        } else if (pair.value().equals("[]")) {
          routesSeen = true;
          routesClosed = true;
        } else if (pair.value().isEmpty()) {
          routesSeen = true;
        } else {
          throw error(
              source,
              lineNumber,
              "`routes` is a list of `- name: …` entries under it, or `[]`; got: " + pair.value());
        }
        continue;
      }

      if (!routesSeen || routesClosed) {
        throw error(source, lineNumber, "an indented line belongs under `routes:`");
      }
      if (content.equals("-") || content.startsWith("- ")) {
        if (current != null && current.methodsBlock && indent > current.keyColumn) {
          current.method(content.substring(1).strip(), source, lineNumber);
          continue;
        }
        if (itemIndent < 0) {
          itemIndent = indent;
        }
        if (indent != itemIndent) {
          throw error(source, lineNumber, "a route entry must line up with the first one");
        }
        if (current != null) {
          routes.add(current.build(source, names, projectSlug));
        }
        String rest = content.substring(1);
        String key = rest.strip();
        if (key.isEmpty()) {
          throw error(source, lineNumber, "a route entry starts with its first key: `- name: …`");
        }
        current = new RouteBuilder(lineNumber, indent + 1 + (rest.length() - rest.stripLeading().length()));
        current.key(keyValue(key, source, lineNumber), source, lineNumber);
        continue;
      }
      if (current == null || indent != current.keyColumn) {
        throw error(
            source,
            lineNumber,
            "unexpected indentation — a route's keys line up under its first key");
      }
      current.key(keyValue(content, source, lineNumber), source, lineNumber);
    }
    if (current != null) {
      routes.add(current.build(source, names, projectSlug));
    }
    if (version == null) {
      throw new SpecException(source + ": `version: 1` is required");
    }
    return List.copyOf(routes);
  }

  /** The index of the first line with content, so a leading {@code ---} is the document marker. */
  private static int firstContent(String[] lines) {
    for (int i = 0; i < lines.length; i++) {
      if (!stripComment(lines[i]).isBlank()) {
        return i;
      }
    }
    return -1;
  }

  /** One route's keys as they arrive, checked once the route is complete. */
  private static final class RouteBuilder {
    final int line;
    final int keyColumn;
    final Set<String> seen = new HashSet<>();
    String name;
    String target;
    String secret;
    String inject;
    List<String> methods;
    boolean methodsBlock;
    int methodsLine;

    RouteBuilder(int line, int keyColumn) {
      this.line = line;
      this.keyColumn = keyColumn;
    }

    void key(KeyValue pair, String source, int lineNumber) {
      methodsBlock = false;
      if (!ROUTE_KEYS.contains(pair.key())) {
        throw error(
            source,
            lineNumber,
            "unknown key `"
                + pair.key()
                + "` in a route — a route knows "
                + String.join(", ", ROUTE_KEYS));
      }
      if (!seen.add(pair.key())) {
        throw error(source, lineNumber, "duplicate key `" + pair.key() + "` in one route");
      }
      String value = pair.value();
      switch (pair.key()) {
        case "name" -> name = value;
        case "target" -> target = value;
        case "secret" -> secret = value;
        case "inject" -> inject = value;
        case "methods" -> {
          methodsLine = lineNumber;
          if (value.isEmpty()) {
            methods = new ArrayList<>();
            methodsBlock = true;
          } else if (value.startsWith("[") && value.endsWith("]")) {
            methods = new ArrayList<>();
            String inner = value.substring(1, value.length() - 1).strip();
            if (!inner.isEmpty()) {
              for (String method : inner.split(",", -1)) {
                method(method.strip(), source, lineNumber);
              }
            }
          } else {
            throw error(
                source, lineNumber, "`methods` is a list — `[GET, POST]` or `- GET` lines under it");
          }
        }
        default -> throw new IllegalStateException(pair.key());
      }
    }

    void method(String raw, String source, int lineNumber) {
      String method = unquote(raw);
      if (!METHODS.contains(method)) {
        throw error(
            source,
            lineNumber,
            "`"
                + method
                + "` is not a method a route may grant — one of "
                + String.join(", ", METHODS)
                + ", upper case");
      }
      if (methods.contains(method)) {
        throw error(source, lineNumber, "the method `" + method + "` is listed twice");
      }
      methods.add(method);
    }

    Route build(String source, Set<String> names, String projectSlug) {
      String where = source + ": the route at line " + line;
      if (name == null || name.isEmpty()) {
        throw new SpecException(where + " has no `name`");
      }
      if (!NAME.matcher(name).matches()) {
        throw new SpecException(
            where + ": `name` must match [a-z][a-z0-9-]{0,62}, got: " + name);
      }
      where = source + ": route `" + name + "` (line " + line + ")";
      if (!names.add(name)) {
        throw new SpecException(where + ": the name is used by an earlier route in this file");
      }
      if (target == null || target.isEmpty()) {
        throw new SpecException(where + " has no `target`");
      }
      if (methods == null || methods.isEmpty()) {
        throw new SpecException(where + " grants no `methods` — list at least one");
      }
      if (target.startsWith(PLATFORM_PREFIX)) {
        if (projectSlug != null) {
          throw new SpecException(
              where
                  + ": a project's file grants no `platform:` target — an agent reaches the"
                  + " platform on its own bearer");
        }
        String label = target.substring(PLATFORM_PREFIX.length());
        if (!HOST_LABEL.matcher(label).matches()) {
          throw new SpecException(
              where + ": `platform:` names one DNS label (a platform host), got: " + target);
        }
        if (secret != null || inject != null) {
          throw new SpecException(
              where
                  + ": a `platform:` target takes no `secret` and no `inject` — it is answered with"
                  + " the token-exchange bearer");
        }
      } else {
        https(target, where);
        if (secret == null || inject == null) {
          throw new SpecException(
              where + ": an `https://` target needs both `secret` and `inject`");
        }
        if (!SECRET.matcher(secret).matches()) {
          throw new SpecException(
              where + ": `secret` must match [A-Z][A-Z0-9_]{0,63}, got: " + secret);
        }
        if (!inject.equals(BEARER)
            && !(inject.startsWith(HEADER_PREFIX)
                && HEADER.matcher(inject.substring(HEADER_PREFIX.length())).matches())
            && !(inject.startsWith(COOKIE_PREFIX)
                && COOKIE.matcher(inject.substring(COOKIE_PREFIX.length())).matches())) {
          throw new SpecException(
              where
                  + ": `inject` is `bearer`, `header:<Header-Name>` or `cookie:<name>`, got: "
                  + inject);
        }
        if (projectSlug == null && secret.startsWith(PROJECT_SECRET_PREFIX)) {
          throw new SpecException(
              where
                  + ": `secret` may not start with `"
                  + PROJECT_SECRET_PREFIX
                  + "` — that namespace is the projects' (their wrapper's egress.yml), got: "
                  + secret);
        }
      }
      String granted =
          projectSlug == null || secret == null ? secret : projectSecret(projectSlug, secret);
      return new Route(name, target, granted, inject, List.copyOf(new TreeSet<>(methods)));
    }
  }

  /** An {@code https://<host>[:port][/base path]} target, and nothing a URL may carry besides. */
  private static void https(String target, String where) {
    if (target.toLowerCase(Locale.ROOT).startsWith("http://")) {
      throw new SpecException(
          where + ": plain `http://` is refused — a target is `https://…` or `platform:<label>`");
    }
    if (!target.startsWith("https://")) {
      throw new SpecException(
          where + ": a target is `https://<host>[:port][/path]` or `platform:<label>`, got: " + target);
    }
    URI uri;
    try {
      uri = new URI(target);
    } catch (URISyntaxException e) {
      throw new SpecException(where + ": `target` is not a URL: " + target);
    }
    if (uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
      throw new SpecException(
          where
              + ": a target is `https://<host>[:port][/path]` — no user info, query or fragment;"
              + " got: "
              + target);
    }
  }

  private record KeyValue(String key, String value) {}

  private static KeyValue keyValue(String content, String source, int line) {
    int colon = content.indexOf(':');
    if (colon < 1) {
      throw error(source, line, "expected `key: value`, got: " + content);
    }
    String key = content.substring(0, colon).strip();
    String rest = content.substring(colon + 1);
    if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
      throw error(source, line, "expected `key: value`, got: " + content);
    }
    String value = rest.strip();
    if (value.startsWith("&") || value.startsWith("*") || value.startsWith("|")
        || value.startsWith(">") || value.startsWith("{")) {
      throw error(source, line, "anchors, aliases, block scalars and flow maps are not read here");
    }
    return new KeyValue(key, unquote(value));
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /** A {@code #} at the line's start or after whitespace opens a comment; inside a word it does not. */
  private static String stripComment(String line) {
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static int indentOf(String line, String source, int lineNumber) {
    int indent = 0;
    while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
      if (line.charAt(indent) == '\t') {
        throw error(source, lineNumber, "a tab in the indentation — indent with spaces");
      }
      indent++;
    }
    return indent;
  }

  private static SpecException error(String source, int line, String message) {
    return new SpecException(source + ": line " + line + ": " + message);
  }
}
