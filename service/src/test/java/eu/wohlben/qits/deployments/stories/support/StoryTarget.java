package eu.wohlben.qits.deployments.stories.support;

import eu.wohlben.qits.userflows.Labels;

/**
 * The one launched process, addressed the way every one of its surfaces is addressed — and named
 * the way a diagram names it.
 *
 * <p>{@code quarkus.rest.path=/deployments/api} is the JSON API and {@code
 * quarkus.http.non-application-root-path=/deployments/q} is what Quarkus itself serves, so
 * the framework's shipped RestAssured tap — which skips any path carrying a {@code /q/}
 * <i>segment</i> rather than a leading one — is exactly right here and no story class overrides the
 * predicate. That is the line to re-read when this class is copied: a service whose probe root is
 * {@code /q} rather than {@code /<segment>/q} would need a different check.
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths, and RestAssured is
 * handed the port by the Quarkus integration-test extension. Only {@link StoryPlatform}'s
 * tap-invisible fixture client builds a url of its own, and it reads {@code RestAssured.port} for
 * exactly that reason.
 *
 * <p><b>Every tier, application, project and commit a story uses is a stable literal</b>, never a
 * run stamp. A name is a whole path segment and {@link Labels} rewrites only segments it can tell
 * were generated (a uuid, a long hex run, a bare number); {@code story-web} is none of those and
 * would survive into a label exactly as written, so a stamped one would move every {@code
 * networkHash} on every run. The commit shas <b>are</b> long hex and are rewritten — which is the
 * point: a sha is generated, and the git host's blob url is template-shaped because of it.
 *
 * <p><b>A query string never reaches a label from the shipped tap.</b> {@code
 * NetworkTaps.restAssured} labels {@code METHOD <scrubbed PATH> -> <status>} and drops the query
 * entirely. The deployment listing's {@code ?environmentId=<uuid>} is the one genuinely run-local
 * value a story of this catalogue sends, and it is invisible to the diagram for that reason rather
 * than because anything here templated it. The corollary is the trap: two routes differing only in
 * their query are ONE edge, so a story that wanted them apart has to address different paths.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-platform-deployments";

  /** {@code /deployments/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/deployments/api";

  /** The release intake: the manual and bootstrap door a released version is announced through. */
  public static final String SOFTWARE_RELEASED_PATH = API_PATH + "/events/software-released";

  /** The tiers. A write is a machine's; the listing is a person's. */
  public static final String ENVIRONMENTS_PATH = API_PATH + "/environments";

  /** Every application this component deploys, both planes flattened into one list. */
  public static final String APPLICATIONS_PATH = API_PATH + "/applications";

  /** The service catalogue — the same data, one row per service with its link set. */
  public static final String SERVICES_PATH = API_PATH + "/services";

  /** One plane's recorded deployments. The environment is a required filter, never a path segment. */
  public static final String DEPLOYMENTS_PATH = API_PATH + "/deployments";

  /** The image shas a garbage collection must not delete — qits-platform-artifacts' read. */
  public static final String PINS_PATH = API_PATH + "/pins";

  /**
   * The tier every story in this catalogue deploys into — the platform's <b>entry tier</b>, which
   * is what branch matching was replaced by: a release lands in the designated platform
   * environment.
   *
   * <p>One tier for the whole catalogue rather than one per class: the launched process is one, its
   * database is one, and a story about "what is deployed here" is more honest against a platform
   * that has several applications on one tier than against four tiers holding one each.
   */
  public static final String TIER = "story-tier";

  /** The project every story repository belongs to — half of the public {@code (project, repo)} pair. */
  public static final String PROJECT = "qits";

  private StoryTarget() {}

  /** The wire alias — and, under swarm, the service NAME — of one application in {@link #TIER}. */
  public static String wireAlias(String applicationName) {
    return TIER + "-" + applicationName;
  }

  /** The image reference this component derives for one application at one released version. */
  public static String imageRef(String applicationName, String version) {
    return "registry.qits.dev.localhost:8080/qits/" + applicationName + ":" + version;
  }
}
