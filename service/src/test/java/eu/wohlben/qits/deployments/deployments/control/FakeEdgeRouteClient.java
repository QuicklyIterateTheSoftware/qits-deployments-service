package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The suite's stand-in for the two edges' route-table door (epic qits-2174), the seventh fake beside
 * {@link FakeArtifactPurge} and its siblings: {@code @Mock}, so no {@code @QuarkusTest} pushes a
 * table anywhere. What the real door is asked and answers is the pact's ({@code Contracts.EDGE}) and
 * {@code HttpEdgeRouteClientTest}'s; what the pusher does with the answer is held here.
 *
 * <p><b>The default is the real edge's version rule</b>: a pushed table is stored when its version
 * is above the held one ({@code 204}), else {@code 200 {"held":v}}; {@link #held} answers what was
 * stored. {@link #failWith} makes every call to one edge throw, {@link #reportHeld} overrides what
 * the edge says it holds.
 *
 * <p>Application-scoped and shared: reset in {@code @BeforeEach}, read through methods only.
 */
@Mock
@ApplicationScoped
public class FakeEdgeRouteClient implements EdgeRouteClient {

  /** One push, as the pusher made it. */
  public record Push(String edge, String table) {}

  private final List<Push> pushes = Collections.synchronizedList(new ArrayList<>());
  private final List<String> reads = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Long> stored = Collections.synchronizedMap(new HashMap<>());
  private final Map<String, Long> reported = Collections.synchronizedMap(new HashMap<>());
  private final Map<String, String> failures = Collections.synchronizedMap(new HashMap<>());

  public void reset() {
    pushes.clear();
    reads.clear();
    stored.clear();
    reported.clear();
    failures.clear();
  }

  /** Every call to this edge throws with this message, until reset or cleared. */
  public void failWith(String edge, String message) {
    failures.put(edge, message);
  }

  public void clearFailure(String edge) {
    failures.remove(edge);
  }

  /** The edge says it holds {@code version} (null: nothing), whatever was pushed. */
  public void reportHeld(String edge, Long version) {
    reported.put(edge, version == null ? -1L : version);
  }

  /** Every push to this edge, oldest first. */
  public List<Push> pushesTo(String edge) {
    synchronized (pushes) {
      return pushes.stream().filter(push -> push.edge().equals(edge)).toList();
    }
  }

  /** Every push, oldest first. */
  public List<Push> pushes() {
    synchronized (pushes) {
      return List.copyOf(pushes);
    }
  }

  /** How many times the pusher asked this edge what it holds. */
  public long readsOf(String edge) {
    synchronized (reads) {
      return reads.stream().filter(edge::equals).count();
    }
  }

  @Override
  public Pushed push(String edge, String table) {
    pushes.add(new Push(edge, table));
    String failure = failures.get(edge);
    if (failure != null) {
      throw new EdgeRouteException(failure);
    }
    long version;
    try {
      version = EdgeRouteTables.mapper().readTree(table).path("version").asLong();
    } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
      return new Refused("the body is not a JSON object");
    }
    Long held = stored.get(edge);
    if (held != null && version <= held) {
      return new Held(held);
    }
    stored.put(edge, version);
    reported.remove(edge);
    return new Stored();
  }

  @Override
  public OptionalLong held(String edge) {
    reads.add(edge);
    String failure = failures.get(edge);
    if (failure != null) {
      throw new EdgeRouteException(failure);
    }
    Long override = reported.get(edge);
    if (override != null) {
      return override < 0 ? OptionalLong.empty() : OptionalLong.of(override);
    }
    Long held = stored.get(edge);
    return held == null ? OptionalLong.empty() : OptionalLong.of(held);
  }
}
