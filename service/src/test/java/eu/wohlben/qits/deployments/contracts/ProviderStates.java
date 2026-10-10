package eu.wohlben.qits.deployments.contracts;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
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

  /** The released version the deployed state ships. */
  static final String VERSION = "2026.101.120000";

  /** What a state hands back: its parameters, keys sorted, and its unique tokens. */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject FakeDeploymentDriver driver;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<String> removable = Collections.synchronizedList(new ArrayList<>());

  public ProviderStates() {
    states.put(AN_ENVIRONMENT, this::anEnvironment);
    states.put(AN_APPLICATION_DEPLOYED, this::anApplicationDeployed);
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

  /** Tears down every environment a state created that can be torn down. */
  public void cleanUp() {
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
   * One release deployed and settled ACTIVE in a designated environment: a deployment row, and the
   * pin that keeps its image.
   */
  private Setup anApplicationDeployed() {
    driver.reset();
    String token = token();
    String environmentId = createEnvironment("contract-" + token, true);
    String application = "contract-app-" + token;
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-contract", "repoId", application, "version", VERSION))
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    settle(environmentId);
    Map<String, String> params = new TreeMap<>();
    params.put("environmentId", environmentId);
    return new Setup(params, List.of(token));
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

  /** Waits for the one deployment to finish ACTIVE. */
  private static void settle(String environmentId) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<String> statuses =
          given()
              .when()
              .get("/deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments.status");
      if (statuses.size() == 1 && "ACTIVE".equals(statuses.get(0))) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while settling " + environmentId, e);
      }
    }
    throw new IllegalStateException("the deployment into " + environmentId + " never settled ACTIVE");
  }

  /** Eight lower-case hex characters: a dns label part, unique per state run. */
  private static String token() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
