package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

/**
 * The suite's stand-in for qits-egress's grant-table door (epic qits-2523), the ninth fake beside
 * {@link FakeEdgeRouteClient} and its siblings: {@code @Mock}, so no {@code @QuarkusTest} pushes a
 * table anywhere. What the real door is asked and answers is the pact's ({@code Contracts.EGRESS})
 * and {@code HttpEgressGrantClientTest}'s; what the pusher does with the answer is held here.
 *
 * <p><b>The default is the real door's version rule</b>: a pushed table is stored when its version
 * is not below the held one ({@code 204}), else {@code 409} with the held version; {@link
 * #heldVersion} answers what was stored, empty before the first push. {@link #failWith} makes every
 * call throw, {@link #reportHeld} overrides what qits-egress says it holds.
 *
 * <p>Application-scoped and shared: reset in {@code @BeforeEach}, read through methods only.
 */
@Mock
@ApplicationScoped
public class FakeEgressGrantClient implements EgressGrantClient {

  private final List<String> pushes = Collections.synchronizedList(new ArrayList<>());
  private volatile int reads;
  private volatile Long stored;
  private volatile Long reported;
  private volatile boolean overridden;
  private volatile String failure;

  public synchronized void reset() {
    pushes.clear();
    reads = 0;
    stored = null;
    reported = null;
    overridden = false;
    failure = null;
  }

  /** Every call throws with this message, until reset or cleared. */
  public void failWith(String message) {
    failure = message;
  }

  public void clearFailure() {
    failure = null;
  }

  /** qits-egress says it holds {@code version} (null: nothing), whatever was pushed. */
  public synchronized void reportHeld(Long version) {
    reported = version;
    overridden = true;
  }

  /** Every pushed body, oldest first. */
  public List<String> pushes() {
    synchronized (pushes) {
      return List.copyOf(pushes);
    }
  }

  /** The version of every push, oldest first. */
  public List<Long> pushedVersions() {
    return pushes().stream().map(FakeEgressGrantClient::versionOf).toList();
  }

  /** How many times the pusher asked what qits-egress holds. */
  public int reads() {
    return reads;
  }

  @Override
  public synchronized Pushed push(String tableJson) {
    pushes.add(tableJson);
    if (failure != null) {
      throw new EgressGrantException(failure);
    }
    long version = versionOf(tableJson);
    Long held = overridden ? reported : stored;
    if (held != null && version < held) {
      return new Newer(held);
    }
    stored = version;
    overridden = false;
    reported = null;
    return new Stored();
  }

  @Override
  public synchronized OptionalLong heldVersion() {
    reads++;
    if (failure != null) {
      throw new EgressGrantException(failure);
    }
    Long held = overridden ? reported : stored;
    return held == null ? OptionalLong.empty() : OptionalLong.of(held);
  }

  private static long versionOf(String tableJson) {
    try {
      return EdgeRouteTables.mapper().readTree(tableJson).path("version").asLong();
    } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
      throw new IllegalArgumentException("the pushed body is not JSON: " + tableJson, unreadable);
    }
  }
}
