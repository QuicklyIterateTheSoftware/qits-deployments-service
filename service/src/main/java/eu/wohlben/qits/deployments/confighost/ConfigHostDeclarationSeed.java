package eu.wohlben.qits.deployments.confighost;

import eu.wohlben.qits.deployments.deployments.control.DeclarationRefused;
import eu.wohlben.qits.deployments.deployments.control.DeclarationSeed;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link DeclarationSeed}: one {@code POST} of a released
 * repository's {@code .config/qits/configuration.yml} into qits-configuration, before the deployment
 * that carries it is scheduled.
 *
 * <pre>
 * POST &lt;extras-url&gt;/configuration/api/applications/&lt;app&gt;/declarations/&lt;version&gt;?deploymentTarget=environment
 *   Content-Type: application/yaml
 *   &lt;the file, verbatim&gt;
 * </pre>
 *
 * <p><b>It is {@link ConfigHostExtrasSource}'s sibling and deliberately shares its whole posture</b>
 * — the same base url key, the same forwarded headers, the same bearer, the same two patience keys.
 * That is not economy: it is the same peer. A second url key would let a platform point the write at
 * one qits-configuration and the read at another, which is a way to seed a declaration the
 * deployment then resolves against somebody else's store. A second credential would be a second
 * peer, and the peer count here is one.
 *
 * <p><b>The url unset is a no-op, and it has to be.</b> {@code
 * qits.deployments.extras-url} is unset shipped, which is a platform whose extras come from
 * the config volume's file — there is no store to seed, so a deployment that refused itself for
 * lacking one would be every file-mode platform failing every deployment. It logs at debug and
 * returns, and the deployment proceeds exactly as it did.
 *
 * <p><b>The status map is the whole design and its two halves are asked differently.</b>
 *
 * <ul>
 *   <li><b>2xx — done.</b> 201 is created, 200 is the idempotent no-op for a version whose content
 *       hash the store already holds. Both are the same answer to this component: the store has this
 *       version's declaration.
 *   <li><b>409 and 422 — {@link DeclarationRefused.Kind#DECLARATION_BROKEN}, immediately and never
 *       retried.</b> A 422 is a file that does not parse and a 409 is a DIFFERENT file under a
 *       version already stored; both are statements about the released tag's own content, and a
 *       second POST of the same bytes gets the same answer. Retrying would spend the budget proving
 *       it.
 *   <li><b>5xx, a timeout, a refused connection — {@link DeclarationRefused.Kind#SERVICE_UNAVAILABLE}
 *       after the budget.</b> The store being redeployed is a few seconds of refusals and no
 *       deployment should die of one; an outage that outlasts the budget must be a loud refusal
 *       rather than an unbounded wait on {@code pd-deploy-worker}, which is single-threaded with
 *       every other event queued behind it. This is {@code ConfigHostExtrasSource}'s argument
 *       verbatim, because it is the same worker and the same peer.
 *   <li><b>Any other 4xx — {@code SERVICE_UNAVAILABLE}, with no retry.</b> A 404 here is a
 *       mis-deployed store rather than patience material: this component built the path, so a route
 *       that is not there will not be there in a second. It is not {@code DECLARATION_BROKEN}
 *       because nothing read the file — sending a person to fix a repository over a 401 would be the
 *       wrong repository.
 * </ul>
 *
 * <p><b>Nothing here parses the body it sends.</b> The bytes are the git host's, and the store is
 * the only thing on the platform that knows the grammar — see {@code SpecSource.readDeclaration}.
 * What this validates is the two values it splices into a URL, and both are belts at the boundary:
 * the application name through the same charset check the resolved read uses, and the version
 * through its own.
 */
@ApplicationScoped
public class ConfigHostDeclarationSeed implements DeclarationSeed {

  private static final Logger LOG = Logger.getLogger(ConfigHostDeclarationSeed.class);

  /**
   * How much of a refusal's body reaches the deployment row. A store answering an HTML error page
   * or a stack trace must not put a page of it into a text column a person reads in a listing.
   */
  static final int EXCERPT_LIMIT = 300;

  /**
   * qits-configuration, or nothing — <b>the extras read's key, not one of this seam's own</b>. See
   * the class javadoc for why a second key would be a way to write and read different stores.
   */
  /** What the store is told about the plane, which is the only thing there is to tell it. */
  private static final String DEPLOYMENT_TARGET = "environment";

  @ConfigProperty(name = "qits.deployments.extras-url")
  Optional<String> extrasUrl;

  @ConfigProperty(name = "qits.deployments.extras-timeout-seconds")
  long timeoutSeconds;

  @ConfigProperty(name = "qits.deployments.extras-attempts")
  int attempts;

  @Inject ExtrasBearer bearer;

  @Inject QitsHttpClients clients;

  /**
   * How long a failed attempt waits before the next. Package-private rather than a key, {@link
   * ConfigHostExtrasSource}'s arrangement: it is an implementation detail of the budget above, and
   * the suite zeroes it so a refusal test costs no seconds.
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

  /**
   * <p><b>{@code deploymentTarget=environment} is a constant now, and it stays on the wire.</b> The
   * plane is deleted here, so this component has nothing left to state — but the query parameter is
   * qits-configuration's route and its own vocabulary, and dropping it would be a change to a peer's
   * contract made from the wrong repository. Every application is an environment application, so the
   * honest value is the constant; retiring the parameter is qits-configuration's to lead.
   */
  @Override
  public void seed(String applicationName, String version, String rawYaml) {
    String base = extrasUrl.map(String::trim).filter(url -> !url.isEmpty()).orElse(null);
    if (base == null) {
      // No store named, so there is nothing to seed and nothing to refuse. Every file-mode platform
      // is this case, and it deploys exactly as it did.
      LOG.debugf(
          "No qits.deployments.extras-url, so the declaration of %s@%s is not seeded"
              + " anywhere — this platform's configuration is the config volume's file",
          applicationName, version);
      return;
    }
    String url =
        trimTrailingSlash(base)
            + "/configuration/api/applications/"
            + segment(applicationName)
            + "/declarations/"
            + versionSegment(version)
            + "?deploymentTarget="
            + DEPLOYMENT_TARGET;
    post(url, applicationName, version, rawYaml);
  }

  /**
   * The POST, and the whole of the retry budget. Every exit is either "the store has it" or a {@link
   * DeclarationRefused} — there is deliberately no arm that shrugs and deploys anyway.
   */
  private void post(String url, String application, String version, String rawYaml) {
    String lastFailure = null;
    int budget = Math.max(1, attempts);
    for (int attempt = 1; attempt <= budget; attempt++) {
      if (attempt > 1 && retryPauseMillis > 0) {
        try {
          Thread.sleep(retryPauseMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw DeclarationRefused.unavailable(url, application, version, attempt, "interrupted");
        }
      }
      int status;
      String body;
      try {
        HttpResponse<String> response =
            client().send(request(url, rawYaml), HttpResponse.BodyHandlers.ofString());
        status = response.statusCode();
        body = response.body();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw DeclarationRefused.unavailable(url, application, version, attempt, "interrupted");
      } catch (Exception e) {
        // A transport failure never saw the file, so it says nothing about it: patience material.
        lastFailure = e.toString();
        LOG.warnf(
            "Could not seed the declaration of %s@%s at %s (attempt %d of %d): %s",
            application, version, url, attempt, budget, lastFailure);
        continue;
      }
      if (status / 100 == 2) {
        // 201 created, 200 the idempotent no-op for a hash the store already holds. One answer.
        LOG.infof(
            "%s accepted the declaration of %s@%s (%d)", url, application, version, status);
        return;
      }
      if (status == 409 || status == 422) {
        // Read and refused. A second POST of the same bytes gets the same answer, so this is
        // raised on the first attempt rather than after the budget.
        throw DeclarationRefused.broken(application, version, status, excerpt(body));
      }
      if (status / 100 == 5) {
        lastFailure = "it answered " + status;
        LOG.warnf(
            "Could not seed the declaration of %s@%s at %s (attempt %d of %d): %s",
            application, version, url, attempt, budget, lastFailure);
        continue;
      }
      // Any other 4xx: the request is wrong or the route is not there, and neither changes in a
      // second. Not DECLARATION_BROKEN, because nothing read the file — a 404 here is a
      // mis-deployed store, and pointing a person at a repository over it would be the wrong one.
      throw DeclarationRefused.unavailable(
          url, application, version, attempt, "it answered " + status + " — " + excerpt(body));
    }
    throw DeclarationRefused.unavailable(url, application, version, budget, lastFailure);
  }

  private HttpRequest request(String url, String rawYaml) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            // The forward-auth half, the resolved read's pair exactly: a platform running
            // qits-configuration open on qits-net during the transition takes these alone.
            .header("X-Qits-User", "qits-deployments")
            .header("X-Qits-Roles", "qits:system")
            // The file as the repository wrote it. Not JSON, not a wrapper object: the store owns
            // the grammar and this component is a courier.
            .header("Content-Type", "application/yaml")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    rawYaml == null ? "" : rawYaml, StandardCharsets.UTF_8));
    bearer.token().ifPresent(token -> request.header("Authorization", "Bearer " + token));
    return request.build();
  }

  /** The name as it goes into a path segment, refused rather than escaped if it is not one. */
  private static String segment(String application) {
    // ConfigHostExtrasSource's belt, and the same argument: every application name reaching here
    // came out of the topology, where PdIdentifiers holds it to the dns-label charset, so a name
    // that needed escaping is a name the rest of this component could not have stored.
    if (application == null || !application.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new DeclarationRefused(
          DeclarationRefused.Kind.SERVICE_UNAVAILABLE,
          "'" + application + "' is not an application name qits-configuration can be told about");
    }
    return application;
  }

  /**
   * The version as a path segment. The CalVer stamp is digits and dots, and {@code
   * DeploymentIdentifiers} has already held it to that — this is the belt at the line before the
   * URL, exactly as the name above is.
   */
  private static String versionSegment(String version) {
    if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new DeclarationRefused(
          DeclarationRefused.Kind.SERVICE_UNAVAILABLE,
          "'" + version + "' is not a version qits-configuration can store a declaration under");
    }
    return version;
  }

  /** As much of a refusal's body as belongs on a deployment row — see {@link #EXCERPT_LIMIT}. */
  private static String excerpt(String body) {
    if (body == null) {
      return "(no body)";
    }
    String single = body.strip().replaceAll("\\s+", " ");
    if (single.isEmpty()) {
      return "(no body)";
    }
    return single.length() <= EXCERPT_LIMIT ? single : single.substring(0, EXCERPT_LIMIT) + "…";
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
