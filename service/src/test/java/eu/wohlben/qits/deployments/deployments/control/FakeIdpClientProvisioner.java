package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The suite's stand-in for qits-idp's service-client API — a scripted fake, not an honest one: it
 * creates and rotates nothing, keeps a set of client ids "qits-idp" already knows, and answers what
 * the test told it to. {@code @Mock} makes it the {@link IdpClientProvisioner} for every {@code
 * @QuarkusTest} in this module, which is what keeps a clone's {@code mvn verify} free of any idp it
 * did not start itself.
 *
 * <p>It is the fifth fake now, beside {@link FakeDeploymentDriver}, {@link FakeSpecSource}, {@link
 * FakeResourceProvisioner} and {@link FakeDeclarationSeed} — the same rule applied a fifth time.
 * What a real qits-idp does with these requests is proven against the JDK's own {@code HttpServer}
 * in {@code HttpIdpClientProvisionerTest}; what the ORCHESTRATION does with the answers is proven
 * here.
 *
 * <p><b>The defaults are self-consistent and drift-free</b>: {@link #create} adds the id to the
 * "idp already knows this" set and {@link #databaseClientPresent} reads it back, so a test that
 * never scripts anything still gets a create that succeeds once and conflicts the second time, and
 * a rotate that succeeds once the id has been created. Script {@link #scriptCreateResult} or {@link
 * #scriptRotateResult} for the shapes the defaults cannot produce — a 409 on a client the presence
 * check still calls absent, or an outright failure.
 *
 * <p>Application-scoped and therefore shared across tests: reset it in {@code @BeforeEach} and use
 * distinct client ids per test, the {@link FakeResourceProvisioner} arrangement exactly. State is
 * exposed through <b>methods only</b> — the injected reference is a CDI client proxy, and a field
 * read on a proxy sees the proxy's own fields, never the bean's.
 */
@Mock
@ApplicationScoped
public class FakeIdpClientProvisioner implements IdpClientProvisioner {

  private final Set<String> databaseClients = Collections.synchronizedSet(new HashSet<>());
  private final List<String> presenceChecks = Collections.synchronizedList(new ArrayList<>());
  private final List<String> createCalls = Collections.synchronizedList(new ArrayList<>());
  private final List<String> rotateCalls = Collections.synchronizedList(new ArrayList<>());
  private final List<String> deleteCalls = Collections.synchronizedList(new ArrayList<>());

  private volatile Result nextCreateResult;
  private volatile Result nextRotateResult;
  private volatile Result nextDeleteResult;
  private volatile int secretCounter;

  public void reset() {
    databaseClients.clear();
    presenceChecks.clear();
    createCalls.clear();
    rotateCalls.clear();
    deleteCalls.clear();
    nextCreateResult = null;
    nextRotateResult = null;
    nextDeleteResult = null;
    secretCounter = 0;
  }

  /** Script that qits-idp already holds a DATABASE row for this client id, with no call. */
  public void seedDatabaseClient(String clientId) {
    databaseClients.add(clientId);
  }

  /** Script the next (and every following, until reset or rescripted) answer to {@link #create}. */
  public void scriptCreateResult(Result result) {
    nextCreateResult = result;
  }

  /** Script the next (and every following, until reset or rescripted) answer to {@link #rotate}. */
  public void scriptRotateResult(Result result) {
    nextRotateResult = result;
  }

  /** Script the next (and every following, until reset or rescripted) answer to {@link #delete}. */
  public void scriptDeleteResult(Result result) {
    nextDeleteResult = result;
  }

  /** Whether "qits-idp" currently holds a database row for this id — no call is recorded. */
  public boolean holds(String clientId) {
    return databaseClients.contains(clientId);
  }

  /** Every id {@link #databaseClientPresent} was asked about, in arrival order. */
  public List<String> presenceChecks() {
    return List.copyOf(presenceChecks);
  }

  /** Every id {@link #create} was called with, in arrival order. */
  public List<String> createCalls() {
    return List.copyOf(createCalls);
  }

  /** Every id {@link #rotate} was called with, in arrival order. */
  public List<String> rotateCalls() {
    return List.copyOf(rotateCalls);
  }

  /** Every id {@link #delete} was called with, in arrival order. */
  public List<String> deleteCalls() {
    return List.copyOf(deleteCalls);
  }

  @Override
  public boolean databaseClientPresent(String clientId) {
    presenceChecks.add(clientId);
    return databaseClients.contains(clientId);
  }

  @Override
  public Result create(String clientId) {
    createCalls.add(clientId);
    if (nextCreateResult != null) {
      return nextCreateResult;
    }
    if (databaseClients.contains(clientId)) {
      return new Result(false, true, null, "a database row already exists for " + clientId);
    }
    databaseClients.add(clientId);
    return new Result(true, false, freshSecret(clientId), null);
  }

  @Override
  public Result rotate(String clientId) {
    rotateCalls.add(clientId);
    if (nextRotateResult != null) {
      return nextRotateResult;
    }
    if (!databaseClients.contains(clientId)) {
      return new Result(false, false, null, "no database row exists for " + clientId + " to rotate");
    }
    return new Result(true, false, freshSecret(clientId), null);
  }

  /** Absent already is done, as qits-idp's 404 is: the default never fails. */
  @Override
  public Result delete(String clientId) {
    deleteCalls.add(clientId);
    if (nextDeleteResult != null) {
      return nextDeleteResult;
    }
    databaseClients.remove(clientId);
    return new Result(true, false, null, null);
  }

  private String freshSecret(String clientId) {
    return "fake-secret-" + clientId + "-" + ++secretCounter;
  }
}
