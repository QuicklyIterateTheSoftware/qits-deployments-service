package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for the public edge as the split check probes it (epic qits-2174, task
 * qits-2215), the eighth fake beside {@link FakeEdgeRouteClient}: {@code @Mock}, so no
 * {@code @QuarkusTest} resolves a name or opens a socket. The default answer is {@code 404} — the
 * public edge refusing a private application, which is the pass; {@link #answer} scripts another
 * status for one server name and {@link #failWith} makes its probe throw.
 *
 * <p>Application-scoped and shared: reset in {@code @BeforeEach}, read through methods only.
 */
@Mock
@ApplicationScoped
public class FakePublicEdgeProbe implements PublicEdgeProbe {

  /** One probe, as the split check made it. */
  public record Probe(String apex, String serverName, String path) {}

  private final List<Probe> probes = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Integer> answers = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();

  public void reset() {
    probes.clear();
    answers.clear();
    failures.clear();
  }

  /** The public edge answers {@code status} for {@code serverName}. */
  public void answer(String serverName, int status) {
    answers.put(serverName, status);
  }

  /** The probe of {@code serverName} gets no status line, with this message. */
  public void failWith(String serverName, String message) {
    failures.put(serverName, message);
  }

  public List<Probe> probes() {
    synchronized (probes) {
      return List.copyOf(probes);
    }
  }

  @Override
  public int status(String apex, String serverName, String path) throws IOException {
    probes.add(new Probe(apex, serverName, path));
    String failure = failures.get(serverName);
    if (failure != null) {
      throw new IOException(failure);
    }
    return answers.getOrDefault(serverName, 404);
  }
}
