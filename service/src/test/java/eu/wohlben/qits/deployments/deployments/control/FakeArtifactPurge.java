package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The suite's stand-in for qits-artifacts' purge door (qits-1941), the fifth fake beside {@link
 * FakeIdpClientProvisioner} and its siblings, and for their reason: {@code @Mock} makes it the
 * {@link ArtifactPurge} of every {@code @QuarkusTest}, so no decommission reaches a registry.
 * What the real door is asked and answers is the pact's ({@code Contracts.ARTIFACTS}) and {@code
 * HttpArtifactPurgeTest}'s; what the decommission does with the answer is held here.
 *
 * <p><b>The default is the real door's idempotence</b>: an application scripted with {@link
 * #publish} is reported removed by its first purge and answers an empty list after that; an
 * unscripted one answers empty at once. {@link #failWith} makes every purge of one application
 * throw until reset.
 *
 * <p>Application-scoped and shared: reset in {@code @BeforeEach}, use distinct application names,
 * read state through methods only (the injected reference is a client proxy).
 */
@Mock
@ApplicationScoped
public class FakeArtifactPurge implements ArtifactPurge {

  /** One call, as the decommission made it. */
  public record Call(String applicationName, List<String> repositories) {}

  private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, List<RemovedArtifact>> published =
      Collections.synchronizedMap(new HashMap<>());
  private final Map<String, String> failures = Collections.synchronizedMap(new HashMap<>());
  private final Set<String> purged = Collections.synchronizedSet(new HashSet<>());

  public void reset() {
    calls.clear();
    published.clear();
    failures.clear();
    purged.clear();
  }

  /** Script what the first purge of this application reports removed. */
  public void publish(String applicationName, List<RemovedArtifact> artifacts) {
    published.put(applicationName, List.copyOf(artifacts));
  }

  /** Make every purge of this application fail with this message, until reset or cleared. */
  public void failWith(String applicationName, String message) {
    failures.put(applicationName, message);
  }

  public void clearFailure(String applicationName) {
    failures.remove(applicationName);
  }

  /** Every call for one application, in arrival order. */
  public List<Call> callsFor(String applicationName) {
    synchronized (calls) {
      return calls.stream().filter(call -> call.applicationName().equals(applicationName)).toList();
    }
  }

  @Override
  public List<RemovedArtifact> purge(String applicationName, List<String> repositories) {
    calls.add(new Call(applicationName, List.copyOf(repositories)));
    String failure = failures.get(applicationName);
    if (failure != null) {
      throw new PurgeException(failure);
    }
    if (!purged.add(applicationName)) {
      return List.of();
    }
    return published.getOrDefault(applicationName, List.of());
  }
}
