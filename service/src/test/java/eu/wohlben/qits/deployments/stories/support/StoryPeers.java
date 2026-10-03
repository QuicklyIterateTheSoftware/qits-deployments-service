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
 * The three peers a deployment reaches out to, and the <b>outgoing</b> tap that draws what the
 * launched process asked each of them.
 *
 * <h2>What a deployment needs from somebody else</h2>
 *
 * <p>Everything this component sends is on the deploy worker, on the far side of a socket from this
 * JVM, so the only place that traffic exists is the far side's own record of it. There are exactly
 * three far sides in a deployment, and each is a decision written down elsewhere in this
 * repository:
 *
 * <ul>
 *   <li><b>the git host</b> — {@code GET /git/<project>/<repo>/blob/<rev>/.config/qits/deployments.yml},
 *       the spec read that decides <i>which places exist</i>. It is read at the BUILT sha, which is
 *       why an unknown key in an old commit's file is a failed deployment and why every retired key
 *       stays tolerated;
 *   <li><b>the git host, a second time</b> — the same address one file over, {@code
 *       .config/qits/configuration.yml}: what the application declares about its own configuration,
 *       read at the released tag and handed on unparsed. A repository that carries none answers 404
 *       here, which is most of them and is a real request rather than an absence;
 *   <li><b>qits-configuration</b> — {@code GET
 *       /configuration/api/applications/<app>/envs/<env>/resolved}, read ONCE PER ARGV, and {@code
 *       POST /configuration/api/applications/<app>/declarations/<version>}, written once per
 *       release before its deployment is scheduled. Where the url is set the service is
 *       <i>authoritative</i>, meaning sole: the config volume's file is not read at all, a service
 *       that cannot be read REFUSES the deployment rather than falling back to a value that may be
 *       months stale, and a declaration it will not take refuses the deployment before anything
 *       runs;
 *   <li><b>qits-platform-idp</b> — {@code POST /idp/token}, the machine credential the read above
 *       presents. It is the {@code qits} NAMED oidc client, and the peer count is one.
 * </ul>
 *
 * <p><b>One process impersonates all three, and the diagram is drawn from the PATH.</b> The three
 * are three different urls in a deployment's config and would be three hosts on a platform; here
 * they are three contexts on one stub, and {@link #peer} maps a path prefix onto the name a reader
 * knows the peer by. Nothing about the evidence changes — direction, method, path and status are
 * what an edge is — and a second and third server would only be two more ports to park.
 *
 * <h2>Stateless on purpose</h2>
 *
 * <p>There is no arming mechanism and there is nothing to reset: every answer is a pure function of
 * the path, keyed by the application name in it. That is what makes each story class runnable on its
 * own — {@code story-misconfigured} is refused by this stub in every run, in every order, because
 * being unreadable is what that name MEANS here. The alternative, a file a story writes before
 * acting, buys nothing when no two stories want different answers to one question.
 *
 * <h2>The tap, and the floor</h2>
 *
 * <p>Every answered request is appended to a file as {@code METHOD PATH STATUS} — before the
 * response is written, so a line is on disk by the time its effect is observable — and {@link
 * #install()} takes the end of that file as a <b>floor</b>. Unlike {@link StorySwarm}'s recording
 * there is genuinely nothing here from before the first story: the spec read happens per event and
 * the oidc client acquires lazily ({@code early-tokens-acquisition=false}), so the floor is a belt
 * rather than a correction.
 *
 * <h2>The credential is minted ONCE, and that is why one diagram carries it</h2>
 *
 * <p>quarkus-oidc-client caches the token it acquires and re-mints only when it expires, so the
 * {@code POST /idp/token} arrow belongs to the <b>first deployment of the run that had to read
 * configuration</b> and to no other. That is a real property of the deployer rather than an artefact
 * here: a production token lives minutes and is amortised over every deployment issued inside them.
 *
 * <p>What this stand-in chooses is only that the horizon is the whole run — the token says {@code
 * expires_in: 3600}, so the mint lands in exactly one story and every other story's edge count is
 * stable. It was measured the other way first: at {@code expires_in: 1} (and, as it turns out, at
 * {@code 0}, which quarkus reads as the same thing) the credential outlived some deployments and not
 * others, and the arrow appeared in whichever diagram happened to be more than a second after the
 * last one. An edge that comes and goes with the clock is a {@code networkHash} that never settles.
 *
 * <p>The consequence to know when running one class alone: {@code DeploymentConfigurationIT} claims
 * that arrow, and any other story class run on its own inherits it and fails its own edge count —
 * loudly, which is the right way for that assumption to break.
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

  /** How a diagram names the configuration service this stub answers {@code /configuration/…} as. */
  public static final String CONFIGURATION = "qits-configuration";

  /** How a diagram names the identity provider this stub mints the deployer's own token as. */
  public static final String IDP = "qits-platform-idp";

  /** Where the spec of one repository lives, at one commit — the path qits-ci reads a pipeline at. */
  public static final String SPEC_PATH = ".config/qits/deployments.yml";

  /**
   * The second file the git host serves at the same rev: what an application DECLARES about its own
   * configuration. It is read through this component and belongs to qits-configuration, which is
   * why the stub answers it as bytes and nobody here has an opinion about them.
   */
  public static final String DECLARATION_PATH = ".config/qits/configuration.yml";

  /** The one key qits-configuration states for {@link #CONFIGURED}, in the deployer's own grammar. */
  public static final String EXTRA_ENV_KEY = "env.QITS_FEATURE_FLAGS";

  public static final String EXTRA_ENV_VALUE = "trace-headers";

  /** The variable the key above becomes on the argv. */
  public static final String EXTRA_ENV_VARIABLE = "QITS_FEATURE_FLAGS=" + EXTRA_ENV_VALUE;

  /** The application whose configuration this stub serves — the handoff story's subject. */
  public static final String CONFIGURED = "story-configured";

  /**
   * The application whose configuration this stub refuses with a 503, every time.
   *
   * <p>It is a name rather than a flag because the refusal is the application's whole identity in
   * this catalogue: qits-configuration being unreadable is a deployment that must not go green, and
   * a story about it wants an answer that does not depend on what ran before it.
   */
  public static final String MISCONFIGURED = "story-misconfigured";

  /** The status an unreachable-enough configuration service answers with. */
  public static final int REFUSED_STATUS = 503;

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

  /** The application that carries a configuration declaration and has it seeded before it runs. */
  public static final String DECLARED = "story-declared";

  /**
   * The application whose declaration qits-configuration refuses, every time.
   *
   * <p>A name rather than a flag, {@link #MISCONFIGURED}'s arrangement and its argument: carrying a
   * declaration the store will not take is this application's whole identity in the catalogue, so
   * the answer must not depend on what ran before it.
   */
  public static final String BROKEN_DECLARATION = "story-broken-declaration";

  /** The status a store that has READ a declaration and refused it answers with. */
  public static final int DECLARATION_REFUSED_STATUS = 422;

  /**
   * What each repository declares about its own configuration, at every rev — the second blob.
   *
   * <p>Only the two declaration stories carry one. Every other story application answers 404 here,
   * which is the ordinary state of this platform: a repository that has not migrated its
   * configuration yet seeds nothing and deploys exactly as it did. That 404 is a real request and
   * shows up as a real edge, because it is one.
   */
  private static final Map<String, String> DECLARATIONS = new LinkedHashMap<>();

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
    SPECS.put(
        MISCONFIGURED,
        """
        deployment_target: environment
        health_path: /q/health/ready
        """);
    SPECS.put(
        DECLARED,
        """
        deployment_target: environment
        health_path: /q/health/ready
        """);
    SPECS.put(
        BROKEN_DECLARATION,
        """
        deployment_target: environment
        health_path: /q/health/ready
        """);

    // The declaration bytes. Nothing in the deployer parses them — qits-configuration owns the
    // grammar — so what a story can watch is that these exact characters travelled from one peer to
    // the other, which is the whole of what a courier can be asked to prove.
    DECLARATIONS.put(
        DECLARED,
        """
        defaults:
          QITS_FEATURE_FLAGS: trace-headers
        """);
    DECLARATIONS.put(
        BROKEN_DECLARATION,
        """
        defaults:
        	QITS_FEATURE_FLAGS: trace-headers
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
    String seeding = declarationSeedFor(method, path);
    if (blob != null) {
      status = 200;
      contentType = "text/plain; charset=utf-8";
      body = blob;
      // What the real git host answers every blob read with — the commit the rev resolved to, and
      // the only way a released deployment learns one.
      exchange.getResponseHeaders().add("Git-Commit-Sha", RESOLVED_COMMIT);
    } else if (seeding != null) {
      // The other direction, and the only write any peer here takes: a released version's
      // declaration going INTO qits-configuration before its deployment is scheduled. 201 is the
      // ordinary answer; one application is refused because that refusal is what it is for.
      if (BROKEN_DECLARATION.equals(seeding)) {
        status = DECLARATION_REFUSED_STATUS;
        body = "{\"error\":\"line 2: found a tab character where an indentation space is expected\"}";
      } else {
        status = 201;
        body = "{\"created\":true}";
      }
    } else if (isTokenRequest(method, path)) {
      status = 200;
      // An hour, so the mint lands in exactly one story of the run — see the class javadoc.
      body =
          "{\"access_token\":\"story-deployer-machine-token\",\"token_type\":\"Bearer\","
              + "\"expires_in\":3600}";
    } else {
      String application = resolvedApplication(path);
      if (MISCONFIGURED.equals(application)) {
        status = REFUSED_STATUS;
        body = "{\"error\":\"qits-configuration is being redeployed\"}";
      } else if (application != null) {
        status = 200;
        body = resolvedBody(application);
      }
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
    // ONE endpoint, two files, and which one is asked for is the last segment. The deployer reads
    // both at the same rev through the same address, so a stub that served one and 404'd the other
    // by construction would be describing a git host nobody runs.
    if (path.endsWith(SPEC_PATH)) {
      return SPECS.get(segments[3]);
    }
    if (path.endsWith(DECLARATION_PATH)) {
      return DECLARATIONS.get(segments[3]);
    }
    return null;
  }

  /**
   * The application one {@code …/applications/<name>/envs/<env>/resolved} asks about, or null.
   *
   * <p><b>The tier is in the path now</b>, which is what makes one qits-configuration able to answer
   * for every environment: the read used to be addressed by the application alone and a platform
   * therefore needed one store per tier to tell dev's configuration from prod's.
   */
  private static String resolvedApplication(String path) {
    String[] segments = path.split("/");
    // ["", "configuration", "api", "applications", name, "envs", env, "resolved"]
    if (segments.length != 8
        || !"configuration".equals(segments[1])
        || !"applications".equals(segments[3])
        || !"envs".equals(segments[5])
        || !"resolved".equals(segments[7])) {
      return null;
    }
    return segments[4];
  }

  /**
   * The application one {@code POST …/applications/<name>/declarations/<version>} is seeding, or
   * null.
   *
   * <p>The METHOD is half the match and not decoration: this is the one write any peer here takes,
   * and a stub that answered a GET on the same path would let a bug that read where it should have
   * written pass unnoticed.
   */
  private static String declarationSeedFor(String method, String path) {
    if (!"POST".equals(method)) {
      return null;
    }
    String[] segments = path.split("/");
    // ["", "configuration", "api", "applications", name, "declarations", version]
    if (segments.length != 7
        || !"configuration".equals(segments[1])
        || !"applications".equals(segments[3])
        || !"declarations".equals(segments[5])) {
      return null;
    }
    return segments[4];
  }

  private static boolean isTokenRequest(String method, String path) {
    return "POST".equals(method) && "/idp/token".equals(path);
  }

  /**
   * One application's resolved configuration, in the full prefixed spelling the deployer's own
   * grammar is written in. Only {@link #CONFIGURED} states anything: every other application is a
   * real answer too — an application qits-configuration knows and which asks for nothing.
   */
  private static String resolvedBody(String application) {
    // Two applications state the key and they state it for different reasons: CONFIGURED because a
    // platform put it there, DECLARED because the release seeded a declaration that resolves to it.
    // The document is the same either way, which is the point — a deployment cannot tell where a
    // resolved value came from, and must not be able to.
    if (!CONFIGURED.equals(application) && !DECLARED.equals(application)) {
      return "{\"headRevision\":1,\"properties\":{}}";
    }
    return "{\"headRevision\":4,\"properties\":{\"qits.deployments.extras."
        + application
        + "."
        + EXTRA_ENV_KEY
        + "\":\""
        + EXTRA_ENV_VALUE
        + "\"}}";
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

  /** The label an answered DECLARATION read renders as — the second blob, at the same rev. */
  public static String declarationLabel(String repository, String version, int status) {
    return Labels.scrub(
        "GET /git/"
            + StoryTarget.PROJECT
            + "/"
            + repository
            + "/blob/refs%2Ftags%2F"
            + version
            + "/"
            + DECLARATION_PATH
            + " -> "
            + status);
  }

  /**
   * The ONE label a repository that carries no declaration produces — and one is the claim.
   *
   * <p>It was two for a release. The name-addressed 404 was disbelieved and re-asked id-addressed,
   * the spec read's fallback one file over — and on the live platform that second request is what
   * qits-githost's storage-client guard answers 403 to, since it serves {@code /git/<repoId>} to
   * qits-projects alone. A 403 is retryable, so every unmigrated repository's deploy was held
   * {@code SPEC_UNREADABLE} for an hour over a file that was never there (qits-ci@2026.907.184918,
   * 2026-09-07). The name is authoritative on this read, so absence is the answer at once, and the
   * diagram shows one edge rather than a second request nothing can be served.
   */
  public static String declarationMissLabel(String repository, String version) {
    return declarationLabel(repository, version, 404);
  }

  /**
   * The label an answered configuration read renders as.
   *
   * <p>The tier is in it because the tier is in the address; the version is not, because it is a
   * query parameter and the recording keeps paths. That is the same line the retry count sits on
   * the far side of: a diagram says which dependencies exist, and the rest belongs to the log.
   */
  public static String resolvedLabel(String application, String environmentName, int status) {
    return Labels.scrub(
        "GET /configuration/api/applications/"
            + application
            + "/envs/"
            + environmentName
            + "/resolved -> "
            + status);
  }

  /** The label the seed of one released version's declaration renders as. */
  public static String declarationSeedLabel(String application, String version, int status) {
    return Labels.scrub(
        "POST /configuration/api/applications/"
            + application
            + "/declarations/"
            + version
            + " -> "
            + status);
  }

  /** The label the deployer's own credential mint renders as. */
  public static String tokenLabel() {
    return "POST /idp/token -> 200";
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

  /** Which peer a path belongs to — the whole of how one stub draws as three. */
  private static String peer(String path) {
    if (path.startsWith("/git/")) {
      return GIT_HOST;
    }
    if (path.startsWith("/configuration/")) {
      return CONFIGURATION;
    }
    if (path.startsWith("/idp/")) {
      return IDP;
    }
    return null;
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
