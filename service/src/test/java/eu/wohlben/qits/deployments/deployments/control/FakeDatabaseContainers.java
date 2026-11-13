package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The suite's stand-in for the database move's containers (qits-2175) — a scripted fake: it runs
 * nothing, records every one-shot, and answers by the one-shot's {@code purpose} what the test told
 * it to. {@code @Mock} makes it the {@link DatabaseContainers} for every {@code @QuarkusTest}, which
 * keeps a clone's suite free of docker. What docker is asked is {@code DockerDatabaseContainersTest}'s,
 * argv by argv.
 *
 * <p><b>The default is a move that works</b>: the target does not exist ({@code check}), the extract
 * and the ingest both print the same one-table manifest, and every admin statement succeeds. A test
 * scripts a purpose to make one step fail or disagree.
 *
 * <p>Application-scoped and shared across tests: reset it in {@code @BeforeEach}, and read it through
 * its methods — the injected reference is a CDI client proxy.
 */
@Mock
@ApplicationScoped
public class FakeDatabaseContainers implements DatabaseContainers {

  /** The manifest both sides print by default. */
  public static final String MANIFEST = "public.items=3\npublic.flyway_schema_history=2";

  private final List<OneShot> shots = Collections.synchronizedList(new ArrayList<>());
  private final List<String> created = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removed = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Function<OneShot, Outcome>> scripted = new ConcurrentHashMap<>();
  private volatile Drain drain = Drain.DRAINED;

  public void reset() {
    shots.clear();
    created.clear();
    removed.clear();
    scripted.clear();
    drain = Drain.DRAINED;
  }

  /** Every one-shot, in the order it was run. */
  public List<OneShot> shots() {
    return List.copyOf(shots);
  }

  /** The one-shots of one purpose. */
  public List<OneShot> shots(String purpose) {
    return shots().stream().filter(shot -> shot.purpose().equals(purpose)).toList();
  }

  public List<String> createdVolumes() {
    return List.copyOf(created);
  }

  public List<String> removedVolumes() {
    return List.copyOf(removed);
  }

  /** What every one-shot of {@code purpose} answers from now on. */
  public void script(String purpose, Function<OneShot, Outcome> answer) {
    scripted.put(purpose, answer);
  }

  public void scriptDrain(Drain answer) {
    drain = answer;
  }

  /** A script's output, as a one-shot that printed {@code manifest} between the markers. */
  public static Outcome printing(String manifest) {
    return new Outcome(
        0,
        DatabaseScripts.MANIFEST_BEGIN + "\n" + manifest + "\n" + DatabaseScripts.MANIFEST_END + "\n",
        false);
  }

  @Override
  public Outcome run(OneShot shot, Duration timeout) {
    shots.add(shot);
    Function<OneShot, Outcome> answer = scripted.get(shot.purpose());
    if (answer != null) {
      return answer.apply(shot);
    }
    return switch (shot.purpose()) {
      case "check" -> new Outcome(0, DatabaseScripts.EXISTS_MARKER + "f\n", false);
      case "extract", "ingest" -> printing(MANIFEST);
      default -> new Outcome(0, "", false);
    };
  }

  @Override
  public boolean createVolume(String volume) {
    created.add(volume);
    return true;
  }

  @Override
  public boolean removeVolume(String volume) {
    removed.add(volume);
    return true;
  }

  @Override
  public Drain drained(String service) {
    return drain;
  }
}
