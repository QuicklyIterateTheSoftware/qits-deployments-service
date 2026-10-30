package eu.wohlben.qits.deployments.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one peer a deployment reaches out to, and the <b>outgoing</b> tap that draws what the
 * launched process asked it.
 *
 * <h2>What a deployment needs from somebody else</h2>
 *
 * <p>Everything this component sends is on the deploy worker, on the far side of a socket from this
 * JVM, so the only place that traffic exists is the far side's own record of it. There is exactly
 * one far side in a deployment now:
 *
 * <ul>
 *   <li><b>the git host</b> — {@code GET /git/<project>/<repo>/blob/<rev>/.config/qits/deployments.yml},
 *       the spec read that decides <i>which places exist</i>. It is read at the released tag, which
 *       is why an unknown key in an old commit's file is a failed deployment and why every retired
 *       key stays tolerated.
 * </ul>
 *
 * <p>There used to be three more: a second blob ({@code .config/qits/configuration.yml}),
 * qits-configuration's resolved read and declaration seed, and the idp mint of the credential those
 * presented. All of them went with qits-configuration (qits-1393) — an application's environment is
 * the settings store now, a table in this component's own database, so it is a JDBC edge rather than
 * a peer.
 *
 * <h2>Stateless on purpose</h2>
 *
 * <p>There is no arming mechanism and there is nothing to reset: every answer is a pure function of
 * the path, keyed by the repository name in it, which is what makes each story class runnable on its
 * own.
 *
 * <h2>The tap, and the floor</h2>
 *
 * <p>Every answered request is appended to a file as {@code METHOD PATH STATUS} — before the
 * response is written, so a line is on disk by the time its effect is observable — and {@link
 * #install()} takes the end of that file as a <b>floor</b>. Unlike {@link StorySwarm}'s recording
 * there is genuinely nothing here from before the first story: the spec read happens per event, so
 * the floor is a belt rather than a correction.
 */
public final class StoryPeers {

  /**
   * The commit every spec read here reports the released tag resolved to. One literal for the whole
   * catalogue: a story asserts that a deployment RECORDED a commit, never which one, and a stamped
   * value would move the {@code networkHash} on every run.
   */
  public static final String RESOLVED_COMMIT = "0f1e2d3c4b5a69788796a5b4c3d2e1f009182736";

  /** How a diagram names the blob store this stub answers {@code /git/…} as. */
  public static final String GIT_HOST = "qits-githost";

  /** Where the spec of one repository lives, at one commit — the path qits-ci reads a pipeline at. */
  public static final String SPEC_PATH = ".config/qits/deployments.yml";

  private static final String PORT_PROPERTY = "qits.test.story-peers.port";

  private static final String SOURCE_ID = "story-peers";

  private static final Path ROOT = Path.of("target", "story-peers");

  /** The recording: one line per answered request, the shape a git host's access log has. */
  private static final Path ACCESS_LOG = ROOT.resolve("access.log");

  /**
   * What each repository's {@code deployments.yml} says, at every sha.
   *
   * <p>The keys are the smallest set that makes each story true and no larger: the plane, the gate
   * the container is probed with, and — for {@link #WEB} — the {@code update_order} a repository
   * states when it cannot be two processes at once, which is the one spec value a story can watch
   * travel all the way into an argv.
   */
  private static final Map<String, String> SPECS = new LinkedHashMap<>();

  /** The application the deployment stories create and then update. */
  public static final String WEB = "story-web";

  /** The application no pipeline ever published an image for. */
  public static final String UNPUBLISHED = "story-unpublished";

  /** The application the settings story states an environment variable for. */
  public static final String CONFIGURED = "story-configured";

  static {
    SPECS.put(
        WEB,
        """
        deployment_target: environment
        health_path: /q/health/ready
        update_order: stop-first
        """);
    SPECS.put(
        UNPUBLISHED,
        """
        deployment_target: environment
        health_path: /q/health/ready
        """);
    SPECS.put(
        CONFIGURED,
        """
        deployment_target: environment
        health_path: /q/health/ready
        """);
  }

  private static final Object LOCK = new Object();

  private static boolean registered;

  private static int floor;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryPeers() {}

  // --- the server --------------------------------------------------------------------------------

  /**
   * Start the stub once per JVM and park its port, wiping whatever an earlier run left behind.
   * Called from the test profile, which is the only place that knows the urls in time.
   */
  public static synchronized String ensureStarted() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port != null) {
      return baseUrl(Integer.parseInt(port));
    }
    wipe();
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the story peers stub", e);
    }
    server.createContext("/", StoryPeers::handle);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return baseUrl(server.getAddress().getPort());
  }

  private static String baseUrl(int port) {
    return "http://localhost:" + port;
  }

  private static void handle(HttpExchange exchange) throws IOException {
    // The RAW path: a git rev is one path segment, so `refs/tags/<version>` arrives percent-encoded
    // and the decoded path would read as three segments instead of one — which is exactly the shape
    // `specFor` counts. Reading it decoded made every release-tag spec read a 404.
    String path = exchange.getRequestURI().getRawPath();
    String method = exchange.getRequestMethod();
    int status = 404;
    String contentType = "application/json";
    String body = "{}";

    String blob = blobFor(path);
    if (blob != null) {
      status = 200;
      contentType = "text/plain; charset=utf-8";
      body = blob;
      // What the real git host answers every blob read with — the commit the rev resolved to, and
      // the only way a released deployment learns one.
      exchange.getResponseHeaders().add("Git-Commit-Sha", RESOLVED_COMMIT);
    }

    // Recorded BEFORE the answer leaves, so a story that observed an effect can rely on the line
    // for it already being on disk. There is nothing to await.
    record(method, path, status);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /**
   * The blob at {@code /git/<project>/<repo>/blob/<rev>/.config/qits/deployments.yml}, or null.
   *
   * <p>The rev is the RELEASED TAG and arrives percent-encoded ({@code refs%2Ftags%2F<version>}),
   * which is why the caller hands this the raw path: one segment, as the git host's own route
   * regex reads it.
   *
   * <p>Only the name-addressed route is served, which is a claim rather than a shortcut: every
   * announcement above the storage seam carries the public {@code (projectId, repoName)} pair now,
   * and a story that fell back to the id-addressed url would be documenting the compatibility arm
   * rather than the flow.
   */
  private static String blobFor(String path) {
    String[] segments = path.split("/");
    // ["", "git", project, repo, "blob", rev, ".config", "qits", "<file>.yml"]
    if (segments.length != 9 || !"git".equals(segments[1]) || !"blob".equals(segments[4])) {
      return null;
    }
    return path.endsWith(SPEC_PATH) ? SPECS.get(segments[3]) : null;
  }

  // --- what a story class calls ------------------------------------------------------------------

  /**
   * Register the tap once per JVM, taking the current end of the recording as the floor. Called from
   * every story class's {@code @BeforeAll}; whichever runs first bounds what any story can see.
   */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      floor = allLines().size();
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryPeers::edges);
      registered = true;
    }
  }

  /** The label an answered spec read renders as — what an assertion has to spell. */
  public static String specLabel(String repository, String version, int status) {
    return Labels.scrub(
        "GET /git/"
            + StoryTarget.PROJECT
            + "/"
            + repository
            + "/blob/refs%2Ftags%2F"
            + version
            + "/"
            + SPEC_PATH
            + " -> "
            + status);
  }

  /**
   * Wait, briefly and without asserting anything, for a recorded line containing {@code fragment}.
   *
   * <p>Deliberately silent on timeout: the proof is the {@code assertEdge} in {@code @AfterAll},
   * which names the missing edge, and a failure here would only obscure it.
   */
  public static void awaitRecorded(String fragment) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (true) {
      for (String line : readLines()) {
        if (line.contains(fragment)) {
          return;
        }
      }
      if (System.nanoTime() >= deadline) {
        return;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // --- the source --------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = readLines();
    if (harvested > lines.size()) {
      harvested = 0;
      floor = 0;
      lines = readLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /** One recorded line as an edge, attributed to the peer whose path prefix it carries. */
  private static Optional<NetworkEdge> edge(String line) {
    // "METHOD PATH STATUS" — three fields, no quoting, and a path carries no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 3 || !fields[1].startsWith("/")) {
      return Optional.empty();
    }
    String peer = peer(fields[1]);
    if (peer == null) {
      return Optional.empty();
    }
    return Optional.of(
        NetworkEdge.http(
            StoryTarget.SERVICE, peer, Labels.scrub(fields[0] + " " + fields[1] + " -> " + fields[2])));
  }

  /** Which peer a path belongs to. */
  private static String peer(String path) {
    return path.startsWith("/git/") ? GIT_HOST : null;
  }

  /** Everything recorded since the floor — i.e. everything a story could own. */
  private static List<String> readLines() {
    List<String> all = allLines();
    return floor >= all.size() ? List.of() : all.subList(floor, all.size());
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the server appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> allLines() {
    if (!Files.isRegularFile(ACCESS_LOG)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(ACCESS_LOG, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static synchronized void record(String method, String path, int status) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(
          ACCESS_LOG,
          method + " " + path + " " + status + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // A recording that cannot be written costs the diagram an arrow; it must not cost the
      // launched process its answer, which is what a deployment is actually waiting for.
    }
  }

  private static void wipe() {
    try {
      Files.deleteIfExists(ACCESS_LOG);
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear " + ROOT, e);
    }
  }
}
