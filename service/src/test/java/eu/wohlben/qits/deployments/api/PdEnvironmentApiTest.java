package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.deployments.deployments.control.FakeDeploymentDriver;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The environment surface end to end, against {@link FakeDeploymentDriver} (no docker).
 *
 * <p>Both ancestors had a suite here and they merged into this one. qits-serviceregistry's proved
 * the rows and the validation; qits-cd's proved the docker side effects and, after the extraction,
 * that its own endpoints faithfully <b>proxied</b> to the other service. That third set of
 * assertions is gone with the proxy: there is one service, one transaction, and nothing on the wire
 * between the row and the network. What is left is the two halves that were always real.
 *
 * <p>Tests address the absolute {@code /deployments/api} paths, which is what makes them
 * catch a prefix regression, and every test names its own environment: the suite shares one
 * in-memory database across classes (Flyway cleans at start, not between tests), so a shared name
 * is a test that passes alone and fails in a run. Assert with {@code hasItem} and {@code find{}}
 * wherever a foreign class's rows could appear, never with a size.
 *
 * <p><b>One reason that discipline used to be sharper here is gone.</b> A platform service carried
 * no link and was therefore in <em>every</em> environment's link query — so a service another test
 * class registered turned up in this class's answers, and a fresh tier came up already holding it.
 * A service linked nowhere is now in no tier's answer at all, so a link query returns exactly what
 * somebody linked into that tier and nothing reaches it by being absent from everything.
 */
@QuarkusTest
public class PdEnvironmentApiTest {

  private static final String ENVIRONMENTS = "/deployments/api/environments";
  private static final String SERVICES = "/deployments/api/services";

  @Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  // --- creation ---------------------------------------------------------------------------------

  @Test
  public void creationFillsTheConventionsAndEnsuresTheNetwork() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-conventions"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .body("environment.name", equalTo("env-conventions"))
        // No branch: a tier listened to environment/<name> while a green build was the trigger,
        // and a release names a tag. V8 dropped the column and the field with it.
        .body("environment.branch", nullValue())
        .body("environment.network", equalTo("qits-env-env-conventions"))
        .body("environment.applications", hasSize(0))
        .body("environment.id", notNullValue())
        .body("environment.createdAt", notNullValue());

    assertTrue(
        driver.ensuredNetworks().contains("qits-env-env-conventions"),
        "creation must ensure the environment's bundle network: " + driver.ensuredNetworks());
  }

  @Test
  public void anExplicitNetworkWinsAndAnOldSendersBranchIsIgnored() {
    // The dev tier is exactly this shape: its bundle is qits-net by history, not by convention.
    //
    // `branch` is sent here on purpose. A bootstrap or an operator's script written against the
    // previous surface still spells it, and the tier it would have named is decided by `platform`
    // now — so the field has to deserialize into nothing rather than fail a creation.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-explicit", "branch", "environment/dev", "network", "qits-net"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .body("environment.branch", nullValue())
        .body("environment.network", equalTo("qits-net"));
  }

  @Test
  public void declaredApplicationsAreAcceptedAndIgnored() {
    // The deprecated field. It is still accepted so an older sender's payload deserializes, but the
    // catalogue holds one identity for a service (its name), and rows are derived from each
    // repository's own deployments.yml — so nothing is registered from it.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "env-declared",
                "applications",
                    List.of(Map.of("repoId", "repo-declared", "name", "app-declared"))))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .body("environment.applications", hasSize(0));

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(SERVICES)
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("app-declared")));
  }

  @Test
  public void aDuplicateNameIsAConflict() {
    Map<String, Object> payload = Map.of("name", "env-duplicate");
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON).body(payload).when().post(ENVIRONMENTS).then().statusCode(201);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(409)
        .body("message", equalTo("Environment already exists: env-duplicate"));
  }

  @Test
  public void hostileNamesAreRejectedBeforeTheyReachAnArgv() {
    // The name becomes a docker network name, an alias and an image path segment, and this surface
    // is attacker-reachable while the gate is off — so a 400 is owed here rather than at the argv.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Evil Name"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(400)
        .body("message", notNullValue());

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-hostile-net", "network", "--privileged"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(400);

    assertTrue(
        driver.calls().isEmpty(), "no refused request reached the driver: " + driver.calls());
  }

  // --- reads ------------------------------------------------------------------------------------

  @Test
  public void theEnvironmentReadShowsTheTiersOwnServices() {
    // Was `theEnvironmentReadShowsTheTiersOwnServicesWithoutThePlatformOnes`. The exclusion in that
    // name described a second population — services that belonged to no tier and were reached
    // through the links query and the flat listing instead — and there is no such population now.
    // The aggregate is the tier's own services because that is all there is to be shown; it is not
    // withholding anything.
    //
    // What survives from the old test is the other half, and it is worth keeping for its own sake:
    // the FLAT listing still emits a row for a service the catalogue links nowhere, with a null
    // tier on it. That shape used to be the plane's — one row, no environment, present everywhere —
    // and it now reports honestly that the service runs nowhere. An operator goes looking for
    // exactly that row, so the listing that hid it would be the one lying.
    String environmentId = create("env-read");
    upsertService("envsuite-app-read", environmentId);
    upsertUnlinkedService("envsuite-svc-read-unlinked");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.applications", hasSize(1))
        .body("environment.applications[0].name", equalTo("envsuite-app-read"))
        .body("environment.applications[0].repoId", equalTo("envsuite-app-read"))
        .body("environment.applications[0].environmentId", equalTo(environmentId))
        .body("environment.applications[0].environmentName", equalTo("env-read"));

    // ...and the id is the derived one a client joins a deployment row against. There is no plane
    // in it any more: both sides say `<environmentId>:<name>` and the `platform:` stand-in is gone.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + environmentId)
        .then()
        .body("environment.applications[0].id", equalTo(environmentId + ":envsuite-app-read"));

    List<Map<String, Object>> flat =
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get("/deployments/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("applications");
    assertTrue(
        flat.stream()
            .anyMatch(
                a ->
                    "envsuite-svc-read-unlinked".equals(a.get("name"))
                        && a.get("environmentId") == null),
        "the flat listing carries the service that runs nowhere, with no tier on it: " + flat);
  }

  @Test
  public void aListingLeavesTheApplicationsUnaskedRatherThanEmpty() {
    // Null, not []. "This tier holds nothing" and "you did not ask" are different answers, and a
    // client that renders an empty list as the first would be wrong on every listing row.
    create("env-listed");
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS)
        .then()
        .statusCode(200)
        .body("environments.name", hasItem("env-listed"))
        .body("environments.find { it.name == 'env-listed' }.applications", nullValue());
  }

  @Test
  public void anUnknownEnvironmentIsNotFound() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(ENVIRONMENTS + "/no-such-id").then().statusCode(404);
  }

  @Test
  public void theLinkQueryIsExactlyTheTiersOwnServices() {
    // Was `theLinkQueryComposesTheTiersServicesWithEveryPlatformService`, and its comment read "a
    // reconciliation needs the platform plane too, or a fresh tier would come up without qits-idp
    // in it". That composition is deleted: the answer was this tier's links PLUS every service
    // carrying no link at all, and the second half described the plane's way of being everywhere.
    //
    // One query now. The pull query and the aggregate above answer the same rows, and they are kept
    // as two endpoints because their shapes and their readers differ — an aggregate carries the
    // tier's name on each row, a reconciliation does not — not because one of them composes a
    // second population into its answer.
    String mine = create("env-links-mine");
    String other = create("env-links-other");
    upsertService("envsuite-svc-links-linked", mine);
    upsertService("envsuite-svc-links-elsewhere", other);
    upsertUnlinkedService("envsuite-svc-links-nowhere");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + mine + "/links")
        .then()
        .statusCode(200)
        .body("services.name", hasItem("envsuite-svc-links-linked"))
        .body("services.name", not(hasItem("envsuite-svc-links-elsewhere")))
        // The assertion that was a `hasItem` before: a service linked nowhere is in NO tier's
        // answer, which is the property the composed half used to invert.
        .body("services.name", not(hasItem("envsuite-svc-links-nowhere")))
        .body(
            "services.find { it.name == 'envsuite-svc-links-linked' }.availableOnEnv",
            equalTo(false));
  }

  @Test
  public void aBrandNewEnvironmentHoldsNothingUntilSomethingIsLinkedIntoIt() {
    // Was `aBrandNewEnvironmentAlreadyHoldsEveryPlatformService`, whose comment read "Created after
    // the platform service, linked to nothing, and it has it. That is the whole reason a platform
    // service has no links." This is the reversal at its sharpest: a tier used to come up already
    // holding qits-idp and the rest of the plane, with nothing written and nobody having decided
    // anything.
    //
    // A brand-new tier holds NOTHING. Presence is a link now, and a link is somebody's statement —
    // which costs a fresh tier one registration per service and buys a link query with exactly one
    // source.
    upsertUnlinkedService("envsuite-svc-preexisting-unlinked");
    String fresh = create("env-fresh");
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + fresh + "/links")
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("envsuite-svc-preexisting-unlinked")));
  }

  @Test
  public void theLinkQueryOfAnUnknownEnvironmentIsNotFound() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(ENVIRONMENTS + "/no-such-id/links").then().statusCode(404);
  }

  // --- patch ------------------------------------------------------------------------------------

  @Test
  public void patchRenamesWithoutTouchingDocker() {
    String environmentId = create("env-patch");
    driver.reset();

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patched"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.name", equalTo("env-patched"))
        // The bundle network is NOT renamed with it: the rename is a row change, and the running
        // containers keep the networks they are on until their own next deploy.
        .body("environment.network", equalTo("qits-env-env-patch"));

    // A rename must be safe on a live tier: nothing was ensured, removed, disconnected or reaped.
    assertTrue(driver.calls().isEmpty(), "PATCH has no runtime side effects: " + driver.calls());
    assertTrue(driver.removedEnvironments().isEmpty());
  }

  @Test
  public void patchLeavesAnOmittedFieldAloneAndRejectsWhatCreateWouldReject() {
    String environmentId = create("env-patch-partial");

    // An empty patch leaves everything alone — and an old sender's `branch` is exactly that now.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "environment/dev"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.name", equalTo("env-patch-partial"))
        .body("environment.branch", nullValue());

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Evil Name"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(400);

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-partial"))
        .when()
        .patch(ENVIRONMENTS + "/no-such-environment")
        .then()
        .statusCode(404);
  }

  @Test
  public void renamingOntoATakenNameIsAConflictAndOntoItsOwnIsNot() {
    create("env-patch-taken");
    String environmentId = create("env-patch-other");

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-taken"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(409);

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-other"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200);
  }

  // --- teardown ---------------------------------------------------------------------------------

  @Test
  public void teardownRemovesContainersAndNetworkAndThenTheTier() {
    String environmentId = create("env-teardown");

    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(ENVIRONMENTS + "/" + environmentId).then().statusCode(404);

    assertTrue(
        driver.removedEnvironments().contains(environmentId),
        "teardown must remove the environment's containers");
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-teardown"),
        "teardown must remove the environment's bundle network");
  }

  @Test
  public void theRuntimeTeardownRunsBeforeTheRowsGo() {
    // The order is the contract. The teardown is label-driven and needs nothing from the topology,
    // but deleting the tier first would leave a failed teardown with no row to retry it from — so a
    // half-finished teardown stays addressable.
    //
    // With the two services merged this is no longer two processes agreeing on an order; it is one
    // method, and the only place the ordering is observable from is inside a driver call. The hook
    // below runs while the containers are being reaped and reads the tier back over HTTP: still 200
    // there means the rows had not gone yet.
    String environmentId = create("env-order");
    AtomicInteger statusDuringReap = new AtomicInteger();
    driver.scriptDuringContainerReap(
        () ->
            statusDuringReap.set(
                given()
                    .header("Authorization", SuiteBearers.operator())
                    .when().get(ENVIRONMENTS + "/" + environmentId).thenReturn().statusCode()));

    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);

    assertEquals(
        200,
        statusDuringReap.get(),
        "the tier is still addressable while its containers are being reaped");
    assertTrue(
        driver.removedEnvironments().contains(environmentId),
        "the containers were reaped: " + driver.calls());
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-order"),
        "and the network removed: " + driver.removedNetworks());
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(ENVIRONMENTS + "/" + environmentId).then().statusCode(404);
  }

  @Test
  public void teardownFreesThePlatformContainersBeforeRemovingTheDerivedNetworks() {
    String environmentId = create("env-derived-teardown");
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-env-derived-teardown-app-x",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-x"));

    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);

    // A platform service survives the tier it merely served, so it is what holds the networks open
    // — docker refuses to remove a network with an endpoint on it. Releasing whatever the plane
    // holds is asked of the driver first, and only then are the networks removed.
    assertTrue(
        driver.detached().contains("qits-env-env-derived-teardown-app-x"),
        "the plane is released from the derived networks first: " + driver.detached());
    List<String> calls = driver.calls();
    int released = indexOfPrefix(calls, "detachPlatformPlane:");
    assertTrue(
        released >= 0 && released < calls.indexOf("removeNetwork:qits-env-env-derived-teardown-app-x"),
        "and first is the order: " + calls);
    assertTrue(driver.removedNetworks().contains("qits-env-env-derived-teardown"));
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-derived-teardown-app-x"),
        "the derived per-application networks go too: " + driver.removedNetworks());
  }

  @Test
  public void teardownLeavesTheLegacyNetworkAloneWhenItIsTheEnvironmentsBundle() {
    // The dev tier's shape exactly: its bundle IS qits.deployments.legacy-network. That
    // network is the transition membership of every container on the host — platform services
    // included — so it is not this environment's to take away. Disconnecting them from it would cut
    // qits-idp off from the platform, and this component would be doing it to itself mid-request.
    String environmentId = create(Map.of("name", "env-legacy-bundle", "network", "qits-net"));
    driver.reset();
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-env-legacy-bundle-app-y",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-y"));

    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);

    assertTrue(
        driver.detached().stream().noneMatch("qits-net"::equals),
        "the plane is never released from the legacy network: " + driver.detached());
    assertTrue(
        !driver.removedNetworks().contains("qits-net"),
        "and the legacy network itself stays: " + driver.removedNetworks());
    // The environment's OWN derived network still goes, and the plane is released from it.
    assertTrue(
        driver.detached().contains("qits-env-env-legacy-bundle-app-y"), driver.detached().toString());
    assertTrue(driver.removedNetworks().contains("qits-env-env-legacy-bundle-app-y"));
  }

  @Test
  public void deleteTakesTheLinksIntoItAndLeavesTheServiceItself() {
    String kept = create("env-delete-kept");
    String dropped = create("env-delete-dropped");
    upsertService("envsuite-svc-survives-env-delete", kept, dropped);

    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(ENVIRONMENTS + "/" + dropped).then().statusCode(204);

    // A tier going away is not a service going away: the row and its other link survive.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(SERVICES)
        .then()
        .statusCode(200)
        .body("services.find { it.name == 'envsuite-svc-survives-env-delete' }.environmentIds", hasSize(1))
        .body(
            "services.find { it.name == 'envsuite-svc-survives-env-delete' }.environmentIds", hasItem(kept));
  }

  @Test
  public void deletingAMissingEnvironmentIs404() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(ENVIRONMENTS + "/no-such-environment").then().statusCode(404);
  }

  @Test
  public void deploymentsListingRequiresAnExistingEnvironment() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get("/deployments/api/deployments").then().statusCode(400);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=no-such")
        .then()
        .statusCode(404);
  }

  @Test
  public void thePlatformFilterValueIsGoneAndIsNowAnUnknownTierLikeAnyOther() {
    // Was `thePlatformPlaneIsAValidFilterValueAndNotAMissingEnvironment`, and the old claim was
    // that `platform` goes where an environment id goes and names the plane instead — so it had to
    // answer 200 where every other non-id got a 404. It reused the `platform:` stand-in from
    // ApplicationKeys, so the word at the front of an application's id was the word a client
    // filtered with, and the two sides agreed.
    //
    // Both are deleted. The stand-in is gone from the key and the plane is gone from the rows, so
    // `platform` names no tier and gets the answer every unknown tier id gets. Nothing is lost by
    // it: the plane's deployments already named the designated tier (V8), so that tier's own
    // listing is where they are — which is the answer an operator asking "what is running in dev"
    // was after all along, and is why the two filters overlapped.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/deployments/api/deployments?environmentId=platform")
        .then()
        .statusCode(404);

    // Dropping the filter is still a 400, and that is untouched: a listing has to be scoped, and
    // the retired value was never an escape from having a scope.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get("/deployments/api/deployments").then().statusCode(400);
  }

  // --- the platform environment -----------------------------------------------------------------

  @Test
  public void anEnvironmentIsNotThePlatformOneUnlessItSaysSo() {
    create("env-plain");
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS)
        .then()
        .statusCode(200)
        .body("environments.find { it.name == 'env-plain' }.platform", equalTo(false));
  }

  @Test
  public void designatingMovesTheFlagRatherThanAddingASecondHolder() {
    // The at-most-one invariant, and the schema does not hold it — H2 has no partial unique index,
    // so EnvironmentService moves the flag inside one transaction and this is what pins that.
    String first = create(Map.of("name", "env-plane-a", "platform", true));
    String second = create(Map.of("name", "env-plane-b", "platform", true));

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + first)
        .then()
        .statusCode(200)
        .body("environment.platform", equalTo(false));
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + second)
        .then()
        .statusCode(200)
        .body("environment.platform", equalTo(true));
  }

  @Test
  public void aPatchMovesTheDesignationToAnExistingTier() {
    // The "switch the platform environment" call. It is rows only: a platform service keeps its
    // bare wire alias, which is its address and under swarm its service name, so nothing on the
    // host moves — what changes is the tier the plane's NEXT deployment names, in its row, its
    // labels and its QITS_ENVIRONMENT.
    create(Map.of("name", "env-move-from", "platform", true));
    String to = create("env-move-to");

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("platform", true))
        .when()
        .patch(ENVIRONMENTS + "/" + to)
        .then()
        .statusCode(200)
        .body("environment.platform", equalTo(true))
        .body("environment.name", equalTo("env-move-to"));

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS)
        .then()
        .statusCode(200)
        .body("environments.find { it.name == 'env-move-from' }.platform", equalTo(false));
  }

  @Test
  public void theDesignationIsMovedNeverCleared() {
    // A window with no platform environment is a window in which a release of a platform service
    // registers nothing, has no tier to deploy into, and reports no error. So there is no way to
    // ask for one.
    String only = create(Map.of("name", "env-nodrop", "platform", true));
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("platform", false))
        .when()
        .patch(ENVIRONMENTS + "/" + only)
        .then()
        .statusCode(409);
  }

  @Test
  public void thePlatformEnvironmentCannotBeTornDown() {
    // Deleting it would leave the platform plane running with nowhere to deploy and no release
    // able to enter. Designate another tier first; that is a move, and the plane has a tier
    // throughout.
    String platform = create(Map.of("name", "env-undeletable", "platform", true));
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .delete(ENVIRONMENTS + "/" + platform)
        .then()
        .statusCode(409);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(ENVIRONMENTS + "/" + platform).then().statusCode(200);

    // …and once the designation has moved, the same delete goes through.
    create(Map.of("name", "env-undeletable-successor", "platform", true));
    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(ENVIRONMENTS + "/" + platform).then().statusCode(204);
  }

  /** The first call whose tag starts with this prefix, or -1 — the ORDER assertions read it. */
  private static int indexOfPrefix(List<String> calls, String prefix) {
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String create(String name) {
    return create(Map.of("name", name));
  }

  private String create(Map<String, Object> payload) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  /**
   * Register one service into the environments named — the only registration there is. It was
   * {@code upsertEnvironmentService} while {@code upsertPlatformService} stood beside it registering
   * the other shape of row; one shape needs no qualifier, and a name that still carried one would
   * read as a choice the caller does not have.
   */
  private void upsertService(String name, String... environmentIds) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("availableOnEnv", false, "environmentIds", List.of(environmentIds)))
        .when()
        .put(SERVICES + "/" + name)
        .then()
        .statusCode(201);
  }

  /**
   * The same registration naming no environment — what {@code upsertPlatformService} used to be,
   * with the meaning inverted. That body once registered a service present in every tier; it now
   * registers one present in none, which is the only thing an empty link set can honestly mean.
   * It is still worth a fixture of its own, because "the service that runs nowhere" is what several
   * tests here have to have in the database in order to assert it does not turn up.
   */
  private void upsertUnlinkedService(String name) {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "main", "availableOnEnv", false, "environmentIds", List.of()))
        .when()
        .put(SERVICES + "/" + name)
        .then()
        .statusCode(201);
  }
}
