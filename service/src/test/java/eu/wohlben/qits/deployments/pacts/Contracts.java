package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.confighost.ConfigHostDeclarationSeed;
import eu.wohlben.qits.deployments.confighost.ExtrasBearer;
import eu.wohlben.qits.deployments.confighost.ExtrasStub;
import eu.wohlben.qits.deployments.deployments.control.IdpClientProvisioner;
import eu.wohlben.qits.deployments.idphost.IdpStub;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.quarkus.oidc.OidcConfigurationMetadata;
import io.quarkus.oidc.runtime.JsonWebKeySet;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * <b>Every JSON REST call qits-deployments-service makes to another qits service</b> (ticket
 * qits-1149), as {@code qits-pact-consumer} rows: one {@link ConsumerPact} per provider, and for
 * each row the call this repository's real client makes against the pact mock server.
 *
 * <ul>
 *   <li>{@link #CONFIGURATION} — {@code ConfigHostExtrasSource}'s resolved read and {@code
 *       ConfigHostDeclarationSeed}'s declaration POST;
 *   <li>{@link #IDP} — {@code HttpIdpClientProvisioner}'s three service-client calls, and the two
 *       reads quarkus-oidc makes at startup: the discovery document and the JWKS.
 * </ul>
 *
 * <p>The bus library qits-eventstream owns its catch-up and publish pact, so no events row is here.
 */
public final class Contracts {

  private Contracts() {}

  /** The consumer, as every pact names it: this repository. */
  public static final String CONSUMER = "qits-deployments-service";

  /** One row and the call that proves the client asks and understands it. */
  public record Check(GoldenInteraction row, ConsumerPact.Call call) {}

  /** One provider's pact and the call for each of its rows. */
  public record Contract(ConsumerPact pact, List<Check> checks) {

    static Contract of(GoldenMasters masters, Check... checks) {
      List<Check> all = List.of(checks);
      return new Contract(
          ConsumerPact.of(CONSUMER, masters, all.stream().map(Check::row).toList()), all);
    }
  }

  // --- qits-configuration ----------------------------------------------------------------------

  static final GoldenMasters CONFIGURATION_MASTERS =
      GoldenMasters.of("qits-configuration-service", "qits-configuration");

  private static final ExtrasBearer NO_BEARER = Optional::empty;

  private static Config boot() {
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(Map.of(), "boot", 260))
        .build();
  }

  private static Config resolved(String url, Map<String, String> params, boolean declared) {
    return ExtrasStub.source(boot(), "config/application.properties", NO_BEARER, url)
        .forDeployment(params.get("application"), params.get("env"), params.getOrDefault("version", "2026.101.1"), declared);
  }

  public static final Contract CONFIGURATION =
      Contract.of(
          CONFIGURATION_MASTERS,
          // A release that seeded a declaration reads the overrides resolved against it.
          new Check(
              GoldenInteraction.of(
                      Trigger.event("SoftwareRelease"),
                      "a declared application with entries",
                      "resolveConfiguration")
                  .consumes("properties", "headRevision"),
              (url, recorded) -> {
                Config served = resolved(url, recorded.params(), true);
                assertEquals(
                    "hello from the store",
                    served.getValue(
                        "qits.platform.deployments.extras.golden-declared-app.env.QITS_GREETING",
                        String.class));
              }),
          // A release that declared nothing reads the stored entries alone.
          new Check(
              GoldenInteraction.of(
                      Trigger.event("SoftwareRelease"),
                      "an application with stored entries and no declaration",
                      "resolveConfiguration")
                  .consumes("properties", "headRevision"),
              (url, recorded) -> assertNotNull(resolved(url, recorded.params(), false))),
          // The seed reads the status alone: 2xx is stored, 409/422 is the file's own fault.
          new Check(
              GoldenInteraction.of(
                  Trigger.event("SoftwareRelease"), "an application with no declaration", "declareKeys"),
              (url, recorded) -> {
                ConfigHostDeclarationSeed seed = ExtrasStub.seed(NO_BEARER, url);
                seed.seed(
                    recorded.params().get("application"),
                    recorded.params().get("version"),
                    recorded.body().asText());
              }));

  // --- qits-idp --------------------------------------------------------------------------------

  static final GoldenMasters IDP_MASTERS = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String A_DATABASE_SERVICE_CLIENT = "a database service client";
  static final String NO_SERVICE_CLIENT = "no service client with the given id";
  static final String A_PUBLISHED_SIGNING_KEY = "a published signing key";

  /** The discovery read, whose {@code issuer} the pact binds by value: see {@link PactFileTest}. */
  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(
              Trigger.event("StartupEvent"), A_PUBLISHED_SIGNING_KEY, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint");

  /** The adapter, presenting the caller the state's {@code authorization} param names. */
  private static IdpClientProvisioner idp(String url, Map<String, String> params) {
    String basic = params.get("authorization").substring("Basic ".length());
    String[] pair = new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":", 2);
    return IdpStub.adapterAt(url + "/idp/api/service-clients", pair[0], pair[1]);
  }

  private static GoldenInteraction serviceClients(String state, String operationId) {
    return GoldenInteraction.of(Trigger.event("SoftwareRelease"), state, operationId)
        .header("Authorization", "{authorization}");
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> answer =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, answer.statusCode(), url);
    return answer.body();
  }

  public static final Contract IDP =
      Contract.of(
          IDP_MASTERS,
          new Check(
              serviceClients(A_DATABASE_SERVICE_CLIENT, "getServiceClient").consumes("source"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).databaseClientPresent(recorded.params().get("clientId")))),
          new Check(
              serviceClients(NO_SERVICE_CLIENT, "getServiceClient"),
              (url, recorded) ->
                  assertFalse(idp(url, recorded.params()).databaseClientPresent(recorded.params().get("clientId")))),
          new Check(
              serviceClients(NO_SERVICE_CLIENT, "createServiceClient").consumes("secret"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).create(recorded.params().get("clientId")).ok())),
          new Check(
              serviceClients(A_DATABASE_SERVICE_CLIENT, "createServiceClient"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).create(recorded.params().get("clientId")).conflict())),
          new Check(
              serviceClients(A_DATABASE_SERVICE_CLIENT, "rotateServiceClientSecret").consumes("secret"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).rotate(recorded.params().get("clientId")).ok())),
          new Check(
              serviceClients(NO_SERVICE_CLIENT, "rotateServiceClientSecret"),
              (url, recorded) ->
                  assertFalse(idp(url, recorded.params()).rotate(recorded.params().get("clientId")).ok())),
          // quarkus-oidc reads the discovery document at startup, as quarkus-oidc parses it.
          new Check(
              DISCOVERY,
              (url, recorded) -> {
                OidcConfigurationMetadata metadata =
                    new OidcConfigurationMetadata(new JsonObject(get(url + recorded.path())));
                assertEquals(
                    IDP_MASTERS.json(A_PUBLISHED_SIGNING_KEY, "getOpenIdConfiguration").path("issuer").asText(),
                    metadata.getIssuer());
                assertNotNull(metadata.getJsonWebKeySetUri());
                assertNotNull(metadata.getTokenUri());
              }),
          // ...then the keys at the jwks_uri it names, and finds the signing key by kid.
          new Check(
              GoldenInteraction.of(Trigger.event("StartupEvent"), A_PUBLISHED_SIGNING_KEY, "getJwks")
                  .consumes(
                      "keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use"),
              (url, recorded) -> {
                JsonWebKeySet keys = new JsonWebKeySet(get(url + recorded.path()));
                assertNotNull(keys.getKeyWithId(recorded.params().get("kid")), "the state's signing key");
              }));

  /** Every contract this repository holds. */
  public static List<Contract> all() {
    return List.of(CONFIGURATION, IDP);
  }
}
