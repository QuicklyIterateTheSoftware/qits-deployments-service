package eu.wohlben.qits.deployments.githost;

import eu.wohlben.qits.deployments.deployments.control.DeploymentSpecParser;
import eu.wohlben.qits.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.deployments.deployments.control.SpecException;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link SpecSource}: one {@code GET} against the platform
 * git host's blob endpoint ({@code qits.deployments.git-host-url}), the same contract
 * qits-ci reads a pipeline definition through.
 *
 * <pre>
 * GET &lt;git-host-url&gt;/git/&lt;projectId&gt;/&lt;repoName&gt;/blob/&lt;rev&gt;/.config/qits/deployments.yml
 * GET &lt;git-host-url&gt;/git/&lt;repoId&gt;/blob/&lt;rev&gt;/.config/qits/deployments.yml
 * </pre>
 *
 * <p><b>It reads TWO files now, and they are one read twice rather than two readers.</b> Beside the
 * spec sits {@link SpecSource#DECLARATION_PATH}, the configuration a repository declares for its own
 * application, fetched at the same rev through the same address pair and handed on <b>unparsed</b> —
 * qits-configuration owns that grammar. Almost everything below the file name is shared: the
 * encoding of the rev, the 404-is-an-answer stance and the retryable-versus-permanent
 * classification. <b>Neither read falls back from the name route to the id route</b> —
 * {@link #readDeclaration} says why, and qits-883 is why the spec read lost its fallback too.
 *
 * <p><b>The rev is the RELEASED TAG, and it is fully qualified.</b> A deployment is a version now,
 * so the file that decides where its container runs has to be the file that version was cut from —
 * {@code refs/tags/2026.903.113443}, never the tip of a branch and never a bare tag name (a branch
 * of the same name would win). The git host takes a rev as one path segment and its own charset
 * refuses a literal {@code /}, so the slashes are percent-encoded; jgit's {@code
 * Repository#resolve} then peels an annotated tag for us.
 *
 * <p><b>The answer carries the commit the tag resolved to</b>, in the {@code Git-Commit-Sha}
 * header, and that is why this returns a {@link SpecRead} rather than a spec. The git host offers
 * no ref-resolution endpoint at all, so without this header a released deployment could record no
 * commit — and the edge from a container back to a diff would be gone. One request, both answers.
 *
 * <p><b>Two addresses for one blob, and the first is the one to use.</b> The git host's repository
 * key is an opaque storage UUID now, and {@code /git/<repoId>} is its internal scheme; the public
 * address is {@code (projectId, repoName)} and it is what a build event carries. So an announcement
 * with the name pair is read name-addressed, and one without it — an older publisher, or a push
 * that arrived on the internal route — keeps the id URL, which is exactly the request this made
 * before the pair existed.
 *
 * <p>This is the component's <b>only</b> outbound HTTP call, and it is deliberately made with the
 * JDK's own client rather than a generated REST client — one request, one path, no model to share.
 * Every path segment was validated at the intake (the slug discipline for the id, the project and
 * the name; a hex sha) before anything reached here, so none can leave the path it is written into.
 *
 * <p>It used to be one of two: the topology was another service, and every registration and every
 * resolution was a second client with a second failure mode. The merge left this one.
 *
 * <p><b>404 is an answer, not a failure.</b> A repository that carries no spec gets every default
 * — and, since 2026-09-05, gets them marked as nobody's declaration ({@link SpecRead#declared()}),
 * so the release intake can tell a repository that asked for the conventional deployment from one
 * that published an image and never asked to be deployed at all. Every other outcome — a refused
 * connection, a 500, a 403, a timeout, an unparseable file — raises {@link SpecException} and stops
 * the deployment: the spec decides where the container runs and what may reach it, and a guess
 * there is worse than a recorded failure.
 *
 * <p><b>Those failures are not all the same failure, and this class is where the difference is
 * decided.</b> A {@code SpecException} carries {@link SpecException#retryable()}, and everything
 * raised here that never saw the file — a transport failure, a 5xx, a 401/403/408/429 — says yes,
 * so the deployment is recorded {@code SPEC_UNREADABLE} and read again rather than stranded. What
 * the parser raises on a file it DID read says no. See {@link #statusFailure}.
 */
@ApplicationScoped
public class GitHostSpecSource implements SpecSource {

  private static final Logger LOG = Logger.getLogger(GitHostSpecSource.class);

  @ConfigProperty(name = "qits.deployments.git-host-url")
  String gitHostUrl;

  @ConfigProperty(name = "qits.deployments.git-host-timeout-seconds")
  long timeoutSeconds;

  @Inject QitsHttpClients clients;

  /**
   * One client for the life of the process, the sibling's arrangement (qits-ci's {@code
   * HttpGitConfigSource} holds one too). A {@code HttpClient} owns a selector thread and a
   * connection pool, so building one per green build spends both on a single request; built lazily
   * because the timeout it is configured with is a config value, and config is not injected yet
   * when the field initialiser would run — {@code clients} is the same case, so the wrap happens
   * here too. Wrapped through {@link QitsHttpClients} so this call to the git host opens a CLIENT
   * span and carries {@code X-Qits-Caller}.
   */
  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              clients.wrap(
                  HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build());
          client = existing;
        }
      }
    }
    return existing;
  }

  /** The header the git host answers every blob and tree read with: the resolved commit. */
  static final String COMMIT_SHA_HEADER = "Git-Commit-Sha";

  @Override
  public SpecRead read(RepositoryRef repository, String rev) {
    String url = blobUrl(address(repository), rev, SPEC_PATH);
    HttpResponse<String> response = get(url);

    if (response.statusCode() == 404) {
      // A name-addressed 404 is believed, with no second request. The fallback this used to run
      // here existed because the name route resolved through qits-projects and could false-miss
      // during a cutover; qits-githost now answers /git/<projectId>/<repoName> from its own name
      // index, so a name it does not hold is a 404 and an unreadable index is a 503 (retryable) —
      // there is no resolver left to disbelieve. And the id route is refused to this caller
      // anyway (qits-githost's storage-client guard serves /git/<repoId> to qits-projects alone),
      // so the fallback turned every image/daemon repository without a deployments.yml into a
      // retryable 403 that held and then parked the release (qits-883).
      LOG.debugf(
          "%s carries no %s at %s — deploying with the defaults",
          repository.applicationName(), SPEC_PATH, rev);
      // No blob, so no commit: a 404 says nothing about where the rev points, and inventing a sha
      // here would put a commit on the row that nobody resolved. And no blob is reported AS no
      // blob — SpecRead.undeclared() carries the defaults and the fact that nothing declared them,
      // which is the difference between a repository that asked for the conventional deployment
      // and one that never asked to be deployed at all.
      return SpecRead.undeclared();
    }
    if (response.statusCode() != 200) {
      throw statusFailure(url, response.statusCode());
    }
    return new SpecRead(
        DeploymentSpecParser.parse(
            response.body(), SPEC_PATH + " of " + repository.applicationName() + "@" + rev),
        commitShaOf(response));
  }

  /**
   * The second blob, at the same two addresses and with the same classification: {@link
   * SpecSource#DECLARATION_PATH}, returned <b>unparsed</b>.
   *
   * <pre>
   * GET &lt;git-host-url&gt;/git/&lt;projectId&gt;/&lt;repoName&gt;/blob/&lt;rev&gt;/.config/qits/configuration.yml
   * GET &lt;git-host-url&gt;/git/&lt;repoId&gt;/blob/&lt;rev&gt;/.config/qits/configuration.yml
   * </pre>
   *
   * <p><b>Nothing here reads the body.</b> qits-configuration owns the declaration grammar and is
   * the one thing that validates it — this hands the bytes on as the body of one POST. So there is
   * no parse arm, and therefore no permanent-failure arm that is this component's own: every failure
   * this can raise is a failure of the HOP, which is why the classification below is {@link
   * #statusFailure}'s unchanged.
   *
   * <p><b>There is NO name-then-id fallback here.</b> A 404 on the name-addressed route is {@link
   * DeclarationRead#absent()} at once. The name a declaration read is addressed with comes off the
   * release event or the acceptance ledger and is authoritative — the rename door corrects it — so
   * there is no false miss to disbelieve; and the id route is refused to this caller anyway, because
   * qits-githost's storage-client guard serves {@code /git/<repoId>} to qits-projects alone. So the
   * fallback could only ever convert <b>not migrated yet</b>, which is most repositories, into a 403
   * — and a 403 is classified retryable, which is a sixty-minute {@code SPEC_UNREADABLE} hold for a
   * file that was never there. Measured on qits-ci@2026.907.184918 on 2026-09-07, where it held
   * every unmigrated repository's deployment. {@link #read} dropped its fallback too, on
   * 2026-10-30 (qits-883): image and daemon repositories carry no {@code deployments.yml}, and the
   * fallback held every one of their releases on the same 403.
   *
   * <p><b>A ref that carries no name is still read id-addressed</b>, exactly as it always was — that
   * is {@link #address}'s answer, not a fallback — and a 404 there is absent for the same reason.
   *
   * <p><b>No {@code Git-Commit-Sha} is wanted from this read.</b> The commit is the spec read's
   * answer and the row already has it; asking twice would be two sources for one fact.
   */
  @Override
  public DeclarationRead readDeclaration(RepositoryRef repository, String rev) {
    String url = blobUrl(address(repository), rev, DECLARATION_PATH);
    HttpResponse<String> response = get(url);

    if (response.statusCode() == 404) {
      LOG.debugf(
          "%s carries no %s at %s — nothing to seed, which is what a repository that has not"
              + " migrated its configuration yet looks like",
          repository.applicationName(), DECLARATION_PATH, rev);
      return DeclarationRead.absent();
    }
    if (response.statusCode() != 200) {
      throw statusFailure(url, response.statusCode());
    }
    // Verbatim. Anything done to these bytes here is a difference between what the repository wrote
    // and what the platform stores.
    return new DeclarationRead(response.body());
  }

  /**
   * The failure a status that is neither 200 nor 404 raises, <b>classified</b>.
   *
   * <p>The message is what it always was; what is new is the second half of the answer — whether
   * reading again may yet succeed. {@link SpecException} says why that matters at all; this says
   * which statuses qualify.
   *
   * <p><b>Everything about the git host's MOMENT is retryable.</b> A 5xx is the host having a bad
   * second. A 401 or a 403 is the same shape here and not an authorisation decision worth
   * believing: this reader sends one fixed forwarded identity ({@code qits-deployments} /
   * {@code qits:system}) on every request, so a refusal cannot be about the credential — it is the
   * peer mid-boot, mid-cutover, or an edge answering for it. That is exactly what was measured on
   * 2026-09-04: the same blob 403'd once and read fine minutes later. A 408 and a 429 say the word
   * themselves.
   *
   * <p><b>Every other 4xx is the REQUEST, and the request will not change.</b> A 400 or a 405 on a
   * URL this class built is a bug in this class or a route the git host no longer serves; retrying
   * it every thirty seconds would hide it behind a status that reads like patience. The 404 never
   * reaches here — a repository with no spec gets the defaults, above.
   */
  private static SpecException statusFailure(String url, int status) {
    return new SpecException(
        "could not read " + url + ": the git host answered " + status, retryableStatus(status));
  }

  /** @see #statusFailure */
  static boolean retryableStatus(int status) {
    return status >= 500 || status == 401 || status == 403 || status == 408 || status == 429;
  }

  /**
   * The commit the rev resolved to, or null when the answer carried no such header — an older git
   * host, or a proxy that dropped it. Null costs the row its commit and never the deployment: the
   * version is the coordinate, and the sha is the trace edge.
   */
  private static String commitShaOf(HttpResponse<String> response) {
    return response
        .headers()
        .firstValue(COMMIT_SHA_HEADER)
        .map(String::strip)
        .filter(sha -> !sha.isEmpty())
        .orElse(null);
  }

  /**
   * A rev is ONE path segment to the git host, whose route regex is {@code [^/]+} and whose own
   * charset refuses a literal slash — so {@code refs/tags/<version>} has to arrive percent-encoded.
   * Package-private so {@code GitHostSpecSourceTest} can spell the expected url without a socket.
   *
   * <p><b>The FILE is a parameter now</b>, because the same blob endpoint serves two of them: the
   * deployment spec this class parses, and the configuration declaration it hands on unread. One
   * builder rather than two keeps the rev encoding — the thing that was got wrong once and cost
   * every release-tag read a 404 — spelled in a single place.
   */
  static String blobUrl(String gitHostUrl, String addressSegment, String rev, String path) {
    return trimTrailingSlash(gitHostUrl)
        + "/git/"
        + addressSegment
        + "/blob/"
        + rev.replace("/", "%2F")
        + "/"
        + path;
  }

  private String blobUrl(String addressSegment, String rev, String path) {
    return blobUrl(gitHostUrl, addressSegment, rev, path);
  }

  private HttpResponse<String> get(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .header("X-Qits-User", "qits-deployments")
              .header("X-Qits-Roles", "qits:system")
              .GET()
              .build();
      return client().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      // NOT retryable, and the interrupt is why: this thread has been asked to stop, so the one
      // thing that must not happen is scheduling the same read again on the way out.
      Thread.currentThread().interrupt();
      throw new SpecException("interrupted while reading " + url, e);
    } catch (Exception e) {
      // A transport failure is the retryable case by definition — a refused connection, a reset, a
      // timeout, a DNS miss while the git host's alias is being re-bound. None of them looked at
      // the file, so none of them says anything about it.
      throw new SpecException("could not read " + url + ": " + e, e, true);
    }
  }

  /**
   * The path segments that name the repository: the public pair when the event carried one, the
   * internal storage id when it did not. Package-private so {@code GitHostSpecSourceTest} can hold
   * both arms without a socket.
   */
  static String address(RepositoryRef repository) {
    return repository.nameAddressed()
        ? repository.projectId() + "/" + repository.repoName()
        : repository.repoId();
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
