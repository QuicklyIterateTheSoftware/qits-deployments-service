package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The service catalogue: the upsert's semantics, the link-set replacement, and the link query.
 *
 * <p><b>There is one shape of service here now, and this suite is what is left after the platform
 * plane was deleted.</b> It used to hold four rules and it holds one: the link set is replaced,
 * never merged. The other three each said something about a column the catalogue no longer has —
 * "a platform service carries no links" (a 400), "the plane flip is one-way" (a 409 backwards), and
 * "state a deploymentTarget or be refused" (a 400) — and every one of them is gone rather than
 * moved, because there is no second shape of row to validate a write against. Where such a rule had
 * a test, the test below states the property that replaced it and names the claim it used to make,
 * so a reader who remembers the old behaviour can see what happened to it rather than wondering
 * whether it was merely forgotten.
 *
 * <p>Every test names its own environments and services — the suite shares one in-memory database
 * across classes, so a shared name is a test that passes alone and fails in a run, and an assertion
 * about the SIZE of a listing is a test that passes alone and fails beside a foreign class's rows.
 * Assert with {@code hasItem} and {@code find{}}.
 *
 * <p><b>One hazard that used to make that worse is gone.</b> A platform service leaked into
 * <em>every</em> environment's link query, including the ones other test classes had just created,
 * because carrying no link was how the plane said "present everywhere" — so a service registered
 * here changed the answer a test over there read. A service linked nowhere is now in no tier's
 * answer at all, so the only rows that can appear in a link query are the ones something linked
 * into that exact tier. The naming discipline above still stands; the cross-class leak it was also
 * guarding against does not exist any more.
 */
@QuarkusTest
public class PdServiceApiTest {

  private static final String ENVIRONMENTS = "/deployments/api/environments";
  private static final String SERVICES = "/deployments/api/services";

  @Test
  void registeringAServiceForTheFirstTimeIsCreated() {
    String env = createEnvironment("svc-first-env");
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"availableOnEnv":true,
             "healthPath":"/gateway/q/health/ready","environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-first")
        .then()
        .statusCode(201)
        .body("service.name", equalTo("svc-first"))
        .body("service.availableOnEnv", equalTo(true))
        .body("service.healthPath", equalTo("/gateway/q/health/ready"))
        .body("service.environmentIds", hasItem(env))
        .body("service.id", not(nullValue()));
  }

  @Test
  void aSecondUpsertOfTheSameNameUpdatesRatherThanCreating() {
    String env = createEnvironment("svc-second-env");
    String firstId = upsertService("svc-second", 201, env);
    // Same name, different shape: 200, and the identity survives.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"availableOnEnv":true,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-second")
        .then()
        .statusCode(200)
        .body("service.id", equalTo(firstId))
        .body("service.availableOnEnv", equalTo(true));
  }

  @Test
  void theLinkSetIsReplacedRatherThanMerged() {
    String a = createEnvironment("svc-replace-a");
    String b = createEnvironment("svc-replace-b");
    upsertService("svc-replace", 201, a, b);

    // The writer holds the whole spec, so what it sends IS the set. A merge would keep the link to
    // `a` forever, and nothing would ever remove it.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(b))
        .when()
        .put(SERVICES + "/svc-replace")
        .then()
        .statusCode(200)
        .body("service.environmentIds", hasSize(1))
        .body("service.environmentIds", hasItem(b));
  }

  @Test
  void anEmptyLinkSetUnlinksAServiceEverywhere() {
    String env = createEnvironment("svc-unlink-env");
    upsertService("svc-unlink", 201, env);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false,\"environmentIds\":[]}")
        .when()
        .put(SERVICES + "/svc-unlink")
        .then()
        .statusCode(200)
        .body("service.environmentIds", empty());
    // ...and it is gone from the environment's link query, which is the point of the row. Emptying
    // the links means the service runs NOWHERE — it used to be the spelling of "everywhere", which
    // is the reversal this whole change is about.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + env + "/links")
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("svc-unlink")));
  }

  @Test
  void namingAnEnvironmentTwiceStatesTheSameLinkOnce() {
    String env = createEnvironment("svc-dupe-env");
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"availableOnEnv":false,"environmentIds":["%s","%s"]}
            """
                .formatted(env, env))
        .when()
        .put(SERVICES + "/svc-dupe")
        .then()
        .statusCode(201)
        .body("service.environmentIds", hasSize(1));
  }

  @Test
  void aServiceCarriesTheLinksItWasRegisteredWithAndItsBranchRoundTrips() {
    // This test used to be `aPlatformServiceCarriesNoLinksAndItsOwnBranch`, and it asserted the two
    // halves of what the plane was: a row with `deploymentTarget: PLATFORM`, no links at all, and a
    // `branch` that was stored only because the plane was the one place a branch meant anything.
    // Both halves are gone. qits-idp and this component are ordinary services linked into the tier
    // they run in, so a registration carries its links; and `branch` is stored whenever it is
    // non-blank, on any row, because the rule that dropped it beside an environment target was the
    // plane being asked a question about a column it had no opinion on.
    //
    // What is asserted here is therefore the ordinary case, which is the only case: the links are
    // exactly what was stated, and `branch` comes back as it went in. It remains VESTIGIAL —
    // nothing decides a deployment on it, a release names a tag — so this is a round-trip claim
    // about an operator's write and deliberately not a claim that anything reads the value.
    String env = createEnvironment("svc-branch-roundtrip-env");
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"branch":"main","availableOnEnv":false,
             "healthPath":"/idp/q/health/ready","environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-branch-roundtrip")
        .then()
        .statusCode(201)
        .body("service.branch", equalTo("main"))
        .body("service.healthPath", equalTo("/idp/q/health/ready"))
        .body("service.environmentIds", hasItem(env));
  }

  @Test
  void environmentLinksAreStoredRatherThanRefused() {
    // This test used to be `aPlatformServiceGivenEnvironmentLinksIsRefused`, and the 400 it asserted
    // read "A platform service carries no environment links...". The refusal was right while there
    // were two shapes of row: a caller stating PLATFORM and naming environments was holding a
    // different model of the row from the one this service held, and storing either reading would
    // have hidden the disagreement instead of surfacing it.
    //
    // There is one shape now, so there is nothing to disagree about and nothing to refuse. A link
    // set is a link set: it is stored, and the service runs in the tiers it names. Registering the
    // very shape that used to be a 400 is therefore an ordinary 201, which is what this asserts.
    String env = createEnvironment("svc-links-stored-env");
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-links-stored")
        .then()
        .statusCode(201)
        .body("service.environmentIds", hasSize(1))
        .body("service.environmentIds", hasItem(env));

    // ...and it is present in that tier's link query, which is where a stored link is observable.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + env + "/links")
        .then()
        .statusCode(200)
        .body("services.name", hasItem("svc-links-stored"));
  }

  @Test
  void aBranchIsAcceptedAndStoredAndStillDecidesNothing() {
    // This test used to be `aBranchBesideAnEnvironmentTargetIsAcceptedAndIgnored` and it asserted a
    // null `branch` on the way back: the value was stored beside PLATFORM and dropped everywhere
    // else, so a `branch` on an environment service was tolerated the way a harmless extra key is
    // tolerated by a parser. With the plane gone there is no "everywhere else" to drop it in, and
    // the column is written whenever the value is non-blank.
    //
    // Accepted AND stored is not the same as acted on. `branch` is vestigial: a release names a tag
    // and `DeployService.entryTiers()` answers with the designated environment, so nothing anywhere
    // decides a deployment on this string. It is kept because an operator's PUT round-trips through
    // this surface, and a field that silently disappeared on the way back would be a worse answer
    // than one that is honestly stored and honestly unread.
    String env = createEnvironment("svc-branch-stored-env");
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"branch":"main","availableOnEnv":false,
             "environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-branch-stored")
        .then()
        .statusCode(201)
        .body("service.branch", equalTo("main"));

    // Blank is still null rather than an empty string: the column says "no branch stated".
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"branch":"","availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-branch-stored")
        .then()
        .statusCode(200)
        .body("service.branch", nullValue());
  }

  @Test
  void anUnknownEnvironmentIdIsNotFound() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false,\"environmentIds\":[\"no-such-id\"]}")
        .when()
        .put(SERVICES + "/svc-bad-env")
        .then()
        .statusCode(404);
  }

  @Test
  void aBodyStatingNoPlaneIsAnOrdinaryWriteAndAnOlderSendersDeploymentTargetIsIgnored() {
    // This test used to be `aMissingDeploymentTargetIsRefused`, and the 400 it asserted read
    // "Missing deploymentTarget — ENVIRONMENT or PLATFORM". Refusing was right while the value
    // chose between two shapes of row: a write that did not say which one it meant could not be
    // stored as either without this service inventing the caller's intent.
    //
    // There is one shape, so the question has no second answer and the field is gone from the
    // payload. Two claims replace the refusal, and they are the whole of the compatibility owed.
    //
    // First: a body that never mentions a plane is an ORDINARY write. Nothing is missing from it —
    // it is the complete statement of a service.
    String env = createEnvironment("svc-no-target-env");
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-no-target")
        .then()
        .statusCode(201)
        .body("service.environmentIds", hasItem(env));

    // Second: an older sender still spells the field. A bootstrap, an operator's script, or a
    // deployer that has not been replaced yet will keep sending `deploymentTarget` for as long as
    // it takes them to be re-rendered, and a write refused for naming a plane that no longer exists
    // would be this component breaking a peer over a word it stopped caring about. Jackson ignores
    // what the record does not declare, so the value deserializes into nothing: the write is
    // accepted, the links are stored exactly as stated, and no plane is recorded anywhere. That is
    // the same answer `branch` got when `pd_environment.branch` left the environment create
    // payload, and it is the only compatibility this change owes.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"PLATFORM","availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-old-sender")
        .then()
        .statusCode(201)
        .body("service.environmentIds", hasItem(env))
        // ...and nothing of it survives onto the response. No service, application or linked-service
        // row carries a plane any more, so the absence of the key is what a client reads.
        .body("service", not(hasKey("target")));
  }

  @Test
  void aServiceNameOutsideTheDnsLabelCharsetIsRefused() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false}")
        .when()
        .put(SERVICES + "/Svc_Bad")
        .then()
        .statusCode(400);
  }

  @Test
  void aHealthPathCarryingShellPunctuationIsRefused() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false,\"healthPath\":\"/q;curl evil\"}")
        .when()
        .put(SERVICES + "/svc-bad-health")
        .then()
        .statusCode(400);
  }

  @Test
  void anUpsertReplacesTheLinkSetInBothDirectionsAndNeverConverts() {
    // This test replaces two: `anEnvironmentServiceConvertsToAPlatformServiceAndLosesItsLinks` and
    // `aPlatformServiceCannotBecomeAnEnvironmentServiceAgain`. Between them they held the plane
    // flip — a one-way live migration, where writing PLATFORM over a linked service silently
    // dropped every link, and writing ENVIRONMENT back was refused with a 409 carrying a
    // remediation, because a service that had already been deployed under the bare wire alias could
    // not be pulled back into a tier without leaving a running container addressed by a name nobody
    // would deploy again.
    //
    // There is no plane to flip to, so there is no conversion, no silent drop and no 409. What is
    // left underneath both tests is the one rule that survived the change: an upsert REPLACES the
    // link set. It replaces it in both directions — down to nothing and back up again — and neither
    // direction is special, which is precisely what "no conversion" means.
    String a = createEnvironment("svc-replace-both-a");
    String b = createEnvironment("svc-replace-both-b");
    String id = upsertService("svc-replace-both", 201, a);

    // Down to nothing. The identity survives — it is the same row being restated, not a retirement
    // — and the service now runs NOWHERE, which is the honest reading of an empty link set and the
    // exact opposite of what an empty link set used to mean.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false,\"environmentIds\":[]}")
        .when()
        .put(SERVICES + "/svc-replace-both")
        .then()
        .statusCode(200)
        .body("service.id", equalTo(id))
        .body("service.environmentIds", empty());

    // ...so it has dropped out of the tier it was linked into. Under the plane this is the moment
    // the service would have appeared in every tier's answer instead.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + a + "/links")
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("svc-replace-both")));

    // And back up, into a different tier. No 409, no remediation to follow, no row to delete first:
    // the way back is an ordinary write because the way out was one too.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(b))
        .when()
        .put(SERVICES + "/svc-replace-both")
        .then()
        .statusCode(200)
        .body("service.id", equalTo(id))
        .body("service.environmentIds", hasSize(1))
        .body("service.environmentIds", hasItem(b));

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + b + "/links")
        .then()
        .statusCode(200)
        .body("services.name", hasItem("svc-replace-both"));
  }

  @Test
  void theCatalogueIsFlatAndCarriesAServiceThatRunsNowhere() {
    // Was `theCatalogueIsFlatAndCarriesBothPlanes`. There is one plane, so what the listing has to
    // carry is not two kinds of service but the two states one service can be in: linked into a
    // tier, and linked nowhere. The second is the shape the plane used to occupy, and it is kept on
    // the listing deliberately — a row nothing has registered into a tier is exactly the row an
    // operator goes looking for, so hiding it would make the listing lie by omission.
    String env = createEnvironment("svc-flat-env");
    upsertService("svc-flat-linked", 201, env);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false,\"environmentIds\":[]}")
        .when()
        .put(SERVICES + "/svc-flat-unlinked")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(SERVICES)
        .then()
        .statusCode(200)
        .body("services.name", hasItem("svc-flat-linked"))
        .body("services.name", hasItem("svc-flat-unlinked"))
        .body("services.find { it.name == 'svc-flat-linked' }.environmentIds", hasItem(env))
        .body("services.find { it.name == 'svc-flat-unlinked' }.environmentIds", empty());
  }

  @Test
  void theLinkQueryIsExactlyTheTiersOwnLinks() {
    // Was `theLinkQueryComposesTheLinkedServicesWithEveryPlatformService`, and the word that left is
    // "composes". The answer used to be two queries glued together: the services linked into this
    // tier, PLUS every platform service, because carrying no link was how the plane said "present
    // everywhere". `PdServiceRepository.listPlatformServices()` was the second half and it is
    // deleted.
    //
    // One query now, and the claim to hold is the sharpened one: a service linked nowhere appears
    // in NO tier's answer. That is the property the second half used to invert, and it is what makes
    // this listing readable — what a reconciliation gets is what somebody linked into this tier, and
    // nothing else reaches it by being absent from everything.
    String mine = createEnvironment("svc-links-mine");
    String other = createEnvironment("svc-links-other");
    upsertService("svc-links-linked", 201, mine);
    upsertService("svc-links-elsewhere", 201, other);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(
            """
            {"branch":"main","availableOnEnv":true,
             "healthPath":"/idp/q/health/ready","environmentIds":[]}
            """)
        .when()
        .put(SERVICES + "/svc-links-nowhere")
        .then()
        .statusCode(201);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + mine + "/links")
        .then()
        .statusCode(200)
        // Its own link...
        .body("services.name", hasItem("svc-links-linked"))
        // ...nothing from another tier...
        .body("services.name", not(hasItem("svc-links-elsewhere")))
        // ...and nothing that runs nowhere. This is the assertion that was a `hasItem` before.
        .body("services.name", not(hasItem("svc-links-nowhere")))
        // Each entry carries what a deployer reconciles with.
        .body("services.find { it.name == 'svc-links-linked' }.availableOnEnv", equalTo(false));

    // The service that runs nowhere is in the OTHER tier's answer no more than in this one: it is
    // not a question of which tier asks, it is that there is no link to find.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + other + "/links")
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("svc-links-nowhere")));
  }

  @Test
  void aBrandNewEnvironmentHoldsNothingUntilSomethingIsLinkedIntoIt() {
    // Was `aBrandNewEnvironmentAlreadyHoldsEveryPlatformService`, and this is the sharpest reversal
    // in the change: the old test's comment read "Created after the platform service, linked to
    // nothing, and it has it. That is the whole reason a platform service has no links." A tier came
    // up already holding qits-idp and the rest of the plane, with nothing written and nobody having
    // decided anything — presence by absence.
    //
    // A fresh tier holds NOTHING. "Present everywhere by being linked nowhere" is exactly what was
    // deleted, so a service that exists and is linked to no environment is invisible to a brand-new
    // environment's link query, and stays invisible until something links it in. The cost is that a
    // new tier is an explicit registration away from being useful; the gain is that the link query
    // has one source, so what a tier holds is what somebody stated it holds.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"main\",\"availableOnEnv\":false,\"environmentIds\":[]}")
        .when()
        .put(SERVICES + "/svc-preexisting-unlinked")
        .then()
        .statusCode(201);

    String fresh = createEnvironment("svc-fresh-env");
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + fresh + "/links")
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("svc-preexisting-unlinked")));

    // ...and one write is all it takes to put it there, which is the whole of what replaced the
    // plane: a link, stated.
    upsertService("svc-preexisting-unlinked", 200, fresh);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + fresh + "/links")
        .then()
        .statusCode(200)
        .body("services.name", hasItem("svc-preexisting-unlinked"));
  }

  @Test
  void theLinkQueryOfAnUnknownEnvironmentIsNotFound() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(ENVIRONMENTS + "/no-such-id/links").then().statusCode(404);
  }

  @Test
  void deletingAServiceTakesItsLinksWithIt() {
    String env = createEnvironment("svc-delete-env");
    upsertService("svc-delete", 201, env);
    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(SERVICES + "/svc-delete").then().statusCode(204);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ENVIRONMENTS + "/" + env + "/links")
        .then()
        .body("services.name", not(hasItem("svc-delete")));
    given()
        .header("Authorization", SuiteBearers.operator())
        .when().get(SERVICES).then().body("services.name", not(hasItem("svc-delete")));
  }

  @Test
  void deletingAnUnknownServiceIsNotFound() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .when().delete(SERVICES + "/no-such-service").then().statusCode(404);
  }

  private static String createEnvironment(String name) {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + name + "\"}")
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  /**
   * Register one service into the environments named — the only registration there is. It was
   * {@code upsertEnvironmentService} while a second fixture registered the other shape of row; one
   * shape needs no qualifier, and a name that still carried it would read as a choice.
   */
  private static String upsertService(String name, int expectedStatus, String... environmentIds) {
    String ids =
        String.join(",", Arrays.stream(environmentIds).map(id -> "\"" + id + "\"").toList());
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false,\"environmentIds\":[" + ids + "]}")
        .when()
        .put(SERVICES + "/" + name)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .path("service.id");
  }
}
