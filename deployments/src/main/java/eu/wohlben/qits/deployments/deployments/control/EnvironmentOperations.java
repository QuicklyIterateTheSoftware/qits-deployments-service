package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.error.ConflictException;
import eu.wohlben.qits.deployments.events.EnvironmentChanged;
import eu.wohlben.qits.deployments.events.EnvironmentCreated;
import eu.wohlben.qits.deployments.events.EnvironmentDeleted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Environment lifecycle <b>with its docker side</b>: the operational door onto {@link
 * EnvironmentService}, which owns the rows and touches nothing else.
 *
 * <p>The split is the module partition made concrete. Creating a tier is a row; making its network
 * is a container-runtime act. Deleting a tier is a row; reaping its containers and removing its
 * networks is not. Everything in the second half needs the docker seam, and the docker seam belongs
 * to the execution domain — so this class is where the two halves are composed, and it is the only
 * place that knows the order they go in.
 *
 * <p>Reads pass straight through. They are here so a caller has one door rather than two, not
 * because there is anything to add to them.
 */
@ApplicationScoped
public class EnvironmentOperations {

  private static final Logger LOG = Logger.getLogger(EnvironmentOperations.class);

  @Inject EnvironmentService environments;
  @Inject DeployService deployService;
  @Inject DeploymentDriver driver;
  @Inject EdgeRoutePusher edgeRoutes;

  /**
   * Where a tier's lifecycle leaves this component ({@link EnvironmentAnnouncer}). An {@code
   * Instance} for the reason {@code DeployService}'s is one: zero implementations is a supported
   * configuration, and a component without the bus manages tiers exactly as before and says
   * nothing.
   *
   * <p>It is injected HERE and not into {@link EnvironmentService}, which owns the rows, and the
   * split is the same one the class exists for. The row service is the transaction; an announcement
   * must happen after it, and a port injected inside it would sit under the bracket where the only
   * available moment is the wrong one.
   */
  @Inject Instance<EnvironmentAnnouncer> announcers;

  /**
   * The transition network every container also joins ({@link DeployService}). Read here for one
   * reason only: a teardown must never take it down. See {@link #delete}.
   *
   * <p>{@code Optional} for the same reason it is there — SmallRye reads the enforcement flip's own
   * empty value as ABSENT rather than as an empty string.
   */
  @ConfigProperty(name = "qits.deployments.legacy-network")
  Optional<String> legacyNetwork;

  /**
   * Create the tier, then make sure its bundle network exists.
   *
   * <p>The docker half is best-effort and happens <b>after</b> the row: an environment whose
   * network could not be created yet is still an environment (the driver re-ensures every network
   * before every deployment), so a momentarily unreachable docker must not make the create fail.
   *
   * <p><b>The announcement goes out between the two halves, not after both</b>, and the order is
   * deliberate for the same reason the docker half is best-effort: the tier exists the moment the
   * row commits, and a {@code driver} call that threw on its way to the network would otherwise
   * leave a committed tier that no consumer is ever told about — a permanent gap, where a missing
   * network is a self-healing one.
   */
  public PdEnvironment create(String name, String network, boolean platform) {
    PdEnvironment environment = environments.create(name, network, platform);
    announce(
        environment.id,
        announcer ->
            announcer.onCreated(
                new EnvironmentCreated(
                    environment.id,
                    environment.name,
                    environment.designated,
                    environment.createdAt)));
    driver.ensureNetwork(
        new DeploymentDriver.Network(
            environment.network, environment.id, DeploymentDriver.NetworkKind.BUNDLE, null));
    return environment;
  }

  /**
   * Rename an environment, or designate it the platform one. <b>No docker side effects</b>, so this
   * is a pass-through and stays one: a rename that tore containers down would be a delete in
   * disguise, and delete is the one operation never to reach for on a live environment. The next
   * deployment of each application moves it onto the networks the new name derives; what runs now
   * keeps running.
   *
   * <p>Moving the platform designation is a pass-through for the same reason, and it is the one
   * place that reasoning is worth restating: a platform service keeps its bare wire alias, so a
   * peer reaches it under the same name whichever tier is designated. What the move changes is the
   * tier the plane's <em>next</em> deployment names — its row, its labels and its
   * {@code QITS_ENVIRONMENT}.
   *
   * <p><b>It is a pass-through no longer, and the one thing added is the announcement</b> — after
   * the update's transaction, carrying the row as it now stands. {@code changedAt} is read from the
   * clock rather than from the row, because there is no {@code updated_at} column and this change
   * does not add one: the moment a tier's name moved is a fact about the event, and the row's job
   * is to say what the name IS.
   */
  public PdEnvironment update(String environmentId, String name, Boolean platform) {
    PdEnvironment environment = environments.update(environmentId, name, platform);
    announce(
        environment.id,
        announcer ->
            announcer.onChanged(
                new EnvironmentChanged(
                    environment.id, environment.name, environment.designated, Instant.now())));
    return environment;
  }

  /**
   * Tear the environment down: the recorded deployments, then the containers and the networks, then
   * the tier itself.
   *
   * <p><b>Docker first, the rows last</b>, and the order is the contract. The teardown is
   * label-driven — it reaps by {@code qits.designated.deployments.environment} and removes the
   * networks carrying that id — so it needs nothing from the topology, but deleting the tier first
   * would leave a failed teardown with no row to retry it from. Deleting last means a half-finished
   * teardown is still addressable.
   *
   * <p>The runtime half is best-effort otherwise: a teardown must succeed even when the daemon
   * already lost the services.
   *
   * <p>The order between the container reap and the network removal is load-bearing too. Platform
   * services live on this environment's networks without being this environment's,
   * so they survive the reap and would then hold every network open — docker refuses
   * to remove a network with an endpoint on it. The plane is detached first ({@link
   * DeploymentDriver#detachPlatformPlane}, which is nothing at all under swarm, where a service's
   * networks are declared rather than joined), and only the networks THIS environment owns (its bundle plus
   * everything labelled with its id) are removed, so a platform service keeps every other
   * environment it serves.
   *
   * <p><b>The legacy network is never one of them.</b> An environment may have been created with
   * {@code qits.deployments.legacy-network} as its bundle — the dev tier IS that case, its
   * bundle is {@code qits-net} — and it is not that environment's to take away: it is the
   * transition membership of every container on the host, platform services included. Disconnecting
   * them from it would cut qits-idp off from the platform, and this component would be doing it to
   * itself mid-request. So it is skipped for both steps, and the environment's derived
   * per-application networks still go.
   *
   * <p><b>The platform environment is refused.</b> It is the tier a release enters at and the tier
   * the platform plane is deployed into, so tearing it down would leave qits-platform-idp and the
   * rest with nowhere to deploy — running, unreachable by any future release, and with nothing in
   * the model to say why. Designate another environment first; that is a move, and it leaves the
   * plane with a tier throughout.
   *
   * <p><b>And the reap does not take the plane with it</b>, which the label alone no longer
   * guarantees: a platform service carries this tier's environment label now, so {@link
   * DeploymentDriver#removeEnvironmentContainers} demands the {@code target=environment} label
   * beside it. That refusal above means the designated tier is never the one being torn down, but
   * the two guards are independent on purpose — a designation moved a minute earlier would
   * otherwise make a teardown reap the plane.
   */
  public void delete(String environmentId) {
    PdEnvironment environment = environments.require(environmentId);
    if (environment.designated) {
      throw new ConflictException(
          "Environment "
              + environment.name
              + " is the platform environment: a release would enter nowhere and the platform"
              + " plane would have no tier to deploy into. Designate another environment first.");
    }
    String legacy = legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).orElse(null);
    Set<String> networks = new LinkedHashSet<>();
    networks.add(environment.network);
    for (DeploymentDriver.Network network : driver.networks()) {
      if (environmentId.equals(network.environmentId())) {
        networks.add(network.name());
      }
    }
    if (networks.remove(legacy)) {
      LOG.infof(
          "Environment %s was on the legacy network '%s' — left in place, it is the platform's",
          environment.name, legacy);
    }

    deployService.forgetEnvironment(environmentId);
    int removed = driver.removeEnvironmentContainers(environmentId);
    if (removed > 0) {
      LOG.infof("Removed %d container(s) of environment %s", removed, environmentId);
    }
    if (removed > 0) {
      // The tier's services left the labels, so they leave the route tables (qits-2174) — on the
      // worker, where every rebuild runs, and never failing the teardown.
      deployService.enqueue(edgeRoutes::refresh);
    }
    driver.detachPlatformPlane(List.copyOf(networks));
    for (String network : networks) {
      driver.removeNetwork(network);
    }

    environments.delete(environmentId);
    announce(
        environmentId,
        announcer ->
            announcer.onDeleted(
                new EnvironmentDeleted(environmentId, environment.name, Instant.now())));
  }

  public PdEnvironment require(String environmentId) {
    return environments.require(environmentId);
  }

  public List<PdEnvironment> list() {
    return environments.list();
  }

  /**
   * The belt every announcement is fastened with, and it is the rule rather than caution — {@code
   * DeployService.announceQueued} argues it at length and nothing here differs.
   *
   * <p>An announcement is a statement ABOUT an environment operation, never part of it. A bus that
   * is unreachable, a serializer that refuses a value, an implementation with a bug must cost the
   * log line below and nothing else: a tier that was created is created whether or not anybody was
   * told, and failing the caller's request would invite a retry that then conflicts on the name it
   * just took. Every announcer is offered the event — {@code Instance} is a set, usually of one and
   * validly of none — and one that throws does not stop the next.
   *
   * <p>{@code RuntimeException} and no wider, which is exactly what the deployment side catches: an
   * {@code Error} is not this method's to swallow.
   */
  private void announce(String environmentId, Consumer<EnvironmentAnnouncer> announcement) {
    for (EnvironmentAnnouncer announcer : announcers) {
      try {
        announcement.accept(announcer);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing environment %s failed", environmentId);
      }
    }
  }
}
