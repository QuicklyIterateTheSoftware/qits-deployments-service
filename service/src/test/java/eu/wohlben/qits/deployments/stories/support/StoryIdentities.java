package eu.wohlben.qits.deployments.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two identity tracks this component accepts, one helper each — because a story presenting the
 * wrong one would be documenting a door that does not exist.
 *
 * <h2>The two role sets do not overlap, and that is the whole design</h2>
 *
 * <ul>
 *   <li><b>{@code qits:system} is a MACHINE's</b>, and it arrives only in an idp-minted
 *       bearer: qits-platform-idp copies a client's granted roles into the token's {@code groups}
 *       claim and quarkus-oidc reads it as roles with no configuration at all. It opens the build
 *       intake, every topology write, the rollback pins and the deployment-request listing
 *       qits-projects draws a release's deploy phase from.
 *   <li><b>{@code qits:admin} is a PERSON's</b>, and it arrives in a signed-in person's bearer
 *       from the same idp (forward-auth's {@code X-Qits-User} / {@code X-Qits-Roles} pair leaves
 *       qits-auth-core with qits-1338, epic qits-543). It opens every read of this surface — the
 *       applications, the services, the tiers and the deployment listing.
 * </ul>
 *
 * <p>A machine token never carries the admin role and a browser session never carries the system
 * one, so each set refuses the other's doors with a 403. {@code stories.refusals} is where that is
 * shown rather than described.
 *
 * <p><b>The synthetic {@code %test} dev user is not available here, and that is the point.</b>
 * {@code qits-auth-core}'s dev identity holds every platform role and is {@code LaunchMode}-guarded,
 * while a launched artifact runs in {@code NORMAL} mode — so an anonymous request really is
 * anonymous and the credentials below are the only thing opening these doors. Every refusal in this
 * catalogue is a claim only a packaged run with {@code qits.auth.machine.required=true} can make.
 *
 * <p>Minting is local crypto against the keypair {@link MockIdp} parked at startup: it makes no
 * request to the mock at all, which is why no story's diagram carries an arrow for getting a token.
 * The one token that <i>is</i> fetched over the wire is the deployer's own, outbound — see {@link
 * StoryPeers}.
 */
public final class StoryIdentities {

  /**
   * The one audience this platform mints, and the one {@code quarkus.oidc.token.audience} accepts.
   * It is spelled out as a literal in {@code application.properties} and repeated as a literal here,
   * so the audience under test is the shipped one and there is no expression to feed. Every token
   * carries it — a service's, a bootstrap's and a person's CLI token alike — and what a caller may
   * do is decided by its roles.
   */
  public static final String AUDIENCE = "qits-platform";

  /** The machine role: the intake, the topology writes and the pins. */
  public static final String MACHINE_ROLE = "qits:system";

  /** The person's role, which reaches this service in a signed-in person's bearer. */
  public static final String HUMAN_ROLE = "qits:admin";

  /** The machine this catalogue is mostly told from: qits-ci, whose green build starts everything. */
  public static final String CI = "qits-ci";

  /** The pin ledger's one real caller: qits-platform-artifacts' OCI garbage collector. */
  public static final String COLLECTOR = "qits-platform-artifacts";

  /** The person reading the platform's deployment history through the client. */
  public static final String OPERATOR = "an operator's session";

  /** Who a refused caller is, on the diagram. Direction is who initiated, and this one did. */
  public static final String IMPOSTOR = "an impostor";

  private StoryIdentities() {}

  /**
   * A platform peer's bearer.
   *
   * <p>Minted fresh per call rather than cached: a token is a credential, and a helper that handed
   * the same string to two stories would make {@link
   * eu.wohlben.qits.userflows.report.ReportAssertions#assertNotLeaked} a weaker claim than it reads
   * as.
   */
  public static String machineToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(AUDIENCE)
        .groups(MACHINE_ROLE)
        .mint();
  }

  /**
   * A signed-in person's token: the person's role, minted by the idp. At a machine door it
   * authenticates perfectly and covers nothing, so it is 403 rather than 401 — the difference
   * between "who are you" and "you may not", which is how a missing grant is told from a missing
   * token.
   */
  public static String adminRoleToken(String subject) {
    return MockIdp.attach().token().subject(subject).audience(AUDIENCE).groups(HUMAN_ROLE).mint();
  }

  /** A token minted for a real sibling's audience — the confusion that could happen on qits-net. */
  public static String foreignAudienceToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience("qits-configuration")
        .groups(MACHINE_ROLE)
        .mint();
  }

  /** {@code given()} with one machine peer's bearer on it. */
  public static RequestSpecification machine(RequestSpecification request, String subject) {
    return bearer(request, machineToken(subject));
  }

  /** {@code given()} with a bearer a story minted itself and wants to assert about afterwards. */
  public static RequestSpecification bearer(RequestSpecification request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }

  /** {@code given()} with a signed-in admin's bearer. */
  public static RequestSpecification person(RequestSpecification request, String user) {
    return person(request, user, HUMAN_ROLE);
  }

  /**
   * …and a signed-in person holding some other role (comma-separated), which is how a 403 is asked
   * for.
   */
  public static RequestSpecification person(
      RequestSpecification request, String user, String roles) {
    String[] groups =
        java.util.Arrays.stream(roles.split(","))
            .map(String::trim)
            .filter(role -> !role.isEmpty())
            .toArray(String[]::new);
    return bearer(
        request,
        MockIdp.attach().token().subject(user).audience(AUDIENCE).groups(groups).mint());
  }
}
