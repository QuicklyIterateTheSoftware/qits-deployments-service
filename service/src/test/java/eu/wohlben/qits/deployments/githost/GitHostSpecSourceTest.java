package eu.wohlben.qits.deployments.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import eu.wohlben.qits.deployments.deployments.control.SpecException;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which URL the spec is read at — the one thing the identity rollback changed about this component's
 * single outbound call.
 *
 * <p>An HTTP stub on a real socket rather than a fake at the seam, for the reason {@code ExtrasStub}
 * gives next door: what is under test IS the request, and a fake would assert this test's own model
 * of a client. The JDK's own server, so nothing arrives on the classpath and no docker is involved.
 *
 * <p>Plain JUnit and no {@code @QuarkusTest}: the two config values are package-private fields, so
 * the class is built and pointed at the stub directly.
 */
public class GitHostSpecSourceTest {

  private static final String SHA = "a".repeat(40);
  private static final String UUID_ID = "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88";

  /** What a release deploys, and what the spec has to be read at. */
  private static final String VERSION = "2026.903.113443";

  private HttpServer server;

  /** RAW paths: the encoding of the rev segment is exactly what these tests are about. */
  private final List<String> paths = new ArrayList<>();
  private volatile int status = 200;

  /**
   * What the SECOND and later requests answer, when the arms have to differ — the SPEC read's 404
   * fallback from the name route onto the id route is the only place two answers are one read. The
   * declaration read has no such fallback, and one test sets this precisely to prove nothing asks.
   */
  private volatile Integer laterStatus;
  private volatile String body = "deployment_target: platform\n";
  private volatile String commitSha = SHA;

  @BeforeEach
  void start() throws IOException {
    paths.clear();
    laterStatus = null;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    int answered;
    synchronized (paths) {
      paths.add(exchange.getRequestURI().getRawPath());
      answered = laterStatus != null && paths.size() > 1 ? laterStatus : status;
    }
    int status = answered;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (status != 404 && commitSha != null) {
      // What the git host answers every blob read with: the commit the rev resolved to.
      exchange.getResponseHeaders().add(GitHostSpecSource.COMMIT_SHA_HEADER, commitSha);
    }
    exchange.sendResponseHeaders(status, status == 404 ? -1 : bytes.length);
    if (status != 404) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  @Test
  public void anEventCarryingTheNamePairIsReadNameAddressed() {
    // The public address. The storage id is not in the URL at all — githost serves blob and tree
    // under (projectId, repoName) since it became dumb storage, and the id route is internal.
    DeploymentSpec spec =
        source().read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA).spec();

    assertNotNull(spec);
    assertEquals(
        "/git/qits/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void anEventWithNoNamesKeepsTheIdAddressedUrlItAlwaysUsed() {
    // The regression arm: byte for byte the request this made before the name fields existed.
    source().read(RepositoryRef.ofId("qits-gateway"), SHA);

    assertEquals("/git/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void halfAnAddressTakesTheIdRouteRatherThanBuildingHalfAPath() {
    source().read(new RepositoryRef(UUID_ID, "qits", null), SHA);

    assertEquals("/git/" + UUID_ID + "/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void aTrailingSlashOnTheConfiguredHostDoesNotDoubleTheSeparator() {
    GitHostSpecSource source = source();
    source.gitHostUrl = source.gitHostUrl + "/";

    source.read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA);

    assertEquals(
        "/git/qits/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void a404IsAnAnswerOnBothArms() {
    // A repository carrying no file deploys with every default — unchanged by which URL asked.
    status = 404;

    SpecSource.SpecRead byName = source().read(new RepositoryRef(UUID_ID, "qits", "gw"), SHA);
    assertSame(DeploymentSpec.DEFAULTS, byName.spec());
    SpecSource.SpecRead byId = source().read(RepositoryRef.ofId("gw"), SHA);
    assertSame(DeploymentSpec.DEFAULTS, byId.spec());
    // No blob, so no commit: a 404 says nothing about where the rev points, and inventing a sha
    // here would put a commit on the deployment row that nobody resolved.
    assertNull(byId.commitSha());
    // ...and the defaults arrive marked as nobody's declaration, which is what lets the release
    // door tell "the file says nothing" from "there is no file". A read that DID find the file
    // says so — every other case in this class asserts that side by simply deploying.
    assertFalse(byName.declared(), "a 404 on both arms declared nothing");
    assertFalse(byId.declared());
    status = 200;
    assertTrue(source().read(RepositoryRef.ofId("gw"), SHA).declared(), "a file that was read is");
  }

  @Test
  public void anythingElseFailsTheDeploymentNamingTheUrlItAsked() {
    // The fe26a6c stance: a read that could not be answered is a failure, never an empty spec.
    status = 503;

    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> source().read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA));

    assertTrue(refused.getMessage().contains("/git/qits/qits-gateway/blob/"), refused.getMessage());
    assertTrue(refused.getMessage().contains("503"), refused.getMessage());
    // ...and it says the read may yet answer, which is what keeps the release from stranding.
    assertTrue(refused.retryable(), "a 503 is the git host's moment, not the repository's content");
  }

  @Test
  public void anIntermittent403IsTheGitHostsMomentAndAsksToBeReadAgain() {
    // Measured on 2026-09-04: qits-githost answered 403 on a blob a later request served fine, and
    // three releases stranded on the terminal FAILED rows that produced. This reader sends one
    // fixed forwarded identity on every request, so a refusal here cannot be about the credential.
    status = 403;

    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION)));

    assertTrue(refused.getMessage().contains("403"), refused.getMessage());
    assertTrue(refused.retryable(), "403 is retryable");
  }

  @Test
  public void aBadRequestIsThisComponentsOwnBugAndIsNotRepeatedForever() {
    // The mirror of the case above, and the reason retryability is a decision rather than "not a
    // 404": a 400 or a 405 on a URL this class built will answer exactly the same in an hour, so
    // looping on it would hide a bug behind a status that reads like patience.
    status = 400;

    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION)));

    assertFalse(refused.retryable(), "400 is permanent");
  }

  @Test
  public void aGitHostThatDoesNotAnswerAtAllAsksToBeReadAgain() {
    // A refused connection: nothing looked at the file, so nothing is being claimed about it.
    GitHostSpecSource source = source();
    server.stop(0);

    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> source.read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION)));

    assertTrue(refused.retryable(), "a transport failure is retryable");
  }

  @Test
  public void aFileThatDoesNotParseIsTheRepositorysProblemAndStaysTerminal() {
    // It WAS read. Reading it again answers the same thing, so this must never be held: a broken
    // commit would become a permanent background job.
    body = "  deployment_target: platform\n"; // indented, and this file has no nesting

    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION)));

    assertFalse(refused.retryable(), "a parse failure is permanent: " + refused.getMessage());
  }

  @Test
  public void aNameAddressed404ThatFallsBackOntoA403KeepsThatVerdict() {
    // The name route's 404 fallback asks the id route, and a 403 THERE has to arrive with the same
    // verdict as one on the direct route — or the fallback would quietly strand the release.
    status = 404;
    laterStatus = 403;

    SpecException refused =
        assertThrows(
            SpecException.class,
            () ->
                source()
                    .read(new RepositoryRef(UUID_ID, "qits", "gw"), SpecSource.tagRev(VERSION)));

    assertTrue(refused.retryable(), "403 on the name route is retryable too");
  }

  @Test
  public void aReleaseIsReadAtTheTagRefWithItsSlashesEncoded() {
    // THE claim of the version campaign, and it has two halves. The rev is refs/tags/<version>
    // rather than the bare version, because a bare name is whatever the repository holds under it
    // — a branch of that name would win, and the file that decides where this container runs has
    // to be the file the release was cut from. And a rev is ONE path segment to the git host,
    // whose route regex is [^/]+ and whose charset refuses a literal slash, so it arrives encoded.
    source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertEquals(
        "/git/qits-gateway/blob/refs%2Ftags%2F" + VERSION + "/.config/qits/deployments.yml",
        onlyPath());
  }

  @Test
  public void theResolvedCommitComesBackWithTheSpecRatherThanFromASecondRequest() {
    // The git host offers no ref-resolution endpoint at all, so without this header a released
    // deployment could record no commit and the edge from a container back to a diff would be
    // gone. One request, both answers.
    SpecSource.SpecRead read =
        source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertEquals(SHA, read.commitSha());
    assertEquals(1, paths.size(), "one request, not a read plus a resolution");
  }

  @Test
  public void anAnswerWithNoCommitHeaderCostsTheTraceEdgeAndNotTheDeployment() {
    // An older git host, or a proxy that dropped the header. The version is the coordinate and the
    // sha is advisory, so the read still answers and the row simply names no commit.
    commitSha = null;

    SpecSource.SpecRead read =
        source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertNull(read.commitSha());
    assertNotNull(read.spec());
  }

  // --- the second blob: the configuration declaration ---------------------------------------------

  @Test
  public void theDeclarationIsReadAtTheSameTagFromItsOwnPath() {
    // The same address, the same encoded rev, one file over. The rev encoding is the half that was
    // got wrong once and 404'd every release-tag read, so it is pinned on both files rather than on
    // whichever one the builder happens to be shared through.
    body = "defaults:\n  FLAGS: on\n";

    SpecSource.DeclarationRead read =
        source().readDeclaration(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertTrue(read.present());
    assertEquals(
        "/git/qits-gateway/blob/refs%2Ftags%2F" + VERSION + "/.config/qits/configuration.yml",
        onlyPath());
  }

  @Test
  public void theDeclarationsBodyIsHandedOnUnparsed() {
    // qits-configuration owns this grammar and is the only thing that validates it. A parser here
    // would be a second opinion about somebody else's document, and the two would disagree on the
    // day the grammar grows a key — which is the day a deployment refuses a file the store takes.
    // So a body this component could not possibly understand still reads fine.
    body = "@@ not yaml at all, and not this component's business @@\n";

    SpecSource.DeclarationRead read =
        source().readDeclaration(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertEquals(body, read.yaml(), "the bytes reached the caller untouched");
  }

  @Test
  public void aNameAddressed404IsAbsentImmediatelyAndAsksTheIdRouteNothing() {
    // THE regression, measured live on qits-ci@2026.907.184918 (2026-09-07). A repository that has
    // not migrated its configuration yet — which is most of them — 404s the name route, and the
    // fallback then asked the id route, which qits-githost's storage-client guard refuses to every
    // caller but qits-projects. That 403 is classified retryable, so "not migrated" arrived as a
    // sixty-minute SPEC_UNREADABLE hold. The name on this read is authoritative, so there is no
    // false miss to disbelieve: one request, and absence is the answer.
    status = 404;
    laterStatus = 403; // what the id route would have said. Nothing must ask it.

    SpecSource.DeclarationRead read =
        source()
            .readDeclaration(new RepositoryRef(UUID_ID, "qits", "gw"), SpecSource.tagRev(VERSION));

    assertFalse(read.present(), "not migrated is absent, not a failure");
    assertEquals(
        "/git/qits/gw/blob/refs%2Ftags%2F" + VERSION + "/.config/qits/configuration.yml",
        onlyPath());
  }

  @Test
  public void anIdOnlyRefStillReadsIdAddressedAndA404ThereIsAbsentToo() {
    // The other arm is unchanged: a ref carrying no name has one address, which is what `address`
    // answers rather than a fallback, and a 404 on it is the same clean answer.
    status = 404;

    assertFalse(
        source().readDeclaration(RepositoryRef.ofId("gw"), SpecSource.tagRev(VERSION)).present());
    assertEquals(
        "/git/gw/blob/refs%2Ftags%2F" + VERSION + "/.config/qits/configuration.yml", onlyPath());
  }

  @Test
  public void aDeclarationTheGitHostWouldNotServeAsksToBeReadAgain() {
    // One hop, one posture. A 5xx on the declaration is the same host having the same bad second as
    // a 5xx on the spec, so it holds the release rather than failing it — and there is no parse arm
    // here at all, because nothing on this side reads the body.
    status = 503;

    SpecException refused =
        assertThrows(
            SpecException.class,
            () ->
                source()
                    .readDeclaration(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION)));

    assertTrue(refused.getMessage().contains(".config/qits/configuration.yml"), refused.getMessage());
    assertTrue(refused.retryable(), "a 503 is the git host's moment");
  }

  private GitHostSpecSource source() {
    GitHostSpecSource source = new GitHostSpecSource();
    source.gitHostUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    source.timeoutSeconds = 5;
    source.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    return source;
  }

  private String onlyPath() {
    synchronized (paths) {
      assertEquals(1, paths.size(), "requests made");
      return paths.get(0);
    }
  }
}
