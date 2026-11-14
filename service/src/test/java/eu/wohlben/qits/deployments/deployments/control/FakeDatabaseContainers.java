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
 * <p><b>The default is a move that works</b>: the target does not exist ({@code check}), the source
 * is small and both filesystems have room ({@code size}, {@code space} — qits-2243's measures: one
 * filesystem, {@link #DEFAULT_SIZE} against {@link #DEFAULT_FREE}), the extract and the ingest both
 * print the same one-table manifest, and every admin statement succeeds. A test scripts a purpose to
 * make one step fail or disagree.
 *
 * <p>Application-scoped and shared across tests: reset it in {@code @BeforeEach}, and read it through
 * its methods — the injected reference is a CDI client proxy.
 */
@Mock
@ApplicationScoped
public class FakeDatabaseContainers implements DatabaseContainers {

  /** The manifest both sides print by default. */
  public static final String MANIFEST = "public.items=3\npublic.flyway_schema_history=2";

  /** What {@code size} answers by default: 8 MiB. */
  public static final long DEFAULT_SIZE = 8L << 20;

  /** What {@code space} answers free on its one filesystem by default: 100 GiB. */
  public static final long DEFAULT_FREE = 100L << 30;

  private static final long GIB = 1L << 30;

  private final List<OneShot> shots = Collections.synchronizedList(new ArrayList<>());
  private final List<String> created = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removed = Collections.synchronizedList(new ArrayList<>());
  private final List<String> killed = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Function<OneShot, Outcome>> scripted = new ConcurrentHashMap<>();
  private final Map<String, Runnable> onKill = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> labelled = new ConcurrentHashMap<>();
  private volatile Drain drain = Drain.DRAINED;

  public void reset() {
    shots.clear();
    created.clear();
    removed.clear();
    killed.clear();
    scripted.clear();
    onKill.clear();
    labelled.clear();
    drain = Drain.DRAINED;
  }

  /** The move ids {@link #kill} was asked for, in order. */
  public List<String> killed() {
    return List.copyOf(killed);
  }

  /** What a {@link #kill} of any move does besides recording it — a blocked one-shot released. */
  public void onKill(Runnable action) {
    onKill.put("any", action);
  }

  /** What {@code size} prints for a source of {@code bytes}. */
  public static Outcome sized(long bytes) {
    return new Outcome(0, DatabaseScripts.SIZE_MARKER + bytes + "\n", false);
  }

  /**
   * What {@code space} prints: the data directory's filesystem and the volume's, each as {@code df
   * -Pk} would — a device, its total and its free bytes.
   */
  public static Outcome space(
      String dataDevice, long dataTotal, long dataFree, String volumeDevice, long volumeTotal, long volumeFree) {
    return new Outcome(
        0,
        DatabaseScripts.DATA_DF_MARKER
            + "Filesystem     1024-blocks      Used Available Capacity Mounted on\n"
            + DatabaseScripts.DATA_DF_MARKER
            + df(dataDevice, dataTotal, dataFree, "/var/lib/postgresql/data")
            + DatabaseScripts.VOLUME_DF_MARKER
            + df(volumeDevice, volumeTotal, volumeFree, DatabaseContainers.MOUNT),
        false);
  }

  /** {@link #space} with one filesystem holding both, {@code free} of it free. */
  public static Outcome oneFilesystem(long free) {
    return space("/dev/sda1", 150 * GIB, free, "/dev/sda1", 150 * GIB, free);
  }

  private static String df(String device, long total, long free, String mount) {
    long totalK = total / 1024;
    long freeK = free / 1024;
    return device + " " + totalK + " " + (totalK - freeK) + " " + freeK + " 50% " + mount + "\n";
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
      case "check" -> shot.script().contains(DatabaseScripts.DESCRIBE_MARKER)
          ? absent(shot.script())
          : new Outcome(0, DatabaseScripts.EXISTS_MARKER + "f\n", false);
      case "size" -> sized(DEFAULT_SIZE);
      case "space" -> oneFilesystem(DEFAULT_FREE);
      case "extract", "ingest" -> printing(MANIFEST);
      default -> new Outcome(0, "", false);
    };
  }

  /** What {@link DatabaseScripts#describe} prints when none of the databases it names exists. */
  static Outcome absent(String script) {
    StringBuilder out = new StringBuilder();
    java.util.regex.Matcher names =
        java.util.regex.Pattern.compile("'" + DatabaseScripts.DESCRIBE_MARKER + "([a-z0-9_]+)='").matcher(script);
    while (names.find()) {
      out.append(DatabaseScripts.DESCRIBE_MARKER).append(names.group(1)).append("=false=\n");
    }
    return new Outcome(0, out.toString(), false);
  }

  /** What {@link DatabaseScripts#describe} prints for one database that exists with {@code mark}. */
  public static Outcome described(String database, String mark) {
    return new Outcome(0, DatabaseScripts.DESCRIBE_MARKER + database + "=true=" + mark + "\n", false);
  }

  @Override
  public boolean createVolume(String volume) {
    created.add(volume);
    return true;
  }

  @Override
  public boolean createVolume(String volume, Map<String, String> labels) {
    created.add(volume);
    if (!labels.isEmpty()) {
      labelled.put(volume, Map.copyOf(labels));
    }
    return true;
  }

  @Override
  public Map<String, Map<String, String>> labelledVolumes(String key, String value) {
    Map<String, Map<String, String>> found = new java.util.TreeMap<>();
    labelled.forEach(
        (volume, labels) -> {
          if (value.equals(labels.get(key))) {
            found.put(volume, labels);
          }
        });
    return found;
  }

  @Override
  public boolean removeVolume(String volume) {
    removed.add(volume);
    labelled.remove(volume);
    return true;
  }

  @Override
  public boolean kill(String moveId) {
    killed.add(moveId);
    onKill.values().forEach(Runnable::run);
    return true;
  }

  @Override
  public Drain drained(String service) {
    return drain;
  }
}
