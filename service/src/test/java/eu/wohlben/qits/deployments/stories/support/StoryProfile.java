package eu.wohlben.qits.deployments.stories.support;

import eu.wohlben.qits.deployments.api.PdPackagedSurfaceIT;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>One launched qits-platform-deployments for the whole story catalogue</b>, and every seam a
 * story moves, declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * deployers — two boots, two JWKS fetches, two databases and a diagram whose startup traffic landed
 * in whichever process happened to be running. Every story class in this catalogue therefore names
 * this one, {@code api.TokenValidationBootstrapIT} included: it is a story class like the others
 * and it owns the boot.
 *
 * <p>It extends {@link PdPackagedSurfaceIT.PackagedUnderTarget} rather than restating it. What a
 * launched qits-platform-deployments needs in order to boot at all — the two mandatory {@code
 * QITS_RESOURCE_*} triples, and the parking trick that carries them across the two classloaders a
 * test profile is instantiated in — is one answer, written out at length over there, and a second
 * copy would be a second place for it to drift.
 *
 * <h2>Its own databases</h2>
 *
 * <p>The catalogue <b>writes</b>: a tier, a service catalogue, deployment rows and provisioned
 * resource rows. Sharing {@code PdPackagedSurfaceIT}'s databases would make either suite's
 * assertions depend on whether the other had run, so the names here are this profile's own and the
 * mechanism is the parent's {@code databaseUrl}.
 *
 * <h2>Every key below is a RUNTIME key</h2>
 *
 * <p>A packaged process takes its configuration as {@code -D} arguments on a jar that was already
 * built, so a build-time key here would be silently ignored and the stories would prove the opposite
 * of what they say.
 *
 * <h2>The seams, and why each one is moved</h2>
 *
 * <ul>
 *   <li><b>{@code qits.auth.machine.required=true}</b> — the gate. The shipped tenant is {@code
 *       quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, so this one key is the
 *       difference between a service that validates machine bearers and one that does not. Every
 *       refusal in this catalogue is a claim only a gate-on packaged run can make. No audience key
 *       rides with it: there is one audience on this platform, {@code quarkus.oidc.token.audience}
 *       names it as a literal, and qits-auth-core ships the same one for {@code MachineAuth}.
 *   <li><b>{@code quarkus.oidc.auth-server-url}</b> — where the idp is. Discovery is on, as
 *       shipped: the boot reads the mock's discovery document and then the JWKS it names, so the
 *       shipped boot-time fetch is exercised rather than replaced.
 *   <li><b>{@code qits.deployments.container-runtime}</b> — {@link StorySwarm}, a recording
 *       executable. This is the seam that makes the orchestrator hop evidence instead of a claim.
 *   <li><b>{@code git-host-url}</b> — {@link StoryPeers}, the one peer a deployment reads from,
 *       and {@code quarkus.oidc-client.qits.*} — the bearer the read presents, minted there too.
 *       An application's environment is the settings store, in this process's own database, so
 *       there is no second peer to point anywhere.
 *   <li><b>{@code qits.deployments.edges.*-url}</b> — a closed loopback port. The route-table
 *       pushes run on the deploy worker, and an edge alias the build machine happens to resolve
 *       would hold that worker for a whole retry budget per edge. Nothing here records them.
 *   <li><b>{@code qits.deployments.egress.url}</b> — the same closed port, for the egress grant
 *       table's push to qits-egress (qits-2523), which runs on the same worker.
 *   <li><b>{@code observe-interval-seconds=0}</b> — see below.
 *   <li><b>{@code otel} and {@code eventstream} dark</b> — a step container has no qits-observability
 *       and no qits-events, and an exporter retrying against an unresolvable host would bury the
 *       story's own log. The eventstream DATASOURCE is still opened and migrated, which is why the
 *       inherited second triple is not optional.
 * </ul>
 *
 * <h2>The observation ticker is OFF, and that is a stated coverage gap</h2>
 *
 * <p>{@code DeploymentObserver} runs every {@code observe-interval-seconds} on the deploy worker and
 * asks the orchestrator {@code service ps <name>} about the latest row of each place. That call is
 * <b>byte-identical</b> to the one a deployment's own convergence check makes, so a recording cannot
 * tell a timer pass from a story-driven one — and an arrow that appears or disappears depending on
 * how long a story took is a {@code networkHash} that never settles. Filtering only works when lines
 * differ by content, so the loop is switched off with the shipped value that exists for it rather
 * than filtered.
 *
 * <p>What that costs is stated rather than hidden: <b>no story here covers the observer</b> — the
 * {@code FAILED}/{@code GONE} → {@code ACTIVE} recovery, the two-strike demotion, the
 * decommissioning of a recovered row's predecessor. {@code PdDeploymentObservationTest} holds those
 * against the fake driver, and they stay a {@code @QuarkusTest}'s claim.
 */
public class StoryProfile extends PdPackagedSurfaceIT.PackagedUnderTarget {

  /** Where this catalogue's own registry lives, on this JVM's embedded postgres. */
  private static final String DB_PROPERTY = "qits.test.stories.db-url";

  /** …and the bus client's store, which is opened and migrated even though the bus is dark. */
  private static final String EVENTSTREAM_PROPERTY = "qits.test.stories.eventstream-url";

  /**
   * Where every edge's route-table push goes: a loopback literal on port 1 (tcpmux, never bound on
   * a build machine), refused in one attempt with no lookup and no retry.
   */
  static final String EDGE_NOBODY_LISTENS = "http://127.0.0.1:1";

  @Override
  public Map<String, String> getConfigOverrides() {
    MockIdp idp = MockIdp.ensureStarted();
    String peers = StoryPeers.ensureStarted();
    String runtime = StorySwarm.install();

    Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
    // Databases of this catalogue's own — the parent's parking trick, this profile's names.
    overrides.put("QITS_RESOURCE_DB_URL", databaseUrl(DB_PROPERTY, "pd_stories_it"));
    overrides.put(
        "QITS_RESOURCE_EVENTSTREAM_URL",
        databaseUrl(EVENTSTREAM_PROPERTY, "pd_stories_eventstream_it"));

    // The gate, and where the keys it validates against come from.
    overrides.put("qits.auth.machine.required", "true");
    overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());

    // Dark outside a deployment, like %dev/%test. Both are runtime keys, and both ship ENABLED
    // because a library that shipped dark would be one whose first deployment discovers it was
    // never wired up.
    overrides.put("quarkus.otel.sdk.disabled", "true");
    overrides.put("qits.eventstream.enabled", "false");
    // ...and the trust anchors (qits-2633): no qits-certificates in a step container, and a held
    // bundle would put QITS_TRUST_ANCHORS on every recorded argv.
    overrides.put("qits.trust.enabled", "false");

    // The orchestrator: a recording executable rather than a daemon this container cannot reach.
    overrides.put("qits.deployments.container-runtime", runtime);

    // The domain every image reference and StoryTarget.imageRef() are derived from (RegistryHost).
    overrides.put("qits.deployments.platform-domain", "dev.localhost:8080");

    // The one peer a deployment reads from, and the credential it presents there (qits-1331): the
    // `qits` oidc client, on, minting from the same stub (StoryPeers answers /idp/token unrecorded).
    overrides.put("qits.deployments.git-host-url", peers);
    overrides.put("quarkus.oidc-client.qits.client-enabled", "true");
    overrides.put("quarkus.oidc-client.qits.auth-server-url", peers + "/idp");
    overrides.put("quarkus.oidc-client.qits.credentials.secret", "story-deployer-client-secret");

    // The timer whose pass a recording cannot tell from a deployment's own. See the class javadoc.
    overrides.put("qits.deployments.observe-interval-seconds", "0");

    // The three edges' route-table door (qits-2174), at a closed loopback port. EdgeRoutePusher
    // pushes on the deploy worker — the boot's first rebuild included — and the shipped addresses
    // are wire aliases (http://dev-qits-edge-private:80, …-overlay:80) that a build machine may
    // well resolve: on a CI lease they did, every connect timed out, and qits-http-core's
    // RetryPolicy rode each one for its whole budget, ~35 s apiece, holding the ONE deploy worker
    // past StoryPlatform's settle wait. A loopback literal is never retried (RetryPolicy.loopback)
    // and is refused at once, so every push is one WARN in milliseconds, as the public edge's
    // *.localhost name already was. No recorder sits behind these sockets, so no story's diagram
    // moves: the rebuild's `service ls` is StorySwarm's, and that is unchanged.
    overrides.put("qits.deployments.edges.public-url", EDGE_NOBODY_LISTENS);
    overrides.put("qits.deployments.edges.private-url", EDGE_NOBODY_LISTENS);
    overrides.put("qits.deployments.edges.overlay-url", EDGE_NOBODY_LISTENS);

    // qits-egress's grant-table door (qits-2523), the same closed port for the same reason: the
    // push runs on the deploy worker at boot and after every ACTIVE, and the shipped address is
    // https://egress.qits.${QITS_DOMAIN}. Refused at once, one WARN, and nothing recorded.
    overrides.put("qits.deployments.egress.url", EDGE_NOBODY_LISTENS + "/egress/api");
    return overrides;
  }
}
