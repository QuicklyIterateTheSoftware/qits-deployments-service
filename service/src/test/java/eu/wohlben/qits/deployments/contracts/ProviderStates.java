package eu.wohlben.qits.deployments.contracts;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.deployments.deployments.control.SpecSource;
import io.restassured.http.ContentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

  /** The version the rollback state ships second, so {@link #VERSION} is its rollback target. */
  static final String NEXT_VERSION = "2026.102.120000";

  /** What a state hands back: its parameters, keys sorted, and its unique tokens. */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<String> removable = Collections.synchronizedList(new ArrayList<>());

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
    List<String> ids;
    synchronized (removable) {
      ids = List.copyOf(removable);
      removable.clear();
    }
    for (String id : ids) {
      given().when().delete("/deployments/api/environments/" + id);
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
   */
  private Setup anApplicationWithARollback() {
    driver.reset();
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

  private static void release(String application, String version) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-contract", "repoId", application, "version", version))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private static String createEnvironment(String name, boolean designated) {
    return given()
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
