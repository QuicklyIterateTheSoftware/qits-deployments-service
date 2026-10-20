package eu.wohlben.qits.deployments.confighost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.ServiceExtras;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Where a deployment's extras come from when the platform runs qits-configuration — against a stub
 * on a real socket, because what is under test is the request itself.
 *
 * <p>The claim underneath all of it: <b>a service that cannot answer refuses the deployment</b>.
 * Every arm that could quietly hand back an older value is a test here, because that value is the
 * one this whole line of work exists to stop shipping.
 */
class ConfigHostExtrasSourceTest {

  private static final String P = DeploymentDriver.EXTRAS_PREFIX;

  /** The retired spelling, still read by {@code ServiceExtras.of} and still in the boot config. */
  private static final String LEGACY_P = DeploymentDriver.LEGACY_EXTRAS_PREFIX;

  private static final ExtrasBearer NONE = Optional::empty;

  /** The tier this deployment is going into — a path segment of the resolved read's address. */
  private static final String ENV = "dev";

  /** The released coordinate the overrides are resolved against — the read's query parameter. */
  private static final String VERSION = "2026.903.113443";

  /**
   * This release seeded a declaration, which is what makes the version-addressed read the right
   * question to ask. Spelled as a constant because the OTHER answer is the whole of the section at
   * the bottom of this class and the two must be told apart at a glance.
   */
  private static final boolean DECLARED = true;

  /** The released tag carried no {@code .config/qits/configuration.yml}, so nothing was seeded. */
  private static final boolean UNDECLARED = false;

  private ExtrasStub stub;

  @BeforeEach
  void start() {
    stub = new ExtrasStub();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  private static Config boot(Map<String, String> properties) {
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "boot", 260))
        .build();
  }

  /** The shipped path, which no working directory of this suite has a file at. */
  private static final String NO_FILE = "config/application.properties";

  private static List<String> env(Config config) {
    return ServiceExtras.of(config, "qits-ci").env();
  }

  @Test
  void noExtrasUrlIsTheFileBehaviourByteForByte() {
    // Unset is the shipped state and the whole compatibility claim: nothing is fetched, nothing is
    // parsed, and the answer is the config volume's file over the boot config exactly as before.
    // The tier and the version are handed in and IGNORED here, which is not an oversight — a
    // properties file on a volume has one answer per key, so an address with more in it would name
    // a file that does not exist. This arm stayed byte-identical when the served one grew.
    ConfigHostExtrasSource source =
        ExtrasStub.source(boot(Map.of(P + "qits-ci.env.FOO", "bar")), NO_FILE, NONE, null);

    assertEquals(
        List.of("FOO=bar"), env(source.forDeployment("qits-ci", ENV, VERSION, DECLARED)));
    assertTrue(stub.paths().isEmpty(), "an unset url must reach nothing");
  }

  @Test
  void whatTheServiceStatesIsWhatTheDeploymentCarries() {
    stub.resolves(7, P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertEquals(
        List.of("QITS_EVENTS_URL=http://dev-qits-events:8080"),
        env(source.forDeployment("qits-ci", ENV, VERSION, DECLARED)));
    // ADDRESSED BY THE PLACE AND THE RELEASE. The tier is a path segment because one application's
    // configuration in dev is a different document from its configuration in prod, and the version
    // is a parameter because it selects which declaration the overrides resolve against — which is
    // what makes deploying an older version give that version's configuration back.
    assertEquals(
        List.of("/configuration/api/applications/qits-ci/envs/dev/resolved?version=" + VERSION),
        stub.targets(),
        "the resolved read is one GET per argv, at this deployment's own address");
  }

  @Test
  void anEnvironmentNameThatIsNotOneIsRefusedRatherThanEscaped() {
    // The belt at the boundary, now on two segments rather than one: every tier name reaching here
    // came out of the topology, where PdIdentifiers holds it to the dns-label charset, so a name
    // that would need escaping is a name this component could not have stored. Refusing beats
    // escaping, and it beats building the url and letting the peer decide.
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertThrows(
        ServiceExtras.Refused.class,
        () -> source.forDeployment("qits-ci", "../prod", VERSION, DECLARED));
    assertThrows(
        ServiceExtras.Refused.class,
        () -> source.forDeployment("qits-ci", ENV, "2026.903 113443", DECLARED));
    assertTrue(stub.paths().isEmpty(), "a refused address must reach nothing");
  }

  @Test
  void theFileIsNotConsultedAtAll() throws IOException {
    // AUTHORITATIVE MEANS SOLE. The file was layered under the served map for one release, so a key
    // DELETED from the service came straight back out of a file nobody had emptied — the one
    // operation the service exists to make possible was the one it could not perform. With the url
    // set the file contributes nothing: not a shadowed value, not a value it alone states.
    //
    // The boot config carries the SAME keys the file does — production's shape, not a weaker one:
    // Quarkus' config-dir read already put this file's last-boot contents into `config` before this
    // class ever runs, so a stub that only put the stale value in the FILE and left the boot config
    // clean would not be testing the layer qits-1468 actually came back out of.
    Path file = Files.createTempFile("qits-extras", ".properties");
    file.toFile().deleteOnExit();
    Files.writeString(
        file,
        P
            + "qits-ci.env.QITS_EVENTS_URL=http://stale:8080\n"
            + P
            + "qits-ci.env.ONLY_IN_THE_FILE=kept\n");
    stub.resolves(3, P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080");

    ConfigHostExtrasSource source =
        stub.source(
            boot(
                Map.of(
                    P + "qits-ci.env.QITS_EVENTS_URL", "http://stale:8080",
                    P + "qits-ci.env.ONLY_IN_THE_FILE", "kept")),
            file.toString(),
            NONE);

    assertEquals(
        List.of("QITS_EVENTS_URL=http://dev-qits-events:8080"),
        env(source.forDeployment("qits-ci", ENV, VERSION, DECLARED)),
        "the served map is the whole of it: neither the file's own key nor the boot config's copy"
            + " of it may reach the argv");
  }

  @Test
  void aKeyDeletedFromTheServiceDoesNotComeBackOutOfTheBootConfigsOwnCopyOfTheFile() {
    // qits-1468, reproduced end to end. The boot config here carries what Quarkus' config-dir read
    // actually leaves on a deployment host: the config volume's file, read once at boot, at ordinal
    // 260 — the `boot(...)` helper's own ordinal. The service has stopped stating the key (the stub
    // answers an EMPTY document), so a correct read serves nothing for it; the bug served the boot
    // config's copy of the file instead, which is how QUARKUS_OIDC_AUTH_SERVER_URL reached qits-ci
    // with qits.deployments.extras-url set and no file anywhere in the path.
    Config boot =
        boot(
            Map.of(
                P + "qits-ci.env.QUARKUS_OIDC_AUTH_SERVER_URL", "http://qits-platform-idp:8080/idp",
                LEGACY_P + "qits-ci.env.QUARKUS_OIDC_AUTH_SERVER_URL",
                    "http://qits-platform-idp:8080/idp"));
    stub.answers(200, "{\"headRevision\":1,\"properties\":{}}");
    ConfigHostExtrasSource source = stub.source(boot, NO_FILE, NONE);

    Config snapshot = source.forDeployment("qits-ci", ENV, VERSION, DECLARED);

    assertFalse(
        env(snapshot).stream().anyMatch(line -> line.startsWith("QUARKUS_OIDC_AUTH_SERVER_URL=")),
        "no QUARKUS_OIDC_AUTH_SERVER_URL in " + env(snapshot));
  }

  @Test
  void anUnreachableServiceRefusesTheDeploymentNamingTheUrl() {
    // Never a fall-back to the file: a stale extras value ships as a green deployment, which is the
    // failure class this whole change exists to end.
    String unreachable = "http://127.0.0.1:1";
    ConfigHostExtrasSource source =
        ExtrasStub.source(
            boot(Map.of(P + "qits-ci.env.FOO", "stale")), NO_FILE, NONE, unreachable);

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class,
            () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));

    assertTrue(refused.getMessage().contains(unreachable), refused.getMessage());
  }

  @Test
  void aServiceErrorRefusesTheDeploymentNamingTheUrl() {
    stub.answers(500, "nope");
    ConfigHostExtrasSource source =
        stub.source(boot(Map.of(P + "qits-ci.env.FOO", "stale")), NO_FILE, NONE);

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class,
            () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));

    assertTrue(refused.getMessage().contains(stub.url()), refused.getMessage());
    assertTrue(refused.getMessage().contains("500"), refused.getMessage());
  }

  @Test
  void anApplicationTheServiceHasNeverHeardOfRefusesToo() {
    // Unlike the spec read's 404, which really is an answer: a repository with no deployments.yml
    // deploys with the defaults. An application with no entries is an application whose extras this
    // deployment cannot know it is missing.
    stub.answers(404, "{}");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertThrows(
        ServiceExtras.Refused.class, () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));
  }

  @Test
  void aBodyThatIsNotJsonRefusesTheDeployment() {
    stub.answers(200, "<html>a proxy answered instead</html>");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class,
            () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));

    assertTrue(refused.getMessage().contains(stub.url()), refused.getMessage());
  }

  @Test
  void aDocumentWithNoPropertiesObjectRefusesTheDeployment() {
    // "This application states nothing" and "this is not the resolved document" must not be the
    // same deployment.
    stub.answers(200, "{\"headRevision\":9}");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertThrows(
        ServiceExtras.Refused.class, () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));
  }

  @Test
  void theBearerIsPresentedWhenTheClientHoldsOne() {
    stub.resolves(1);
    ConfigHostExtrasSource source =
        stub.source(boot(Map.of()), NO_FILE, () -> Optional.of("a-machine-token"));

    source.forDeployment("qits-ci", ENV, VERSION, DECLARED);

    assertEquals(List.of("Bearer a-machine-token"), stub.authorizations());
  }

  @Test
  void nothingIsPresentedWhenTheClientIsDisabled() {
    // The shipped posture: qits-configuration open behind forward-auth on qits-net, and this read
    // carrying the X-Qits-* pair alone. An Authorization header invented here would be a credential
    // nobody minted.
    stub.resolves(1);
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    source.forDeployment("qits-ci", ENV, VERSION, DECLARED);

    assertEquals(1, stub.authorizations().size());
    assertNull(stub.authorizations().get(0));
  }

  @Test
  void theRetryBudgetSpendsItselfBeforeRefusing() {
    // A service being redeployed is a few seconds of refusals, and no deployment should die of one.
    stub.then(503, "restarting").resolves(4, P + "qits-ci.env.FOO", "bar");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);
    source.attempts = 2;

    assertEquals(
        List.of("FOO=bar"), env(source.forDeployment("qits-ci", ENV, VERSION, DECLARED)));
    assertEquals(2, stub.paths().size(), "the second attempt is the one that answered");
  }

  @Test
  void theBudgetIsBoundedAndThenItRefuses() {
    stub.answers(503, "still restarting");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);
    source.attempts = 3;

    assertThrows(
        ServiceExtras.Refused.class, () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));

    assertEquals(3, stub.paths().size(), "spent, not unbounded");
  }

  @Test
  void aMalformedBodyIsNotRetried() {
    // A body that will not parse will not parse a second time, and the deploy worker is single
    // threaded with every other event queued behind it.
    stub.answers(200, "not json");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);
    source.attempts = 3;

    assertThrows(
        ServiceExtras.Refused.class, () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));

    assertEquals(1, stub.paths().size());
  }

  @Test
  void theRevisionDeployedWithIsRecorded() {
    // The deployment row's detail has no seam a revision could ride today — it is written by
    // DeployService out of the driver's verdict, and the extras are read a layer below that — so
    // what was deployed with is stated in the log beside the read. Asserted rather than assumed:
    // an unrecorded revision makes "which config is this container running" unanswerable, which is
    // half of why the service exists.
    stub.resolves(42, P + "qits-ci.env.FOO", "bar");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    List<String> logged = capture(() -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));

    assertTrue(
        logged.stream().anyMatch(line -> line.contains("config-revision=42")),
        "no config-revision in " + logged);
  }

  // --- the release that declared nothing ------------------------------------------------------

  @Test
  void aReleaseThatDeclaredNothingIsReadVersionLess() {
    // THE DEFECT THIS SECTION EXISTS FOR. The version parameter is what turns this into the overlay
    // read, and the store answers 404 for a version it holds no declaration for — deliberately, on
    // its own route's javadoc. The deployer seeds a declaration only for a release whose tag carries
    // .config/qits/configuration.yml, which almost nothing on the fleet does yet, so passing the
    // parameter unconditionally refused every one of those deployments and left the OLD container
    // serving. Version-less is the store's own documented transitional form and it is what an
    // undeclared release asks for.
    stub.resolves(7, P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertEquals(
        List.of("QITS_EVENTS_URL=http://dev-qits-events:8080"),
        env(source.forDeployment("qits-ci", ENV, VERSION, UNDECLARED)));
    assertEquals(
        List.of("/configuration/api/applications/qits-ci/envs/dev/resolved"),
        stub.targets(),
        "an undeclared release must not ask about a declaration nobody seeded");
  }

  @Test
  void theVersionLessReadSaysWhyItIsVersionLess() {
    // The transitional signal for the config-declarations epic's cutover: one WARN per deployment of
    // a repository that has not declared itself, naming the application and the version so the
    // remaining inventory can be read straight off a log. Asserted rather than assumed — a signal
    // nobody can grep is not one, and the day these lines stop appearing is the day the parameter
    // can stop being conditional.
    stub.resolves(7);
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    List<String> logged = capture(() -> source.forDeployment("qits-ci", ENV, VERSION, UNDECLARED));

    assertTrue(
        logged.stream()
            .anyMatch(
                line ->
                    line.contains("qits-ci@" + VERSION)
                        && line.contains("VERSION-LESS")
                        && line.contains(".config/qits/configuration.yml")),
        "no version-less warning naming the application and the version in " + logged);
  }

  @Test
  void aDeclaredReleaseStillAsksByVersionAndItsFourOhFourIsStillAnError() {
    // The other half, and the half that must not be softened. A release that DID seed a declaration
    // was answered 2xx by the store a moment before this read, so the document is there: a 404 on
    // the version-addressed read means something is wrong with the store, not with the repository,
    // and refusing the deployment is the right answer. That is also why the fallback is decided from
    // what the release carried rather than by retrying a 404 — a retry cannot tell the two 404s
    // apart and would quietly paper over exactly this one.
    stub.answers(404, "{}");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertThrows(
        ServiceExtras.Refused.class, () -> source.forDeployment("qits-ci", ENV, VERSION, DECLARED));

    assertEquals(
        List.of("/configuration/api/applications/qits-ci/envs/dev/resolved?version=" + VERSION),
        stub.targets(),
        "a seeded declaration is asked about by version, once, and never re-asked without it");
  }

  @Test
  void aVersionThatIsNotOneCannotRefuseAnUndeclaredDeployment() {
    // The charset check is a belt on a value going into a QUERY, so it applies where the value goes
    // into one. An undeclared release sends no version at all, and refusing its deployment over the
    // spelling of a string this read never transmits would be inventing a second way to fail.
    stub.resolves(1, P + "qits-ci.env.FOO", "bar");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertEquals(
        List.of("FOO=bar"),
        env(source.forDeployment("qits-ci", ENV, "2026.903 113443", UNDECLARED)));
  }

  /** Everything {@link ConfigHostExtrasSource} logs while {@code body} runs. */
  private static List<String> capture(Runnable body) {
    List<String> lines = new ArrayList<>();
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ConfigHostExtrasSource.class.getName());
    Level level = logger.getLevel();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            lines.add(String.valueOf(record.getMessage()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    logger.setLevel(Level.INFO);
    try {
      body.run();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(level);
    }
    assertFalse(lines.isEmpty(), "nothing was logged at all");
    return lines;
  }
}
