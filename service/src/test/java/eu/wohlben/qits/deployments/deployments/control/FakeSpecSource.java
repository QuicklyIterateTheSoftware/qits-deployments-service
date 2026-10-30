package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for the git host — {@code @Mock}, so no {@code @QuarkusTest} in this module
 * ever makes cd's one outbound HTTP call.
 *
 * <p><b>Its default answer is a DECLARED {@link DeploymentSpec#DEFAULTS}</b> — a repository that
 * carries the file and sets nothing in it — which is the backward compatibility contract in one
 * line: it behaves exactly as every repository did before the file existed. A test that wants a
 * spec scripts one for its repository by name.
 *
 * <p><b>A repository with NO FILE is a different answer and is scripted separately</b> ({@link
 * #scriptNoSpec}). The two produce the same spec and the release door treats them differently: a
 * file that says nothing asks for the conventional deployment, and no file asks for none. Keeping
 * the default on the declared side is what keeps every test that only wants "and then it deploys"
 * saying that.
 *
 * <p>Application-scoped and therefore shared: reset it in {@code @BeforeEach} and use distinct
 * repository ids per test. State is read through methods only — the injected reference is a CDI
 * client proxy.
 */
@Mock
@ApplicationScoped
public class FakeSpecSource implements SpecSource {

  /** The commit every scripted read reports the rev resolved to. One value: no test varies it. */
  public static final String RESOLVED_COMMIT = "0f1e2d3c4b5a69788796a5b4c3d2e1f009182736";

  private final Map<String, DeploymentSpec> specs = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();
  private final Map<String, String> revs = new ConcurrentHashMap<>();

  /**
   * The whole {@link RepositoryRef} each read was made with, so a test can hold WHICH ADDRESS a
   * door produced rather than only what came back.
   *
   * <p>It is the ref and not a derived string on purpose: which of githost's two routes a read takes
   * is {@link RepositoryRef#nameAddressed()}'s answer and the real source composes the path from
   * exactly these fields ({@code GitHostSpecSource.address}). Recording the ref lets a door's suite
   * assert the choice without a socket, while {@code GitHostSpecSourceTest} keeps holding the other
   * half — that the choice really becomes that URL — against a real HTTP server.
   */
  private final Map<String, RepositoryRef> refs = new ConcurrentHashMap<>();

  /** Which scripted failures say "read me again" — see {@link #scriptRetryableFailure}. */
  private final Set<String> retryable = ConcurrentHashMap.newKeySet();

  /** Which applications carry no file at all — see {@link #scriptNoSpec}. */
  private final Set<String> undeclared = ConcurrentHashMap.newKeySet();

  /** How often each application's spec has been read, so a retry is countable rather than timed. */
  private final Map<String, Integer> reads = new ConcurrentHashMap<>();

  public void reset() {
    specs.clear();
    failures.clear();
    revs.clear();
    refs.clear();
    retryable.clear();
    undeclared.clear();
    reads.clear();
  }

  /**
   * The rev this application's spec was last read at — {@code refs/tags/<version>} on every release
   * path. It is recorded rather than asserted here so a test can hold the claim that the file the
   * deployment ran on is the file the RELEASED TAG carries, which is the difference between
   * deploying a version and deploying whatever a branch happens to point at.
   */
  public String revOf(String applicationName) {
    return revs.get(applicationName);
  }

  /**
   * What this application declares, whatever rev is asked for.
   *
   * <p><b>Keyed by the APPLICATION NAME</b>, which is the repository's name when the announcement
   * carried one and its storage id when it did not — the same answer {@link
   * RepositoryRef#applicationName()} gives the deployment. A test that announces an id alone
   * scripts by that id, exactly as it always did; a test that announces the name pair scripts by
   * the name. Keying by the raw id instead would make a name-addressed test script a spec no read
   * could find.
   */
  public void script(String applicationName, DeploymentSpec spec) {
    specs.put(applicationName, spec);
  }

  /**
   * Script a PERMANENT spec failure for this application — a file that does not parse, a key the
   * schema refuses. The deployment ends {@code FAILED} and nothing reads it again.
   */
  public void scriptFailure(String applicationName, String message) {
    failures.put(applicationName, message);
    retryable.remove(applicationName);
  }

  /**
   * Script a spec failure the read may yet survive — the git host refusing the blob, 500-ing, or
   * not answering. The deployment ends {@code SPEC_UNREADABLE} and the release is held.
   *
   * <p>It is a second method rather than a boolean on the first because the two are different
   * claims about the world, and a test that means "the git host had a moment" should not read as
   * "the file is broken, retryable = true".
   */
  public void scriptRetryableFailure(String applicationName, String message) {
    failures.put(applicationName, message);
    retryable.add(applicationName);
  }

  /**
   * Script this application as carrying <b>no</b> {@link #SPEC_PATH} at all — the git host's 404,
   * which is a clean answer and not a failure.
   *
   * <p>It is the shape a repository that publishes a docker image and is not a service has: a
   * workspace base image, a build image. The read answers the defaults, as it always did, and says
   * that nobody declared them.
   */
  public void scriptNoSpec(String applicationName) {
    undeclared.add(applicationName);
    specs.remove(applicationName);
  }

  /**
   * Stop failing this application's reads — what a git host that has come back looks like.
   */
  public void recover(String applicationName) {
    failures.remove(applicationName);
    retryable.remove(applicationName);
  }

  /**
   * The reference this application's spec was last read WITH — the address the announcing door
   * produced, not the one this fake would have chosen.
   *
   * <p>Keyed like everything else here, by {@link RepositoryRef#applicationName()}: a read carrying
   * the name pair is filed under the repository NAME and one carrying an id alone under the id, so
   * a test looks up whichever coordinate its frame announced.
   */
  public RepositoryRef refOf(String applicationName) {
    return refs.get(applicationName);
  }

  /** How many reads this application's spec has been asked for, retries included. */
  public int readsOf(String applicationName) {
    return reads.getOrDefault(applicationName, 0);
  }

  @Override
  public SpecRead read(RepositoryRef repository, String rev) {
    revs.put(repository.applicationName(), rev);
    refs.put(repository.applicationName(), repository);
    reads.merge(repository.applicationName(), 1, Integer::sum);
    String failure = failures.get(repository.applicationName());
    if (failure != null) {
      throw new SpecException(failure, retryable.contains(repository.applicationName()));
    }
    if (undeclared.contains(repository.applicationName())) {
      // The 404 arm, verbatim: the defaults, no commit — a missing blob says nothing about where
      // the tag points — and nothing declared.
      return SpecRead.undeclared();
    }
    return new SpecRead(
        specs.getOrDefault(repository.applicationName(), DeploymentSpec.DEFAULTS),
        RESOLVED_COMMIT);
  }
}
