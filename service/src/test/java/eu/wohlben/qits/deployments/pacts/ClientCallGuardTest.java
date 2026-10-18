package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every outbound call qits-deployments' code can make has a pact row</b> (qits-1149). The set of
 * calls is derived from the code, not written down: every class under any module's {@code
 * src/main/java} that imports an HTTP client — {@code QitsHttpClients}, {@code
 * java.net.http.HttpClient}, {@code quarkus-oidc-client}'s {@code OidcClient}, a Vert.x {@code
 * WebClient} or a MicroProfile REST client — is a client of another service, and each of its public
 * instance methods is a call site. Each one must be the {@link Contracts.Check#method} of some row,
 * live or pending, and every row must name a method that exists.
 *
 * <p>So a new client class, or a new public method on one, fails here until it has a row. The calls
 * the framework makes are held to the configuration that switches them on instead: the quarkus-oidc
 * tenant's {@code auth-server-url} needs its discovery and JWKS rows.
 *
 * <p><b>Named exclusions</b>, none of them a call this source tree makes: the qits-eventstream
 * library's catch-up reads and outbox publish to qits-events (pacted by qits-eventstream-javalib,
 * which owns that code); the OTLP telemetry export to qits-observability (an exporter, not a REST
 * client); and the docker engine and swarm the deployer drives (not a qits service). This service
 * probes no peer's health and dials no websocket.
 */
class ClientCallGuardTest {

  private static final List<String> CLIENT_IMPORTS =
      List.of(
          "import eu.wohlben.qits.http.QitsHttpClients;",
          "import java.net.http.HttpClient;",
          "import io.quarkus.oidc.client.OidcClient;",
          "import io.vertx.ext.web.client.",
          "import org.eclipse.microprofile.rest.client.");

  @Test
  void everyClientMethodHasARowAndEveryRowAClientMethod() throws IOException {
    Set<String> inCode = new TreeSet<>();
    for (Class<?> client : clientClasses()) {
      for (Method method : client.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())
            || Modifier.isStatic(method.getModifiers())
            || method.isSynthetic()) {
          continue;
        }
        inCode.add(client.getSimpleName() + "#" + method.getName());
      }
    }
    assertFalse(inCode.isEmpty(), "no client class found under src/main/java");

    Set<String> bound =
        Contracts.all().stream()
            .flatMap(contract -> contract.checks().stream())
            .filter(check -> check.client() != null)
            .map(check -> check.client().getSimpleName() + "#" + check.method())
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> unbound = new TreeSet<>(inCode);
    unbound.removeAll(bound);
    Set<String> stale = new TreeSet<>(bound);
    stale.removeAll(inCode);
    assertEquals(
        Set.of(),
        unbound,
        "client methods with no row in Contracts (add one, pending if the provider has no state for"
            + " it)");
    assertEquals(Set.of(), stale, "rows naming a client method the code does not have");
  }

  @Test
  void theOidcTenantsReadsArePacted() throws IOException {
    Properties shipped = new Properties();
    try (InputStream in = Files.newInputStream(moduleRoot().resolve("src/main/resources/application.properties"))) {
      shipped.load(in);
    }
    String server = shipped.getProperty("quarkus.oidc.auth-server-url", "");
    assertTrue(server.contains("qits-idp"), "the tenant reads qits-idp: " + server);
    Set<String> framework =
        Contracts.IDP.checks().stream()
            .filter(check -> check.client() == null)
            .map(check -> check.row().operationId())
            .collect(Collectors.toSet());
    boolean discovery =
        Boolean.parseBoolean(shipped.getProperty("quarkus.oidc.discovery-enabled", "true"));
    assertEquals(
        discovery ? Set.of("getOpenIdConfiguration", "getJwks") : Set.of("getJwks"),
        framework,
        "the oidc tenant's reads of " + server);
  }

  /** Every main class of every module that imports an HTTP client, loaded by the name its path gives. */
  static List<Class<?>> clientClasses() throws IOException {
    List<Class<?>> found = new ArrayList<>();
    try (Stream<Path> modules = Files.list(moduleRoot().getParent())) {
      for (Path module : modules.toList()) {
        Path root = module.resolve("src/main/java");
        if (!Files.isDirectory(root)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(root)) {
          files
              .filter(f -> f.toString().endsWith(".java"))
              .filter(ClientCallGuardTest::importsAClient)
              .map(f -> load(root.relativize(f)))
              .forEach(found::add);
        }
      }
    }
    return found;
  }

  /** The {@code service} module, which surefire starts this test in. */
  private static Path moduleRoot() {
    Path here = Path.of("").toAbsolutePath();
    assertTrue(Files.isDirectory(here.resolve("src/main/java")), "not a module root: " + here);
    return here;
  }

  private static boolean importsAClient(Path file) {
    try {
      String text = Files.readString(file);
      return CLIENT_IMPORTS.stream().anyMatch(text::contains);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Class<?> load(Path relative) {
    String name = relative.toString().replace(".java", "").replace('/', '.');
    try {
      return Class.forName(name, false, ClientCallGuardTest.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(name + " imports an HTTP client and is not on this classpath", e);
    }
  }
}
