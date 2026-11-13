package eu.wohlben.qits.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.api.SuiteBearers;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Where a database move learns the NAME of the live deployment's repository (qits-2175, fix in
 * verify).
 *
 * <p>The spec read needs it: the git host serves {@code /git/<repoId>} to qits-projects alone, so
 * an id-addressed read is a 403 for the deployer. Live, qits-orchestrator@2026.1113.141108 was put
 * live by the bus and then deployed AGAIN through the manual door ({@code POST
 * /events/software-released}, qits-projects' "deploy again"), whose request row records no package —
 * so the exact (package, version) lookup had nothing to match, the read went by id, and the door
 * answered 502 with the git host's raw 403.
 *
 * <p>{@link FakeSpecSource} keys a read by {@link RepositoryRef#applicationName()} — the name when
 * one is carried, the repository id otherwise — so a test tells the two routes apart by whose read
 * count moved. Both keys are scripted with the same spec, so only the route differs.
 */
@QuarkusTest
class PdDatabaseMoveRepositoryNameTest {

  private static final String MOVES = "/deployments/api/database-moves";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject FakeDatabaseContainers containers;
  @Inject DeployService deployService;
  @Inject DatabaseMoves databaseMoves;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    containers.reset();
  }

  /** One application, its repository's id and name, and a tier of its own with both servers up. */
  private record Fixture(String environment, String environmentId, String application, String repoId) {}

  private Fixture fixture(String prefix) {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String environment = prefix + "-" + token;
    String environmentId =
        given()
            .header("Authorization", SuiteBearers.platform())
            .contentType(ContentType.JSON)
            .body(Map.of("name", environment, "designated", true))
            .when()
            .post("/deployments/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");
    String application = prefix + "-app-" + token;
    String repoId = UUID.randomUUID().toString();
    DeploymentSpec spec = declaring();
    specs.script(application, spec);
    specs.script(repoId, spec);
    driver.scriptRunningImage(environment + "-qits-oci-postgresql", "registry.example/qits/pg:1");
    driver.scriptRunningImage(environment + "-qits-oci-postgresql-private", "registry.example/qits/pg:1");
    return new Fixture(environment, environmentId, application, repoId);
  }

  private static DeploymentSpec declaring() {
    return new DeploymentSpec(
        false, null, null, null, List.of(new ResourceSpec("db", null)), List.of(), null, null, null,
        8080, null, false, null, null, null, null, null, null, DeploymentDriver.Exposure.PUBLIC,
        null, null);
  }

  /** The bus door: an obligation naming the repository and the package, then the deployment. */
  private void releasedByTheBus(Fixture f, String version) throws Exception {
    deployService.announceDurably(
        UUID.randomUUID().toString(),
        null,
        new RepositoryRef(f.repoId(), UUID.randomUUID().toString(), f.application()),
        f.application(),
        version,
        "qits/" + f.application(),
        null,
        null);
    deployService.awaitIdle();
  }

  /** The manual door, as qits-projects' "deploy again" knocks on it: no package, no obligation. */
  private void deployedAgainByHand(Fixture f, String version) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("repoId", f.repoId());
    body.put("application", f.application());
    body.put("version", version);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/deployments/api/events/software-released")
        .then()
        .statusCode(202);
    deployService.awaitIdle();
  }

  private static io.restassured.response.ValidatableResponse askToMove(Fixture f) {
    return given()
        .header("Authorization", SuiteBearers.bearer("dbmove-agent", "qits:agent"))
        .contentType(ContentType.JSON)
        .body(Map.of("application", f.application(), "server", "private"))
        .when()
        .post(MOVES)
        .then();
  }

  @Test
  void aVersionTheManualDoorDeployedAgainIsMovedReadingItsSpecByName() throws Exception {
    // The live shape: the bus put the version live, the manual door deployed the same one again.
    Fixture f = fixture("dbmn-a");
    releasedByTheBus(f, "2026.101.120000");
    deployedAgainByHand(f, "2026.101.120000");
    int byIdBefore = specs.readsOf(f.repoId());
    int byNameBefore = specs.readsOf(f.application());

    String id = askToMove(f).statusCode(202).extract().path("move.id");
    deployService.awaitIdle();

    assertEquals(byIdBefore, specs.readsOf(f.repoId()), "nothing was read by repository id");
    assertTrue(specs.readsOf(f.application()) >= byNameBefore + 2, "the door and the redeploy read by name");
    RepositoryRef ref = specs.refOf(f.application());
    assertTrue(ref.nameAddressed(), String.valueOf(ref));
    assertEquals(f.repoId(), ref.repoId());
    assertEquals(
        "MOVED",
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get(MOVES + "/" + id)
            .then()
            .statusCode(200)
            .extract()
            .path("move.state"));
  }

  @Test
  void aVersionNoReleaseWasAcceptedForFallsBackToTheRepositorysNewestNamedRelease() throws Exception {
    // No obligation exists for the live version at all: the exact match is absent, and the
    // repository's earlier release is what names it.
    Fixture f = fixture("dbmn-b");
    releasedByTheBus(f, "2026.101.120000");
    deployedAgainByHand(f, "2026.102.120000");
    int byIdBefore = specs.readsOf(f.repoId());

    askToMove(f).statusCode(202);
    deployService.awaitIdle();

    assertEquals(byIdBefore, specs.readsOf(f.repoId()), "nothing was read by repository id");
    assertTrue(specs.refOf(f.application()).nameAddressed());
    assertEquals("2026.102.120000", driver.applied().get(driver.applied().size() - 1).version());
  }

  @Test
  void aRepositoryNoReleaseEverNamedAnswers502SayingTheNameIsUnknown() throws Exception {
    Fixture f = fixture("dbmn-c");
    deployedAgainByHand(f, "2026.101.120000");
    specs.scriptFailure(f.repoId(), "the git host answered 403");

    String refusal = askToMove(f).statusCode(502).extract().asString();

    assertTrue(refusal.contains("the name of its repository " + f.repoId() + " is unknown"), refusal);
    assertTrue(!refusal.contains("answered 403"), refusal);
  }

  @Test
  void theBootSettleRedeploysAManuallyDeployedVersionByName() throws Exception {
    Fixture f = fixture("dbmn-d");
    String database = "qits_" + f.application().replace('-', '_');
    releasedByTheBus(f, "2026.101.120000");
    deployedAgainByHand(f, "2026.101.120000");
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = id;
              row.batchId = id;
              row.applicationName = f.application();
              row.environmentName = f.environment();
              row.resourceName = "db";
              row.sourceServer = "public";
              row.sourceDatabase = database;
              row.targetServer = "private";
              row.targetDatabase = database;
              row.keptDatabase = database + "_moved_202601010000";
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = PdDatabaseMoveState.SWITCHING;
              row.serviceName = f.environment() + "-" + f.application();
              row.serviceReplicas = 1;
              row.createdAt = Instant.now();
              row.persist();
            });
    int byIdBefore = specs.readsOf(f.repoId());
    int byNameBefore = specs.readsOf(f.application());

    databaseMoves.settleInterrupted(true);
    deployService.awaitIdle();

    assertEquals(byIdBefore, specs.readsOf(f.repoId()), "nothing was read by repository id");
    assertTrue(specs.readsOf(f.application()) > byNameBefore, "the redeploy read its spec by name");
    assertTrue(specs.refOf(f.application()).nameAddressed());
  }
}
