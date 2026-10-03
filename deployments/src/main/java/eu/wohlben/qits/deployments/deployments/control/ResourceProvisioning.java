package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Everything between "the repository asked for a resource" and "the container is started with the
 * credential for it": read the registry, resolve which server to talk to, drive the right seam,
 * record what came back, and answer with the bindings the argv needs.
 *
 * <p><b>It runs on the deploy worker, before the pull.</b> The worker has no request context, so
 * every read and every write brackets itself in {@link QuarkusTransaction#requiringNew()} — and,
 * just as deliberately, <b>no transaction spans the call to a seam</b>. Both seams open a socket to
 * another server; holding a database transaction across either would put this component's own
 * connection pool behind somebody else's server for as long as it takes to answer.
 *
 * <p><b>Nothing here is ever dropped.</b> The failure modes are all shaped as convergence: a role
 * that is missing is created, a database that is missing is created, an ownership that drifted is
 * put back, an idp client nobody remembers is recreated or rotated. A resource the deployment no
 * longer declares is left exactly where it is.
 *
 * <p><b>One claim can MOVE, and only because a file asked for it by name.</b> {@code renamed_from}
 * in {@code .config/qits/deployments.yml} names the application this one used to be called, and a
 * postgres claim held by exactly that application is transferred onto this one instead of refusing
 * the deployment — see {@link #ensureAll}. Everything the refusal was right about survives: a claim
 * held by anybody else still throws, and it throws on the same sentence.
 *
 * <p><b>Two resource types now, dispatched by {@link ResourceSpec.Type}</b>: {@code postgresql} over
 * {@link ResourceProvisioner}, one idempotent {@code ensure} call; {@code idp-client} over {@link
 * IdpClientProvisioner}, a read and up to two writes, because qits-idp's own API has no single call
 * that means "converge, whatever the state" — see that interface's javadoc. The admin password is
 * read, and demanded, only when a postgres resource is actually declared: an idp-only declaration
 * needs no postgres credential at all.
 */
@ApplicationScoped
public class ResourceProvisioning {

  private static final Logger LOG = Logger.getLogger(ResourceProvisioning.class);

  /** Every platform repository carries it, and no database identifier may. */
  private static final String NAME_PREFIX = "qits-";

  /** The postgres resource type, and the application name the platform's postgres deploys under. */
  static final String POSTGRES_APPLICATION = "qits-oci-postgresql";

  /**
   * Postgres' own port, inside the network. It is a constant rather than a config key because the
   * address it belongs to is derived too: the only reachable postgres is the one this component
   * deploys, under the alias its own naming rule produced.
   */
  static final int POSTGRES_PORT = 5432;

  static final String RESOURCE_TYPE = "postgresql";

  /**
   * The idp client's fixed resource name — reserved, because there is one idp per platform and
   * therefore nothing for a repository to name. {@code DeploymentSpecParser} refuses a repository
   * that tries to use it for a postgres resource instead.
   */
  static final String IDP_RESOURCE_NAME = "idp";

  static final String IDP_RESOURCE_TYPE = "idp-client";

  /** The application the idp deploys under, and the port every service reaches it on. */
  static final String IDP_APPLICATION = "qits-idp";

  static final int IDP_PORT = 8080;

  /** 128 bits, hex — argv-safe, URL-safe, and it needs no quoting in a SQL string literal. */
  private static final int PASSWORD_BYTES = 16;

  @Inject PdResourceRepository resources;
  @Inject ResourceProvisioner provisioner;
  @Inject IdpClientProvisioner idpClients;

  @ConfigProperty(name = "qits.deployments.postgres.admin-username")
  String adminUsername;

  /**
   * The idp client this process is itself signed in as — the very key {@code
   * HttpIdpClientProvisioner} presents as its Basic id, declared beside the {@code qits} oidc
   * client's own chain in the service module's {@code application.properties} so the three can only
   * name one pair. Read here for {@link #rotateGuarded} alone. {@code Optional} because the key
   * resolves to empty where no pair was handed over (the suite, a developer's jar).
   */
  @ConfigProperty(name = "qits.deployments.idp.client-id")
  Optional<String> ownClientId;

  /**
   * Deliberately without a default. There is no password this repository could ship that would be
   * right, and a wrong one fails at the first CREATE ROLE with an authentication error nobody reads
   * as "nothing configured this". Absent, a deployment that declares a postgres resource fails
   * naming the key — which is the only actionable thing to say. Read only when a postgres resource
   * is actually declared: an idp-only declaration never touches it.
   */
  @ConfigProperty(name = "qits.deployments.postgres.admin-password")
  Optional<String> adminPassword;

  /**
   * One resource with its database resolved — what a {@code Target} carries. The spec's null
   * database has been replaced by the convention here for a postgres resource, so nothing downstream
   * has to know there was ever a default; an idp-client resource's database stays null, because it
   * has none.
   *
   * <p>The two-argument constructor keeps defaulting to {@link ResourceSpec.Type#POSTGRESQL}, so
   * every existing caller of the postgres shape is unaffected.
   */
  public record Resolved(String name, String database, ResourceSpec.Type type) {

    public Resolved(String name, String database) {
      this(name, database, ResourceSpec.Type.POSTGRESQL);
    }
  }

  /**
   * Fill in the databases the file left out, and refuse the collision only a resolved list can see.
   *
   * <p>The convention is {@code qits_} plus the application name without its {@code qits-} prefix,
   * dashes to underscores — so qits-artifacts gets {@code qits_artifacts} and this component gets
   * {@code qits_deployments}. It is resolved here, at registration, because this is the first place
   * that knows the application's name; the parser never does. An {@code idp-client} entry has no
   * database to resolve and passes through unchanged.
   *
   * <p>The parser already refused two entries naming one <b>literal</b> database. What it could not
   * see is two entries whose defaults collide, which after resolution is the same mistake, so it is
   * caught in the same shape rather than left to become two applications' worth of writes into one
   * store.
   */
  public static List<Resolved> resolve(String applicationName, List<ResourceSpec> declared) {
    if (declared == null || declared.isEmpty()) {
      return List.of();
    }
    List<Resolved> resolved = new ArrayList<>();
    Set<String> databases = new HashSet<>();
    for (ResourceSpec spec : declared) {
      if (spec.type() == ResourceSpec.Type.IDP_CLIENT) {
        resolved.add(new Resolved(spec.name(), null, spec.type()));
        continue;
      }
      String database =
          spec.database() != null ? spec.database() : conventionDatabase(applicationName);
      if (!databases.add(database)) {
        throw new ResourceException(
            "two resources of "
                + applicationName
                + " resolve to the database `"
                + database
                + "` — name one of them explicitly");
      }
      resolved.add(new Resolved(spec.name(), database, spec.type()));
    }
    return List.copyOf(resolved);
  }

  /** {@code qits_} + the application name without its {@code qits-} prefix, dashes underscored. */
  static String conventionDatabase(String applicationName) {
    String segment =
        applicationName.startsWith(NAME_PREFIX)
            ? applicationName.substring(NAME_PREFIX.length())
            : applicationName;
    return PdIdentifiers.requireDatabaseName(
        "qits_" + segment.replace('-', '_').toLowerCase(Locale.ROOT));
  }

  /**
   * Make every declared resource exist and answer with what to inject for it.
   *
   * <p><b>The tier is part of the key and there is always one.</b> It used to be null for a
   * platform-plane deployment, and that null was doing two jobs: it named the plane, and it was the
   * {@code pd_resource} lookup key those rows were written under. Both jobs are an ordinary tier
   * name now — the postgres a deployment talks to is its tier's, which is the same instance the null
   * arm resolved to, and its registry rows are keyed by that tier's name. {@code
   * BootResourceRegistration} resolves the same name for its own rows, from the same designation,
   * which is what keeps this component's first self-deploy on the no-op arm rather than rotating a
   * password its pools are holding.
   *
   * <p><b>There was a three-argument form and a {@code target} beside this one</b>, and both went
   * with the plane: the short form delegated with {@code ENVIRONMENT}, and the plane was what an
   * idp-client resource's client id used to be derived with (bare on the plane, tier-qualified
   * otherwise). {@link PdNetworks#alias} answers one way now, so there is one derivation and one
   * method.
   *
   * @param environmentName the tier this deployment goes into
   * @throws ResourceException with an operator-facing sentence, and no credential in it
   */
  public List<DeploymentDriver.ResourceBinding> ensureAll(
      String applicationName, String environmentName, List<Resolved> declared) {
    return ensureAll(applicationName, environmentName, null, declared);
  }

  /**
   * The same, for a deployment whose file declares a predecessor — {@code renamed_from} in {@code
   * .config/qits/deployments.yml}, threaded here by value from {@code DeployService} the way every
   * other spec value is.
   *
   * <p><b>An application name is a key, so renaming one is a new application, and that could not be
   * different.</b> A swarm service's name is its address and swarm cannot rename one; {@code
   * pd_service}, {@code pd_deployment} and {@code pd_resource} are keyed by an application NAME and
   * hold no repository identity at all, by V1's own rule. So the successor deploys beside the
   * predecessor and the operator retires the predecessor by hand. None of that is what {@code
   * renamed_from} changes.
   *
   * <p><b>What it changes is one row, and the refusal it narrows was right.</b> Before it, a
   * successor that declared the predecessor's database — which every rename does, because the
   * alternative is abandoning the data — was refused by {@link #ensurePostgres} with "the database
   * `X` is already provisioned for Y". That refusal exists because the alternative is provisioning a
   * second application an <b>empty</b> database and starting it green, which is a data loss nobody
   * is paged for, and it is unchanged for every caller that does not name the exact application
   * holding the claim. What is new is that a declared predecessor is not a guess: the file said so,
   * in a commit somebody reviewed, read at the released tag like every other statement in it.
   *
   * <p><b>The transfer is worth more than getting past the refusal, and that is the part to keep.</b>
   * The claim row is the single authority for the provisioned credential, and it is read one
   * statement later by {@code findOne(applicationName, …)} to decide whether to send the stored
   * password or a fresh one. Transferred, that read HITS: the successor is provisioned with the
   * password the role already has, so the predecessor — still running, still holding open pools on
   * that role — keeps working. Left alone, the successor would find no row, the reconcile arm would
   * rotate the role, and the rename would take the predecessor down as a side effect. The transfer
   * therefore has to happen inside the same bracket and before that read, and it does.
   *
   * <p><b>A declared predecessor buys nothing anywhere else, and that is deliberate</b> rather than
   * an unfinished sweep. The idp-client arm has its own "already provisioned for" and is left exactly
   * as it was — see {@link #ensureIdpClient}, where the reason is written down beside the check.
   *
   * @param renamedFrom the application this one used to be called, or null — which is every file
   *     that exists today, and which makes every refusal below byte-identical to what it was
   */
  public List<DeploymentDriver.ResourceBinding> ensureAll(
      String applicationName, String environmentName, String renamedFrom, List<Resolved> declared) {
    if (declared == null || declared.isEmpty()) {
      return List.of();
    }
    if (environmentName == null) {
      throw new ResourceException(
          "this deployment declares resources and names no environment, so there is nowhere to"
              + " provision them — designate a platform environment");
    }

    boolean needsPostgres =
        declared.stream().anyMatch(r -> r.type() == ResourceSpec.Type.POSTGRESQL);
    String host = needsPostgres ? PdNetworks.alias(environmentName, POSTGRES_APPLICATION) : null;
    String admin = needsPostgres ? requireAdminPassword() : null;

    List<DeploymentDriver.ResourceBinding> bindings = new ArrayList<>();
    for (Resolved resource : declared) {
      bindings.add(
          switch (resource.type()) {
            case POSTGRESQL ->
                ensurePostgres(
                    applicationName, environmentName, renamedFrom, host, admin, resource);
            case IDP_CLIENT -> ensureIdpClient(applicationName, environmentName, resource);
          });
    }
    return List.copyOf(bindings);
  }

  private String requireAdminPassword() {
    return adminPassword
        .map(String::strip)
        .filter(password -> !password.isEmpty())
        .orElseThrow(
            () ->
                new ResourceException(
                    "this deployment declares resources and nothing configured"
                        + " qits.deployments.postgres.admin-password"));
  }

  private DeploymentDriver.ResourceBinding ensurePostgres(
      String applicationName,
      String environmentName,
      String renamedFrom,
      String host,
      String admin,
      Resolved resource) {
    String database = PdIdentifiers.requireDatabaseName(resource.database());
    String name = PdIdentifiers.requireResourceName(resource.name());

    // The registry read, the cross-application check and the declared transfer, in one bracket: the
    // worker thread has no session of its own, and the answer is a plain String that outlives the
    // transaction. The ORDER inside it is load-bearing — the transfer has to commit before the
    // findOne below, or the successor reads no row and rotates a password its predecessor is using.
    String stored =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  for (PdResource claim : resources.listByDatabase(database)) {
                    if (claim.applicationName.equals(applicationName)) {
                      continue;
                    }
                    if (!claim.applicationName.equals(renamedFrom)) {
                      // Unchanged, and the sentence is unchanged with it: a store this deployment
                      // did not declare a predecessor for belongs to somebody else, and the only
                      // alternative to refusing is an empty database and a green container. A null
                      // `renamedFrom` — every file that exists today — reaches exactly here.
                      throw new ResourceException(
                          "the database `"
                              + database
                              + "` is already provisioned for "
                              + claim.applicationName
                              + " — two repositories cannot share one database, so name a"
                              + " different one in `resources:`");
                    }
                    transferClaim(claim, applicationName, database);
                  }
                  // Keyed by the tier this deployment goes into. The repository still tests null
                  // rather than comparing it, because rows written before the plane had a tier keep
                  // theirs — and `= null` matches nothing, which would rotate a working password on
                  // every deploy.
                  return resources
                      .findOne(applicationName, environmentName, name)
                      .map(row -> row.password)
                      .orElse(null);
                });

    // OUTSIDE any transaction, deliberately — see the class javadoc.
    String fresh = freshPassword();
    ResourceProvisioner.Result result =
        provisioner.ensure(
            new ResourceProvisioner.Request(
                host,
                POSTGRES_PORT,
                adminUsername,
                admin,
                database,
                // The role IS the database: one login per database, and nothing else may use it.
                database,
                stored,
                fresh));
    if (!result.ok()) {
      throw new ResourceException(
          "could not provision the database `" + database + "` on " + host + ": " + result.detail());
    }

    String inEffect = result.passwordInEffect();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<PdResource> existing =
                  resources.findOne(applicationName, environmentName, name);
              PdResource row = existing.orElseGet(PdResource::new);
              if (existing.isEmpty()) {
                row.id = UUID.randomUUID().toString();
                row.applicationName = applicationName;
                row.environmentName = environmentName;
                row.resourceName = name;
                row.createdAt = Instant.now();
              }
              row.resourceType = RESOURCE_TYPE;
              row.databaseName = database;
              row.roleName = database;
              row.clientId = null;
              row.password = inEffect;
              row.lastProvisionedAt = Instant.now();
              // Persist LAST, with every not-null column set: Hibernate queues the insert with the
              // state the entity had at persist() and applies later writes as a following update.
              if (existing.isEmpty()) {
                resources.persist(row);
              }
            });

    LOG.infof(
        "Resource %s of %s (%s) is database %s on %s",
        name, applicationName, environmentName, database, host);
    return DeploymentDriver.ResourceBinding.postgres(
        name, "jdbc:postgresql://" + host + ":" + POSTGRES_PORT + "/" + database, database, inEffect);
  }

  /**
   * Rewrite one claim's {@code application_name} onto the application that declared itself its
   * successor. <b>One column, and nothing else in the row is touched</b>: the database, the role, the
   * password, the created stamp and the last-provisioned stamp are the predecessor's and stay exactly
   * as they are, because the whole point is that the store and its credential did not change — only
   * the name of the application that owns them did.
   *
   * <p><b>It runs inside the caller's bracket</b>, never in one of its own, for the reason that
   * bracket exists: the read that decides which password to send happens two statements later and
   * has to see this. A transaction of its own would also make a failed provisioning leave the claim
   * moved, which is the one outcome worth avoiding — as it stands, a refusal rolls the transfer back
   * with it and the next attempt sees the estate it started from.
   *
   * <p><b>The tier is deliberately not part of the match.</b> {@code listByDatabase} is unscoped by
   * tier because the question it asks is "whose is this store", and a rename is a statement about an
   * application rather than about one tier's copy of it — so a claim in another tier is transferred
   * too, and the collision check below uses the CLAIM's own tier and resource name, which is what
   * {@code uq_pd_resource} is keyed on.
   *
   * <p><b>A key already taken is a refusal and not a merge.</b> {@code uq_pd_resource} is
   * {@code (application_name, environment_name, resource_name)}, so if the successor already holds a
   * resource of that name in that tier the update would violate it — and the honest reading of that
   * state is that two rows both claim to be this application's, which is not a rename but two
   * histories that have to be reconciled by somebody who knows which credential is live. Refusing
   * names both rows; guessing would rotate one of them.
   */
  private void transferClaim(PdResource claim, String applicationName, String database) {
    String predecessor = claim.applicationName;
    if (resources.findOne(applicationName, claim.environmentName, claim.resourceName).isPresent()) {
      throw new ResourceException(
          "the database `"
              + database
              + "` is claimed by "
              + predecessor
              + ", which "
              + applicationName
              + " declares as its predecessor — but "
              + applicationName
              + " already has a `"
              + claim.resourceName
              + "` resource of its own in "
              + claim.environmentName
              + ", so the claim has nowhere to move to. One of the two rows has to go first, and"
              + " which one depends on whose credential the running containers hold");
    }
    claim.applicationName = applicationName;
    // Flushed here rather than at commit, so the unique constraint answers inside this bracket and
    // the refusal above is not the only thing standing between a collision and a stack trace from
    // the transaction manager.
    resources.flush();
    LOG.infof(
        "The database %s was provisioned for %s, which %s declares as `renamed_from`, so the claim"
            + " and its credential are transferred to %s",
        database, predecessor, applicationName, applicationName);
  }

  /**
   * The four-arm idp-client matrix — the registry row crossed with what qits-idp answers for the
   * client id:
   *
   * <table>
   *   <caption>the matrix</caption>
   *   <tr><th>row</th><th>idp</th><th>action</th></tr>
   *   <tr><td>present</td><td>present</td><td>nothing; inject the stored secret</td></tr>
   *   <tr><td>present</td><td>absent</td><td>create → store → inject</td></tr>
   *   <tr><td>absent</td><td>present</td><td>rotate → store → inject</td></tr>
   *   <tr><td>absent</td><td>absent</td><td>create → store → inject (409 falls back to rotate
   *       once)</td></tr>
   * </table>
   *
   * <p><b>"Present" means a row FOR the derived client id.</b> A stored row naming another client id
   * is "absent" — see {@link #storedForThisClient} for the rollback that produces one — and the
   * write arms then overwrite it with the derived client. Both rotate paths are guarded by {@link
   * #rotateGuarded}, which refuses only the id this process is itself signed in as.
   *
   * <p>The presence check is one {@code GET} and is always made — it is what tells "nothing to do"
   * apart from "the row is stale", which a caller cannot see from its own registry alone.
   *
   * <p><b>This arm has the other "already provisioned for" and deliberately does NOT take a declared
   * predecessor.</b> It was checked rather than skipped, and the answer comes out of the derivation:
   * a client id is {@code PdNetworks.alias(environment, application)}, one-to-one with the
   * application name and never stated by a repository. So a rename produces a DIFFERENT client id,
   * {@code listByClientId} finds nothing, and the check cannot fire for a rename at all — there is no
   * refusal here to narrow. A transfer arm would be code no rename can reach, sitting on the one
   * check whose entire purpose is to be unreachable: as its own comment says, this fires only on a
   * derivation bug, and the thing to do about a derivation bug is refuse loudly rather than hand one
   * application's live credential to another's container on the strength of a line in a file.
   *
   * <p>What a rename costs here instead is one orphan: the predecessor's idp client and its
   * {@code pd_resource} row stay, and the successor is issued a client of its own with a fresh
   * secret. That is the correct outcome and not a gap — a secret is not data, nothing is lost by
   * minting another, and the predecessor keeps the credential it is running on for as long as it
   * runs. Retiring the orphan is the same hand step retiring the predecessor's service is.
   */
  private DeploymentDriver.ResourceBinding ensureIdpClient(
      String applicationName, String environmentName, Resolved resource) {
    String clientId =
        PdIdentifiers.requireName(
            PdNetworks.alias(environmentName, applicationName), "idp client id");

    // The registry read, and the cross-application check, in one bracket — the postgres arm's own
    // shape. Structurally this should never fire (the client id is derived one-to-one from
    // (application, environment)), but a derivation bug is exactly the case worth refusing
    // loudly rather than handing one application's credential to another's container.
    String stored =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  for (PdResource claim : resources.listByClientId(clientId)) {
                    if (!claim.applicationName.equals(applicationName)) {
                      throw new ResourceException(
                          "the idp client "
                              + clientId
                              + " is already provisioned for "
                              + claim.applicationName
                              + " — two applications cannot share one idp client");
                    }
                  }
                  return resources
                      .findOne(applicationName, environmentName, IDP_RESOURCE_NAME)
                      .filter(row -> storedForThisClient(row, clientId))
                      .map(row -> row.password)
                      .orElse(null);
                });

    // OUTSIDE any transaction, deliberately — see the class javadoc.
    boolean idpHasIt = idpClients.databaseClientPresent(clientId);

    String secret = stored;
    if (stored == null || !idpHasIt) {
      IdpClientProvisioner.Result result;
      if (stored != null) {
        // present/absent: idp lost the database row a reset or a restore would explain; a fresh
        // client replaces the stale secret, since qits-idp never accepts a caller-supplied one.
        result = idpClients.create(clientId);
      } else if (idpHasIt) {
        // absent/present: this registry lost its row, but idp already knows the client — rotate.
        result = rotateGuarded(applicationName, clientId);
      } else {
        // absent/absent: create, falling back to one rotate on a 409 — a drift the presence check
        // above did not catch, such as another process creating it between the two calls.
        IdpClientProvisioner.Result created = idpClients.create(clientId);
        result = created.conflict() ? rotateGuarded(applicationName, clientId) : created;
      }
      if (!result.ok()) {
        throw new ResourceException(
            "could not provision the idp client " + clientId + ": " + result.detail());
      }
      secret = result.secret();
      String freshSecret = secret;
      QuarkusTransaction.requiringNew()
          .run(() -> storeIdpRow(applicationName, environmentName, clientId, freshSecret));
    }

    LOG.infof(
        "Resource %s of %s (%s) is the idp client %s",
        IDP_RESOURCE_NAME, applicationName, environmentName, clientId);
    return DeploymentDriver.ResourceBinding.idp(
        IDP_RESOURCE_NAME, idpUrl(environmentName), clientId, secret);
  }

  /**
   * Whether a stored {@code idp} row is the credential FOR {@code clientId}. A row whose client id
   * differs from the derived one is treated as no row at all — its secret belongs to some other
   * client, and handing it out under this id is a credential that cannot work.
   *
   * <p><b>The case this exists for is this component's own row, after a self-deploy rollback</b>
   * (the wedge D9 guards against, reached from the other side). {@link BootResourceRegistration}
   * rewrites {@code (qits-deployments, <tier>, idp)} on every boot from whatever pair the running
   * container was started with. While the deployer still runs as its bootstrap client that is
   * {@code qits-deployments}; once it declares {@code idp:client} for itself the derived id is
   * {@code <tier>-qits-deployments}. A successor that fails its gate is rolled back by swarm to the
   * old spec, the old container boots and writes {@code qits-deployments} and ITS secret back onto
   * the row — and the next self-deploy, reusing the stored secret without comparing ids, would start
   * the successor as {@code <tier>-qits-deployments} holding {@code qits-deployments}' secret. No
   * extras read, no idp call and no image pull would work, and nothing is left to redeploy it.
   * Treated as no row, the matrix instead takes the create or the rotate arm for the derived id, and
   * {@link #storeIdpRow} overwrites the mismatched row IN PLACE: it is the same {@code
   * uq_pd_resource} key {@code (application, tier, idp)}, so the re-keyed client id is an update of
   * one row and never a second insert.
   */
  private static boolean storedForThisClient(PdResource row, String clientId) {
    if (clientId.equals(row.clientId)) {
      return true;
    }
    LOG.warnf(
        "The stored idp row of %s (%s) holds the client %s, not the derived %s — treated as no row,"
            + " so the derived client is created or rotated and the row is overwritten with it",
        row.applicationName, row.environmentName, row.clientId, clientId);
    return false;
  }

  /**
   * The rotate arm, refused for the client this process is ITSELF signed in as (D9): rotating the
   * deployer's own live secret mid-deployment would wedge the platform — no extras read, no idp call
   * and no image pull work without it, and there is no third party left to redeploy it.
   *
   * <p><b>The id is compared, not the application.</b> This used to refuse every client of {@code
   * qits-deployments}, which was the same statement while the deployer only ever had one. It stops
   * being one once the deployer declares {@code idp:client} for itself: running as its bootstrap
   * client {@code qits-deployments}, the recovery path — rotating {@code <tier>-qits-deployments},
   * which no running process holds, after a rollback (see {@link #storedForThisClient}) — is a
   * rotate of a client of this application that is NOT this process's identity, and refusing it
   * would leave the self-deploy failing forever. See {@link #rotationRefused}.
   */
  private IdpClientProvisioner.Result rotateGuarded(String applicationName, String clientId) {
    if (rotationRefused(applicationName, clientId, ownClientId)) {
      throw new ResourceException(
          "the idp client "
              + clientId
              + " is the one qits-deployments is itself signed in as, and it never rotates its own"
              + " secret — a lost row is recovered by BootResourceRegistration from its own"
              + " environment, not by asking qits-idp for a new one");
    }
    return idpClients.rotate(clientId);
  }

  /**
   * The guard's decision, pure so it can be held without a booted application. The id this process
   * is signed in as is refused; any other is allowed. When this process cannot say who it is (the
   * key resolved to nothing — a developer's jar, never a deployment, which always carries the pair)
   * it falls back to the old, wider refusal of every client of {@code qits-deployments}: not knowing
   * its own identity is no reason to risk rotating it.
   */
  static boolean rotationRefused(
      String applicationName, String clientId, Optional<String> signedInAs) {
    Optional<String> own = signedInAs.map(String::strip).filter(v -> !v.isEmpty());
    if (own.isPresent()) {
      return own.get().equals(clientId);
    }
    return BootResourceRegistration.APPLICATION.equals(applicationName);
  }

  private void storeIdpRow(
      String applicationName, String environmentName, String clientId, String secret) {
    Optional<PdResource> existing =
        resources.findOne(applicationName, environmentName, IDP_RESOURCE_NAME);
    PdResource row = existing.orElseGet(PdResource::new);
    if (existing.isEmpty()) {
      row.id = UUID.randomUUID().toString();
      row.applicationName = applicationName;
      row.environmentName = environmentName;
      row.resourceName = IDP_RESOURCE_NAME;
      row.createdAt = Instant.now();
    }
    row.resourceType = IDP_RESOURCE_TYPE;
    row.databaseName = null;
    row.roleName = null;
    row.clientId = clientId;
    row.password = secret;
    row.lastProvisionedAt = Instant.now();
    // Persist LAST, with every not-null column set — the postgres arm's own note applies here too.
    if (existing.isEmpty()) {
      resources.persist(row);
    }
  }

  /**
   * {@code http://<tier>-qits-idp:8080/idp} — derived, like the postgres host, never configured.
   *
   * <p><b>It gained the tier when the plane was deleted, and that is a cutover rather than a
   * cosmetic change.</b> It read {@code http://qits-platform-idp:8080/idp} while the plane's services
   * answered on their bare names; the idp is an ordinary service in the one tier now, so the
   * address a provisioned container is handed has to carry the tier or it resolves to nothing the
   * moment the bare-named predecessor is retired. (It was also renamed from {@code qits-platform-idp}
   * to {@code qits-idp} once the tier distinction in the name itself was gone.)
   */
  private static String idpUrl(String environmentName) {
    return "http://" + PdNetworks.alias(environmentName, IDP_APPLICATION) + ":" + IDP_PORT + "/idp";
  }

  /**
   * 32 lowercase hex characters from {@link SecureRandom}. The charset is the point: it survives an
   * argv, a JDBC url and a SQL string literal without one escaping rule between them.
   */
  private static String freshPassword() {
    // Created per call, not held in a static: a build-time SecureRandom lands in the native
    // image heap with a cached seed, and GraalVM refuses to build the image over it.
    // Provisioning is rare; the construction cost is nothing.
    byte[] bytes = new byte[PASSWORD_BYTES];
    new SecureRandom().nextBytes(bytes);
    StringBuilder hex = new StringBuilder(PASSWORD_BYTES * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }
}
