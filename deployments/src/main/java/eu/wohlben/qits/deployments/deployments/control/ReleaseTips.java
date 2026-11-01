package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;

/**
 * Is this release still the newest version of its application?
 *
 * <p><b>Why the question exists at all.</b> The manual door is a live POST: what arrives is what
 * just happened. The bus is not. A durable consumer is caught up from qits-events' log after a
 * disconnect, a restart or a cutover, so it can be handed a release that was cut <em>before</em>
 * one it has already deployed — and a handler that simply applied whatever arrived would, one
 * restart later, roll an older version over a newer one. The library says so in {@code
 * QitsDurableEventListener}'s javadoc: collapsing to the tip is the consumer's job, because only
 * the consumer knows which of its effects commute.
 *
 * <p>This is not about duplicates. The same event arriving twice is impossible — the library claims
 * each {@code (listener, event id)} pair exactly once. This is about <b>different, older</b> events.
 *
 * <p>It is the ancestor {@code BuildTips} re-derived for versions, and the derivation changed two
 * things:
 *
 * <ul>
 *   <li><b>The measure is the VERSION, not a timestamp.</b> A build was ordered by when it
 *       finished, because two commits carry no order at all. A release carries its own order in its
 *       name: the CalVer stamp is monotonic per application, so "is this newer" is a comparison
 *       between the two values themselves rather than between the clock readings of the events that
 *       announced them. That is strictly better — a replayed event whose envelope timestamp was
 *       rewritten still cannot claim to be a version it is not. Compared by {@link Versions},
 *       because the stamp is unpadded and lexical order gets it wrong (see that class).
 *   <li><b>The branch is gone.</b> A build was the tip of a {@code (repository, branch)}; a release
 *       is the tip of an APPLICATION. There are no branch-scoped release lines, and the tiers a
 *       version lands in are no longer chosen by a branch either.
 * </ul>
 *
 * <h2>What "the tip" is measured against, and why it takes two answers</h2>
 *
 * <p>The floor is the newest version this component already accepted for that application, and
 * there are two ways to know it, each covering what the other cannot:
 *
 * <ul>
 *   <li><b>What this process announced</b>, remembered in a map. Exact, and what makes two releases
 *       seconds apart both deploy, in order.
 *   <li><b>The newest deployment REQUEST row</b> for that application, consulted only when the map
 *       has nothing. That is the cross-restart floor: a process that has just booted remembers
 *       nothing, and a catch-up sweep at startup is precisely when a stale event arrives.
 * </ul>
 *
 * <p>The request row rather than the deployment row, and that is the one place this improves on its
 * ancestor rather than translating it. A request is written the moment a release is accepted for a
 * place — before the pull, before the health gate, before any terminal word — so it records "this
 * version was asked for here" even when the deployment then failed. {@code BuildTips} had to read a
 * deployment row instead, which is stamped minutes later and is why it needed a paragraph about
 * comparing a build's finish time against a row's write time. There is no such skew here: both
 * sides of this comparison are version strings.
 *
 * <p><b>The residual, stated rather than hidden.</b> A release accepted for NO place — an
 * application nothing is registered for, a platform with no tier designated — writes no request
 * row, so a restart between that release and its successor loses the floor and a stale catch-up can
 * pass. It costs nothing: there was nowhere to deploy it either way.
 *
 * <p>The manual door writes nothing here and is deliberately not guarded by it: an operator posting
 * a version is choosing that version, including when the choice is a rollback.
 */
@ApplicationScoped
public class ReleaseTips {

  @Inject PdDeploymentRequestRepository requests;

  /**
   * The newest version this process has announced, per application.
   *
   * <p>In memory on purpose, and bounded by how many applications this platform deploys. It is a
   * collapse aid rather than a fact — losing it costs a restart the exactness above and falls back
   * to the request row, which is the arrangement the strike counter in {@code DeploymentObserver}
   * is written the same way for.
   */
  private final Map<String, String> announced = new HashMap<>();

  /**
   * Claim this version as the tip of its application: true if it is the newest one seen, false if
   * something newer was already accepted.
   *
   * <p>Synchronized because the two delivery channels are two threads — the stream's socket worker
   * and the catch-up sweeper — and the read of the floor and the write of the new one have to be
   * one step, or a burst of catch-up rows can each pass a floor none of them then raises.
   *
   * <p><b>Keyed by the application name</b>, which is what a request row records and what an image
   * tag is built from. It is deliberately not the repository id: the release names a package, two
   * repositories may (last-wins, as this component has always allowed) deploy one application, and
   * what must never go backwards is the version of the APPLICATION.
   *
   * @param applicationName what this release deploys under
   * @param version the released CalVer stamp
   */
  public synchronized boolean claim(String applicationName, String version) {
    if (version == null) {
      // A release with no version cannot be ordered against anything, and cannot be deployed
      // either — the caller has already refused it. Nothing to claim.
      return false;
    }
    String floor = announced.get(applicationName);
    if (floor == null) {
      floor = newestRequested(applicationName);
    }
    if (!Versions.isNewerThan(version, floor)) {
      return false;
    }
    announced.put(applicationName, version);
    return true;
  }

  /**
   * Claim this version back as the tip of its application after a restart interrupted it: true if
   * it is still the newest one seen <em>or is that very version</em>, false if something newer was
   * accepted since.
   *
   * <p><b>Why {@link #claim} cannot answer this, and why it must stay as it is for the door that
   * uses it.</b> The floor {@link #claim} measures against is read from the request rows, and a
   * release a restart interrupted (qits-1803) already WROTE its request — the worker's {@code
   * deploy()} does that first. So the floor is this version itself, {@link Versions#isNewerThan}
   * of a version against itself is false, and {@link #claim} would call the one release still owed
   * stale. On the live door that strictness is the point: the same version offered twice there is a
   * duplicate. Here it is not — {@link OwedReleaseSweep} asks only after the request rows have shown
   * that every attempt at this version was cut off by a restart, which is what makes "equal" a
   * resumption rather than a repeat.
   *
   * <p><b>What it still refuses is the rollback</b>, which is the one thing a re-drive must never
   * cause: a version that a newer request has overtaken is below the floor, and stays there whatever
   * became of its own attempt. Synchronized with {@link #claim}, for the same read-then-write reason,
   * and it raises the in-memory floor the same way so a stale catch-up behind it is still collapsed.
   *
   * @param applicationName what this release deploys under
   * @param version the released CalVer stamp the interrupted deployment was performing
   */
  public synchronized boolean reclaim(String applicationName, String version) {
    if (version == null) {
      return false;
    }
    String floor = announced.get(applicationName);
    if (floor == null) {
      floor = newestRequested(applicationName);
    }
    if (Versions.compare(version, floor) < 0) {
      return false;
    }
    announced.put(applicationName, version);
    return true;
  }

  /**
   * The newest version this application was asked for, or null if it never was.
   *
   * <p><b>In a transaction of its own.</b> The caller is inside the library's claim transaction,
   * which has the {@code eventstream} datasource enlisted; joining it would put a second non-XA
   * resource in one transaction, which Narayana refuses. Suspending it is also the honest shape:
   * this is a read about the world, not part of the claim.
   */
  private String newestRequested(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(() -> requests.newestVersionOf(applicationName).orElse(null));
  }
}
