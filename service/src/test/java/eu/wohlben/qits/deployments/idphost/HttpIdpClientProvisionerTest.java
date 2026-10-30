package eu.wohlben.qits.deployments.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.IdpClientProvisioner;
import eu.wohlben.qits.deployments.deployments.control.ResourceException;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sole production implementation of {@link IdpClientProvisioner}, against a real socket —
 * {@code GitHostSpecSourceTest}'s arrangement and its reason: what is under test IS the
 * request — the url it is built at, the Basic header it carries, the status map, and the patience
 * it spends. A fake at the seam would assert this suite's own model of a client, which is exactly
 * what {@link eu.wohlben.qits.deployments.deployments.control.FakeIdpClientProvisioner} is
 * for everywhere else.
 */
class HttpIdpClientProvisionerTest {

  private static final String CLIENT_ID = "dev-qits-ci";

  private IdpStub idp;

  @BeforeEach
  void start() {
    idp = new IdpStub();
  }

  @AfterEach
  void stop() {
    idp.close();
  }

  private HttpIdpClientProvisioner adapter() {
    return idp.adapter("qits-deployments", "this-components-own-secret");
  }

  // --- the urls -----------------------------------------------------------------------------

  @Test
  void presenceReadsTheClientIdSegmentWithAGet() {
    idp.answers(200, "{\"clientId\":\"" + CLIENT_ID + "\",\"source\":\"database\"}");

    boolean present = adapter().databaseClientPresent(CLIENT_ID);

    assertTrue(present);
    assertEquals(List.of("GET"), idp.methods());
    assertEquals(List.of("/idp/api/service-clients/" + CLIENT_ID), idp.paths());
  }

  @Test
  void createPostsToTheBareCollectionWithTheClientIdInTheBody() {
    idp.answers(201, "{\"clientId\":\"" + CLIENT_ID + "\",\"secret\":\"a-fresh-secret\"}");

    IdpClientProvisioner.Result result = adapter().create(CLIENT_ID);

    assertTrue(result.ok());
    assertEquals("a-fresh-secret", result.secret());
    assertEquals(List.of("POST"), idp.methods());
    assertEquals(List.of("/idp/api/service-clients"), idp.paths());
    assertTrue(idp.bodies().get(0).contains("\"clientId\":\"" + CLIENT_ID + "\""), idp.bodies().get(0));
  }

  @Test
  void rotatePostsToTheClientsOwnSecretSubPath() {
    idp.answers(200, "{\"clientId\":\"" + CLIENT_ID + "\",\"secret\":\"a-rotated-secret\"}");

    IdpClientProvisioner.Result result = adapter().rotate(CLIENT_ID);

    assertTrue(result.ok());
    assertEquals("a-rotated-secret", result.secret());
    assertEquals(List.of("POST"), idp.methods());
    assertEquals(List.of("/idp/api/service-clients/" + CLIENT_ID + "/secret"), idp.paths());
  }

  // --- the Basic header -----------------------------------------------------------------------

  @Test
  void everyCallCarriesThisComponentsOwnPairAsBasicAuth() {
    idp.answers(200, "{\"clientId\":\"" + CLIENT_ID + "\",\"source\":\"database\"}");

    adapter().databaseClientPresent(CLIENT_ID);

    String header = idp.authorizations().get(0);
    assertTrue(header != null && header.startsWith("Basic "), header);
    String decoded =
        new String(Base64.getDecoder().decode(header.substring("Basic ".length())));
    assertEquals("qits-deployments:this-components-own-secret", decoded);
  }

  @Test
  void anAbsentOwnPairSendsAnEmptyBasicHeaderRatherThanNoHeaderAtAll() {
    // Never no header at all — an unauthenticated request would be answered differently by a real
    // idp, and this adapter must not silently try one.
    idp.answers(200, "{\"clientId\":\"" + CLIENT_ID + "\",\"source\":\"database\"}");
    HttpIdpClientProvisioner adapter = idp.adapter(null, null);

    adapter.databaseClientPresent(CLIENT_ID);

    String header = idp.authorizations().get(0);
    assertEquals("Basic " + Base64.getEncoder().encodeToString(":".getBytes()), header);
  }

  // --- the status map ---------------------------------------------------------------------------

  @Test
  void presenceAnswersFalseForA404AndForAnEnvironmentOnlyClient() {
    idp.answers(404, "");
    assertFalse(adapter().databaseClientPresent(CLIENT_ID));

    idp.answers(200, "{\"clientId\":\"" + CLIENT_ID + "\",\"source\":\"environment\"}");
    assertFalse(
        adapter().databaseClientPresent(CLIENT_ID), "environment-only is not a database client");

    idp.answers(200, "{\"clientId\":\"" + CLIENT_ID + "\",\"source\":\"both\"}");
    assertTrue(adapter().databaseClientPresent(CLIENT_ID));
  }

  @Test
  void createOnA409AnswersAConflictWithNoSecretAndNoRetry() {
    idp.answers(409, "{\"error\":\"already exists\"}");

    IdpClientProvisioner.Result result = adapter().create(CLIENT_ID);

    assertFalse(result.ok());
    assertTrue(result.conflict());
    assertNull(result.secret());
    assertEquals(1, idp.requestCount(), "a 409 is read, never retried");
  }

  @Test
  void rotateOnA404AnswersNotOkAndNotAConflict() {
    idp.answers(404, "");

    IdpClientProvisioner.Result result = adapter().rotate(CLIENT_ID);

    assertFalse(result.ok());
    assertFalse(result.conflict());
    assertNull(result.secret());
    assertEquals(1, idp.requestCount());
  }

  @Test
  void anUnexpectedStatusOnPresenceIsARefusalNamingIt() {
    idp.answers(500, "internal");

    ResourceException refused =
        assertThrows(ResourceException.class, () -> adapter().databaseClientPresent(CLIENT_ID));
    assertTrue(refused.getMessage().contains(CLIENT_ID), refused.getMessage());
  }

  // --- the budget -------------------------------------------------------------------------------

  @Test
  void a5xxIsRetriedWithinTheBudgetAndThenGivesUp() {
    idp.answers(503, "unavailable");

    assertThrows(ResourceException.class, () -> adapter().databaseClientPresent(CLIENT_ID));

    assertEquals(2, idp.requestCount(), "the stub's own attempts budget, spent in full");
  }

  @Test
  void aFailureThenASuccessWithinTheBudgetSucceeds() {
    idp.then(503, "unavailable")
        .answers(200, "{\"clientId\":\"" + CLIENT_ID + "\",\"source\":\"database\"}");

    assertTrue(adapter().databaseClientPresent(CLIENT_ID));
    assertEquals(2, idp.requestCount());
  }

  @Test
  void a4xxOtherThanTheKnownOnesIsNeverRetried() {
    // create/rotate never throw — they answer a Result the domain layer decides on, ResourceProvisioning's
    // arrangement — so this asserts not-ok and, above all, that the budget was not spent on it.
    idp.answers(400, "bad request");

    IdpClientProvisioner.Result result = adapter().rotate(CLIENT_ID);

    assertFalse(result.ok());
    assertFalse(result.conflict());
    assertEquals(1, idp.requestCount(), "a 400 will not parse differently on a second attempt");
  }

  // --- secret validation --------------------------------------------------------------------

  @Test
  void anEmptySecretIsRefused() {
    idp.answers(201, "{\"clientId\":\"" + CLIENT_ID + "\",\"secret\":\"\"}");

    ResourceException refused =
        assertThrows(ResourceException.class, () -> adapter().create(CLIENT_ID));
    assertFalse(refused.getMessage().toLowerCase().contains("secret=\"\""), refused.getMessage());
  }

  @Test
  void aSecretWithAControlCharacterIsRefusedNotEscaped() {
    idp.answers(201, "{\"clientId\":\"" + CLIENT_ID + "\",\"secret\":\"bad\\u0007secret\"}");

    ResourceException refused =
        assertThrows(ResourceException.class, () -> adapter().create(CLIENT_ID));
    assertFalse(refused.getMessage().contains("bad"), "the value itself is never echoed");
  }

  @Test
  void aSecretOverTheColumnWidthIsRefused() {
    idp.answers(
        201, "{\"clientId\":\"" + CLIENT_ID + "\",\"secret\":\"" + "s".repeat(200) + "\"}");

    assertThrows(ResourceException.class, () -> adapter().create(CLIENT_ID));
  }

  @Test
  void aWellFormedSecretIsReturnedVerbatim() {
    idp.answers(201, "{\"clientId\":\"" + CLIENT_ID + "\",\"secret\":\"perfectly-fine-32-hex\"}");

    assertEquals("perfectly-fine-32-hex", adapter().create(CLIENT_ID).secret());
  }

  // --- delete ------------------------------------------------------------------------------

  @Test
  void deleteSendsADeleteToTheClientIdSegmentAndA204IsDone() {
    idp.answers(204, "");

    IdpClientProvisioner.Result result = adapter().delete(CLIENT_ID);

    assertTrue(result.ok());
    assertNull(result.secret(), "a delete hands no secret back");
    assertNull(result.detail());
    assertEquals(List.of("DELETE"), idp.methods());
    assertEquals(List.of("/idp/api/service-clients/" + CLIENT_ID), idp.paths());
    assertTrue(idp.authorizations().get(0).startsWith("Basic "), "the same Basic pair as every call");
  }

  @Test
  void deleteAnswered200IsDone() {
    idp.answers(200, "{}");

    assertTrue(adapter().delete(CLIENT_ID).ok());
  }

  @Test
  void deleteOfAClientAlreadyGoneIsDoneToo() {
    // 404 is the state a delete asks for, so a retry after a lost answer converges.
    idp.answers(404, "");

    IdpClientProvisioner.Result result = adapter().delete(CLIENT_ID);

    assertTrue(result.ok());
    assertFalse(result.conflict());
    assertEquals(1, idp.requestCount(), "a 404 is an answer, never retried");
  }

  @Test
  void deleteThatKeepsAnswering5xxIsAFailureNamingTheClientNotAThrow() {
    idp.answers(500, "{\"error\":\"boom\"}");

    IdpClientProvisioner.Result result = adapter().delete(CLIENT_ID);

    assertFalse(result.ok());
    assertNull(result.secret());
    assertTrue(result.detail().contains(CLIENT_ID), result.detail());
    assertTrue(result.detail().contains("500"), result.detail());
    assertEquals(2, idp.requestCount(), "the budget is spent before giving up");
  }

  @Test
  void deleteRefusedWithAnotherStatusIsAFailureWithTheStatusAndNoRetry() {
    idp.answers(403, "{\"error\":\"forbidden\"}");

    IdpClientProvisioner.Result result = adapter().delete(CLIENT_ID);

    assertFalse(result.ok());
    assertTrue(result.detail().contains("403"), result.detail());
    assertTrue(result.detail().contains("forbidden"), result.detail());
    assertEquals(1, idp.requestCount());
  }
}
