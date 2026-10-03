package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * The whole guarded surface with the gate on — the posture a deployment reaches by setting {@code
 * QITS_AUTH_MACHINE_REQUIRED=true} once qits-platform-idp is minting the {@code qits-platform}
 * audience.
 *
 * <p>Tokens are real: signed RS256, verified by quarkus-oidc against the public key in {@link
 * MachineGuardEnforcedProfile}. So these tests fail if the OIDC configuration in
 * application.properties is wrong, not only if a guard is missing.
 *
 * <p><b>Nothing here is open any more, and that is the change this suite exists to record.</b> The
 * surface used to be split into guarded writes and open reads; every endpoint carries a {@code
 * @RolesAllowed} now, and the role is what says who the caller is meant to be:
 *
 * <ul>
 *   <li><b>{@code qits:admin}</b> — the reads. It reaches this service only through the
 *       forwarded {@code X-Qits-Roles} header: the platform edge asserts it for an authenticated
 *       admin session, and the bootstrap asserts it on its own hop over qits-net. A machine token
 *       never carries it, which is the point — the read surface is a person's.
 *   <li><b>{@code qits:system}</b> — the pins, the topology writes, the build-succeeded intake and
 *       the deployment-request listing qits-projects draws a release's deploy phase from.
 *       qits-platform-idp copies it into every platform service client's {@code groups} claim, so a
 *       machine bearer carries it and a browser session does not.
 * </ul>
 *
 * <p><b>Three doors now, and this suite pins which shuts first.</b> A token minted for another
 * service is refused by {@code quarkus.oidc.token.audience} before any identity is built, so the
 * answer is a 401 challenge. A token addressed here but granted no roles authenticates and is
 * refused 403 by {@code @RolesAllowed}. {@link eu.wohlben.qits.auth.MachineAuth} is the third and
 * innermost, re-asking the audience question the token already passed — belt and braces, because
 * the annotation and the guard fail independently.
 */
@QuarkusTest
@TestProfile(MachineGuardEnforcedProfile.class)
class MachineGuardEnforcedTest {

  /**
   * The one audience this platform mints and the one {@code quarkus.oidc.token.audience} accepts. A
   * literal, because the shipped properties state a literal: what a caller may do is decided by its
   * roles, and never by which service its token was addressed to.
   */
  private static final String AUDIENCE = "qits-platform";

  private static final String ENVIRONMENTS = "/deployments/api/environments";
  private static final String SERVICES = "/deployments/api/services";
  private static final String INTAKE = "/deployments/api/events/software-released";
  private static final String PINS = "/deployments/api/pins";
  private static final String CLAIMS = "/deployments/api/claims/idp-clients";
  private static final String REQUESTS = "/deployments/api/deployment-requests";

  /** An application nothing here ever deployed — the operator levers' own three paths. */
  private static final String APPLICATION = "/deployments/api/applications/platform:guarded-app";

  private static final String SCALE = APPLICATION + "/scale";
  private static final String RESTART = APPLICATION + "/restart";
  private static final String DECOMMISSION = APPLICATION + "/decommission";

  private static final String ENVIRONMENT_BODY = "{\"name\":\"guarded-env\"}";
  private static final String SERVICE_BODY =
      "{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}";
  private static final String EVENT =
      """
      {"repoId":"guarded-repo","version":"2026.903.193059"}
      """;

  // --- no credential at all: 401 everywhere -----------------------------------------------------

  @Test
  void theIntakeWithNoTokenIsRefused() {
    // This is the exact call a release replay makes, and it stops working the moment the gate is
    // on — which is why the sender has to be holding a credential before a deployment flips it.
    given().contentType(ContentType.JSON).body(EVENT).when().post(INTAKE).then().statusCode(401);
  }

  @Test
  void creatingAnEnvironmentWithNoTokenIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(ENVIRONMENT_BODY)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(401);
  }

  @Test
  void patchingAnEnvironmentWithNoTokenIsRefused() {
    // The guard runs before the lookup, so an unknown id still answers 401 rather than 404 — which
    // is the right order: an unauthenticated caller learns nothing about what exists.
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"guarded-rename\"}")
        .when()
        .patch(ENVIRONMENTS + "/whatever")
        .then()
        .statusCode(401);
  }

  @Test
  void deletingAnEnvironmentWithNoTokenIsRefused() {
    given().when().delete(ENVIRONMENTS + "/whatever").then().statusCode(401);
  }

  @Test
  void upsertingAServiceWithNoTokenIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(SERVICE_BODY)
        .when()
        .put(SERVICES + "/guarded-none")
        .then()
        .statusCode(401);
  }

  @Test
  void deletingAServiceWithNoTokenIsRefused() {
    given().when().delete(SERVICES + "/guarded-none").then().statusCode(401);
  }

  @Test
  void everyReadWithNoCredentialIsRefused() {
    // The reads were open until the surface was protected. They are a person's now, and an
    // anonymous caller is challenged rather than served.
    given().when().get(ENVIRONMENTS).then().statusCode(401);
    given().when().get(SERVICES).then().statusCode(401);
    given().when().get("/deployments/api/applications").then().statusCode(401);
    given().when().get("/deployments/api/deployments?environmentId=whatever").then().statusCode(401);
    given().when().get(PINS).then().statusCode(401);
    given().when().get(CLAIMS).then().statusCode(401);
  }

  // --- a token minted for another service: refused at validation --------------------------------

  @Test
  void aTokenMintedForAnotherServiceIsRefusedOnEveryWrite() {
    given()
        .contentType(ContentType.JSON)
        .header(
            "Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-platform-artifacts"))
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .header(
            "Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-platform-artifacts"))
        .body(ENVIRONMENT_BODY)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .header(
            "Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-platform-artifacts"))
        .body(SERVICE_BODY)
        .when()
        .put(SERVICES + "/guarded-wrong-aud")
        .then()
        .statusCode(401);
  }

  // --- a token addressed here but granted no roles: the second door -----------------------------

  @Test
  void aTokenGrantedNoRolesIsRefusedOnEveryGuardedCall() {
    // A client id in qits.idp.clients with no `.roles` line beside it mints exactly this: correctly
    // signed, correctly addressed, empty `groups`. It authenticates and covers nothing, which is a
    // 403 rather than the 401 an absent token gets — the distinction an operator needs to tell a
    // missing grant from a missing sender.
    String roleless = "Bearer " + MachineTokens.rolelessToken("qits-ci", AUDIENCE);

    given()
        .contentType(ContentType.JSON)
        .header("Authorization", roleless)
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        .statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", roleless)
        .body(ENVIRONMENT_BODY)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(403);
    given().header("Authorization", roleless).when().get(PINS).then().statusCode(403);
    given().header("Authorization", roleless).when().get(CLAIMS).then().statusCode(403);
  }

  // --- the right token: every machine-facing call goes through ----------------------------------

  @Test
  void theIntakeAcceptsATokenMintedForThisService() {
    machine()
        .contentType(ContentType.JSON)
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        // 202 and nothing deploys: nothing is registered for this repository, which is the
        // intake's normal answer. What is asserted is that the guard let the caller through.
        .statusCode(202);
  }

  @Test
  void everyTopologyWriteAcceptsATokenMintedForThisService() {
    String environmentId =
        machine()
            .contentType(ContentType.JSON)
            .body(ENVIRONMENT_BODY)
            .when()
            .post(ENVIRONMENTS)
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    machine()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"guarded-renamed\"}")
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.name", equalTo("guarded-renamed"));

    machine()
        .contentType(ContentType.JSON)
        .body(
            "{\"deploymentTarget\":\"ENVIRONMENT\",\"availableOnEnv\":false,"
                + "\"environmentIds\":[\""
                + environmentId
                + "\"]}")
        .when()
        .put(SERVICES + "/guarded-service")
        .then()
        .statusCode(201);

    machine().when().delete(SERVICES + "/guarded-service").then().statusCode(204);
    machine().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);
  }

  @Test
  void aMachineBearerPassesEveryMachineDoor() {
    // One sweep over the whole machine half with the credential a platform service client actually
    // holds: the intake, both topology writes, the pins and both deletes, in one caller's hands.
    String machineBearer = "Bearer " + MachineTokens.token("qits-ci", AUDIENCE);

    given()
        .contentType(ContentType.JSON)
        .header("Authorization", machineBearer)
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        .statusCode(202);

    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .header("Authorization", machineBearer)
            .body("{\"name\":\"guarded-machine\"}")
            .when()
            .post(ENVIRONMENTS)
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    given()
        .contentType(ContentType.JSON)
        .header("Authorization", machineBearer)
        .body(SERVICE_BODY)
        .when()
        .put(SERVICES + "/guarded-machine")
        .then()
        .statusCode(201);

    given().header("Authorization", machineBearer).when().get(PINS).then().statusCode(200);
    given().header("Authorization", machineBearer).when().get(CLAIMS).then().statusCode(200);

    given()
        .header("Authorization", machineBearer)
        .when()
        .delete(SERVICES + "/guarded-machine")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", machineBearer)
        .when()
        .delete(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(204);
  }

  @Test
  void qitsAdminInAMachineTokensGroupsIsStillRefusedOnEveryMachineOnlyDoor() {
    // The two sets do not overlap. `@RolesAllowed` reads the groups claim regardless of transport —
    // a bearer naming qits:admin passes an admin-only read exactly as a forwarded header would,
    // which is why this asserts the machine-ONLY surface (the intake, the pins) rather than the
    // reads: a correctly signed, correctly addressed token whose groups claim names only qits:admin
    // has no qits:system, and is refused 403 there.
    String adminGroups =
        "Bearer " + MachineTokens.adminGroupsToken("qits-ci", AUDIENCE);
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", adminGroups)
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        .statusCode(403);
    given().header("Authorization", adminGroups).when().get(PINS).then().statusCode(403);
    given().header("Authorization", adminGroups).when().get(CLAIMS).then().statusCode(403);
  }

  @Test
  void thePinListingAnswersTheGarbageCollectorsMachineToken() {
    // qits-platform-artifacts plans its OCI sweep fail-closed on this answer, so what it presents
    // has to be a credential it can actually hold: its idp client is granted this service's
    // audience and the two system roles, which is exactly the token minted here.
    given()
        .header(
            "Authorization",
            "Bearer "
                + MachineTokens.token("qits-platform-artifacts", AUDIENCE))
        .when()
        .get(PINS)
        .then()
        .statusCode(200);
  }

  @Test
  void theClaimsEndpointAnswersTheSameThreeDoorsThePinsDo() {
    // qits-idp's own garbage collection for service clients unused for a while is the one caller,
    // and it is the pins door's own shape: a token minted for another service never reaches
    // identity (401, covered above), a correctly addressed token with no roles authenticates and is
    // refused (403, covered above), and the right token — minted here for qits-ci, which is standing
    // in for qits-idp's client — goes through.
    given()
        .header("Authorization", "Bearer " + MachineTokens.token("qits-ci", AUDIENCE))
        .when()
        .get(CLAIMS)
        .then()
        .statusCode(200);
  }

  // --- the reads are a person's, and only a person's --------------------------------------------

  @Test
  void everyReadAnswersThePlatformAdminAndRefusesAMachine() {
    String environmentId =
        machine()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"guarded-readable\"}")
            .when()
            .post(ENVIRONMENTS)
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    // The headers the edge asserts for an admin session, and the ones the bootstrap asserts on its
    // own hop. Nothing mints them as a token: this is the user track, start to finish.
    admin().when().get(ENVIRONMENTS).then().statusCode(200);
    admin().when().get(ENVIRONMENTS + "/" + environmentId).then().statusCode(200);
    admin().when().get(ENVIRONMENTS + "/" + environmentId + "/links").then().statusCode(200);
    admin().when().get(SERVICES).then().statusCode(200);
    admin().when().get("/deployments/api/applications").then().statusCode(200);
    admin()
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200);

    // A machine holds qits:system and never qits:admin, so the token that just
    // created the environment cannot read it back. That asymmetry is the contract, not an oversight.
    machine().when().get(ENVIRONMENTS).then().statusCode(403);
    machine().when().get(SERVICES).then().statusCode(403);

    machine().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);
  }

  @Test
  void theDeploymentRequestListingAnswersAMachinePeerAndThePersonBoth() {
    // qits-projects draws a release request as one pipeline of three phases and reads the third —
    // the deployment request for (repoId, version) — from this listing. It is a platform peer
    // rather than a person or an agent, so its service client carries qits:system: without the
    // grant its only way in would be to forward somebody's session headers.
    String environmentId =
        machine()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"guarded-requests\"}")
            .when()
            .post(ENVIRONMENTS)
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    machine()
        .when()
        .get(REQUESTS + "?repoId=guarded-repo&version=2026.903.193059")
        .then()
        .statusCode(200);
    machine().when().get(REQUESTS + "?environmentId=" + environmentId).then().statusCode(200);

    // ...and nothing was taken away: the person who polls this listing in the client still reads it.
    admin().when().get(REQUESTS + "?environmentId=" + environmentId).then().statusCode(200);

    // The grant is this listing's alone. The rest of the read surface is still a person's, and so
    // are the operator's levers — the two role sets do not overlap, and this changed who may ask
    // one read rather than what a machine bearer reaches.
    machine().when().get(ENVIRONMENTS).then().statusCode(403);
    machine()
        .when()
        .get("/deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(403);

    machine().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);
  }

  // --- the operator's levers are a person's too -------------------------------------------------

  @Test
  void theScaleAndRestartDoorsAreRefusedWithNoCredentialAndToAMachine() {
    // They are WRITES on the read surface's role, and that is the decision they carry: stopping an
    // application is a person's operational action, driven from this component's own client through
    // the edge's forwarded header. A service bearer opens every machine door here and must not be
    // able to take an application down as a side effect of holding one.
    given()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(SCALE)
        .then()
        .statusCode(401);
    given().when().post(RESTART).then().statusCode(401);

    machine()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(SCALE)
        .then()
        .statusCode(403);
    machine().when().post(RESTART).then().statusCode(403);

    // ...and the admin, who holds the grant, is let through to the answer the resolution gives:
    // nothing has ever been deployed for this application, which is a 404 and not a refusal.
    admin()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(SCALE)
        .then()
        .statusCode(404);
    admin().when().post(RESTART).then().statusCode(404);
  }

  @Test
  void theDecommissionDoorIsAPersonsToo() {
    // It writes no docker call, but it writes the word an operator reads as an application's state
    // — and a service bearer must no more be able to declare an application retired than to stop
    // one. Same grant, same asymmetry, asserted in both directions.
    given().when().post(DECOMMISSION).then().statusCode(401);
    machine().when().post(DECOMMISSION).then().statusCode(403);
    admin().when().post(DECOMMISSION).then().statusCode(404);
  }

  /** A caller with a fresh token minted for this service, carrying the machine roles the idp grants. */
  private static RequestSpecification machine() {
    return given()
        .header(
            "Authorization",
            "Bearer " + MachineTokens.token("qits-ci", AUDIENCE));
  }

  /** A platform admin, as the edge and the bootstrap assert one. */
  private static RequestSpecification admin() {
    return given()
        .header("X-Qits-User", "qits-bootstrap")
        .header("X-Qits-Roles", "qits:admin");
  }
}
