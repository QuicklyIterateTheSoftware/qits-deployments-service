package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.EgressDeclarationParser.Route;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every rule of {@code .config/qits/egress.yml} (epic qits-2523), one refusal at a time. */
class EgressDeclarationParserTest {

  private static final String SOURCE = ".config/qits/egress.yml of qits-edge@2026.1201.101010";

  private static List<Route> parse(String yaml) {
    return EgressDeclarationParser.parse(yaml, SOURCE);
  }

  private static String messageOf(String yaml) {
    SpecException thrown = assertThrows(SpecException.class, () -> parse(yaml));
    assertTrue(thrown.getMessage().startsWith(SOURCE), thrown.getMessage());
    assertTrue(!thrown.retryable(), "a file that was read is never read again for the same answer");
    return thrown.getMessage();
  }

  /** A file of one route with these lines under its {@code - name: r} line. */
  private static String route(String... lines) {
    StringBuilder yaml = new StringBuilder("version: 1\nroutes:\n  - name: r\n");
    for (String line : lines) {
      yaml.append("    ").append(line).append('\n');
    }
    return yaml.toString();
  }

  private static final String HTTPS = "target: https://api.hetzner.cloud";

  // --- the accepted shapes ---------------------------------------------------------------------

  @Test
  void theDossiersExampleParsesWithItsMethodsSorted() {
    List<Route> routes =
        parse(
            """
            # Routes this application sends through qits-egress (qits-2523).
            version: 1
            routes:
              - name: hetzner                       # [a-z][a-z0-9-]{0,62}
                target: https://api.hetzner.cloud   # https://<host>[:port][/base path]
                secret: HETZNER_DNS                 # [A-Z][A-Z0-9_]{0,63}
                inject: bearer                      # bearer | header:<Header-Name>
                methods: [GET, POST, PUT, DELETE]   # HTTP methods
            """);
    assertEquals(
        List.of(
            new Route(
                "hetzner",
                "https://api.hetzner.cloud",
                "HETZNER_DNS",
                "bearer",
                List.of("DELETE", "GET", "POST", "PUT"))),
        routes);
  }

  @Test
  void aPlatformRouteTakesNoSecretAndNoInjection() {
    Route route = parse(route("target: platform:projects", "methods: [GET]")).get(0);
    assertEquals("platform:projects", route.target());
    assertNull(route.secret());
    assertNull(route.inject());
  }

  @Test
  void methodsMayBeABlockListAndConnectIsAMethod() {
    Route route =
        parse(route(HTTPS, "secret: S", "inject: header:X-Api-Key", "methods:", "  - CONNECT", "  - GET"))
            .get(0);
    assertEquals(List.of("CONNECT", "GET"), route.methods());
    assertEquals("header:X-Api-Key", route.inject());
  }

  @Test
  void aTargetMayCarryAPortAndABasePath() {
    Route route =
        parse(route("target: https://dns.example:8443/v1", "secret: S", "inject: bearer", "methods: [GET]"))
            .get(0);
    assertEquals("https://dns.example:8443/v1", route.target());
  }

  @Test
  void severalRoutesKeepTheFilesOrderAndAnEmptyListIsNoRoutes() {
    List<Route> routes =
        parse(
            "version: 1\nroutes:\n"
                + "  - name: b\n    target: platform:projects\n    methods: [GET]\n"
                + "  - name: a\n    target: platform:ci\n    methods: [POST]\n");
    assertEquals(List.of("b", "a"), routes.stream().map(Route::name).toList());
    assertEquals(List.of(), parse("version: 1\nroutes: []\n"));
    assertEquals(List.of(), parse("version: 1\n"));
  }

  // --- version and keys ------------------------------------------------------------------------

  @Test
  void versionIsRequiredAndMustBeOne() {
    assertTrue(messageOf("routes: []\n").contains("`version: 1` is required"));
    assertTrue(messageOf("").contains("`version: 1` is required"));
    assertTrue(messageOf("version: 2\nroutes: []\n").contains("`version` must be 1"));
  }

  @Test
  void anUnknownKeyIsRefusedAtEveryLevel() {
    assertTrue(messageOf("version: 1\ngrants: []\n").contains("unknown key `grants`"));
    String message = messageOf(route(HTTPS, "secret: S", "inject: bearer", "methods: [GET]", "timeout: 5s"));
    assertTrue(message.contains("unknown key `timeout`"), message);
    assertTrue(message.contains("name, target, secret, inject, methods"), message);
  }

  @Test
  void aKeyTwiceIsRefused() {
    assertTrue(messageOf("version: 1\nversion: 1\n").contains("duplicate key `version`"));
    assertTrue(
        messageOf(route("target: platform:a", "target: platform:b", "methods: [GET]"))
            .contains("duplicate key `target`"));
  }

  // --- name ------------------------------------------------------------------------------------

  @Test
  void aNameIsRequiredAndFollowsItsPattern() {
    assertTrue(
        messageOf("version: 1\nroutes:\n  - target: platform:a\n    methods: [GET]\n")
            .contains("has no `name`"));
    for (String bad : List.of("Hetzner", "1dns", "dns_api", "-dns", "a".repeat(64))) {
      String message =
          messageOf("version: 1\nroutes:\n  - name: " + bad + "\n    target: platform:a\n    methods: [GET]\n");
      assertTrue(message.contains("[a-z][a-z0-9-]{0,62}"), message);
    }
  }

  @Test
  void aNameIsUniqueWithinTheFile() {
    String message =
        messageOf(
            "version: 1\nroutes:\n"
                + "  - name: dns\n    target: platform:a\n    methods: [GET]\n"
                + "  - name: dns\n    target: platform:b\n    methods: [GET]\n");
    assertTrue(message.contains("route `dns`"), message);
    assertTrue(message.contains("earlier route"), message);
  }

  // --- target ----------------------------------------------------------------------------------

  @Test
  void aTargetIsRequired() {
    assertTrue(messageOf(route("methods: [GET]")).contains("has no `target`"));
  }

  @Test
  void plainHttpIsRefused() {
    String message =
        messageOf(route("target: http://api.hetzner.cloud", "secret: S", "inject: bearer", "methods: [GET]"));
    assertTrue(message.contains("plain `http://` is refused"), message);
  }

  @Test
  void aTargetIsHttpsOrPlatformAndNothingElse() {
    for (String bad :
        List.of(
            "ftp://x.example",
            "api.hetzner.cloud",
            "https://",
            "https://user@x.example",
            "https://x.example/?q=1",
            "https://x.example/#frag",
            "https://x.example:99999")) {
      String message =
          messageOf(route("target: " + bad, "secret: S", "inject: bearer", "methods: [GET]"));
      assertTrue(message.contains("route `r`"), message);
    }
  }

  @Test
  void aPlatformTargetNamesOneHostLabel() {
    for (String bad : List.of("platform:", "platform:Projects", "platform:a.b", "platform:-x")) {
      String message = messageOf(route("target: " + bad, "methods: [GET]"));
      assertTrue(message.contains("one DNS label"), message);
    }
  }

  // --- secret and inject -----------------------------------------------------------------------

  @Test
  void aPlatformTargetRefusesASecretOrAnInjection() {
    assertTrue(
        messageOf(route("target: platform:projects", "secret: S", "methods: [GET]"))
            .contains("takes no `secret` and no `inject`"));
    assertTrue(
        messageOf(route("target: platform:projects", "inject: bearer", "methods: [GET]"))
            .contains("takes no `secret` and no `inject`"));
  }

  @Test
  void anHttpsTargetNeedsBothASecretAndAnInjection() {
    assertTrue(
        messageOf(route(HTTPS, "secret: S", "methods: [GET]")).contains("needs both `secret` and `inject`"));
    assertTrue(
        messageOf(route(HTTPS, "inject: bearer", "methods: [GET]"))
            .contains("needs both `secret` and `inject`"));
  }

  @Test
  void aSecretFollowsItsPattern() {
    for (String bad : List.of("hetzner_dns", "1DNS", "_DNS", "DNS-KEY", "A".repeat(65))) {
      String message = messageOf(route(HTTPS, "secret: " + bad, "inject: bearer", "methods: [GET]"));
      assertTrue(message.contains("[A-Z][A-Z0-9_]{0,63}"), message);
    }
  }

  @Test
  void anInjectionIsBearerOrANamedHeader() {
    for (String bad : List.of("basic", "header:", "header:X Api", "header:1x", "Bearer")) {
      String message = messageOf(route(HTTPS, "secret: S", "inject: " + bad, "methods: [GET]"));
      assertTrue(message.contains("`bearer` or `header:<Header-Name>`"), message);
    }
  }

  // --- methods ---------------------------------------------------------------------------------

  @Test
  void methodsAreRequiredAndNotEmpty() {
    assertTrue(messageOf(route("target: platform:a")).contains("grants no `methods`"));
    assertTrue(messageOf(route("target: platform:a", "methods: []")).contains("grants no `methods`"));
    assertTrue(messageOf(route("target: platform:a", "methods:")).contains("grants no `methods`"));
  }

  @Test
  void aMethodIsAnUpperCaseHttpMethodListedOnce() {
    assertTrue(
        messageOf(route("target: platform:a", "methods: [get]")).contains("`get` is not a method"));
    assertTrue(
        messageOf(route("target: platform:a", "methods: [TRACE]")).contains("`TRACE` is not a method"));
    assertTrue(
        messageOf(route("target: platform:a", "methods: [GET, GET]")).contains("listed twice"));
    assertTrue(
        messageOf(route("target: platform:a", "methods: GET")).contains("`methods` is a list"));
  }

  // --- shape -----------------------------------------------------------------------------------

  @Test
  void theShapeIsTheOneShape() {
    assertTrue(messageOf("version: 1\nroutes: hetzner\n").contains("`routes` is a list"));
    assertTrue(messageOf("version: 1\n  - name: x\n").contains("belongs under `routes:`"));
    assertTrue(
        messageOf("version: 1\nroutes:\n\t- name: x\n").contains("a tab in the indentation"));
    assertTrue(
        messageOf("version: 1\nroutes:\n  - name: a\n    target: platform:a\n    methods: [GET]\n"
                + "    - name: b\n")
            .contains("must line up"));
    assertTrue(
        messageOf("version: 1\nroutes:\n  - name: a\n      target: platform:a\n")
            .contains("unexpected indentation"));
    assertTrue(messageOf("version: 1\nroutes:\n  -\n").contains("starts with its first key"));
    assertTrue(messageOf("version: &v 1\n").contains("anchors"));
    assertTrue(
        messageOf("version: 1\nroutes: []\n  - name: a\n").contains("belongs under `routes:`"));
  }

  @Test
  void aRefusalNamesTheLine() {
    String message = messageOf("version: 1\nroutes:\n  - name: a\n    colour: blue\n");
    assertTrue(message.contains("line 4"), message);
  }
}
