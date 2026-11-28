package eu.wohlben.qits.deployments.deployments.control;

import static eu.wohlben.qits.deployments.deployments.control.DeployService.CUTOVER_BUDGET;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDatabaseMoveRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.error.BadGatewayException;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.deployments.environments.error.ConflictException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import eu.wohlben.qits.deployments.events.ApplicationRollbackRequested;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The operator's two levers over a deployed application: <b>how many of it run</b>, and <b>replace
 * what is running</b>. Neither is a deployment, and that is the whole design.
 *
 * <p><b>Why it exists.</b> qits-ci wedged on a disk-full incident: the process was up, its health
 * probe answered, and its in-memory dispatch queue had been orphaned — a state its own boot sweep
 * recovers from in seconds. The only lever an operator had was re-firing a same-sha push to {@code
 * environment/dev} so a rebuild and a redeploy would replace the container: a quarter of an hour of
 * ceremony, a new deployment row, and a fresh image build, to restart a process. A restart is an
 * operation, so it is an operation here.
 *
 * <p><b>Everything runs on the deploy worker.</b> The door validates, resolves the row and returns;
 * the {@code docker service update} and the row it writes happen on {@code pd-deploy-worker} behind
 * whatever the queue already holds — see {@link DeployService#enqueueOperation}, which argues why
 * that is a correctness requirement rather than a convention. So the API answers <b>202</b> and the
 * deployment listing is where the result is read, exactly as it is for a build-succeeded event.
 *
 * <p><b>What it does to the row, and it is deliberately little.</b> A deployment row is the record
 * of an attempt to put one commit live, and none of that changes when somebody bounces the container
 * or stops it for an hour. So:
 *
 * <ul>
 *   <li><b>a restart writes no status at all</b> — it stamps the row's {@code detail} and nothing
 *       else. The id, the sha, the container name, the timestamps and the history are untouched,
 *       which is the difference between a bounce and a redeploy stated where a reader will see it;
 *   <li><b>a scale to zero writes {@link PdDeploymentStatus#SCALED_TO_ZERO}</b>, because a place
 *       that is deliberately empty is a fact about the platform an operator has to be able to read
 *       — and because leaving it {@code ACTIVE} would have the deployment listing claim a service
 *       that is stopped is serving;
 *   <li><b>a scale back up writes no status</b>. It stamps the row and leaves it {@code
 *       SCALED_TO_ZERO} for {@link DeploymentObserver} to promote once the tasks are actually
 *       healthy. Writing {@code ACTIVE} here would be this component claiming a health verdict it
 *       has not asked for; the observation is the one place that verdict is reached, and the
 *       recovery arm it reaches it through already exists.
 * </ul>
 *
 * <p><b>The stamp replaces the previous stamp rather than stacking.</b> An operator action is a
 * statement about now and a script hammering the door must not grow a text column without bound, so
 * a new stamp drops a leading one and keeps everything under it — which is the deployment's own
 * diagnosis, and the thing that must never be lost.
 *
 * <p><b>A third lever, {@link #start}, IS a deployment, and says so</b> (qits-2249): the break-glass
 * start of an application on the database claims as they are — the newest {@code ACTIVE} version
 * redeployed through the ordinary deploy path with no database move planned, for a service a move or
 * a hand fix left stopped. It writes a new deployment row like any deploy does.
 *
 * <p><b>Nothing arriving over HTTP shapes an argv.</b> The caller names an application id; the
 * service name the update is issued against comes off the <b>deployment row</b> ({@code
 * container_name}, which under swarm is the service's name and therefore its address), the same
 * place {@link DeploymentObserver} takes it from and for the same reason: only the service a row
 * named may be acted on for that row. The replica count is an integer, bounded below.
 */
@ApplicationScoped
public class ApplicationScaling {

  private static final Logger LOG = Logger.getLogger(ApplicationScaling.class);

  /**
   * The largest replica count this door accepts.
   *
   * <p><b>One, and it is the platform's shape rather than an orchestrator's limit.</b> Swarm would
   * run any number; the applications here would not survive it. They bind host ports from inside the
   * task ({@code publish_mode: host} is the default and what every publishing service uses), several
   * are single-writer stores on one volume, and one holds a config volume — a second task is a
   * refused schedule or a corrupted cluster, not capacity. Raising it is a decision about those
   * applications, and it belongs in the same commit as whatever makes one of them able to run twice.
   *
   * <p>An application that can run twice says so in its own file — {@code replicas:} (qits-2523) —
   * and this door is unchanged by it: an operator's 0 or 1 holds until the next deployment, which
   * restates the declared count, exactly as it restated one before.
   */
  public static final int MAX_REPLICAS = 1;

  @Inject PdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;
  @Inject DeployService worker;
  @Inject DatabaseMoves databaseMoves;
  @Inject PdDatabaseMoveRepository moves;
  @Inject EnvironmentService environments;

  /**
   * What the door answers with: what it resolved, and what it queued. It is not an outcome —
   * nothing has been issued yet when this is returned.
   *
   * <p>{@code replicas} is null on a restart, which asks for no count.
   */
  public record Accepted(
      String applicationId,
      String applicationName,
      String environmentId,
      String serviceName,
      String deploymentId,
      Integer replicas) {}

  /** The place an action was resolved to, as plain values — never an entity past this line. */
  private record Placed(
      String applicationId,
      String applicationName,
      String environmentId,
      String serviceName,
      String deploymentId) {}

  /**
   * Ask the orchestrator to run {@code replicas} tasks of this application.
   *
   * @param applicationId the derived key, {@code <environmentId>:<name>} or {@code platform:<name>}
   * @param replicas 0 to stop the workload, 1 to bring it back — see {@link #MAX_REPLICAS}
   * @param actor who asked, for the row's stamp; null reads as "an operator"
   */
  public Accepted scale(String applicationId, int replicas, String actor) {
    if (replicas < 0) {
      throw new BadRequestException("replicas cannot be negative");
    }
    if (replicas > MAX_REPLICAS) {
      throw new BadRequestException(
          "replicas must be between 0 and "
              + MAX_REPLICAS
              + ": every application here is deployed as a single task, and a second one would"
              + " collide on a published host port or on a store with one writer");
    }
    Placed placed = resolve(applicationId);
    worker.enqueueOperation(
        "Scaling " + placed.serviceName() + " to " + replicas,
        () -> applyScale(placed, replicas, actor));
    return accepted(placed, replicas);
  }

  /**
   * Ask the orchestrator to replace the tasks running under this application's name, unchanged.
   *
   * @param actor who asked, for the row's stamp; null reads as "an operator"
   */
  public Accepted restart(String applicationId, String actor) {
    Placed placed = resolve(applicationId);
    worker.enqueueOperation(
        "Restarting " + placed.serviceName(), () -> applyRestart(placed, actor));
    return accepted(placed, null);
  }

  /**
   * What the start door queued: the place, and the {@code ACTIVE} deployment whose version it
   * redeploys. Not an outcome — the new deployment row is read from the listing.
   */
  public record StartAccepted(
      String applicationId,
      String applicationName,
      String environmentId,
      String deploymentId,
      String version) {}

  /**
   * Start an application on its database claims as they are now — the break-glass door (qits-2249,
   * {@code POST /applications/{id}/start}). The newest {@code ACTIVE} version is redeployed through
   * the ordinary deploy path on the worker: provisioning runs, every binding is rendered from the
   * claim it finds, and NO database move is planned, whatever that version's spec resolves its
   * databases to. For a service a database move left stopped, or one a person stopped and moved by
   * hand: the one door that starts it on the data where it is.
   *
   * <p>Refused, on the request thread: an unknown application or one on no tier (404); a deployment
   * of it {@code QUEUED} or {@code STARTING}, or a database move of it not yet settled — queued,
   * running, awaiting its deployment or being rolled back (409: those decide where the data is, and a
   * start beside them would race them for the claims); no {@code ACTIVE} deployment to start (409);
   * and this deployer itself, which is running if it answers (409).
   *
   * @param actor who asked, for the log; null reads as "an operator"
   */
  public StartAccepted start(String applicationId, String actor) {
    ApplicationKeys.Key key = keyOf(applicationId);
    String application = key.applicationName();
    PdIdentifiers.requireName(application, "application name");
    if (BootResourceRegistration.APPLICATION.equals(application)) {
      throw new ConflictException(
          application + " is this deployer: it is running, since it answered, so there is nothing to start");
    }
    if (key.environmentId() == null || key.environmentId().isBlank()) {
      throw new NotFoundException(applicationId + " names no tier, so nothing of it is deployed to start");
    }
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              List<PdDeployment> rows =
                  deployments.listForPlaceNewestFirst(application, key.environmentId());
              if (rows.isEmpty()) {
                throw new NotFoundException(
                    "nothing has ever been deployed for " + applicationId + ", so there is nothing to start");
              }
              String tier = environments.require(key.environmentId()).name;
              for (PdDeployment row : rows) {
                if (row.status == PdDeploymentStatus.QUEUED || row.status == PdDeploymentStatus.STARTING) {
                  throw new ConflictException(
                      "deployment " + row.id + " of " + applicationId + " is " + row.status
                          + ": it decides what runs, so a start waits for it");
                }
              }
              List<PdDatabaseMove> unsettled =
                  moves.list(
                      "applicationName = ?1 and environmentName = ?2 and state not in ?3",
                      application,
                      tier,
                      List.copyOf(PdDatabaseMoveState.TERMINAL));
              if (!unsettled.isEmpty()) {
                PdDatabaseMove move = unsettled.get(0);
                throw new ConflictException(
                    "the database move "
                        + move.id
                        + " of `"
                        + move.resourceName
                        + "` of "
                        + application
                        + " is "
                        + move.state
                        + ": it decides where the data is, so a start waits for it to settle");
              }
              PdDeployment active =
                  rows.stream()
                      .filter(row -> row.status == PdDeploymentStatus.ACTIVE)
                      .findFirst()
                      .orElseThrow(
                          () ->
                              new ConflictException(
                                  applicationId
                                      + " has no ACTIVE deployment to start: its newest is "
                                      + rows.get(0).status));
              DatabaseMoves.StartQueued queued = databaseMoves.queueStart(active, tier, actor);
              return new StartAccepted(
                  applicationId,
                  application,
                  key.environmentId(),
                  queued.deploymentId(),
                  queued.version());
            });
  }

  /** The longest reason the rollback door takes — a sentence for a person, not a document. */
  public static final int MAX_ROLLBACK_REASON = 500;

  /** How many accepted versions a refused rollback names, newest first. */
  static final int NAMED_ROLLBACK_VERSIONS = 5;

  /** The words of a deployment that SERVED its place — {@link RollbackPins}' rule, verbatim. */
  private static final Set<PdDeploymentStatus> SERVED =
      EnumSet.of(PdDeploymentStatus.ACTIVE, PdDeploymentStatus.DECOMMISSIONED);

  /**
   * The audit trail of the operator's version choices (qits-2779): one INFO line per accepted
   * rollback, on a logger of its own name so it can be routed and kept apart from the worker's
   * chatter.
   */
  private static final Logger AUDIT = Logger.getLogger("eu.wohlben.qits.deployments.audit");

  @Inject SpecSource specs;
  @Inject Instance<DeployAnnouncer> announcers;

  /**
   * What the rollback door queued: the place, the version serving it when the request was accepted
   * ({@code fromVersion}), the one asked for ({@code toVersion}), and {@code deploymentId} — the
   * {@code ACTIVE} deployment serving {@code fromVersion}, the row the rollback replaces. That is
   * the start door's choice and for its reason: the new row is written on the worker, after this
   * answer, so the listing is where it is read.
   */
  public record RollbackAccepted(
      String applicationId,
      String applicationName,
      String environmentId,
      String fromVersion,
      String toVersion,
      String deploymentId) {}

  /** Everything the rollback's guards resolved, as plain values — read in one transaction. */
  private record RollbackPlan(
      String application,
      String environmentId,
      String tier,
      String fromVersion,
      String fromDeploymentId,
      DatabaseMoves.Live target) {}

  /**
   * Put ONE application in one tier back on an earlier version that already served there
   * (qits-2779, {@code POST /applications/{id}/rollback}) — no build, no machine role, and nothing
   * else of the release moving.
   *
   * <p>Refused on the request thread, so every refusal is an answer rather than a log line under a
   * 202: a malformed id (400), a blank {@code version} or {@code reason} or a reason over {@link
   * #MAX_ROLLBACK_REASON} characters (400); this deployer itself (409); an id with no tier, or a
   * place nothing ever deployed (404); a deployment of it {@code QUEUED} or {@code STARTING}, or a
   * database move of it not settled (409, the start door's sentences); no {@code ACTIVE} deployment
   * (409). Then the version: it must have SERVED this place — a row of this (application, tier) at
   * that version, {@code ACTIVE} or {@code DECOMMISSIONED}, which is {@link RollbackPins}' rule —
   * and must not be the version serving now; otherwise a 409 naming the accepted versions. Rolling
   * forward again to a newer version that served is allowed by the same rule. The coordinates come
   * off that row's deployment request ({@link DatabaseMoves#coordinates}), never from the caller,
   * and no request on record is a 409.
   *
   * <p><b>Only a start-first application.</b> The named application's own {@code update_order} at
   * the target tag is read now — the variant's file for a variant — and stop-first is a 409: a
   * rollback is a person reaching for a version that worked, typically while the serving one is
   * misbehaving, and a stop-first update would take the serving one away before its successor has
   * passed the gate. Start-first keeps the serving deployment {@code ACTIVE} until the rolled-back
   * one is healthy, so a rollback that fails — an image the registry no longer has ends {@code
   * IMAGE_MISSING} — leaves the platform exactly as it was. A tag that carries no file is a 409 too.
   * No registry is asked: the pin rule keeps the one rollback step's image, and an older one that
   * was collected fails the pull harmlessly for exactly that reason.
   *
   * <p>Accepted, it queues {@link DeployService#redeployOne} on the worker — that one application,
   * the main one without its variants or exactly the one variant, in this tier alone — with no
   * database move planned ({@link DatabaseMoves#onClaims}: a rollback puts the code back, never the
   * data), writes one {@link #AUDIT} line and announces {@code ApplicationRollbackRequested}, whose
   * id the new deployment's rows carry as their cause.
   *
   * @param actor who asked, for the audit line and the event; null reads as "an operator"
   */
  public RollbackAccepted rollback(
      String applicationId, String version, String reason, String actor) {
    ApplicationKeys.Key key = keyOf(applicationId);
    String application = key.applicationName();
    PdIdentifiers.requireName(application, "application name");
    if (version == null || version.isBlank()) {
      throw new BadRequestException("version is required: the earlier version to put back");
    }
    if (reason == null || reason.isBlank()) {
      throw new BadRequestException("reason is required: why this application is being rolled back");
    }
    String why = reason.strip();
    if (why.length() > MAX_ROLLBACK_REASON) {
      throw new BadRequestException(
          "reason is at most " + MAX_ROLLBACK_REASON + " characters, and this one is " + why.length());
    }
    String asked = version.strip();
    if (BootResourceRegistration.APPLICATION.equals(application)) {
      throw new ConflictException(
          application
              + " is this deployer: a rollback of it would replace the process answering this"
              + " request; roll it back by a person");
    }
    if (key.environmentId() == null || key.environmentId().isBlank()) {
      throw new NotFoundException(
          applicationId + " names no tier, so nothing of it is deployed to roll back");
    }
    RollbackPlan plan =
        QuarkusTransaction.joiningExisting()
            .call(() -> planRollback(applicationId, application, key.environmentId(), asked));
    requireStartFirst(plan);
    // Built before anything is queued, so the queued rows can carry its id as their cause.
    ApplicationRollbackRequested event =
        new ApplicationRollbackRequested(
            application,
            plan.environmentId(),
            plan.tier(),
            plan.fromVersion(),
            plan.target().version(),
            actor,
            why,
            plan.fromDeploymentId(),
            Instant.now());
    worker.enqueueOperation(
        "Rolling " + application + " in " + plan.tier() + " back to " + plan.target().version(),
        () ->
            databaseMoves.onClaims(
                application,
                plan.tier(),
                () ->
                    worker.redeployOne(
                        plan.target().repository(),
                        releasedNameOf(plan.target()),
                        application,
                        plan.environmentId(),
                        plan.target().version(),
                        plan.target().packageName(),
                        plan.target().variant(),
                        event.eventId())));
    AUDIT.infof(
        "%s rolled back %s in %s (%s) from %s to %s: %s",
        actor == null || actor.isBlank() ? "An operator" : actor,
        application,
        plan.tier(),
        plan.environmentId(),
        plan.fromVersion(),
        plan.target().version(),
        // Free text from a caller: one line in the log whatever it holds.
        why.replaceAll("\\p{Cntrl}", " "));
    for (DeployAnnouncer announcer : announcers) {
      try {
        announcer.onRollbackRequested(event);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing the rollback of %s failed", applicationId);
      }
    }
    return new RollbackAccepted(
        applicationId,
        application,
        plan.environmentId(),
        plan.fromVersion(),
        plan.target().version(),
        plan.fromDeploymentId());
  }

  /** The rollback's guards over the rows, in the one transaction the caller opened. */
  private RollbackPlan planRollback(
      String applicationId, String application, String environmentId, String asked) {
    List<PdDeployment> rows = deployments.listForPlaceNewestFirst(application, environmentId);
    if (rows.isEmpty()) {
      throw new NotFoundException(
          "nothing has ever been deployed for " + applicationId + ", so there is nothing to roll back");
    }
    String tier = environments.require(environmentId).name;
    for (PdDeployment row : rows) {
      if (row.status == PdDeploymentStatus.QUEUED || row.status == PdDeploymentStatus.STARTING) {
        throw new ConflictException(
            "deployment " + row.id + " of " + applicationId + " is " + row.status
                + ": it decides what runs, so a rollback waits for it");
      }
    }
    List<PdDatabaseMove> unsettled =
        moves.list(
            "applicationName = ?1 and environmentName = ?2 and state not in ?3",
            application,
            tier,
            List.copyOf(PdDatabaseMoveState.TERMINAL));
    if (!unsettled.isEmpty()) {
      PdDatabaseMove move = unsettled.get(0);
      throw new ConflictException(
          "the database move "
              + move.id
              + " of `"
              + move.resourceName
              + "` of "
              + application
              + " is "
              + move.state
              + ": it decides where the data is, so a rollback waits for it to settle");
    }
    PdDeployment active =
        rows.stream()
            .filter(row -> row.status == PdDeploymentStatus.ACTIVE)
            .findFirst()
            .orElseThrow(
                () ->
                    new ConflictException(
                        applicationId
                            + " has no ACTIVE deployment to roll back: its newest is "
                            + rows.get(0).status));
    String serving = active.version;
    PdDeployment target =
        rows.stream()
            .filter(row -> SERVED.contains(row.status) && asked.equals(row.version))
            .findFirst()
            .orElse(null);
    if (target == null || asked.equals(serving)) {
      List<String> accepted =
          rows.stream()
              .filter(row -> SERVED.contains(row.status) && row.version != null)
              .map(row -> row.version)
              .filter(served -> !served.equals(serving))
              .distinct()
              .sorted((left, right) -> Versions.compare(right, left))
              .limit(NAMED_ROLLBACK_VERSIONS)
              .toList();
      String why =
          asked.equals(serving)
              ? asked + " is the version " + application + " is serving in " + tier + " now"
              : asked + " never served " + application + " in " + tier;
      throw new ConflictException(
          why
              + ", so there is nothing to roll back to; "
              + (accepted.isEmpty()
                  ? "no other version ever served there"
                  : "the versions that served there are " + String.join(", ", accepted)));
    }
    DatabaseMoves.Live coordinates = databaseMoves.coordinates(application, target);
    return new RollbackPlan(application, environmentId, tier, serving, active.id, coordinates);
  }

  /**
   * The named application's {@code update_order} at the target tag, read now — the variant's own
   * file for a variant. Stop-first, or no file at all, is a 409; a git host that does not answer is
   * a 502, since nothing about the request was wrong.
   */
  private void requireStartFirst(RollbackPlan plan) {
    DatabaseMoves.Live target = plan.target();
    String path =
        target.variant() == null ? SpecSource.SPEC_PATH : SpecSource.variantPath(target.variant());
    String at = SpecSource.tagRev(target.version());
    SpecSource.SpecRead read;
    try {
      read = specs.read(target.repository(), at, path);
    } catch (RuntimeException unreadable) {
      throw new BadGatewayException(
          "could not read " + path + " of " + plan.application() + " at " + at
              + " to check its update order: " + unreadable.getMessage(),
          unreadable);
    }
    if (!read.declared()) {
      throw new ConflictException(
          plan.application()
              + " declares no "
              + path
              + " at "
              + target.version()
              + ", so how it is updated is unknown; roll it back by a person");
    }
    if (read.spec().updateOrder() == DeploymentDriver.UpdateOrder.STOP_FIRST) {
      throw new ConflictException(
          plan.application()
              + " is stop-first at "
              + target.version()
              + ": a rollback would stop it before its successor starts; roll it back by a person");
    }
  }

  /**
   * The release's own application name — the MAIN application's — off the coordinates: the
   * package's segment when the request recorded one. A variant's request without a package names
   * its own application there, so the repository's name stands in, which is what the release's
   * main application is called by convention.
   */
  private static String releasedNameOf(DatabaseMoves.Live target) {
    if (target.variant() != null && PackageNames.applicationOf(target.packageName()) == null) {
      return target.repository().applicationName();
    }
    return target.releasedName();
  }

  private static Accepted accepted(Placed placed, Integer replicas) {
    return new Accepted(
        placed.applicationId(),
        placed.applicationName(),
        placed.environmentId(),
        placed.serviceName(),
        placed.deploymentId(),
        replicas);
  }

  /**
   * The application id, turned into the row an action acts on — on the <b>request</b> thread, so a
   * caller learns about a bad id, an unknown application or a place nothing ever deployed as an
   * answer rather than as a log line under a 202.
   *
   * <p>Deliberately <b>not</b> wrapped in {@code PdReadPatience}: this read is part of a write, and
   * a write's patience lives one layer down. What it would buy — a scale surviving a database
   * outage — is not worth a request thread sleeping while the operator has no idea whether anything
   * was issued.
   */
  private Placed resolve(String applicationId) {
    ApplicationKeys.Key key = keyOf(applicationId);
    // The name reaches a query and, through the row, a service name — checked where every other
    // stored identifier is, at the boundary.
    PdIdentifiers.requireName(key.applicationName(), "application name");
    Placed placed =
        QuarkusTransaction.joiningExisting()
            .call(
                () ->
                    deployments
                        .newestForPlace(key.applicationName(), key.environmentId())
                        .map(
                            row ->
                                new Placed(
                                    applicationId,
                                    row.applicationName,
                                    row.environmentId,
                                    row.containerName,
                                    row.id))
                        .orElse(null));
    if (placed == null) {
      throw new NotFoundException(
          "nothing has ever been deployed for " + applicationId + ", so there is nothing to run");
    }
    if (placed.serviceName() == null || placed.serviceName().isBlank()) {
      throw new ConflictException(
          "the newest deployment of "
              + applicationId
              + " never reached the orchestrator, so no service carries this application yet");
    }
    return placed;
  }

  private static ApplicationKeys.Key keyOf(String applicationId) {
    return ApplicationKeys.parse(applicationId)
        .orElseThrow(
            () ->
                new BadRequestException(
                    "'"
                        + applicationId
                        + "' is not an application id: it reads <environmentId>:<name>, or"
                        + " platform:<name> for an application on no tier"));
  }

  // --- on the worker -----------------------------------------------------------------------------

  private void applyScale(Placed placed, int replicas, String actor) {
    DeploymentDriver.ScaleResult result = driver.scale(placed.serviceName(), replicas);
    if (!result.applied()) {
      LOG.warnf(
          "Could not scale %s to %d: %s", placed.serviceName(), replicas, result.detail());
      return;
    }
    Instant at = Instant.now();
    if (replicas == 0) {
      settle(
          placed,
          PdDeploymentStatus.SCALED_TO_ZERO,
          "[scaled to 0 by "
              + who(actor)
              + " at "
              + at
              + ": "
              + placed.serviceName()
              + " is declared to run no tasks, and the deployment is otherwise untouched]");
      return;
    }
    settle(
        placed,
        null,
        "[scaled to "
            + replicas
            + " by "
            + who(actor)
            + " at "
            + at
            + ": the observation settles this row when the tasks are healthy]");
  }

  private void applyRestart(Placed placed, String actor) {
    DeploymentDriver.ScaleResult result = driver.restart(placed.serviceName());
    if (!result.applied()) {
      LOG.warnf("Could not restart %s: %s", placed.serviceName(), result.detail());
      return;
    }
    settle(
        placed,
        null,
        "[restarted by "
            + who(actor)
            + " at "
            + Instant.now()
            + ": the tasks of "
            + placed.serviceName()
            + " were replaced, and this deployment is unchanged]");
  }

  /**
   * Write the stamp, and the status when the action has one to write.
   *
   * <p>{@code status} null means "stamp only", which is a restart and a scale back up. A {@code
   * QUEUED} or {@code STARTING} row keeps its status whatever the action was: those belong to the
   * worker's own state machine, a deployment is queued for this place behind us, and that deployment
   * is what will settle the row.
   *
   * <p>A {@link DbRetry#runInNewTx} bracket for the reason the observer's are: this is bookkeeping
   * that runs <i>after</i> a docker call, so losing it leaves the platform in a state no row admits
   * to. The docker call is outside it, never inside.
   */
  private void settle(Placed placed, PdDeploymentStatus status, String stamp) {
    DbRetry.runInNewTx(
        "The operator action on deployment " + placed.deploymentId(),
        () -> {
          PdDeployment row = deployments.findById(placed.deploymentId());
          if (row == null) {
            return; // torn down while the action ran
          }
          if (status != null
              && row.status != PdDeploymentStatus.QUEUED
              && row.status != PdDeploymentStatus.STARTING) {
            row.status = status;
          }
          row.detail = stamped(stamp, row.detail);
          // Flushed rather than left to the commit, so a lost connection is a body failure the
          // retry can safely repeat — the observer's own brackets say the same thing at length.
          deployments.flush();
        },
        CUTOVER_BUDGET);
    LOG.infof("%s on %s (deployment %s)", stamp, placed.serviceName(), placed.deploymentId());
  }

  /**
   * The opening words of every operator stamp this component writes — the three levers' own, and
   * the whole of what {@link #withoutLeadingStamp} drops.
   *
   * <p>{@code [decommissioned by } is {@code ApplicationRetirement}'s and belongs on this list for
   * the reason the other two are: a door called twice must not grow a text column. It lives here
   * rather than there because there is exactly one stamp format on a deployment row and one place
   * that knows it.
   */
  private static final String[] STAMP_PREFIXES = {
    "[scaled to ", "[restarted by ", "[decommissioned by "
  };

  /**
   * The new stamp over whatever the row already said, with a previous operator stamp dropped.
   *
   * <p>Package-private for its own test, and for {@code ApplicationRetirement}, which writes the
   * third kind. The deployment's diagnosis is everything under the stamp and is kept whole —
   * eaa34fbc is the row where that text was the reason a bug was findable at all — while a stamp is
   * a statement about now and there is no reason to keep yesterday's.
   */
  static String stamped(String stamp, String detail) {
    String previous = withoutLeadingStamp(detail);
    return previous.isBlank() ? stamp : stamp + "\n" + previous;
  }

  private static String withoutLeadingStamp(String detail) {
    if (detail == null) {
      return "";
    }
    String trimmed = detail.stripLeading();
    boolean stamped = false;
    for (String prefix : STAMP_PREFIXES) {
      stamped |= trimmed.startsWith(prefix);
    }
    if (!stamped) {
      return detail;
    }
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? "" : trimmed.substring(newline + 1);
  }

  private static String who(String actor) {
    return actor == null || actor.isBlank() ? "an operator" : actor;
  }
}
