package eu.wohlben.qits.deployments.contracts;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.control.ArtifactPurge;
import eu.wohlben.qits.deployments.deployments.control.DeployService;
import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeArtifactPurge;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>qits-deployments' provider states</b> (ticket qits-1149): each seeds what one consumer
 * situation needs, through this service's own API as its tests do, and hands back the ids as
 * parameters.
 *
 * <p>Two callers: {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards.
 *
 * <p>Every name carries a random token, because the suite shares one database and an environment
 * name is unique; the recorder freezes the token. A designated environment cannot be torn down, so
 * the deployed state leaves its environment behind — every list it is read from is filtered to the
 * state's own entries.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String AN_ENVIRONMENT = "an environment";
  public static final String AN_APPLICATION_DEPLOYED = "an application deployed in an environment";
  public static final String THE_PLATFORM_ENVIRONMENT = "the platform environment exists";
  public static final String NO_ENVIRONMENT_OF_THE_NAME = "no environment of the name";
  public static final String A_RELEASED_VERSION = "a released version of a deployable repository";
  public static final String AN_APPLICATION_WITH_A_ROLLBACK =
      "an application serving with a rollback version";
  public static final String AN_APPLICATION_HOLDING_A_SERVICE_CLIENT =
      "an application holding a service client";

  /** Named by qits-platform-access-monorepo's {@code DeploymentsContract} (qits-1924). */
  public static final String AN_APPLICATION_WITH_SETTINGS =
      "an application with settings in an environment";

  /** Named by qits-platform-access-monorepo's {@code DeploymentsContract} (qits-1924). */
  public static final String AN_APPLICATION_WITH_CHANGED_SETTINGS =
      "an application whose settings changed since its deployment";

  public static final String A_DEPLOYMENT_REQUEST = "a deployment request that deployed";
  public static final String AN_APPLICATION_RUNNING = "an application running in an environment";
  public static final String AN_APPLICATION_WITH_A_FAILED_DEPLOYMENT =
      "an application whose only deployment failed";
  public static final String A_DECOMMISSIONED_APPLICATION =
      "an application decommissioned with its artifacts purged";
  public static final String A_SERVICE_LINKED_INTO_AN_ENVIRONMENT =
      "a service linked into an environment";
  public static final String AN_ENVIRONMENT_TO_TEAR_DOWN = "an environment to tear down";

  /**
   * One application deployed ACTIVE whose spec declares a postgres resource {@code db}, its claim on
   * the public server, and both postgres servers running in its tier — what an operator's database
   * move (qits-2175) is asked of. Named by qits-platform-access-monorepo's {@code qits deployments
   * database move}.
   */
  public static final String AN_APPLICATION_HOLDING_A_DATABASE =
      "an application holding a database";

  /**
   * One database move that finished: moved from the public server to the private one, both sides'
   * counts equal, the source kept until its retention runs out. Named by the CLI's {@code moves} and
   * {@code move-status}.
   */
  public static final String A_FINISHED_DATABASE_MOVE = "a database move that finished";

  /**
   * One database move EXTRACTING — the step an operator's abort (qits-2243) kills. Named by the
   * CLI's {@code qits deployments database abort}. Clean-up settles the row, so no later restart
   * settle in the suite finds an unfinished move it never started.
   */
  public static final String A_RUNNING_DATABASE_MOVE = "a database move that is running";

  /**
   * The machine gate is on: a bearer no idp issued answers 401. Only {@code
   * MachineGuardEnforcedProfile} runs the service gated, so only {@link
   * GatedGoldenMasterRecordingTest} and {@link GatedConsumerPactVerificationTest} use this state. It
   * seeds nothing.
   */
  public static final String THE_MACHINE_GATE_IS_ON = "the machine gate is on";

  /** States only a gated application can answer for. */
  public static final Set<String> GATED = Set.of(THE_MACHINE_GATE_IS_ON);

  /** A {@code @PactFilter} regex matching every state except the gated ones. */
  public static final String UNGATED_STATES = "^(?!" + THE_MACHINE_GATE_IS_ON + "$).*";

  /** The released version the deployed states ship. */
  static final String VERSION = "2026.101.120000";

  /** The plain setting the settings state seeds; its {@code key} param. */
  static final String PLAIN_SETTING = "LOG_LEVEL";

  /** What the orchestrator double answers the failed-deployment states' release with. */
  static final String CONVERGENCE_FAILURE = "task exited with code 1 before it went healthy";

  /** The version the rollback state ships second, so {@link #VERSION} is its rollback target. */
  static final String NEXT_VERSION = "2026.102.120000";

  /** The image both postgres servers run on the orchestrator double, for the move's containers. */
  static final String POSTGRES_IMAGE = "registry.qits.dev.localhost/qits/qits-oci-postgresql:2026.101.120000";

  /**
   * What a state hands back: its parameters, keys sorted, and its unique tokens.
   *
   * @param params the state's parameters, keys sorted — what a pact {@code @State} method returns.
   *     A UUID's frozen number in a param — the whole value or embedded in it — is sticky to the
   *     committed {@code golden-masters/index.json} ({@link Freezer#seed(Map, Map)}): adding or
   *     removing a param never renumbers the others
   * @param uniqueTokens random tokens the state had to put into names to keep them unique
   */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeArtifactPurge artifacts;
  @Inject DeployService deployService;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<String> removable = Collections.synchronizedList(new ArrayList<>());

  /** Catalogue services a state registered by hand: clean-up deletes them. */
  private final List<String> removableServices = Collections.synchronizedList(new ArrayList<>());

  /** Database moves a state left unfinished: clean-up settles them, the worker never ran them. */
  private final List<String> runningMoves = Collections.synchronizedList(new ArrayList<>());

  /** Environments a release was announced into: clean-up waits for their deployments to finish. */
  private final List<String> settling = Collections.synchronizedList(new ArrayList<>());

  public ProviderStates() {
    states.put(AN_ENVIRONMENT, this::anEnvironment);
    states.put(AN_APPLICATION_DEPLOYED, this::anApplicationDeployed);
    states.put(THE_PLATFORM_ENVIRONMENT, this::thePlatformEnvironment);
    states.put(NO_ENVIRONMENT_OF_THE_NAME, this::noEnvironmentOfTheName);
    states.put(A_RELEASED_VERSION, this::aReleasedVersion);
    states.put(AN_APPLICATION_WITH_A_ROLLBACK, this::anApplicationWithARollback);
    states.put(AN_APPLICATION_HOLDING_A_SERVICE_CLIENT, this::anApplicationHoldingAServiceClient);
    states.put(THE_MACHINE_GATE_IS_ON, this::theMachineGateIsOn);
    states.put(AN_APPLICATION_WITH_SETTINGS, this::anApplicationWithSettings);
    states.put(AN_APPLICATION_WITH_CHANGED_SETTINGS, this::anApplicationWithChangedSettings);
    states.put(A_DEPLOYMENT_REQUEST, this::aDeploymentRequest);
    states.put(AN_APPLICATION_RUNNING, this::anApplicationRunning);
    states.put(AN_APPLICATION_WITH_A_FAILED_DEPLOYMENT, this::anApplicationWithAFailedDeployment);
    states.put(A_DECOMMISSIONED_APPLICATION, this::aDecommissionedApplication);
    states.put(A_SERVICE_LINKED_INTO_AN_ENVIRONMENT, this::aServiceLinkedIntoAnEnvironment);
    states.put(AN_ENVIRONMENT_TO_TEAR_DOWN, this::anEnvironmentToTearDown);
    states.put(AN_APPLICATION_HOLDING_A_DATABASE, this::anApplicationHoldingADatabase);
    states.put(A_FINISHED_DATABASE_MOVE, this::aFinishedDatabaseMove);
    states.put(A_RUNNING_DATABASE_MOVE, this::aRunningDatabaseMove);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /**
   * Waits for every announced release to finish deploying, so it cannot land in the next state's
   * environment, then tears down every environment a state created that can be torn down.
   */
  public void cleanUp() {
    List<String> announced;
    synchronized (settling) {
      announced = List.copyOf(settling);
      settling.clear();
    }
    announced.forEach(ProviderStates::drain);
    // A scale, a restart or a retirement an interaction queued runs on the deploy worker: let it
    // finish here, so it cannot act on the fakes while the next state seeds.
    awaitIdle();
    List<String> ids;
    synchronized (removable) {
      ids = List.copyOf(removable);
      removable.clear();
    }
    for (String id : ids) {
      given()
          .header("Authorization", SuiteBearers.platform())
          .when().delete("/deployments/api/environments/" + id);
    }
    List<String> services;
    synchronized (removableServices) {
      services = List.copyOf(removableServices);
      removableServices.clear();
    }
    for (String name : services) {
      given()
          .header("Authorization", SuiteBearers.platform())
          .when().delete("/deployments/api/services/" + name);
    }
    List<String> moves;
    synchronized (runningMoves) {
      moves = List.copyOf(runningMoves);
      runningMoves.clear();
    }
    for (String id : moves) {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                PdDatabaseMove row = PdDatabaseMove.findById(id);
                if (row != null) {
                  row.state = PdDatabaseMoveState.ABORTED;
                  row.finishedAt = Instant.now();
                }
              });
    }
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /** One environment, not designated, holding no application. */
  private Setup anEnvironment() {
    String token = token();
    String id = createEnvironment("contract-" + token, false);
    removable.add(id);
    Map<String, String> params = new TreeMap<>();
    params.put("environmentId", id);
    return new Setup(params, List.of(token));
  }

  /**
   * One release deployed and settled ACTIVE in a designated environment: a deployment row, its
   * deployment request, and the pin that keeps its image. The application name is also the
   * repository id the release named.
   */
  private Setup anApplicationDeployed() {
    driver.reset();
    String token = token();
    String environmentId = createEnvironment("contract-" + token, true);
    String application = "contract-app-" + token;
    release(application, VERSION);
    settle(environmentId, 1);
    Map<String, String> params = new TreeMap<>();
    params.put("applicationName", application);
    params.put("environmentId", environmentId);
    params.put("repoId", application);
    params.put("version", VERSION);
    return new Setup(params, List.of(token));
  }

  /**
   * One environment carrying the platform designation. It cannot be torn down, so it stays behind;
   * every list it is read from is filtered to it.
   */
  private Setup thePlatformEnvironment() {
    String token = token();
    String name = "contract-" + token;
    String id = createEnvironment(name, true);
    Map<String, String> params = new TreeMap<>();
    params.put("environmentId", id);
    params.put("environmentName", name);
    return new Setup(params, List.of(token));
  }

  /**
   * Nothing is created: the params name an environment and a network no row holds yet. The suite
   * shares one database, so other environments exist; none has this name.
   */
  private Setup noEnvironmentOfTheName() {
    String token = token();
    Map<String, String> params = new TreeMap<>();
    params.put("environmentName", "contract-" + token);
    params.put("network", "contract-" + token + "-net");
    return new Setup(params, List.of(token));
  }

  /**
   * A designated environment to deploy into, and the coordinates of a release nobody announced
   * yet: the repository's id, its project and name, the application and the version. The fake git
   * host answers every repository with the default spec, so an announcement of it deploys.
   */
  private Setup aReleasedVersion() {
    driver.reset();
    String token = token();
    String environmentId = createEnvironment("contract-" + token, true);
    settling.add(environmentId);
    String application = "contract-app-" + token;
    Map<String, String> params = new TreeMap<>();
    params.put("application", application);
    params.put("projectId", "contract-project-" + token);
    params.put("repoId", UUID.randomUUID().toString());
    params.put("repoName", application);
    params.put("version", VERSION);
    return new Setup(params, List.of(token));
  }

  /**
   * Two releases of one application deployed in turn: {@link #NEXT_VERSION} serves and {@link
   * #VERSION}, decommissioned by it, is what a rollback restores. Its pin holds both.
   *
   * <p><b>The only application serving anywhere.</b> {@code listPins} answers instance-wide, and
   * the deployed states leave their designated environments behind, so every application an
   * earlier state deployed would answer beside this one — with one sha, where a consumer that binds
   * "a pin carries its serving and its rollback sha" (qits-orchestrator-service's GC) reads two.
   * The state forgets every deployment row before it seeds, so the answer is its own.
   */
  private Setup anApplicationWithARollback() {
    driver.reset();
    QuarkusTransaction.requiringNew().run(() -> PdDeployment.deleteAll());
    String token = token();
    String environmentId = createEnvironment("contract-" + token, true);
    String application = "contract-app-" + token;
    release(application, VERSION);
    settle(environmentId, 1);
    release(application, NEXT_VERSION);
    settle(environmentId, 2);
    Map<String, String> params = new TreeMap<>();
    params.put("applicationName", application);
    params.put("environmentId", environmentId);
    return new Setup(params, List.of(token));
  }

  /**
   * One application whose spec declares an {@code idp:client} resource, deployed ACTIVE: the deploy
   * provisioned its service client (through the suite's fake qits-idp) and holds the claim row.
   */
  private Setup anApplicationHoldingAServiceClient() {
    driver.reset();
    String token = token();
    String environmentName = "contract-" + token;
    String environmentId = createEnvironment(environmentName, true);
    String application = "contract-app-" + token;
    specs.script(
        application,
        new SpecSource.DeploymentSpec(
            false,
            null,
            null,
            null,
            List.of(
                new SpecSource.DeploymentSpec.ResourceSpec(
                    "idp", null, SpecSource.DeploymentSpec.ResourceSpec.Type.IDP_CLIENT))));
    release(application, VERSION);
    settle(environmentId, 1);
    Map<String, String> params = new TreeMap<>();
    params.put("applicationName", application);
    params.put("environmentName", environmentName);
    return new Setup(params, List.of(token));
  }

  /** Nothing seeded: what this state stands for is the profile, not a row. */
  private Setup theMachineGateIsOn() {
    return new Setup(Map.of(), List.of());
  }

  /**
   * One application deployed ACTIVE in a designated environment, holding two settings there: a
   * plain one ({@link #PLAIN_SETTING}, the {@code key} param) and a secret one. {@code
   * environment} is the environment's NAME, which is how the settings doors address a place.
   */
  private Setup anApplicationWithSettings() {
    driver.reset();
    String token = token();
    String environment = "contract-" + token;
    String environmentId = createEnvironment(environment, true);
    String application = "contract-app-" + token;
    release(application, VERSION);
    settle(environmentId, 1);
    putSetting(application, environment, PLAIN_SETTING, "info", false);
    putSetting(application, environment, "API_TOKEN", "contract-secret-value", true);
    Map<String, String> params = new TreeMap<>();
    params.put("application", application);
    params.put("environment", environment);
    params.put("key", PLAIN_SETTING);
    return new Setup(params, List.of(token));
  }

  /**
   * One application deployed ACTIVE with three settings, whose live service carries exactly those
   * three; then one setting is added, one changed and one removed. The preview answers one of
   * each, and one unchanged.
   *
   * <p>The live environment is scripted on the suite's orchestrator double under the deployment
   * row's own service name — the name the preview inspects — because the double runs nothing.
   */
  private Setup anApplicationWithChangedSettings() {
    driver.reset();
    String token = token();
    String environment = "contract-" + token;
    String environmentId = createEnvironment(environment, true);
    String application = "contract-app-" + token;
    putSetting(application, environment, "GREETING", "hello", false);
    putSetting(application, environment, PLAIN_SETTING, "info", false);
    putSetting(application, environment, "LEGACY_FLAG", "on", false);
    release(application, VERSION);
    settle(environmentId, 1);
    driver.scriptLiveEnvironment(
        containerNameOf(environmentId),
        List.of("GREETING=hello", PLAIN_SETTING + "=info", "LEGACY_FLAG=on"));
    putSetting(application, environment, "NEW_FLAG", "off", false);
    putSetting(application, environment, PLAIN_SETTING, "debug", false);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .delete("/deployments/api/settings/" + application + "/" + environment + "/LEGACY_FLAG")
        .then()
        .statusCode(204);
    Map<String, String> params = new TreeMap<>();
    params.put("application", application);
    params.put("environment", environment);
    return new Setup(params, List.of(token));
  }

  /** One release deployed ACTIVE; {@code id} is the deployment request that deployed it. */
  private Setup aDeploymentRequest() {
    driver.reset();
    String token = token();
    String environmentId = createEnvironment("contract-" + token, true);
    String application = "contract-app-" + token;
    release(application, VERSION);
    settle(environmentId, 1);
    List<String> ids =
        given()
            .header("Authorization", SuiteBearers.operator())
            .queryParam("repoId", application)
            .queryParam("version", VERSION)
            .when()
            .get("/deployments/api/deployment-requests")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("deploymentRequests.id");
    if (ids.size() != 1) {
      throw new IllegalStateException("expected one deployment request, got " + ids);
    }
    Map<String, String> params = new TreeMap<>();
    params.put("applicationName", application);
    params.put("environmentId", environmentId);
    params.put("id", ids.get(0));
    return new Setup(params, List.of(token));
  }

  /**
   * One application deployed ACTIVE in a designated environment: its service runs, so the
   * operator's levers (scale, restart, retire) are accepted. {@code applicationId} is the
   * {@code <environmentId>:<name>} key the levers take. A lever only queues; clean-up waits for the
   * deploy worker to finish it.
   */
  private Setup anApplicationRunning() {
    driver.reset();
    String token = token();
    String environmentId = createEnvironment("contract-" + token, true);
    String application = "contract-app-" + token;
    release(application, VERSION);
    settle(environmentId, 1);
    Map<String, String> params = new TreeMap<>();
    params.put("applicationId", environmentId + ":" + application);
    params.put("applicationName", application);
    params.put("environmentId", environmentId);
    return new Setup(params, List.of(token));
  }

  /**
   * One application whose only release failed — the orchestrator never saw its service converge —
   * so nothing is deployed and a decommission is allowed; qits-artifacts (the suite's double) holds
   * one published image of it, which the decommission's purge reports removed.
   */
  private Setup anApplicationWithAFailedDeployment() {
    driver.reset();
    String token = token();
    String environmentId = createEnvironment("contract-" + token, true);
    String application = "contract-app-" + token;
    failedRelease(environmentId, application);
    Map<String, String> params = new TreeMap<>();
    params.put("applicationId", environmentId + ":" + application);
    params.put("applicationName", application);
    params.put("environmentId", environmentId);
    return new Setup(params, List.of(token));
  }

  /** {@link #anApplicationWithAFailedDeployment}, then decommissioned once: one PURGED run. */
  private Setup aDecommissionedApplication() {
    Setup failed = anApplicationWithAFailedDeployment();
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post("/deployments/api/applications/" + failed.params().get("applicationId") + "/decommission")
        .then()
        .statusCode(200);
    return failed;
  }

  /**
   * One environment, not designated, and one catalogue service registered by hand and linked into
   * it. Both are torn down afterwards.
   */
  private Setup aServiceLinkedIntoAnEnvironment() {
    String token = token();
    String environmentId = createEnvironment("contract-" + token, false);
    removable.add(environmentId);
    String service = "contract-svc-" + token;
    removableServices.add(service);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "availableOnEnv", false,
                "healthPath", "/q/health",
                "environmentIds", List.of(environmentId)))
        .when()
        .put("/deployments/api/services/" + service)
        .then()
        .statusCode(201);
    Map<String, String> params = new TreeMap<>();
    params.put("environmentId", environmentId);
    params.put("serviceName", service);
    return new Setup(params, List.of(token));
  }

  /** One environment, not designated, holding nothing: it can be torn down. */
  private Setup anEnvironmentToTearDown() {
    String token = token();
    String id = createEnvironment("contract-" + token, false);
    removable.add(id);
    Map<String, String> params = new TreeMap<>();
    params.put("environmentId", id);
    return new Setup(params, List.of(token));
  }

  /**
   * {@link #AN_APPLICATION_HOLDING_A_DATABASE}: a release of an application declaring {@code db},
   * settled ACTIVE, with the image of both postgres servers on the orchestrator double so the move
   * an interaction queues can run its containers (the suite's {@code FakeDatabaseContainers}).
   * {@code application} is the application NAME, which is what the move door takes.
   */
  private Setup anApplicationHoldingADatabase() {
    driver.reset();
    String token = token();
    String environment = "contract-" + token;
    String environmentId = createEnvironment(environment, true);
    String application = "contract-app-" + token;
    specs.script(
        application,
        new SpecSource.DeploymentSpec(
            false,
            null,
            null,
            null,
            List.of(new SpecSource.DeploymentSpec.ResourceSpec("db", null))));
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", POSTGRES_IMAGE);
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", POSTGRES_IMAGE);
    release(application, VERSION);
    settle(environmentId, 1);
    Map<String, String> params = new TreeMap<>();
    params.put("application", application);
    params.put("environmentName", environment);
    params.put("resource", "db");
    return new Setup(params, List.of(token));
  }

  /**
   * {@link #A_FINISHED_DATABASE_MOVE}: one {@code pd_database_move} row written straight to the
   * table, MOVED, with fixed manifests — the record a move leaves, without staging the five steps
   * that write it (those are {@code DatabaseMovesTest}'s). {@code id} is the move's.
   */
  private Setup aFinishedDatabaseMove() {
    String token = token();
    String application = "contract-app-" + token;
    String database = "qits_contract_app_" + token;
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = application;
              row.environmentName = "contract-" + token;
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = database;
              row.targetServer = "private";
              row.targetDatabase = database;
              row.keptDatabase = database + "_moved_202601010000";
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = PdDatabaseMoveState.MOVED;
              row.sourceCounts = "{\"public.flyway_schema_history\":4,\"public.items\":12}";
              row.targetCounts = row.sourceCounts;
              row.serviceName = "contract-" + token + "-" + application;
              row.serviceReplicas = 1;
              row.createdAt = Instant.now();
              row.startedAt = row.createdAt;
              row.finishedAt = row.createdAt;
              row.dropAfter = row.createdAt.plus(java.time.Duration.ofDays(7));
              row.persist();
            });
    Map<String, String> params = new TreeMap<>();
    params.put("application", application);
    params.put("id", id);
    return new Setup(params, List.of(token));
  }

  /**
   * {@link #A_RUNNING_DATABASE_MOVE}: one {@code pd_database_move} row at {@code EXTRACTING},
   * written straight to the table with what its space check measured — the row an abort is asked
   * of. No worker runs it, so the abort's 202 is the whole interaction. {@code id} is the move's.
   */
  private Setup aRunningDatabaseMove() {
    String token = token();
    String application = "contract-app-" + token;
    String database = "qits_contract_app_" + token;
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = application;
              row.environmentName = "contract-" + token;
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = database;
              row.targetServer = "private";
              row.targetDatabase = database;
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = PdDatabaseMoveState.EXTRACTING;
              row.serviceName = "contract-" + token + "-" + application;
              row.serviceReplicas = 1;
              row.sourceBytes = 20401094656L;
              row.targetFreeBytes = 64424509440L;
              row.volumeFreeBytes = 64424509440L;
              row.createdAt = Instant.now();
              row.startedAt = row.createdAt;
              row.persist();
            });
    runningMoves.add(id);
    Map<String, String> params = new TreeMap<>();
    params.put("application", application);
    params.put("id", id);
    return new Setup(params, List.of(token));
  }

  /**
   * Announces a release the orchestrator double answers as failed, waits for its row to end
   * FAILED, and publishes one image of the application on the artifacts double.
   */
  private void failedRelease(String environmentId, String application) {
    driver.scriptConvergence(DeploymentDriver.Convergence.failed(CONVERGENCE_FAILURE));
    artifacts.publish(
        application,
        List.of(
            new ArtifactPurge.RemovedArtifact(
                "oci", "oci", "qits/" + application, List.of(VERSION))));
    release(application, VERSION);
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<String> statuses = statuses(environmentId);
      if (statuses.equals(List.of("FAILED"))) {
        awaitIdle();
        driver.scriptConvergence(DeploymentDriver.Convergence.converged(List.of()));
        return;
      }
      pause(environmentId);
    }
    throw new IllegalStateException(
        "the release into " + environmentId + " never failed: " + statuses(environmentId));
  }

  private static void putSetting(
      String application, String environment, String key, String value, boolean secret) {
    given()
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body(Map.of("value", value, "secret", secret))
        .when()
        .put("/deployments/api/settings/" + application + "/" + environment + "/" + key)
        .then()
        .statusCode(200);
  }

  /** The service name the environment's newest deployment row recorded. */
  private static String containerNameOf(String environmentId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .path("deployments[0].containerName");
  }

  private void awaitIdle() {
    try {
      deployService.awaitIdle();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while the deploy worker drained", e);
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private static void release(String application, String version) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-contract", "repoId", application, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private static String createEnvironment(String name, boolean designated) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "designated", designated))
        .when()
        .post("/deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private static List<String> statuses(String environmentId) {
    return given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments.status");
  }

  /**
   * Waits until the environment holds {@code count} deployments, the newest ACTIVE and every older
   * one DECOMMISSIONED by its successor. The listing is newest-first.
   */
  private static void settle(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<String> statuses = statuses(environmentId);
      if (statuses.size() == count
          && "ACTIVE".equals(statuses.get(0))
          && statuses.subList(1, count).stream().allMatch("DECOMMISSIONED"::equals)) {
        return;
      }
      pause(environmentId);
    }
    throw new IllegalStateException(
        "the deployments into " + environmentId + " never settled: " + statuses(environmentId));
  }

  /**
   * Waits until a release announced into the environment has finished: a deployment exists and none
   * is still QUEUED or STARTING. Gives up quietly after the deadline: an announcement a pact
   * refused deploys nothing, and that is not this state's failure.
   */
  private static void drain(String environmentId) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<String> statuses = statuses(environmentId);
      if (!statuses.isEmpty()
          && statuses.stream().noneMatch(s -> "QUEUED".equals(s) || "STARTING".equals(s))) {
        return;
      }
      pause(environmentId);
    }
  }

  private static void pause(String environmentId) {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while settling " + environmentId, e);
    }
  }

  /** Eight lower-case hex characters: a dns label part, unique per state run. */
  private static String token() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
