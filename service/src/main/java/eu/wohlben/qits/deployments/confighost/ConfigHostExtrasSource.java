package eu.wohlben.qits.deployments.confighost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.DeploymentExtrasSource;
import eu.wohlben.qits.deployments.deployments.control.ExtrasSnapshot;
import eu.wohlben.qits.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link DeploymentExtrasSource}: the config volume's file,
 * or — where a deployment names one — qits-configuration <b>instead of it</b>.
 *
 * <pre>
 * GET &lt;extras-url&gt;/configuration/api/applications/&lt;app&gt;/envs/&lt;env&gt;/resolved?version=&lt;version&gt;
 *   → {"headRevision": 7, "properties": {"qits.deployments.extras.&lt;app&gt;.&lt;key&gt;": "…"}}
 * </pre>
 *
 * <p><b>The read is addressed by the PLACE and the RELEASE now, and the body is the same body.</b>
 * It used to name the application alone, which could only ever describe one configuration per
 * platform; a tier segment and a version parameter are what make it possible for {@code dev} and
 * {@code prod} to differ and for a rollback to bring back the configuration that version was
 * released with. Nothing about the answer changed — same document, same {@code headRevision} and
 * {@code properties}, same single parser one layer up.
 *
 * <p><b>...but the version is asked for only when the release SEEDED one.</b> The parameter is what
 * turns this into the OVERLAY read, and the store answers 404 for a version it holds no declaration
 * for — which is right, because "resolve me against this document" cannot be answered with a
 * document that is not there. The deployer seeds a declaration only for a release whose tag carries
 * {@code .config/qits/configuration.yml}, and almost no repository has that file yet, so passing the
 * parameter unconditionally refused every deployment of every undeclared repository. Those read the
 * version-less form the store documents as the transitional one, with a WARN naming them — see
 * {@link #versionlessBecauseNothingWasDeclared}.
 *
 * <p><b>{@code qits.deployments.extras-url} unset is today's behaviour byte for byte.</b>
 * No request is made, nothing is parsed, and the answer is {@link ExtrasSnapshot#over(Config,
 * String)} — which is what a dev run, the clone-alone suite and every platform that has not adopted
 * the service still get.
 *
 * <p><b>Set, the service is AUTHORITATIVE — and authoritative means SOLE.</b> The file is not read
 * at all: the served properties over this process's boot config are the whole snapshot. It used to
 * be layered above the file, which read well and deployed badly — a key DELETED from the service
 * came straight back out of a file nobody had emptied, so the one operation the service exists to
 * make possible was the one it could not perform. Two sources cannot both be authoritative, and a
 * file left on the volume is the stale one by construction. The map arrives in the full prefixed
 * spelling, so nothing here translates the extras grammar — {@code ServiceExtras} stays its single
 * parser.
 *
 * <p><b>The rollback is the key.</b> Emptying {@code extras-url} returns this to the file, which may
 * by then be stale — re-render or export it before leaning on it.
 *
 * <p><b>An unreachable or non-200 service REFUSES the deployment.</b> There is deliberately no
 * fall-back to the file, to the boot config or to anything read earlier: a stale extras value is the
 * exact failure that cost 2026-08-16, and a fall-back would ship it as a green deployment. {@link
 * ServiceExtras.Refused} names the url and what happened, and it reaches the deployment's detail
 * through the driver's own refusal arm — the argv is never built, so the deployment changed nothing.
 *
 * <p><b>The patience is the spec read's, stated as two keys.</b> {@code
 * qits.deployments.extras-timeout-seconds} bounds the connect and the read, {@code
 * qits.deployments.extras-attempts} is the whole retry budget — a service being redeployed
 * is seconds of refusals and no deployment should die of one, while an outage that outlasts the
 * budget must be a loud refusal rather than an unbounded wait on the deploy worker, which is
 * single-threaded and has everything else queued behind it.
 */
@ApplicationScoped
public class ConfigHostExtrasSource implements DeploymentExtrasSource {

  private static final Logger LOG = Logger.getLogger(ConfigHostExtrasSource.class);

  /**
   * Its own mapper, and nothing is registered for reflection: the body is read as a tree and pulled
   * apart by field name, so there is no bound type for the build-time analysis to miss. That is the
   * whole reason it is a tree rather than a record — a payload record here would be a third list to
   * keep beside {@code ApiWireReflection} and {@code EventWireReflection}.
   */
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String PROPERTIES = "properties";

  private static final String HEAD_REVISION = "headRevision";

  /** Looked up per key rather than {@code @ConfigProperty}: the key carries the application name. */
  @Inject Config config;

  @ConfigProperty(name = "qits.deployments.extras-file")
  String extrasFile;

  /**
   * qits-configuration, or nothing. {@code Optional<String>} because SmallRye reads an empty value
   * as absent, so a deployment turns the service back off by emptying the variable rather than by
   * having to unset it.
   */
  @ConfigProperty(name = "qits.deployments.extras-url")
  Optional<String> extrasUrl;

  @ConfigProperty(name = "qits.deployments.extras-timeout-seconds")
  long timeoutSeconds;

  @ConfigProperty(name = "qits.deployments.extras-attempts")
  int attempts;

  @Inject ExtrasBearer bearer;

  @Inject QitsHttpClients clients;

  /**
   * How long a failed attempt waits before the next. Package-private rather than a key: it is an
   * implementation detail of the budget above, and the suite zeroes it so a refusal test costs no
   * seconds.
   */
  long retryPauseMillis = 1000;

  /**
   * One client for the life of the process, {@code GitHostSpecSource}'s arrangement and its why.
   * Wrapped through {@link QitsHttpClients} so this call to qits-configuration opens a CLIENT span
   * and carries {@code X-Qits-Caller}, like every other internal client in this component.
   */
  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              clients.wrap(
                  HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build());
          client = existing;
        }
      }
    }
    return existing;
  }

  @Override
  public Config forDeployment(
      String application, String environmentName, String version, boolean declarationSeeded) {
    String base = extrasUrl.map(String::trim).filter(url -> !url.isEmpty()).orElse(null);
    if (base == null) {
      // No service named, so the file is the source — WP0's behaviour, byte for byte. The tier and
      // the version are ignored here and that is not an oversight: a properties file on a volume
      // has one answer per key, so an address with more in it would describe a file that does not
      // exist. This arm is the compatibility claim and it stays byte-identical.
      return ExtrasSnapshot.over(config, extrasFile);
    }
    // ADDRESSED BY THE PLACE AND THE RELEASE, not by the application alone. The tier is a path
    // segment because it is part of the resource's identity — one application's configuration in
    // dev is a different document from its configuration in prod, not a query over one — and the
    // version is a query parameter because it selects WHICH declaration the overrides are resolved
    // against, which is what makes rolling a version back give that version's configuration rather
    // than the newest one's.
    //
    // ...AND THE VERSION IS ONLY ASKED FOR WHEN THERE IS A DECLARATION TO ASK ABOUT. See
    // versionlessBecauseNothingWasDeclared below: a version naming no declaration is a 404 by that
    // route's own design, so a release that seeded nothing has to read the transitional,
    // version-less form or it cannot be deployed at all.
    String place =
        trimTrailingSlash(base)
            + "/configuration/api/applications/"
            + segment(application)
            + "/envs/"
            + segment(environmentName)
            + "/resolved";
    String url =
        declarationSeeded
            ? place + "?version=" + versionParameter(version)
            : versionlessBecauseNothingWasDeclared(place, application, version);
    JsonNode body = fetch(url);
    Map<String, String> served = properties(url, body);
    // Built rather than deferred to `infof`: this is the one line that records what a deployment
    // was configured with, and a backend that keeps the format and the parameters apart would make
    // the sentence unreadable to anything grepping for it — the suite included.
    LOG.info(
        url
            + " answered "
            + served.size()
            + " extras properties for "
            + application
            + " at config-revision="
            + revision(body));
    // Over the BOOT config and not over the file: a deleted entry has to disappear, and a file
    // under this layer would serve it again at the next deployment.
    return ExtrasSnapshot.over(config, served, url);
  }

  /**
   * The version-less address, plus the one WARN that says why this deployment is reading it.
   *
   * <p><b>It is the store's own documented transitional form.</b> qits-configuration's resolve says
   * an absent {@code version} is "EXACTLY today's behaviour and never a 404" — the stored entries
   * alone — while a version PRESENT means "resolve me against this document" and a version naming no
   * declaration is a 404. Both halves are deliberate over there, and both are right; what was wrong
   * here was passing the parameter unconditionally, because the deployer only seeds a declaration
   * for a release whose tag carries {@code .config/qits/configuration.yml}. Almost no repository on
   * the fleet does yet, so every release of an undeclared one asked the store to resolve against a
   * document nobody had written, was answered 404, and had its deployment refused for the absence of
   * a file it never had.
   *
   * <p><b>The WARN is the transitional signal and it is meant to be read.</b> It names the
   * application and the version, and it says the read was version-less because the release declared
   * nothing — which is precisely the inventory the config-declarations epic's cutover needs: every
   * line here is one repository that still has to grow the file, and the day the log falls silent is
   * the day the parameter can stop being conditional. It is a WARN rather than an INFO because it
   * describes configuration this deployment could NOT resolve declared defaults for, not a routine
   * choice.
   *
   * <p><b>It never covers for a release that DID seed.</b> A 404 on the version-addressed read is a
   * real error — the seed answered 2xx a moment earlier, so the store holds that document and a
   * miss means something is wrong with the store rather than with the repository — and it stays a
   * refused deployment. That is also why this is a decision made from what the release carried
   * rather than a retry on the 404: a retry cannot tell those two 404s apart, and the one it would
   * quietly paper over is the one worth failing on.
   */
  private static String versionlessBecauseNothingWasDeclared(
      String place, String application, String version) {
    // Built rather than deferred to `warnf`, for the reason the revision line above is built: this
    // is a sentence somebody greps for — one line per repository that has not declared itself yet —
    // and a format string kept apart from its parameters is unreadable to whatever is doing the
    // grepping, the suite included.
    LOG.warn(
        application
            + "@"
            + version
            + " declared nothing, so its extras are read VERSION-LESS at "
            + place
            + ": the release carried no .config/qits/configuration.yml, nothing was seeded, and the"
            + " store answers 404 for a version it holds no declaration for. This is the"
            + " transitional form — the repository declaring itself is what ends it.");
    return place;
  }

  /** A name as it goes into a path segment, refused rather than escaped if it is not one. */
  private static String segment(String name) {
    // Every application and environment name reaching here came out of the topology, where
    // PdIdentifiers holds both to the dns-label charset. The check is the belt at the boundary, and
    // refusing beats escaping: a name that needs escaping is a name the rest of this component
    // could not have stored.
    if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new ServiceExtras.Refused(
          "'" + name + "' is not a name qits-configuration can be asked about");
    }
    return name;
  }

  /**
   * The version as it goes into the query, held to the same charset for the same reason. It is
   * checked rather than URL-encoded: a CalVer stamp that needs encoding is not one, and a value
   * this component would have to escape is a value it should never have had.
   */
  private static String versionParameter(String version) {
    if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new ServiceExtras.Refused(
          "'" + version + "' is not a version qits-configuration can resolve against");
    }
    return version;
  }

  /**
   * The read, and the whole of the retry budget. Everything that is not a 200 with a body this can
   * parse ends as a refusal naming the url — there is no arm that answers with something older.
   */
  private JsonNode fetch(String url) {
    String lastFailure = null;
    int budget = Math.max(1, attempts);
    for (int attempt = 1; attempt <= budget; attempt++) {
      if (attempt > 1 && retryPauseMillis > 0) {
        try {
          Thread.sleep(retryPauseMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ServiceExtras.Refused(url + " could not be read: interrupted");
        }
      }
      try {
        HttpResponse<String> response =
            client().send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return parse(url, response.body());
        }
        // A 404 is NOT an answer here, unlike the spec read's: a repository with no deployments.yml
        // deploys with the defaults, while an application qits-configuration has never heard of is
        // an application whose extras this deployment cannot know it is missing.
        lastFailure = "it answered " + response.statusCode();
      } catch (ServiceExtras.Refused e) {
        // A body that will not parse is not going to parse on the second attempt.
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceExtras.Refused(url + " could not be read: interrupted");
      } catch (Exception e) {
        lastFailure = e.toString();
      }
      LOG.warnf("Could not read %s (attempt %d of %d): %s", url, attempt, budget, lastFailure);
    }
    throw new ServiceExtras.Refused(
        url
            + " is this deployment's extras and could not be read after "
            + budget
            + " attempts: "
            + lastFailure);
  }

  private HttpRequest request(String url) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            // The forward-auth half, for a platform running qits-configuration open on qits-net
            // during the transition. The bearer below is the other half and outranks it where the
            // client is enabled; a service that takes neither refuses the read, loudly.
            .header("X-Qits-User", "qits-deployments")
            .header("X-Qits-Roles", "qits:system")
            .GET();
    bearer.token().ifPresent(token -> request.header("Authorization", "Bearer " + token));
    return request.build();
  }

  private static JsonNode parse(String url, String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new ServiceExtras.Refused(
          url + " is this deployment's extras and did not answer JSON: " + e.getMessage());
    }
  }

  /**
   * The properties map, in the full prefixed spelling the service already stores it in. A document
   * with no such object is a refusal rather than an empty answer: "this application states nothing"
   * and "this is not the resolved document" would otherwise be the same deployment.
   */
  private static Map<String, String> properties(String url, JsonNode body) {
    JsonNode properties = body == null ? null : body.get(PROPERTIES);
    if (properties == null || !properties.isObject()) {
      throw new ServiceExtras.Refused(
          url + " is this deployment's extras and answered no '" + PROPERTIES + "' object");
    }
    Map<String, String> served = new HashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode value = field.getValue();
      if (!value.isValueNode()) {
        throw new ServiceExtras.Refused(
            url + ": '" + field.getKey() + "' is not a config value");
      }
      served.put(field.getKey(), value.asText());
    }
    return served;
  }

  /**
   * What was deployed with, as far as this component can record it today: the deployment row's
   * detail is written by {@code DeployService} out of the driver's verdict and there is no seam a
   * config revision could ride there, so it is stated in the log beside the read.
   */
  private static String revision(JsonNode body) {
    JsonNode revision = body == null ? null : body.get(HEAD_REVISION);
    return revision == null || !revision.isNumber() ? "unknown" : revision.asText();
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
