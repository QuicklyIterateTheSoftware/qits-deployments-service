package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.confighost.ConfigHostDeclarationSeed;
import eu.wohlben.qits.deployments.confighost.ConfigHostExtrasSource;
import eu.wohlben.qits.deployments.confighost.ExtrasBearer;
import eu.wohlben.qits.deployments.confighost.ExtrasStub;
import eu.wohlben.qits.deployments.confighost.IdpExtrasBearer;
import eu.wohlben.qits.deployments.deployments.control.IdpClientProvisioner;
import eu.wohlben.qits.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.deployments.githost.GitHostSources;
import eu.wohlben.qits.deployments.githost.GitHostSpecSource;
import eu.wohlben.qits.deployments.idphost.HttpIdpClientProvisioner;
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
 *   <li>{@link #IDP} — {@code HttpIdpClientProvisioner}'s four service-client calls, the two
 *       reads quarkus-oidc makes at startup (the discovery document and the JWKS), and the
 *       client-credentials mint quarkus-oidc-client makes for {@code IdpExtrasBearer};
 *   <li>{@link #GITHOST} — {@code GitHostSpecSource}'s blob reads of {@code deployments.yml} and
 *       {@code configuration.yml}: name-addressed when the release carries the name pair,
 *       id-addressed when it does not, with no fallback between them (qits-883).
 * </ul>
 *
 * <p>The bus library qits-eventstream owns its catch-up and publish pact, so no events row is here.
 */
public final class Contracts {

  private Contracts() {}

  /** The consumer, as every pact names it: this repository. */
  public static final String CONSUMER = "qits-deployments-service";

  /**
   * One row and the call that proves the client asks and understands it.
   *
   * @param client the class under {@code service/src/main/java} that makes the call, or null for a
   *     call the framework makes (quarkus-oidc's tenant reads); {@code ClientCallGuardTest} holds
   *     every public method of every client class to a row
   * @param method the public method of {@code client} that makes it
   * @param alongside rows the same call goes on to make against the same mock server, for a call
   *     that makes more than one request for one answer
   * @param call null for a row whose client exists only inside the application — the token mint,
   *     whose test is {@code IdpTokenMintPactTest}, a {@code @QuarkusTest} of its own
   */
  public record Check(
      GoldenInteraction row,
      Class<?> client,
      String method,
      List<GoldenInteraction> alongside,
      ConsumerPact.Call call) {

    static Check of(Class<?> client, String method, GoldenInteraction row, ConsumerPact.Call call) {
      return new Check(row, client, method, List.of(), call);
    }

    static Check framework(GoldenInteraction row, ConsumerPact.Call call) {
      return new Check(row, null, "quarkus-oidc tenant", List.of(), call);
    }

    Check then(GoldenInteraction next) {
      List<GoldenInteraction> all = new java.util.ArrayList<>(alongside);
      all.add(next);
      return new Check(row, client, method, all, call);
    }

    /** Every row this check's call makes, in the order it makes them. */
    public List<GoldenInteraction> rows() {
      List<GoldenInteraction> all = new java.util.ArrayList<>(List.of(row));
      all.addAll(alongside);
      return all;
    }
  }

  /** One provider's pact and the call for each of its rows. */
  public record Contract(ConsumerPact pact, List<Check> checks) {

    static Contract of(GoldenMasters masters, Check... checks) {
      List<Check> all = List.of(checks);
      List<GoldenInteraction> rows = all.stream().map(Check::row).toList();
      for (Check check : all) {
        if (!rows.containsAll(check.alongside())) {
          throw new IllegalArgumentException(
              check.row().description() + " goes on to a row no check of its own holds");
        }
      }
      return new Contract(ConsumerPact.of(CONSUMER, masters, rows), all);
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
          Check.of(
              ConfigHostExtrasSource.class,
              "forDeployment",
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
          Check.of(
              ConfigHostExtrasSource.class,
              "forDeployment",
              GoldenInteraction.of(
                      Trigger.event("SoftwareRelease"),
                      "an application with stored entries and no declaration",
                      "resolveConfiguration")
                  .consumes("properties", "headRevision"),
              (url, recorded) -> assertNotNull(resolved(url, recorded.params(), false))),
          // The seed reads the status alone: 2xx is stored, 409/422 is the file's own fault.
          Check.of(
              ConfigHostDeclarationSeed.class,
              "seed",
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

  static final String A_PEER_CALLER = "a service client calling its peers";

  /** The {@code qits} oidc client's token request: Basic client auth, the platform audience. */
  static final GoldenInteraction TOKEN_MINT =
      GoldenInteraction.of(Trigger.event("SoftwareRelease"), A_PEER_CALLER, "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

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
          Check.of(
              HttpIdpClientProvisioner.class,
              "databaseClientPresent",
              serviceClients(A_DATABASE_SERVICE_CLIENT, "getServiceClient").consumes("source"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).databaseClientPresent(recorded.params().get("clientId")))),
          Check.of(
              HttpIdpClientProvisioner.class,
              "databaseClientPresent",
              serviceClients(NO_SERVICE_CLIENT, "getServiceClient"),
              (url, recorded) ->
                  assertFalse(idp(url, recorded.params()).databaseClientPresent(recorded.params().get("clientId")))),
          Check.of(
              HttpIdpClientProvisioner.class,
              "create",
              serviceClients(NO_SERVICE_CLIENT, "createServiceClient").consumes("secret"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).create(recorded.params().get("clientId")).ok())),
          Check.of(
              HttpIdpClientProvisioner.class,
              "create",
              serviceClients(A_DATABASE_SERVICE_CLIENT, "createServiceClient"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).create(recorded.params().get("clientId")).conflict())),
          Check.of(
              HttpIdpClientProvisioner.class,
              "rotate",
              serviceClients(A_DATABASE_SERVICE_CLIENT, "rotateServiceClientSecret").consumes("secret"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).rotate(recorded.params().get("clientId")).ok())),
          Check.of(
              HttpIdpClientProvisioner.class,
              "rotate",
              serviceClients(NO_SERVICE_CLIENT, "rotateServiceClientSecret"),
              (url, recorded) ->
                  assertFalse(idp(url, recorded.params()).rotate(recorded.params().get("clientId")).ok())),
          // The retirement's delete reads the status alone: 2xx (and 404) is done.
          Check.of(
              HttpIdpClientProvisioner.class,
              "delete",
              serviceClients(A_DATABASE_SERVICE_CLIENT, "deleteServiceClient"),
              (url, recorded) ->
                  assertTrue(idp(url, recorded.params()).delete(recorded.params().get("clientId")).ok())),
          // quarkus-oidc reads the discovery document at startup, as quarkus-oidc parses it.
          Check.framework(
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
          Check.framework(
              GoldenInteraction.of(Trigger.event("StartupEvent"), A_PUBLISHED_SIGNING_KEY, "getJwks")
                  .consumes(
                      "keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use"),
              (url, recorded) -> {
                JsonWebKeySet keys = new JsonWebKeySet(get(url + recorded.path()));
                assertNotNull(keys.getKeyWithId(recorded.params().get("kid")), "the state's signing key");
              }),
          // quarkus-oidc-client's client-credentials grant, the bearer ConfigHostExtrasSource and
          // ConfigHostDeclarationSeed present to qits-configuration. Its client exists only inside
          // the application, so IdpTokenMintPactTest runs it.
          new Check(TOKEN_MINT, IdpExtrasBearer.class, "token", List.of(), null));

  // --- qits-githost ----------------------------------------------------------------------------

  static final GoldenMasters GITHOST_MASTERS = GoldenMasters.of("qits-githost-service", "qits-githost");

  static final String DECLARING_DEPLOYMENTS = "a repository declaring its deployments";
  static final String DECLARING_NO_DEPLOYMENTS = "a repository declaring no deployments";
  static final String UNNAMED_DECLARING_DEPLOYMENTS = "an unnamed repository declaring its deployments";
  static final String DECLARING_CONFIGURATION = "a repository declaring its configuration";
  static final String DECLARING_NO_CONFIGURATION = "a repository declaring no configuration";

  /**
   * One blob read. A release reads both files at the released tag; the recordings read them at
   * {@code main}, and the rev is a provider-state value, so the pact sends whichever the state names.
   * No body is recorded for a blob (it is the file's raw bytes), so a 200 row reads the status and,
   * for the spec, the {@code Git-Commit-Sha} the deployment records.
   */
  private static GoldenInteraction blob(String state, String operationId) {
    return GoldenInteraction.of(Trigger.event("SoftwareRelease"), state, operationId);
  }

  private static final GoldenInteraction SPEC_BY_NAME =
      blob(DECLARING_DEPLOYMENTS, "getBlob").readsHeader("Git-Commit-Sha");
  private static final GoldenInteraction SPEC_BY_ID =
      blob(DECLARING_DEPLOYMENTS, "getBlobById").readsHeader("Git-Commit-Sha");
  private static final GoldenInteraction NO_SPEC_BY_NAME = blob(DECLARING_NO_DEPLOYMENTS, "getBlob");
  private static final GoldenInteraction NO_SPEC_BY_ID = blob(DECLARING_NO_DEPLOYMENTS, "getBlobById");
  private static final GoldenInteraction UNNAMED_SPEC_BY_ID =
      blob(UNNAMED_DECLARING_DEPLOYMENTS, "getBlobById").readsHeader("Git-Commit-Sha");

  private static RepositoryRef byName(Map<String, String> params) {
    return new RepositoryRef(params.get("repositoryId"), params.get("projectId"), params.get("repoName"));
  }

  private static RepositoryRef byId(Map<String, String> params) {
    return RepositoryRef.ofId(params.get("repositoryId"));
  }

  private static void declared(SpecSource.SpecRead read, GoldenMasters.Operation recorded) {
    assertTrue(read.declared(), "the repository carries the spec");
    assertEquals(recorded.responseHeader("Git-Commit-Sha"), read.commitSha(), "the commit the rev resolved to");
  }

  public static final Contract GITHOST =
      Contract.of(
          GITHOST_MASTERS,
          // A release with the public address reads the spec name-addressed...
          Check.of(
              GitHostSpecSource.class,
              "read",
              SPEC_BY_NAME,
              (url, recorded) ->
                  declared(GitHostSources.at(url).read(byName(recorded.params()), recorded.params().get("rev")), recorded)),
          // ...and one without it (an older publisher, the adoption sweep) id-addressed.
          Check.of(
              GitHostSpecSource.class,
              "read",
              SPEC_BY_ID,
              (url, recorded) ->
                  declared(GitHostSources.at(url).read(byId(recorded.params()), recorded.params().get("rev")), recorded)),
          // A name-addressed 404 is believed at once, with no id request (qits-883).
          Check.of(
              GitHostSpecSource.class,
              "read",
              NO_SPEC_BY_NAME,
              (url, recorded) ->
                  assertFalse(
                      GitHostSources.at(url).read(byName(recorded.params()), recorded.params().get("rev")).declared(),
                      "a name-addressed 404 is no spec at once")),
          Check.of(
              GitHostSpecSource.class,
              "read",
              NO_SPEC_BY_ID,
              (url, recorded) ->
                  assertFalse(
                      GitHostSources.at(url).read(byId(recorded.params()), recorded.params().get("rev")).declared(),
                      "an id-addressed 404 is no spec at once")),
          Check.of(
              GitHostSpecSource.class,
              "read",
              UNNAMED_SPEC_BY_ID,
              (url, recorded) ->
                  declared(GitHostSources.at(url).read(byId(recorded.params()), recorded.params().get("rev")), recorded)),
          // The declaration beside it: verbatim on 200, absent on 404, and no fallback either way.
          Check.of(
              GitHostSpecSource.class,
              "readDeclaration",
              blob(DECLARING_CONFIGURATION, "getBlob"),
              (url, recorded) ->
                  assertTrue(
                      GitHostSources.at(url).readDeclaration(byName(recorded.params()), recorded.params().get("rev")).present())),
          Check.of(
              GitHostSpecSource.class,
              "readDeclaration",
              blob(DECLARING_CONFIGURATION, "getBlobById"),
              (url, recorded) ->
                  assertTrue(
                      GitHostSources.at(url).readDeclaration(byId(recorded.params()), recorded.params().get("rev")).present())),
          Check.of(
              GitHostSpecSource.class,
              "readDeclaration",
              blob(DECLARING_NO_CONFIGURATION, "getBlob"),
              (url, recorded) ->
                  assertFalse(
                      GitHostSources.at(url).readDeclaration(byName(recorded.params()), recorded.params().get("rev")).present(),
                      "a name-addressed 404 is absent at once")),
          Check.of(
              GitHostSpecSource.class,
              "readDeclaration",
              blob(DECLARING_NO_CONFIGURATION, "getBlobById"),
              (url, recorded) ->
                  assertFalse(
                      GitHostSources.at(url).readDeclaration(byId(recorded.params()), recorded.params().get("rev")).present())));

  /** Every contract this repository holds. */
  public static List<Contract> all() {
    return List.of(CONFIGURATION, IDP, GITHOST);
  }
}
