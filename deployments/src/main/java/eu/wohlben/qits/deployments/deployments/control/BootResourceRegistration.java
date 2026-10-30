package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Writes this component's own {@code pd_resource} rows from the credentials it was handed to boot.
 *
 * <p><b>Why it exists at all.</b> This component is adopter #1 of its own {@code resources:}
 * contract, and it is the one adopter whose databases cannot have been provisioned by it: the
 * bootstrap creates the roles and the databases over plain JDBC before this process exists, and
 * hands them over as {@code QITS_RESOURCE_<NAME>_URL} / {@code _USERNAME} / {@code _PASSWORD}. The
 * registry would therefore have no row for them, and the first self-deploy would read the empty
 * registry, take the <b>reconcile</b> arm of the idempotency matrix, and {@code ALTER ROLE} its own
 * passwords to fresh ones — while the running instance still holds pools of connections opened with
 * the old ones. Recording what it was given makes that first self-deploy hit the <b>no-op</b> arm
 * instead.
 *
 * <p><b>Two resources, because the spec declares two.</b> {@code db} is this component's own
 * registry; {@code eventstream} is the bus client's claim ledger and outbox, which arrives with the
 * qits-eventstream jar and is a store of its own with its own Flyway lineage. Both are handed over
 * by the bootstrap and both would be rotated by the first self-deploy, so both are recorded. A
 * third entry in {@code .config/qits/deployments.yml} adds a third line to {@link #RESOURCES}, and
 * that is the whole of the change.
 *
 * <p><b>And it survives everything.</b> The row is rewritten on every boot from the environment the
 * container was started with, so it is correct after all containers die, after the registry
 * database is restored, and after an operator rotates the password in deployment config. The environment is
 * the truth here; the row is a copy this component keeps so it can reason about itself the way it
 * reasons about every other application.
 *
 * <p><b>An absent {@code QITS_ENVIRONMENT} is resolved, not read as a plane.</b> It used to mean
 * "the platform plane", because the swarm driver wrote that variable for environment applications
 * only and this component is a platform service — so the rows were recorded under a null tier, and
 * {@link ResourceProvisioning} looked them up by that null. A platform service is deployed INTO the
 * designated platform environment now and is started with the variable like everything else, so the
 * absence means only one thing: <b>this container was started by the old code</b>, in the window
 * between the deploy that ships this file and the one after it. The tier is then resolved from the
 * database — {@code pd_environment.designated}, the same designation the deploying instance used to
 * choose where to put this container — and the row is written under that name, which is the key the
 * next self-deploy will look it up by. Recording null there instead would send that deploy down the
 * reconcile arm and rotate the passwords this process's pools are holding open.
 *
 * <p>Null survives as the last resort and says so in a WARN: an install with no designated tier has
 * nothing to key a row by, and it also has nothing that could deploy this component, so the row is
 * written where the old code wrote it and the operator is told.
 *
 * <p><b>Warn-only, and skipped under TEST</b> — the {@code DeployService.onStart} shape. A
 * component that cannot record its own resource must still start: it is the thing that redeploys
 * the platform, and refusing to boot over a bookkeeping row would be the worst possible trade. A
 * resource without its full triple is not an error either: that is a developer running the jar, and
 * there is nothing to record.
 */
@ApplicationScoped
public class BootResourceRegistration {

  private static final Logger LOG = Logger.getLogger(BootResourceRegistration.class);

  /**
   * This repository's id, which is the name the intake announces this component under and therefore
   * the {@code application_name} its own deployments and resources are keyed by. A constant rather
   * than a config key: a deployment that could name a different application here would write a row
   * describing somebody else's database.
   */
  static final String APPLICATION = "qits-deployments";

  /** What the spec line {@code resources: postgresql:db} calls it, and the env segment it becomes. */
  static final String RESOURCE_NAME = "db";

  /** The qits-eventstream jar's store, the second entry of the same spec line. */
  static final String EVENTSTREAM_RESOURCE_NAME = "eventstream";

  static final String RESOURCE_TYPE = "postgresql";

  /**
   * Every resource this component is handed at boot, in the spelling the spec uses. The variable
   * names follow the NAME — {@code QITS_RESOURCE_<NAME>_URL} and its two siblings, upper-cased —
   * which is the generic contract and the reason a resource cannot be renamed on one side alone.
   */
  static final List<String> RESOURCES = List.of(RESOURCE_NAME, EVENTSTREAM_RESOURCE_NAME);

  /**
   * The idp client's fixed resource name — {@code ResourceProvisioning}'s own constant, mirrored
   * here rather than shared: this class is warn-only at boot and must not fail to start over a
   * missing dependency between two classes that record the same kind of row for different reasons.
   */
  static final String IDP_RESOURCE_NAME = "idp";

  static final String IDP_RESOURCE_TYPE = "idp-client";

  /**
   * The idp triple's fallback names — the {@code QUARKUS_OIDC_CLIENT_CONFIGURATION_*} variables the
   * deployment has carried since qits-configuration's extras (settings of this component now), read
   * directly rather than through {@code ${…}} because this class reads raw env, not config. No oidc
   * client reads them any more; the names are just where the pair lives. This component does not
   * declare {@code idp:client} for itself (D10), so {@code QITS_RESOURCE_IDP_*} stays unset today
   * and every boot takes this fallback — the same pair the adapter reads.
   */
  static final String FALLBACK_URL = "QUARKUS_OIDC_CLIENT_CONFIGURATION_AUTH_SERVER_URL";

  static final String FALLBACK_CLIENT_ID = "QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID";

  static final String FALLBACK_CLIENT_SECRET = "QUARKUS_OIDC_CLIENT_CONFIGURATION_CREDENTIALS_SECRET";

  static final String ENVIRONMENT_VARIABLE = "QITS_ENVIRONMENT";

  @Inject PdResourceRepository resources;
  @Inject EnvironmentService environments;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    String environmentName = environmentName();
    for (String resourceName : RESOURCES) {
      try {
        Optional<String> url = value(variable(resourceName, "URL"));
        Optional<String> username = value(variable(resourceName, "USERNAME"));
        Optional<String> password = value(variable(resourceName, "PASSWORD"));
        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
          LOG.debugf(
              "Not recording this instance's own %s resource: it was started without the full"
                  + " %s triple",
              resourceName, variable(resourceName, "*"));
          continue;
        }
        // Per resource rather than around the loop: one missing triple must not cost the others
        // their rows, for the same reason this whole observer is warn-only.
        record(resourceName, url.get(), username.get(), password.get(), environmentName);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not record this instance's own %s resource row", resourceName);
      }
    }
    try {
      recordIdp(environmentName);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not record this instance's own idp resource row");
    }
  }

  /**
   * The idp triple, in its own method rather than folded into the loop above: it has no database to
   * derive from a url, its resource type is a different word, and its fallback is a THIRD env
   * family rather than the postgres siblings' own name. Package-private for its own test.
   */
  void recordIdp(String environmentName) {
    Optional<String> url =
        value(variable(IDP_RESOURCE_NAME, "URL")).or(() -> value(FALLBACK_URL));
    Optional<String> clientId =
        value(variable(IDP_RESOURCE_NAME, "CLIENT_ID")).or(() -> value(FALLBACK_CLIENT_ID));
    Optional<String> secret =
        value(variable(IDP_RESOURCE_NAME, "CLIENT_SECRET")).or(() -> value(FALLBACK_CLIENT_SECRET));
    if (url.isEmpty() || clientId.isEmpty() || secret.isEmpty()) {
      LOG.debugf(
          "Not recording this instance's own idp resource: it was started without the full"
              + " %s triple or the %s/%s/%s fallback",
          variable(IDP_RESOURCE_NAME, "*"), FALLBACK_URL, FALLBACK_CLIENT_ID, FALLBACK_CLIENT_SECRET);
      return;
    }
    recordIdp(clientId.get(), secret.get(), environmentName);
  }

  /**
   * Upsert the idp row — {@link #record}'s shape, minus the database this resource type has none of.
   * Package-private for the same reason {@link #record} is.
   */
  void recordIdp(String clientId, String secret, String environmentName) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<PdResource> existing =
                  resources.findOne(APPLICATION, environmentName, IDP_RESOURCE_NAME);
              PdResource resource = existing.orElseGet(PdResource::new);
              if (existing.isEmpty()) {
                resource.id = UUID.randomUUID().toString();
                resource.applicationName = APPLICATION;
                resource.environmentName = environmentName;
                resource.resourceName = IDP_RESOURCE_NAME;
                resource.createdAt = Instant.now();
              }
              resource.resourceType = IDP_RESOURCE_TYPE;
              resource.databaseName = null;
              resource.roleName = null;
              resource.clientId = clientId;
              resource.password = secret;
              // Deliberately NOT touched, the postgres siblings' own note: this component provisioned
              // nothing here either, and a timestamp would claim a check that never happened.
              // resource.lastProvisionedAt stays as it is.
              if (existing.isEmpty()) {
                resources.persist(resource);
              }
            });
    LOG.infof(
        "Recorded this instance's own resource: %s/idp is the idp client %s",
        environmentName == null ? "platform" : environmentName, clientId);
  }

  /**
   * Which tier this instance's own resource rows are keyed by: what it was started with, and — when
   * it was started with nothing — the designation that decides where a platform service is
   * deployed. Package-private for its own test.
   *
   * <p><b>The second arm is the bootstrap window and nothing else.</b> This component is deployed by
   * the instance before it, so the first container carrying this file was started by code that
   * wrote no {@code QITS_ENVIRONMENT} for a platform service. Reading the designation gives that
   * container the same answer its successor will be started with, which is what makes the rows it
   * writes the rows the next self-deploy finds.
   *
   * <p>The read is bracketed rather than left to the callee: this runs on the startup observer's
   * thread, which has no transaction of its own, and {@code EnvironmentService}'s reads join an
   * existing one. It is also the reason for the catch — a component that cannot read its own
   * topology must still start, exactly as the rest of this class is warn-only.
   */
  String environmentName() {
    Optional<String> declared = value(ENVIRONMENT_VARIABLE);
    if (declared.isPresent()) {
      return declared.get();
    }
    String designated = null;
    try {
      designated =
          QuarkusTransaction.requiringNew()
              .call(() -> environments.platformEnvironment().map(e -> e.name).orElse(null));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the designated platform environment while recording own rows");
    }
    if (designated == null) {
      LOG.warnf(
          "This instance was started without %s and no environment is designated the platform one,"
              + " so its own resource rows are recorded with no tier. They are the rows a"
              + " self-deploy looks up, so designate one before deploying this component.",
          ENVIRONMENT_VARIABLE);
      return null;
    }
    LOG.infof(
        "This instance was started without %s — the boot before its first deployment by the"
            + " current code — so its own resource rows are recorded under the designated platform"
            + " environment %s, which is where it is deployed",
        ENVIRONMENT_VARIABLE, designated);
    return designated;
  }

  /** {@code QITS_RESOURCE_<NAME>_<SUFFIX>}, with the name's hyphens spelled as underscores. */
  static String variable(String resourceName, String suffix) {
    return "QITS_RESOURCE_"
        + resourceName.toUpperCase(Locale.ROOT).replace('-', '_')
        + "_"
        + suffix;
  }

  /**
   * Upsert the row for {@code (this application, this plane, this resource)} — a tier name, or null
   * for the platform plane. Package-private because the startup path is skipped under TEST and the
   * suite drives this directly — the {@code sweepInFlight()} arrangement.
   *
   * <p><b>The tier-keyed rows are the live ones again.</b> A platform that ran this per tier has
   * rows keyed {@code ('qits-deployments', 'dev', …)}; the plane's own era wrote {@code
   * ('qits-deployments', null, …)}; V8 moves the null-keyed rows onto the designated tier's name
   * and drops whichever stale tier-keyed row they collide with, so there is one row per resource
   * again. This upsert then rewrites it from the environment on every boot, which is what makes the
   * environment the truth and the row a copy.
   */
  void record(
      String resourceName,
      String url,
      String username,
      String password,
      String environmentName) {
    String database = databaseOf(url);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<PdResource> existing =
                  resources.findOne(APPLICATION, environmentName, resourceName);
              PdResource resource = existing.orElseGet(PdResource::new);
              if (existing.isEmpty()) {
                resource.id = UUID.randomUUID().toString();
                resource.applicationName = APPLICATION;
                resource.environmentName = environmentName;
                resource.resourceName = resourceName;
                resource.createdAt = Instant.now();
              }
              resource.resourceType = RESOURCE_TYPE;
              resource.databaseName = database;
              resource.roleName = username;
              // Whatever this process was actually started with wins over whatever was recorded:
              // the environment is the truth, and a row that disagreed with it would send the next
              // self-deploy down the reconcile arm against a credential that works.
              resource.password = password;
              // Deliberately NOT touched. This component provisioned nothing — the bootstrap did —
              // and a timestamp here would claim a check that never happened.
              // resource.lastProvisionedAt stays as it is.

              // PERSIST LAST, WITH EVERY COLUMN ALREADY SET. Hibernate queues the insert with the
              // state the entity had AT persist() and only then applies later writes as an UPDATE
              // — so a not-null column filled after the call fails the insert before that update
              // can run. Measured here, on `resource_type`.
              if (existing.isEmpty()) {
                resources.persist(resource);
              }
            });
    LOG.infof(
        "Recorded this instance's own resource: %s/%s uses database %s as %s",
        environmentName == null ? "platform" : environmentName, resourceName, database, username);
  }

  /**
   * The database a JDBC url names — the last path segment, query string dropped. Package-private
   * for its own test: the url arrives from a deployment, and reading the wrong segment out of it
   * would write a row about a database that does not exist.
   */
  static String databaseOf(String url) {
    String withoutQuery = url;
    int query = withoutQuery.indexOf('?');
    if (query >= 0) {
      withoutQuery = withoutQuery.substring(0, query);
    }
    int lastSlash = withoutQuery.lastIndexOf('/');
    String database = lastSlash < 0 ? "" : withoutQuery.substring(lastSlash + 1);
    if (database.isBlank()) {
      throw new IllegalArgumentException("no database in the jdbc url");
    }
    return database;
  }

  private static Optional<String> value(String name) {
    return ConfigProvider.getConfig()
        .getOptionalValue(name, String.class)
        .map(String::strip)
        .filter(v -> !v.isEmpty());
  }
}
