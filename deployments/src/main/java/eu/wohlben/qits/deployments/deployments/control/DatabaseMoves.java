package eu.wohlben.qits.deployments.deployments.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMove;
import eu.wohlben.qits.deployments.deployments.entity.PdDatabaseMoveState;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.deployments.deployments.persistence.PdDatabaseMoveRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdOwedReleaseRepository;
import eu.wohlben.qits.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.error.BadGatewayException;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.deployments.environments.error.ConflictException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Moving a postgres claim from one (server, database) to another (qits-2175) — between the two
 * servers, between two names on one server, or both — with the data, verified, and with nobody on the
 * host.
 *
 * <p><b>Why it exists: a changed target used to be an EMPTY database, silently.</b> {@link
 * ResourceProvisioning} finds a claim by (application, tier, resource NAME), so when the database a
 * deployment resolves changed — a {@code resources:} pin edited or removed, an application renamed
 * through {@code renamed_from} so the convention name followed — it reused the stored password,
 * created a new role and an empty database under the new name, rewrote the row, and started the
 * service green on nothing. The old database stayed behind, orphaned. A changed SERVER (an {@code
 * exposure:} that became {@code private}) has the same shape one column over. Neither is a provisioning
 * question: both are this class's, and both run before provisioning in the same deployment.
 *
 * <p><b>When — {@link #beforeProvisioning}.</b> For each declared {@code postgresql} resource the
 * CLAIM is the application's own claim for that resource name, or — on a {@code renamed_from} deploy
 * with no claim of its own — the predecessor's claim for the same name. The TARGET is the rule's
 * server ({@link DatabaseServer#forExposure}) and the database {@code resolve()} answers. No claim:
 * provisioning as before. The same (server, database): as before, no move. Anything else: a move.
 *
 * <p><b>How — the five steps, each recorded durably in {@code pd_database_move}.</b>
 *
 * <ol>
 *   <li><b>Stop.</b> The service holding the claim is scaled to zero ({@link DeploymentDriver#scale})
 *       and waited for until no task of it is left running; the source is closed to new connections
 *       and the rest are terminated.
 *   <li><b>Extract.</b> A container on the SOURCE server's network only, with a fresh volume {@code
 *       qits-dbmove-<id>}: {@code pg_dump -Fc} into it, and an exact {@code count(*)} of every table
 *       into a manifest beside it.
 *   <li><b>Ingest.</b> A container on the TARGET server's network only, with the same volume: the
 *       target role with the claim's stored password (the service's credential does not change) and
 *       the target database owned by it, {@code pg_restore --no-owner --role=<role>}, the same
 *       manifest of the target.
 *   <li><b>Compare.</b> The same tables and the same count in each, or the move ABORTS: the target is
 *       dropped, the source re-opened, the service scaled back up on data nothing touched, and the
 *       deployment fails naming the first table that differed.
 *   <li><b>Switch.</b> The claim takes the target (password unchanged); the source is renamed {@code
 *       <database>_moved_<yyyyMMddHHmm>}, still closed; the volume goes; {@code drop_after} is now plus
 *       {@code qits.deployments.database-move.retention}. The deployment then carries on against the
 *       target, which is where provisioning finds the claim.
 * </ol>
 *
 * <p><b>Two names on ONE server are a rename, not a copy</b> (qits-2193, the owner's ruling of
 * 2026-11-14): steps 2 to 4 are {@link #renameInPlace} instead — {@code ALTER DATABASE … RENAME TO
 * …} and the role with it, in one transaction, the password set again and {@code CONNECT} granted
 * back under the new name. Instant, needing no space and nothing to compare; the switch then keeps
 * no copy ({@code kept_database} and {@code drop_after} null), and an abort renames it back. A dump
 * and a restore stay for a move between the servers.
 *
 * <p><b>Every container joins exactly ONE network</b> — the server's own — so a move between the
 * servers is two containers that share a volume rather than one container that spans two networks,
 * and a move between two names on one server runs both on that one network. See {@link
 * DatabaseContainers} for the seam's other two rules: no secret in an argv, redacted output.
 *
 * <p><b>The moves of one deployment are one batch</b>: the service is stopped once, every database is
 * copied and verified, and only then does any claim switch. An application with two databases that
 * came back up with one moved and one not would be pointed at a store it left behind.
 *
 * <p><b>The deployer's own databases are handed over, not moved here</b> (qits-2209): it cannot stop
 * itself, so {@link #plan} decides the move and its successor runs it before it starts — see {@link
 * SelfMove}.
 *
 * <p><b>Refused before anything stops</b>: a target database another
 * claim already holds, a target database that already exists on the target server and is not an
 * earlier aborted attempt of this same move — and a batch whose copies do not FIT (qits-2243, {@link
 * MoveSpace}). Only a move between the servers copies, so only it is measured: the source's {@code
 * pg_database_size}, the free space of the target's data directory (its own {@code df}) and of the
 * move's volume, each copy counting as {@code space-factor × size}, every filesystem keeping {@code
 * space-reserve} free; a measure that fails refuses too. The row records the three readings either
 * way — null for a rename in place, which needs no space.
 *
 * <p><b>An operator may abort a move that is running</b> ({@link #abort}, qits-2243) — up to its
 * switch, never after it: the request thread records who asked and kills the move's running one-shot,
 * and the worker, which checks after every step and between drain polls, aborts the batch as any
 * failure does.
 *
 * <p><b>It runs on {@code pd-deploy-worker} and nowhere else</b> — inside the deployment, or as the
 * operator's queued redeploy ({@link #request}) — so a move never interleaves with a cutover, an
 * observation, or another move. A restart mid-move is {@link #settleInterrupted}: before the switch it
 * aborts, from the switch on it finishes. Never forgotten.
 */
@ApplicationScoped
public class DatabaseMoves {

  private static final Logger LOG = Logger.getLogger(DatabaseMoves.class);

  /** The volume a move's two containers share: {@code qits-dbmove-<move id>}. */
  static final String VOLUME_PREFIX = "qits-dbmove-";

  /** How often the stop polls the service's tasks while it waits for them to be gone. */
  private static final Duration DRAIN_POLL = Duration.ofSeconds(2);

  /**
   * Docker's own default {@code stop_grace_period} — ten seconds — read as the grace when {@link
   * DeploymentDriver#stopGracePeriod} cannot say. Never the application default this component
   * itself ships ({@link DeploymentDriver#DEFAULT_STOP_GRACE_PERIOD}, thirty seconds): a live
   * service inspected here may be one swarm itself defaulted, not one this deployer created.
   */
  private static final Duration DOCKER_DEFAULT_STOP_GRACE_PERIOD = Duration.ofSeconds(10);

  /** How many moves a listing answers — one screen, the deployment listing's own stance. */
  static final int LIST_LIMIT = 200;

  private static final TypeReference<TreeMap<String, Long>> COUNTS = new TypeReference<>() {};

  @Inject PdDatabaseMoveRepository moves;
  @Inject PdResourceRepository resources;
  @Inject PdDeploymentRepository deployments;
  @Inject PdDeploymentRequestRepository requests;
  @Inject PdOwedReleaseRepository owed;
  @Inject ResourceProvisioning provisioning;
  @Inject DatabaseContainers containers;
  @Inject PostgresAdmin admin;
  @Inject DeploymentDriver driver;
  @Inject SpecSource specs;
  @Inject EnvironmentService environments;
  @Inject DeployService worker;
  @Inject ObjectMapper json;

  /** How long a moved source is kept, closed, before the sweep drops it. {@code P7D} shipped. */
  @ConfigProperty(name = "qits.deployments.database-move.retention")
  Duration retention;

  /** The deadline of each extract or ingest container. */
  @ConfigProperty(name = "qits.deployments.database-move.timeout")
  Duration timeout;

  /**
   * The deadline of the deployer's own extract and ingest, which its successor runs before it starts
   * (qits-2209). The self-update's health start period grows by the same amount.
   */
  @ConfigProperty(name = SELF_TIMEOUT_KEY)
  Duration selfTimeout;

  public static final String SELF_TIMEOUT_KEY = "qits.deployments.database-move.self-timeout";

  /**
   * How long the stop waits for the service's tasks to be gone — a FLOOR, not the whole answer
   * (qits-2228): {@link #drainWait} raises it to the live service's own {@code stop_grace_period}
   * plus {@link DeploymentDriver#RECREATE_DRAIN_MARGIN} when that is longer, so a service that
   * declares a long grace to drain its own connections (the private edge's {@code 10m}) is waited
   * out rather than aborted mid-drain.
   */
  @ConfigProperty(name = "qits.deployments.database-move.stop-timeout")
  Duration stopTimeout;

  /** What one copy of the source counts as, times its size (qits-2243). {@code 1.1} shipped. */
  @ConfigProperty(name = MoveSpace.FACTOR_KEY)
  double spaceFactor;

  /** What every filesystem a move writes to keeps free on top ({@link MoveSpace#parseSize}). */
  @ConfigProperty(name = MoveSpace.RESERVE_KEY)
  String spaceReserve;

  /**
   * The aborts asked for and not yet acted on: move id → who asked (qits-2243). Memory on purpose — a
   * restart aborts every move before its switch anyway. Written by the request thread, read by the
   * worker between steps.
   */
  private final Map<String, String> aborts = new ConcurrentHashMap<>();

  /** The moves the worker is switching: an abort asked now is a 409, never a flag nobody reads. */
  private final Set<String> switching = ConcurrentHashMap.newKeySet();

  /** Makes "may it still abort?" and "the worker starts switching" one decision. */
  private final Object abortLock = new Object();

  /**
   * The operator's moves whose redeploy is running right now, by {@link #key} — what {@link
   * #beforeProvisioning} reads the target from instead of the rule. Written and read on the worker
   * alone; concurrent only for the request thread's sake, which never touches it.
   */
  private final Map<String, String> held = new ConcurrentHashMap<>();

  // --- the deployment's half --------------------------------------------------------------------

  /**
   * Run every move this deployment's declared resources need, before anything is provisioned — or
   * nothing, which is every deployment whose claims already are where its spec says.
   *
   * <p><b>A target server that is not deployed DEFERS the move rather than failing the
   * deployment</b> — the rollout gap between this deployer and {@code qits-oci-postgresql-private}.
   * The claim stays where it is, the deployment provisions against it exactly as before (the
   * answer's entry for that resource names the CLAIM's database), and one WARN says so; the first
   * deployment after the server exists moves it.
   *
   * @return the declared resources as provisioning should see them: unchanged, except that a
   *     deferred move's resource names the database its claim still holds
   * @throws ResourceException with the operator-facing sentence when a move was refused or aborted;
   *     the deployment fails on it, and an abort has already put the old service back
   */
  public List<ResourceProvisioning.Resolved> beforeProvisioning(
      String applicationName,
      String environmentName,
      String renamedFrom,
      DeploymentDriver.Exposure exposure,
      List<ResourceProvisioning.Resolved> declared) {
    return prepare(applicationName, environmentName, renamedFrom, exposure, declared).resolved();
  }

  /**
   * What {@link #prepare} hands the deployment: the resources as provisioning must see them, and
   * the moves this deployment SWITCHED — after which the old service cannot simply be scaled back
   * up, because its binding names a source that is now renamed and fenced.
   */
  public record Prepared(List<ResourceProvisioning.Resolved> resolved, List<PdDatabaseMove> switched) {

    /** One line naming every switched move and the service it stopped, for the failure path. */
    public String describeSwitched() {
      return switched.stream()
          .map(
              move ->
                  "`"
                      + move.resourceName
                      + "` of "
                      + move.applicationName
                      + " moved from "
                      + where(DatabaseServer.parse(move.sourceServer), move.sourceDatabase)
                      + " to "
                      + where(DatabaseServer.parse(move.targetServer), move.targetDatabase)
                      + (move.keptDatabase == null
                          ? " (renamed in place)"
                          : " (the source kept as " + move.keptDatabase + ", fenced)")
                      + ", its service "
                      + (move.serviceName == null ? "(none was running)" : move.serviceName)
                      + " stopped")
          .collect(Collectors.joining("; "));
    }
  }

  /**
   * {@link #beforeProvisioning}, also answering which moves switched.
   *
   * <p><b>An operator's held target is what provisioning sees for that resource</b> (qits-2175,
   * observed live on qits-orchestrator): a move whose target the door overrode switches the claim to
   * that target, so the resolved entry handed on must name it too — otherwise provisioning's backstop
   * compares the switched claim against the spec's database, refuses it as a move that "has not
   * run", and the deployment fails with its service stopped on a database that already moved. Every
   * other resource keeps exactly the database {@code resolve()} answered, so the backstop still
   * guards every other case.
   */
  public Prepared prepare(
      String applicationName,
      String environmentName,
      String renamedFrom,
      DeploymentDriver.Exposure exposure,
      List<ResourceProvisioning.Resolved> declared) {
    return run(plan(applicationName, environmentName, renamedFrom, exposure, declared));
  }

  /**
   * The moves a deployment's resources need, DECIDED and refused-or-not — nothing stopped yet
   * (qits-2249). {@code DeployService} provisions everything a move does not touch, and pulls the
   * image, between this and {@link #run}: the service a move stops may be the very one the rest of
   * the deploy needs (qits-idp's own {@code idp:client} is provisioned BY qits-idp), so nothing that
   * does not depend on the moved database may wait until after the stop.
   *
   * @param resolved the declared resources as provisioning must see them, a moved one naming its
   *     target and a deferred one its claim's database
   * @param planned the moves {@link #run} performs; empty is every deployment with nothing to move
   */
  record Pending(
      List<ResourceProvisioning.Resolved> resolved, List<Planned> planned, List<Planned> handedOver) {

    Pending(List<ResourceProvisioning.Resolved> resolved, List<Planned> planned) {
      this(resolved, planned, List.of());
    }

    /**
     * The names of the resources a move touches — none of them is provisioned before the stop. A
     * move this deployment runs is provisioned after its switch; one handed over is not provisioned
     * at all (see {@link #handedOverResources}).
     */
    Set<String> movedResources() {
      return java.util.stream.Stream.concat(planned.stream(), handedOver.stream())
          .map(Planned::resourceName)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The deployer's own moves (qits-2209): its successor runs them before it starts, so nothing
     * here stops, copies or provisions them. Their bindings come from {@link #handOverBindings}.
     */
    Set<String> handedOverResources() {
      return handedOver.stream()
          .map(Planned::resourceName)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }
  }

  /** {@link #prepare}'s decision half: every refusal, before anything stops. See {@link Pending}. */
  Pending plan(
      String applicationName,
      String environmentName,
      String renamedFrom,
      DeploymentDriver.Exposure exposure,
      List<ResourceProvisioning.Resolved> declared) {
    if (declared == null || declared.isEmpty() || environmentName == null) {
      return new Pending(declared == null ? List.of() : declared, List.of());
    }
    // Abandonment first: a predecessor's resource the successor stopped declaring is refused before
    // any service is stopped, exactly as it was refused before anything was provisioned.
    provisioning.refuseAbandonedPredecessorClaims(
        applicationName, environmentName, renamedFrom, declared);
    DatabaseServer rule = DatabaseServer.forExposure(exposure);

    List<Planned> planned = new ArrayList<>();
    List<ResourceProvisioning.Resolved> effective = new ArrayList<>();
    for (ResourceProvisioning.Resolved resource : declared) {
      effective.add(resource);
      if (resource.type() != ResourceSpec.Type.POSTGRESQL) {
        continue;
      }
      String name = PdIdentifiers.requireResourceName(resource.name());
      String database = PdIdentifiers.requireDatabaseName(resource.database());
      Optional<Claimed> claim = claimFor(applicationName, environmentName, renamedFrom, name);
      String heldId = held.get(key(applicationName, environmentName, name));
      PdDatabaseMove heldRow = heldId == null ? null : read(heldId);
      if (claim.isEmpty()) {
        if (heldRow != null) {
          refuse(heldRow, "there is no claim of `" + name + "` left to move");
        }
        continue;
      }
      DatabaseServer targetServer =
          heldRow != null ? DatabaseServer.parse(heldRow.targetServer) : rule;
      String targetDatabase = heldRow != null ? heldRow.targetDatabase : database;
      Claimed claimed = claim.get();
      if (claimed.server() == targetServer && claimed.database().equals(targetDatabase)) {
        if (heldRow != null) {
          refuse(heldRow, "nothing to move: `" + name + "` already is " + where(targetServer, targetDatabase));
        }
        continue;
      }
      if (claimed.server() != targetServer && !admin.deployed(targetServer, environmentName)) {
        String deferred = deferral(targetServer, environmentName);
        LOG.warnf(
            "%s keeps the database %s on %s — moving it to %s is %s",
            applicationName,
            claimed.database(),
            where(claimed.server(), claimed.database()),
            where(targetServer, targetDatabase),
            deferred);
        if (heldRow != null) {
          refuse(heldRow, deferred);
        }
        // Provisioning finds the claim where it is, under the name it has.
        effective.set(
            effective.size() - 1,
            new ResourceProvisioning.Resolved(name, claimed.database(), resource.type()));
        continue;
      }
      planned.add(
          new Planned(
              heldRow != null ? heldRow.id : UUID.randomUUID().toString(),
              applicationName,
              environmentName,
              name,
              claimed,
              targetServer,
              targetDatabase,
              heldRow));
      // Provisioning finds the claim where the move puts it: the spec's database for a deployment's
      // own move, the held target for an operator's override of it.
      effective.set(
          effective.size() - 1,
          new ResourceProvisioning.Resolved(name, targetDatabase, resource.type()));
    }
    if (planned.isEmpty()) {
      return new Pending(List.copyOf(effective), List.of());
    }
    if (BootResourceRegistration.APPLICATION.equals(applicationName)) {
      // The deployer's own databases (qits-2209): it cannot stop itself, so its successor moves
      // them. Decided and refused here like any move; nothing stops.
      for (Planned move : planned) {
        String refusal = selfRefusal(move);
        if (refusal != null) {
          recordRefusal(move, refusal, move.id(), null);
          throw new ResourceException(refusal);
        }
      }
      return new Pending(List.copyOf(effective), List.of(), List.copyOf(planned));
    }

    // Every refusal before anything stops.
    for (Planned move : planned) {
      String refusal = refusal(move);
      if (refusal != null) {
        recordRefusal(move, refusal, move.id(), null);
        throw new ResourceException(refusal);
      }
    }
    return new Pending(List.copyOf(effective), List.copyOf(planned));
  }

  /**
   * {@link #prepare}'s doing half: measure, then stop, move, verify and switch what {@link #plan}
   * decided. The measurement (qits-2243) still runs before anything stops.
   */
  Prepared run(Pending pending) {
    if (pending.planned().isEmpty()) {
      return new Prepared(pending.resolved(), List.of());
    }
    String batch = UUID.randomUUID().toString();
    List<MoveSpace.Measured> measured = measure(batch, pending.planned());
    return new Prepared(pending.resolved(), run(batch, pending.planned(), measured));
  }

  /** The one sentence a deferred move is told by — the deployment's WARN and the door's 409. */
  static String deferral(DatabaseServer server, String environmentName) {
    return "deferred: the " + server.wire() + " postgres is not deployed in " + environmentName;
  }

  /** A claim, read out of its transaction: the row's id and what the move needs of it. */
  record Claimed(
      String id, String applicationName, String database, DatabaseServer server, String password) {}

  /**
   * One move this deployment will run, decided and not yet started. {@code id} is its row's: the
   * operator's row, or one chosen now — the move's volume is named by it before the row exists.
   */
  record Planned(
      String id,
      String applicationName,
      String environmentName,
      String resourceName,
      Claimed claim,
      DatabaseServer targetServer,
      String targetDatabase,
      PdDatabaseMove operatorRow) {

    DatabaseServer sourceServer() {
      return claim.server();
    }

    String sourceDatabase() {
      return claim.database();
    }

    /** Two names on one server: a rename in place, not a copy (qits-2193). */
    boolean inPlace() {
      return claim.server() == targetServer;
    }
  }

  /**
   * The claim a deployment of {@code applicationName} compares against for resource {@code name}:
   * its own, or on a rename with no claim of its own the predecessor's for the same name.
   */
  private Optional<Claimed> claimFor(
      String applicationName, String environmentName, String renamedFrom, String name) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Optional<PdResource> own = postgresClaim(applicationName, environmentName, name);
              if (own.isEmpty() && renamedFrom != null && !renamedFrom.equals(applicationName)) {
                own = postgresClaim(renamedFrom, environmentName, name);
              }
              return own.map(
                  row ->
                      new Claimed(
                          row.id,
                          row.applicationName,
                          row.databaseName,
                          DatabaseServer.parse(row.server),
                          row.password));
            });
  }

  private Optional<PdResource> postgresClaim(String application, String environment, String name) {
    return resources
        .findOne(application, environment, name)
        .filter(row -> ResourceProvisioning.RESOURCE_TYPE.equals(row.resourceType))
        .filter(row -> row.databaseName != null);
  }

  /**
   * Why the deployer's own move must not be handed over, or null (qits-2209): a target another claim
   * holds, or a target that exists and is not a leftover of an earlier self-move — its comment says
   * which ({@link SelfMove#leftover}). Asked before the self-update, so a refusal stops nothing.
   */
  private String selfRefusal(Planned move) {
    if (renamedOnto(move)) {
      return "refusing to move "
          + where(move.sourceServer(), move.sourceDatabase())
          + ": the deployer moves only its own claims, not a predecessor's";
    }
    String elsewhere =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    resources.listByDatabase(move.targetDatabase()).stream()
                        .filter(row -> !row.id.equals(move.claim().id()))
                        // Only a claim on the target server itself: each tier has its own servers.
                        .filter(row -> move.environmentName().equals(row.environmentName))
                        .filter(row -> DatabaseServer.parse(row.server) == move.targetServer())
                        .map(row -> row.applicationName)
                        .findFirst()
                        .orElse(null));
    if (elsewhere != null) {
      return "refusing to move "
          + where(move.sourceServer(), move.sourceDatabase())
          + " to `"
          + move.targetDatabase()
          + "`: that database is already provisioned for "
          + elsewhere;
    }
    try {
      DatabaseContainers.Outcome asked =
          containers.run(
              admin.shot(
                  "check",
                  move.targetServer(),
                  move.environmentName(),
                  admin.image(move.targetServer(), move.environmentName()),
                  null,
                  Map.of(),
                  DatabaseScripts.describe(move.targetDatabase())),
              ContainerResourceProvisioner.TIMEOUT);
      Map<String, DatabaseScripts.Described> seen =
          asked.ok() ? DatabaseScripts.describeAnswer(asked.output(), move.targetDatabase()) : null;
      if (seen == null) {
        return "could not ask the "
            + move.targetServer().wire()
            + " postgres whether `"
            + move.targetDatabase()
            + "` exists, so nothing was moved: "
            + tail(asked.output());
      }
      DatabaseScripts.Described target = seen.get(move.targetDatabase());
      if (target.exists() && !SelfMove.leftover(target.mark())) {
        return "refusing to move "
            + where(move.sourceServer(), move.sourceDatabase())
            + " onto "
            + where(move.targetServer(), move.targetDatabase())
            + ": that database already exists there, and no earlier move of the deployer left it";
      }
      // Both images are read now, while the deployer can still say why it cannot move.
      admin.image(move.sourceServer(), move.environmentName());
    } catch (ResourceException unreachable) {
      return unreachable.getMessage() + ", so nothing was moved";
    }
    return null;
  }

  /** Whether the claim is a predecessor's — a rename, which the deployer refuses for itself. */
  private static boolean renamedOnto(Planned move) {
    return !move.applicationName().equals(move.claim().applicationName());
  }

  /**
   * The bindings the deployer's successor gets for its handed-over moves (qits-2209): each one names
   * the TARGET — the database the successor will run on — with the claim's role password, and
   * carries the {@link SelfMove.Step} as {@code QITS_RESOURCE_<NAME>_MOVE}. Nothing is provisioned:
   * the successor's copy or rename makes the target.
   */
  List<DeploymentDriver.ResourceBinding> handOverBindings(Pending pending) {
    List<DeploymentDriver.ResourceBinding> bindings = new ArrayList<>();
    for (Planned move : pending.handedOver()) {
      String environment = move.environmentName();
      SelfMove.Step step =
          new SelfMove.Step(
              move.id(),
              environment,
              move.resourceName(),
              move.sourceServer(),
              move.sourceServer().host(environment),
              admin.network(move.sourceServer()),
              move.sourceDatabase(),
              move.targetServer(),
              move.targetServer().host(environment),
              admin.network(move.targetServer()),
              move.targetDatabase(),
              // One image for both one-shots, the source server's — what a copy always uses.
              admin.image(move.sourceServer(), environment),
              admin.adminUsername(),
              selfTimeout.toSeconds());
      bindings.add(
          new DeploymentDriver.ResourceBinding(
              move.resourceName(),
              List.of(
                  new DeploymentDriver.ResourceBinding.Value(
                      "URL",
                      ResourceProvisioning.jdbcUrl(move.targetServer(), environment, move.targetDatabase())),
                  new DeploymentDriver.ResourceBinding.Value("USERNAME", move.targetDatabase()),
                  new DeploymentDriver.ResourceBinding.Value("PASSWORD", move.claim().password()),
                  new DeploymentDriver.ResourceBinding.Value(SelfMove.SUFFIX, step.encode()))));
      LOG.infof(
          "%s (%s) hands the move of `%s` from %s to %s to its successor, which runs it before it"
              + " starts (move %s)",
          move.applicationName(),
          environment,
          move.resourceName(),
          where(move.sourceServer(), move.sourceDatabase()),
          where(move.targetServer(), move.targetDatabase()),
          move.id());
    }
    return List.copyOf(bindings);
  }

  /** Why this move must not start, or null. Asked of every move of the batch before any starts. */
  private String refusal(Planned move) {
    if (BootResourceRegistration.APPLICATION.equals(move.applicationName())
        || BootResourceRegistration.APPLICATION.equals(move.claim().applicationName())) {
      return "refusing to move "
          + where(move.sourceServer(), move.sourceDatabase())
          + ": it is "
          + BootResourceRegistration.APPLICATION
          + "'s own database, and only the deployer's own deployment moves it, through its"
          + " successor (qits-2209)";
    }
    String elsewhere =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    resources.listByDatabase(move.targetDatabase()).stream()
                        .filter(row -> !row.id.equals(move.claim().id()))
                        .map(row -> row.applicationName)
                        .findFirst()
                        .orElse(null));
    if (elsewhere != null) {
      return "refusing to move "
          + where(move.sourceServer(), move.sourceDatabase())
          + " to `"
          + move.targetDatabase()
          + "`: that database is already provisioned for "
          + elsewhere;
    }
    Boolean exists;
    try {
      String image = admin.image(move.targetServer(), move.environmentName());
      DatabaseContainers.Outcome asked =
          containers.run(
              admin.shot(
                  "check",
                  move.targetServer(),
                  move.environmentName(),
                  image,
                  null,
                  Map.of(),
                  DatabaseScripts.exists(move.targetDatabase())),
              ContainerResourceProvisioner.TIMEOUT);
      exists = asked.ok() ? DatabaseScripts.existsAnswer(asked.output()) : null;
      if (exists == null) {
        return "could not ask the "
            + move.targetServer().wire()
            + " postgres whether `"
            + move.targetDatabase()
            + "` exists, so nothing was moved: "
            + tail(asked.output());
      }
    } catch (ResourceException unreachable) {
      return unreachable.getMessage() + ", so nothing was moved";
    }
    if (exists && !leftover(move)) {
      return "refusing to move "
          + where(move.sourceServer(), move.sourceDatabase())
          + " onto "
          + where(move.targetServer(), move.targetDatabase())
          + ": that database already exists there, and no earlier move of this claim left it — drop"
          + " or rename it by hand first";
    }
    return null;
  }

  /**
   * Measure every move of the batch that COPIES — between the two servers; a rename in place on one
   * server writes nothing and is skipped, recording no reading — and refuse the batch when those do
   * not fit or cannot be measured (qits-2243) — after {@link #refusal} and before anything stops, so the refusal path is the one
   * every other refusal takes: recorded, the service still running, the deployment failed.
   *
   * <p>Per move: the volume {@code qits-dbmove-<id>} is created first (the dump will be written into
   * it, so it is measured where it is), then {@link DatabaseScripts#size} on the SOURCE server and
   * {@link DatabaseScripts#space} on the TARGET server with the volume mounted. {@link MoveSpace}
   * decides. A refusal removes every volume it created, because nothing else will.
   */
  private List<MoveSpace.Measured> measure(String batch, List<Planned> planned) {
    List<MoveSpace.Measured> measured = new ArrayList<>();
    String failure = null;
    for (Planned move : planned) {
      if (move.inPlace()) {
        // A rename in place (qits-2193) writes no dump and no copy: nothing to measure, no volume.
        measured.add(new MoveSpace.Measured(null, null, null));
        continue;
      }
      String volume = VOLUME_PREFIX + move.id();
      Long size = null;
      DatabaseScripts.Filesystem data = null;
      DatabaseScripts.Filesystem onVolume = null;
      if (!containers.createVolume(volume)) {
        failure = "could not create the volume " + volume + " for the move of "
            + where(move.sourceServer(), move.sourceDatabase()) + ", so nothing was moved";
      } else {
        try {
          DatabaseContainers.Outcome sized =
              containers.run(
                  admin.shot(
                      "size",
                      move.sourceServer(),
                      move.environmentName(),
                      admin.image(move.sourceServer(), move.environmentName()),
                      null,
                      Map.of(),
                      DatabaseScripts.size(move.sourceDatabase())),
                  ContainerResourceProvisioner.TIMEOUT);
          size = sized.ok() ? DatabaseScripts.sizeAnswer(sized.output()) : null;
          if (size == null) {
            failure =
                "could not measure the size of "
                    + where(move.sourceServer(), move.sourceDatabase())
                    + ", so nothing was moved: "
                    + tail(sized.output());
          } else {
            DatabaseContainers.Outcome spaced =
                containers.run(
                    admin.shot(
                        "space",
                        move.targetServer(),
                        move.environmentName(),
                        admin.image(move.targetServer(), move.environmentName()),
                        volume,
                        Map.of(),
                        DatabaseScripts.space()),
                    ContainerResourceProvisioner.TIMEOUT);
            if (spaced.ok()) {
              data = DatabaseScripts.filesystem(spaced.output(), DatabaseScripts.DATA_DF_MARKER);
              onVolume = DatabaseScripts.filesystem(spaced.output(), DatabaseScripts.VOLUME_DF_MARKER);
            }
            if (data == null || onVolume == null) {
              failure =
                  "could not measure the free space of "
                      + (data == null ? "the " + move.targetServer().wire() + " postgres' data directory" : "")
                      + (data == null && onVolume == null ? " and of " : "")
                      + (onVolume == null ? "the volume " + volume : "")
                      + ", so nothing was moved: "
                      + tail(spaced.output());
            }
          }
        } catch (ResourceException unreachable) {
          failure = unreachable.getMessage() + ", so nothing was moved";
        }
      }
      measured.add(new MoveSpace.Measured(size, data, onVolume));
      if (failure != null) {
        break;
      }
    }
    if (failure == null) {
      List<MoveSpace.Move> batchMoves = new ArrayList<>();
      for (int i = 0; i < planned.size(); i++) {
        Planned move = planned.get(i);
        if (move.inPlace()) {
          continue;
        }
        batchMoves.add(
            new MoveSpace.Move(
                where(move.sourceServer(), move.sourceDatabase()),
                where(move.targetServer(), move.targetDatabase()),
                measured.get(i)));
      }
      failure = MoveSpace.refusal(batchMoves, spaceFactor, reserveBytes());
    }
    if (failure != null) {
      for (int i = 0; i < planned.size(); i++) {
        if (i < measured.size() && !planned.get(i).inPlace()) {
          containers.removeVolume(VOLUME_PREFIX + planned.get(i).id());
        }
        recordRefusal(planned.get(i), failure, batch, i < measured.size() ? measured.get(i) : null);
      }
      throw new ResourceException(failure);
    }
    return measured;
  }

  private long reserveBytes() {
    try {
      return MoveSpace.parseSize(spaceReserve);
    } catch (IllegalArgumentException wrong) {
      throw new ResourceException(wrong.getMessage() + ", so nothing was moved");
    }
  }

  private boolean leftover(Planned move) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                moves.abortedBefore(
                    move.claim().applicationName(),
                    move.environmentName(),
                    move.resourceName(),
                    move.targetServer().wire(),
                    move.targetDatabase())
                    || moves.abortedBefore(
                        move.applicationName(),
                        move.environmentName(),
                        move.resourceName(),
                        move.targetServer().wire(),
                        move.targetDatabase()));
  }

  // --- the move itself --------------------------------------------------------------------------

  /**
   * Stop once, copy and verify every move, then switch them all — or abort them all.
   *
   * @return the switched rows, as they ended
   */
  private List<PdDatabaseMove> run(String batch, List<Planned> planned, List<MoveSpace.Measured> measured) {
    List<String> ids = new ArrayList<>();
    long reserve = reserveBytes();
    for (int i = 0; i < planned.size(); i++) {
      Planned move = planned.get(i);
      ids.add(start(batch, move, measured.get(i)));
      LOG.infof(
          "Database move %s of %s (%s, `%s`) from %s to %s: %s",
          move.id(),
          move.applicationName(),
          move.environmentName(),
          move.resourceName(),
          where(move.sourceServer(), move.sourceDatabase()),
          where(move.targetServer(), move.targetDatabase()),
          move.inPlace()
              ? "a rename in place, which needs no space"
              : MoveSpace.describe(measured.get(i), spaceFactor, reserve));
    }
    try {
      try {
        checkAborted(ids);
        stopServices(planned, ids);
        for (int i = 0; i < planned.size(); i++) {
          Planned move = planned.get(i);
          if (move.inPlace()) {
            renameInPlace(move, ids.get(i), ids);
          } else {
            copy(move, ids.get(i), ids);
          }
        }
        // The last moment an abort is honoured: from here on the claims switch.
        synchronized (abortLock) {
          checkAborted(ids);
          switching.addAll(ids);
        }
      } catch (MoveFailure failure) {
        abortBatch(batch, failure.getMessage());
        throw new ResourceException(failure.getMessage());
      }
      List<PdDatabaseMove> switched = new ArrayList<>();
      for (int i = 0; i < planned.size(); i++) {
        switched.add(finishSwitch(ids.get(i)));
      }
      return List.copyOf(switched);
    } finally {
      synchronized (abortLock) {
        ids.forEach(aborts::remove);
        switching.removeAll(ids);
      }
    }
  }

  /** An abort asked for any move of the batch ends the batch, naming who asked. */
  private void checkAborted(List<String> ids) {
    for (String id : ids) {
      String actor = aborts.get(id);
      if (actor != null) {
        throw new MoveFailure("aborted by " + actor);
      }
    }
  }

  /** The row in {@code STOPPING}: a new one, or the operator's {@code QUEUED} one taken over. */
  private String start(String batch, Planned move, MoveSpace.Measured measured) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDatabaseMove row =
                  move.operatorRow() != null ? moves.findById(move.operatorRow().id) : null;
              if (row == null) {
                row = new PdDatabaseMove();
                row.id = move.id();
                row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
                row.createdAt = Instant.now();
              }
              row.batchId = batch;
              row.sourceBytes = measured.sourceBytes();
              row.targetFreeBytes = measured.targetFreeBytes();
              row.volumeFreeBytes = measured.volumeFreeBytes();
              row.applicationName = move.applicationName();
              row.environmentName = move.environmentName();
              row.resourceName = move.resourceName();
              row.sourceServer = move.sourceServer().wire();
              row.sourceDatabase = move.sourceDatabase();
              row.targetServer = move.targetServer().wire();
              row.targetDatabase = move.targetDatabase();
              row.state = PdDatabaseMoveState.STOPPING;
              row.startedAt = Instant.now();
              if (move.operatorRow() == null) {
                moves.persist(row);
              }
              return row.id;
            });
  }

  /**
   * Scale every service holding a claim of the batch to zero and wait for its tasks to be gone. The
   * service is the claim holder's wire alias — its swarm name — and what it ran is recorded on each
   * of its moves so an abort, or a restart's abort, scales it back to exactly that.
   */
  private void stopServices(List<Planned> planned, List<String> ids) {
    Map<String, List<String>> byService = new LinkedHashMap<>();
    for (int i = 0; i < planned.size(); i++) {
      Planned move = planned.get(i);
      String service = PdNetworks.alias(move.environmentName(), move.claim().applicationName());
      byService.computeIfAbsent(service, s -> new ArrayList<>()).add(ids.get(i));
    }
    for (Map.Entry<String, List<String>> entry : byService.entrySet()) {
      String service = entry.getKey();
      OptionalInt desired = driver.desiredReplicas(service);
      if (desired.isEmpty() || desired.getAsInt() == 0) {
        LOG.infof("Nothing runs as %s, so the move stops nothing", service);
        continue;
      }
      int replicas = desired.getAsInt();
      Optional<Duration> grace = driver.stopGracePeriod(service);
      DeploymentDriver.ScaleResult scaled = driver.scale(service, 0);
      if (!scaled.applied()) {
        throw new MoveFailure("could not stop " + service + " for the move: " + scaled.detail());
      }
      for (String id : entry.getValue()) {
        update(
            id,
            row -> {
              row.serviceName = service;
              row.serviceReplicas = replicas;
            });
      }
      awaitDrained(service, drainWait(stopTimeout, grace), ids);
    }
    for (int i = 0; i < planned.size(); i++) {
      checkAborted(ids);
      Planned move = planned.get(i);
      DatabaseContainers.Outcome closed =
          admin(move.sourceServer(), move.environmentName(), DatabaseScripts.close(move.sourceDatabase(), move.sourceDatabase()));
      if (!closed.ok()) {
        throw new MoveFailure(
            "could not close " + where(move.sourceServer(), move.sourceDatabase()) + ": " + tail(closed.output()));
      }
    }
  }

  /**
   * The deadline {@link #awaitDrained} waits out: {@code max(stopTimeout, grace +
   * DeploymentDriver.RECREATE_DRAIN_MARGIN)}, an empty grace reading as docker's own default of ten
   * seconds (qits-2228). {@code qits-edge-private} declares a {@code stop_grace_period} of {@code
   * 10m} and drains its long-lived connections for up to 9m30s on SIGTERM; the fixed {@code
   * stop-timeout} alone (shipped {@code PT2M}) gave up on it every single time, aborting a move
   * that was never going to finish stopping in two minutes. Package-private for the test.
   */
  static Duration drainWait(Duration stopTimeout, Optional<Duration> grace) {
    Duration effectiveGrace = grace.orElse(DOCKER_DEFAULT_STOP_GRACE_PERIOD);
    Duration graceDeadline = effectiveGrace.plus(DeploymentDriver.RECREATE_DRAIN_MARGIN);
    return stopTimeout.compareTo(graceDeadline) >= 0 ? stopTimeout : graceDeadline;
  }

  private void awaitDrained(String service, Duration wait, List<String> ids) {
    long deadline = System.nanoTime() + wait.toNanos();
    DatabaseContainers.Drain last = DatabaseContainers.Drain.UNKNOWN;
    while (true) {
      checkAborted(ids);
      last = containers.drained(service);
      if (last == DatabaseContainers.Drain.DRAINED) {
        return;
      }
      if (System.nanoTime() >= deadline) {
        throw new MoveFailure(
            service
                + " still had a task "
                + (last == DatabaseContainers.Drain.RUNNING ? "running" : "the runtime could not account for")
                + " after "
                + wait.toSeconds()
                + "s");
      }
      try {
        Thread.sleep(DRAIN_POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new MoveFailure("interrupted while " + service + " stopped");
      }
    }
  }

  /**
   * One move between two names on the same server, renamed in place (qits-2193, the owner's ruling
   * of 2026-11-14): {@code ALTER DATABASE … RENAME TO …} and the role with it, in one transaction,
   * the password set again and {@code CONNECT} granted back — instant, needing no space, where a
   * dump and a restore of a large store loaded the shared postgres for half an hour and died with
   * it (qits-2243). There is nothing to compare: it is the same data under another name. The claim
   * switches with the rest of the batch, in {@link #finishSwitch}.
   *
   * <p><b>Pre-switch, so an abort renames it back</b> — {@link #abortBatch}, with the same script
   * and the names swapped. The rename is one transaction, so after any failure the database is
   * under exactly one of the two names; the way back is idempotent over both, and a restart finding
   * the row {@code RENAMING} or {@code VERIFIED} takes the same way back. Until the claim switches it
   * names the source, so the source name is where the data has to be.
   */
  private void renameInPlace(Planned move, String id, List<String> batch) {
    checkAborted(batch);
    String image;
    try {
      image = admin.image(move.sourceServer(), move.environmentName());
    } catch (ResourceException unreachable) {
      throw new MoveFailure(unreachable.getMessage());
    }
    update(id, row -> row.state = PdDatabaseMoveState.RENAMING);
    DatabaseContainers.Outcome renamed =
        renameShot(
            move.sourceServer(),
            move.environmentName(),
            image,
            move.sourceDatabase(),
            move.targetDatabase(),
            leftover(move),
            move.claim().password());
    checkAborted(batch);
    if (!renamed.ok()) {
      throw new MoveFailure(
          "the rename of "
              + where(move.sourceServer(), move.sourceDatabase())
              + " to `"
              + move.targetDatabase()
              + "` failed ("
              + verdict(renamed)
              + "): "
              + tail(renamed.output()));
    }
    update(id, row -> row.state = PdDatabaseMoveState.VERIFIED);
  }

  /** The rename-in-place one-shot, either way round; the password, when known, as a secret. */
  private DatabaseContainers.Outcome renameShot(
      DatabaseServer server,
      String environmentName,
      String image,
      String from,
      String to,
      boolean dropLeftover,
      String password) {
    return containers.run(
        admin.shot(
            "rename",
            server,
            environmentName,
            image,
            null,
            password == null ? Map.of() : Map.of(DatabaseScripts.ROLE_PASSWORD, password),
            DatabaseScripts.renameInPlace(from, to, dropLeftover, password != null)),
        ContainerResourceProvisioner.TIMEOUT);
  }

  /**
   * Whether a row is a move renamed in place that has not switched yet — the abort's word for
   * "rename it back" rather than "drop the target". A same-server row a copy wrote before qits-2193
   * (an interrupted one, met by the restart after this release) is never one: it was {@code
   * EXTRACTING} or {@code INGESTING}, or {@code VERIFIED} with its manifests read.
   */
  static boolean renamePending(PdDatabaseMove row) {
    if (row.sourceServer == null || !row.sourceServer.equals(row.targetServer)) {
      return false;
    }
    return row.state == PdDatabaseMoveState.RENAMING
        || (row.state == PdDatabaseMoveState.VERIFIED && row.sourceCounts == null);
  }

  /**
   * Extract, ingest and compare one move, recording each step. Its volume exists already — {@link
   * #measure} created it. An abort is asked after each step, and before a failed step is reported, so
   * a one-shot the abort killed reads as the abort it was.
   */
  private void copy(Planned move, String id, List<String> batch) {
    checkAborted(batch);
    String volume = VOLUME_PREFIX + id;
    String image;
    try {
      // One image for both containers: the SOURCE server's, so the pg_dump is never older than the
      // server it reads and the pg_restore reads what that pg_dump wrote.
      image = admin.image(move.sourceServer(), move.environmentName());
    } catch (ResourceException unreachable) {
      throw new MoveFailure(unreachable.getMessage());
    }
    update(id, row -> row.state = PdDatabaseMoveState.EXTRACTING);
    DatabaseContainers.Outcome extracted =
        containers.run(
            admin.shot(
                "extract",
                move.sourceServer(),
                move.environmentName(),
                image,
                volume,
                Map.of(),
                DatabaseScripts.extract(move.sourceDatabase()))
                .forMove(id),
            timeout);
    checkAborted(batch);
    Map<String, Long> source = extracted.ok() ? DatabaseScripts.manifest(extracted.output()) : null;
    if (source == null) {
      throw new MoveFailure(
          "the extract of "
              + where(move.sourceServer(), move.sourceDatabase())
              + " failed ("
              + verdict(extracted)
              + "): "
              + tail(extracted.output()));
    }
    String sourceJson = write(source);
    update(
        id,
        row -> {
          row.sourceCounts = sourceJson;
          row.state = PdDatabaseMoveState.INGESTING;
        });

    DatabaseContainers.Outcome ingested =
        containers.run(
            admin.shot(
                "ingest",
                move.targetServer(),
                move.environmentName(),
                image,
                volume,
                Map.of(DatabaseScripts.ROLE_PASSWORD, move.claim().password()),
                DatabaseScripts.ingest(move.targetDatabase(), move.targetDatabase(), leftover(move)))
                .forMove(id),
            timeout);
    checkAborted(batch);
    Map<String, Long> target = ingested.ok() ? DatabaseScripts.manifest(ingested.output()) : null;
    if (target == null) {
      throw new MoveFailure(
          "the ingest into "
              + where(move.targetServer(), move.targetDatabase())
              + " failed ("
              + verdict(ingested)
              + "): "
              + tail(ingested.output()));
    }
    String targetJson = write(target);
    update(id, row -> row.targetCounts = targetJson);

    String difference = DatabaseScripts.firstDifference(source, target);
    if (difference != null) {
      throw new MoveFailure(
          "the copy of "
              + where(move.sourceServer(), move.sourceDatabase())
              + " does not match: "
              + difference);
    }
    update(id, row -> row.state = PdDatabaseMoveState.VERIFIED);
  }

  /**
   * The switch, for one verified move: the claim takes the target in the same transaction the row
   * enters {@code SWITCHING}, then the source is renamed aside and the volume removed. Idempotent
   * from any point on, which is what lets a restart call it for a row it finds half-way.
   */
  private PdDatabaseMove finishSwitch(String id) {
    PdDatabaseMove switched =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  PdDatabaseMove row = moves.findById(id);
                  if (row.state == PdDatabaseMoveState.VERIFIED) {
                    boolean inPlace = renamePending(row);
                    switchClaim(row);
                    if (!inPlace) {
                      row.keptDatabase = DatabaseScripts.keptName(row.sourceDatabase, Instant.now());
                    }
                    row.state = PdDatabaseMoveState.SWITCHING;
                  }
                  return row;
                });
    if (switched.keptDatabase == null) {
      return finishRenamed(id);
    }
    DatabaseServer source = DatabaseServer.parse(switched.sourceServer);
    DatabaseContainers.Outcome renamed =
        admin(
            source,
            switched.environmentName,
            DatabaseScripts.rename(switched.sourceDatabase, switched.keptDatabase));
    String note = null;
    if (!renamed.ok()) {
      // The claim already points at the target, so this is not a failed move: the source is still
      // closed under its old name, and the retention sweep will try to drop it under the kept one
      // and find nothing. Said so, on the row and in the log.
      note =
          "the source could not be renamed to "
              + switched.keptDatabase
              + " and is still "
              + switched.sourceDatabase
              + ", closed: "
              + tail(renamed.output());
      LOG.warnf("Database move %s: %s", id, note);
    }
    containers.removeVolume(VOLUME_PREFIX + id);
    String detail = note;
    PdDatabaseMove done =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  PdDatabaseMove row = moves.findById(id);
                  row.state = PdDatabaseMoveState.MOVED;
                  row.finishedAt = Instant.now();
                  row.dropAfter = row.finishedAt.plus(retention);
                  if (detail != null) {
                    row.detail = detail;
                  }
                  return row;
                });
    LOG.infof(
        "Moved the database %s of %s (%s): %s with %d rows → %s with %d rows; the source is kept"
            + " as %s until %s",
        done.resourceName,
        done.applicationName,
        done.environmentName,
        where(DatabaseServer.parse(done.sourceServer), done.sourceDatabase),
        totalOf(done.sourceCounts),
        where(DatabaseServer.parse(done.targetServer), done.targetDatabase),
        totalOf(done.targetCounts),
        done.keptDatabase,
        done.dropAfter);
    return done;
  }

  /**
   * The rest of a switch renamed in place: nothing to rename aside, no volume, no copy to keep — so
   * no {@code kept_database} and no {@code drop_after}, and the retention sweep never meets it. The
   * way back is a rename back, which is a move like this one.
   */
  private PdDatabaseMove finishRenamed(String id) {
    PdDatabaseMove done =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  PdDatabaseMove row = moves.findById(id);
                  row.state = PdDatabaseMoveState.MOVED;
                  row.finishedAt = Instant.now();
                  row.dropAfter = null;
                  row.detail = RENAMED_IN_PLACE;
                  return row;
                });
    LOG.infof(
        "Renamed the database %s of %s (%s) in place on %s: %s → %s",
        done.resourceName,
        done.applicationName,
        done.environmentName,
        done.sourceServer,
        done.sourceDatabase,
        done.targetDatabase);
    return done;
  }

  /** The detail of a move renamed in place. */
  static final String RENAMED_IN_PLACE = "renamed in place";

  /** The claim row takes the target server and database; its password is the one it had. */
  private void switchClaim(PdDatabaseMove move) {
    PdResource claim =
        resources
            .findOne(move.applicationName, move.environmentName, move.resourceName)
            .filter(row -> ResourceProvisioning.RESOURCE_TYPE.equals(row.resourceType))
            .filter(row -> move.sourceDatabase.equals(row.databaseName))
            .orElse(null);
    if (claim == null) {
      // A rename's first deploy: the claim is still the predecessor's, under the same resource name
      // and the source database. The transfer onto the successor is provisioning's, a step later.
      claim =
          resources.listByDatabase(move.sourceDatabase).stream()
              .filter(row -> ResourceProvisioning.RESOURCE_TYPE.equals(row.resourceType))
              .filter(row -> move.environmentName.equals(row.environmentName))
              .filter(row -> move.resourceName.equals(row.resourceName))
              .findFirst()
              .orElse(null);
    }
    if (claim == null) {
      LOG.warnf(
          "Database move %s switched with no claim of %s left on %s — nothing to repoint",
          move.id, move.resourceName, move.sourceDatabase);
      return;
    }
    claim.server = move.targetServer;
    claim.databaseName = move.targetDatabase;
    claim.roleName = move.targetDatabase;
    claim.lastProvisionedAt = Instant.now();
  }

  // --- the abort --------------------------------------------------------------------------------

  /**
   * Undo every move of the batch that has not switched: drop what the ingest created, re-open the
   * source, remove the volume, record {@code ABORTED} with the reason — then scale each stopped
   * service back to what it ran. Each step is idempotent and a failed one is a WARN and the next
   * one still runs: an abort that stopped half-way would leave the application down.
   *
   * <p>A move renamed in place (qits-2193) is renamed BACK instead — {@link #renameBack}, never a
   * drop, since the target name may be where the data is. One whose way back cannot run at all is
   * the exception to "the next step still runs": it is left unfinished and its service stopped, and
   * the next settle pass tries again.
   */
  void abortBatch(String batch, String reason) {
    List<PdDatabaseMove> rows =
        QuarkusTransaction.requiringNew().call(() -> moves.list("batchId = ?1 order by createdAt", batch));
    Map<String, Integer> services = new LinkedHashMap<>();
    Set<String> keptDown = new LinkedHashSet<>();
    for (PdDatabaseMove row : rows) {
      if (row.state.switched() || PdDatabaseMoveState.TERMINAL.contains(row.state)) {
        continue;
      }
      List<String> problems = new ArrayList<>();
      DatabaseServer source = DatabaseServer.parse(row.sourceServer);
      DatabaseServer target = DatabaseServer.parse(row.targetServer);
      String why = reason;
      if (row.detail != null && row.detail.contains(RENAME_BACK_OWED)) {
        // An earlier pass could not rename it back; its reason is the one that stands.
        why = row.detail.substring(0, Math.max(0, row.detail.indexOf("\n[" + RENAME_BACK_OWED)));
      }
      if (renamePending(row)) {
        String unresolved = renameBack(row, problems);
        if (unresolved != null) {
          // The data may be under the target name while the claim still names the source: neither
          // ABORTED nor the service started. The row stays unfinished, so the next settle pass
          // (every observation tick) tries the way back again.
          String owed = why + "\n[" + RENAME_BACK_OWED + ": " + unresolved + "]";
          update(row.id, moved -> moved.detail = owed);
          if (row.serviceName != null) {
            keptDown.add(row.serviceName);
          }
          LOG.errorf(
              "Could not undo the in-place rename of %s (%s) on %s, %s → %s, so its service stays"
                  + " stopped and the next pass tries again: %s",
              row.applicationName,
              row.resourceName,
              row.sourceServer,
              row.sourceDatabase,
              row.targetDatabase,
              unresolved);
          continue;
        }
      } else if (row.state == PdDatabaseMoveState.INGESTING || row.state == PdDatabaseMoveState.VERIFIED) {
        String role = targetRoleUnclaimed(row) ? row.targetDatabase : null;
        DatabaseContainers.Outcome dropped =
            safely(() -> admin(target, row.environmentName, DatabaseScripts.drop(row.targetDatabase, role)));
        if (!dropped.ok()) {
          problems.add("the target " + row.targetDatabase + " was not dropped: " + tail(dropped.output()));
        }
      }
      if (row.state != PdDatabaseMoveState.QUEUED && !renamePending(row)) {
        DatabaseContainers.Outcome reopened =
            safely(() -> admin(source, row.environmentName, DatabaseScripts.reopen(row.sourceDatabase, row.sourceDatabase)));
        if (!reopened.ok()) {
          problems.add("the source " + row.sourceDatabase + " was not re-opened: " + tail(reopened.output()));
        }
        containers.removeVolume(VOLUME_PREFIX + row.id);
      }
      if (row.serviceName != null && row.serviceReplicas != null && row.serviceReplicas > 0) {
        services.putIfAbsent(row.serviceName, row.serviceReplicas);
      }
      String detail =
          problems.isEmpty() ? why : why + "\n[the abort is incomplete: " + String.join("; ", problems) + "]";
      update(
          row.id,
          moved -> {
            moved.state = PdDatabaseMoveState.ABORTED;
            moved.finishedAt = Instant.now();
            moved.detail = detail;
          });
      LOG.warnf(
          "Aborted the move of %s (%s) from %s to %s: %s",
          row.applicationName,
          row.resourceName,
          where(source, row.sourceDatabase),
          where(target, row.targetDatabase),
          detail);
    }
    keptDown.forEach(services::remove);
    services.forEach(
        (service, replicas) -> {
          DeploymentDriver.ScaleResult scaled = driver.scale(service, replicas);
          if (!scaled.applied()) {
            LOG.warnf("Could not scale %s back to %d after an aborted move: %s", service, replicas, scaled.detail());
          }
        });
  }

  // --- the rollback after the switch (qits-2249) ------------------------------------------------

  /**
   * What {@link #rollBack} did: whether every move of the batch is back on its source, a sentence
   * for the deployment's row, and the services the moves stopped with the replicas each ran — the
   * ones the caller now starts again on their original spec. Empty when anything is not back: a
   * service whose spec names the source must not start while the data is under the target name.
   */
  public record RolledBack(boolean complete, String summary, Map<String, Integer> stopped) {}

  /**
   * Undo moves that already SWITCHED, because the deployment they ran for failed after the switch
   * (qits-2249, the owner's ruling of 2026-11-14: a failed move rolls back and restores the original
   * service; "FAILED after the switch, application DOWN" is not an end state). Per move, in order:
   *
   * <ol>
   *   <li>the claim holder's service is stopped again when anything is running — a successor that
   *       started on the target and crash-loops must not hold it while it is renamed;
   *   <li><b>renamed in place</b>: renamed BACK ({@link #renameShot}, names swapped, the claim's
   *       password set again) — never a drop, the data is under the target name; <b>a copy</b>: the
   *       kept source renamed back and re-opened ({@link DatabaseScripts#restoreKept});
   *   <li>the claim switched back onto the source (server, database, role; password unchanged);
   *   <li>a copy's target dropped — its role too, when no claim on that server uses it — which is
   *       not the data: the source is;
   *   <li>the row {@code ABORTED}, {@code drop_after} cleared, the detail saying it was rolled back —
   *       which is also what makes the target a leftover the next attempt may clear.
   * </ol>
   *
   * A move whose source cannot be put back is left {@code MOVED} with the reason in its detail, and
   * NO service is handed back to start: its claim still names the target, where the data is.
   */
  public RolledBack rollBack(List<PdDatabaseMove> switched, String reason) {
    List<String> problems = new ArrayList<>();
    List<String> done = new ArrayList<>();
    Map<String, Integer> stopped = new LinkedHashMap<>();
    boolean complete = true;
    for (PdDatabaseMove move : switched) {
      if (move.serviceName != null && move.serviceReplicas != null && move.serviceReplicas > 0) {
        stopped.putIfAbsent(move.serviceName, move.serviceReplicas);
      }
    }
    for (String service : stopped.keySet()) {
      try {
        OptionalInt desired = driver.desiredReplicas(service);
        if (desired.isPresent() && desired.getAsInt() > 0) {
          Optional<Duration> grace = driver.stopGracePeriod(service);
          DeploymentDriver.ScaleResult scaled = driver.scale(service, 0);
          if (!scaled.applied()) {
            throw new MoveFailure("could not stop " + service + " again for the rollback: " + scaled.detail());
          }
          // A rollback is never cut short by an operator abort: no row ids to check.
          awaitDrained(service, drainWait(stopTimeout, grace), List.of());
        }
      } catch (MoveFailure failure) {
        problems.add(failure.getMessage());
      }
    }
    for (PdDatabaseMove listed : switched) {
      PdDatabaseMove row = read(listed.id);
      if (row == null || row.state != PdDatabaseMoveState.MOVED) {
        continue;
      }
      String unresolved = undoSwitch(row, problems);
      if (unresolved != null) {
        complete = false;
        String owed = (row.detail == null ? "" : row.detail + "\n") + "[the rollback is incomplete: " + unresolved + "]";
        update(row.id, moved -> moved.detail = owed);
        LOG.errorf(
            "Could not roll back the database move %s of %s (%s), %s → %s: %s",
            row.id,
            row.applicationName,
            row.resourceName,
            where(DatabaseServer.parse(row.targetServer), row.targetDatabase),
            where(DatabaseServer.parse(row.sourceServer), row.sourceDatabase),
            unresolved);
        problems.add(unresolved);
        continue;
      }
      String detail = "rolled back after the switch: " + reason;
      update(
          row.id,
          moved -> {
            moved.state = PdDatabaseMoveState.ABORTED;
            moved.finishedAt = Instant.now();
            moved.dropAfter = null;
            moved.detail = detail;
          });
      done.add(
          "`"
              + row.resourceName
              + "` back on "
              + where(DatabaseServer.parse(row.sourceServer), row.sourceDatabase)
              + (row.keptDatabase == null ? " (renamed back)" : " (the kept source restored, the target dropped)"));
      LOG.warnf(
          "Rolled back the database move %s of %s (%s): %s is back on %s",
          row.id,
          row.applicationName,
          row.resourceName,
          row.resourceName,
          where(DatabaseServer.parse(row.sourceServer), row.sourceDatabase));
    }
    String summary =
        String.join("; ", done)
            + (problems.isEmpty() ? "" : (done.isEmpty() ? "" : "; ") + "[" + String.join("; ", problems) + "]");
    return new RolledBack(complete, summary, complete ? Map.copyOf(stopped) : Map.of());
  }

  /**
   * One switched move's way back to its source; null when the source is the claim's again and open,
   * otherwise why not (the claim then still names the target). Cosmetic trouble — a target that
   * would not drop — is added to {@code problems} and does not stop the rollback.
   */
  private String undoSwitch(PdDatabaseMove row, List<String> problems) {
    DatabaseServer source = DatabaseServer.parse(row.sourceServer);
    DatabaseServer target = DatabaseServer.parse(row.targetServer);
    if (row.keptDatabase == null && row.sourceServer.equals(row.targetServer)) {
      String password =
          QuarkusTransaction.requiringNew()
              .call(() -> switchedClaim(row).map(claim -> claim.password).orElse(null));
      DatabaseContainers.Outcome back =
          safely(
              () ->
                  renameShot(
                      source,
                      row.environmentName,
                      admin.image(source, row.environmentName),
                      row.targetDatabase,
                      row.sourceDatabase,
                      false,
                      password));
      if (!back.ok()) {
        return "the rename back of "
            + where(target, row.targetDatabase)
            + " to `"
            + row.sourceDatabase
            + "` failed ("
            + verdict(back)
            + "): "
            + tail(back.output());
      }
      switchClaimBack(row);
      return null;
    }
    if (row.keptDatabase != null) {
      DatabaseContainers.Outcome restored =
          safely(() -> admin(source, row.environmentName, DatabaseScripts.restoreKept(row.keptDatabase, row.sourceDatabase)));
      if (!restored.ok()) {
        return "the kept source "
            + where(source, row.keptDatabase)
            + " could not be put back as `"
            + row.sourceDatabase
            + "`: "
            + tail(restored.output());
      }
    } else {
      // A copy whose switch never got as far as renaming the source aside: it is under its own name.
      DatabaseContainers.Outcome reopened =
          safely(() -> admin(source, row.environmentName, DatabaseScripts.reopen(row.sourceDatabase, row.sourceDatabase)));
      if (!reopened.ok()) {
        return "the source " + where(source, row.sourceDatabase) + " could not be re-opened: " + tail(reopened.output());
      }
    }
    switchClaimBack(row);
    String role = targetRoleUnclaimed(row) ? row.targetDatabase : null;
    DatabaseContainers.Outcome dropped =
        safely(() -> admin(target, row.environmentName, DatabaseScripts.drop(row.targetDatabase, role)));
    if (!dropped.ok()) {
      problems.add("the target " + where(target, row.targetDatabase) + " was not dropped: " + tail(dropped.output()));
    }
    return null;
  }

  /** The claim a switched move pointed at its target — whichever application holds it now. */
  private Optional<PdResource> switchedClaim(PdDatabaseMove row) {
    return resources.listByDatabase(row.targetDatabase).stream()
        .filter(claim -> ResourceProvisioning.RESOURCE_TYPE.equals(claim.resourceType))
        .filter(claim -> row.environmentName.equals(claim.environmentName))
        .filter(claim -> row.resourceName.equals(claim.resourceName))
        .filter(claim -> row.targetServer.equals(DatabaseServer.parse(claim.server).wire()))
        .findFirst();
  }

  /** {@link #switchClaim} the other way round: the claim takes its source again. */
  private void switchClaimBack(PdDatabaseMove row) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                switchedClaim(row)
                    .ifPresentOrElse(
                        claim -> {
                          claim.server = row.sourceServer;
                          claim.databaseName = row.sourceDatabase;
                          claim.roleName = row.sourceDatabase;
                          claim.lastProvisionedAt = Instant.now();
                        },
                        () ->
                            LOG.warnf(
                                "Database move %s rolled back with no claim of %s left on %s — nothing to repoint",
                                row.id, row.resourceName, row.targetDatabase)));
  }

  /** The mark on a row whose in-place rename an abort still owes undoing. */
  static final String RENAME_BACK_OWED = "the rename back is owed";

  /**
   * The way back of a move renamed in place: the same script with the names swapped, run only while
   * the claim still names the source. Idempotent over both states the one-transaction rename can
   * have left — renamed (it renames back) or not (it renames nothing, and re-opens the source).
   * Should the way back itself fail, the source re-opened under its own name is enough: it IS
   * there, so the rename never ran or was undone, and only a leftover under the target name remains.
   *
   * @return null when the source is back under its own name and open (anything less than clean is
   *     added to {@code problems}); otherwise why not — the row must not be closed on that
   */
  private String renameBack(PdDatabaseMove row, List<String> problems) {
    DatabaseServer server = DatabaseServer.parse(row.sourceServer);
    Optional<Claimed> claim = inPlaceClaim(row);
    if (claim.isPresent() && row.targetDatabase.equals(claim.get().database())) {
      // Something already switched the claim onto the target: the data is where the claim is.
      problems.add("the claim already holds " + row.targetDatabase + ", so nothing was renamed back");
      return null;
    }
    String password = claim.map(Claimed::password).orElse(null);
    DatabaseContainers.Outcome back =
        safely(
            () ->
                renameShot(
                    server,
                    row.environmentName,
                    admin.image(server, row.environmentName),
                    row.targetDatabase,
                    row.sourceDatabase,
                    false,
                    password));
    if (back.ok()) {
      return null;
    }
    DatabaseContainers.Outcome reopened =
        safely(() -> admin(server, row.environmentName, DatabaseScripts.reopen(row.sourceDatabase, row.sourceDatabase)));
    if (reopened.ok()) {
      problems.add(
          "the rename back failed ("
              + tail(back.output())
              + "), but the source is there under its own name and re-opened");
      return null;
    }
    return "the rename back failed: " + tail(back.output()) + "; re-opening the source failed: " + tail(reopened.output());
  }

  /** The claim an in-place move of {@code row} concerns, under either of its two names. */
  private Optional<Claimed> inPlaceClaim(PdDatabaseMove row) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                java.util.stream.Stream.of(row.sourceDatabase, row.targetDatabase)
                    .flatMap(database -> resources.listByDatabase(database).stream())
                    .filter(claim -> ResourceProvisioning.RESOURCE_TYPE.equals(claim.resourceType))
                    .filter(claim -> row.environmentName.equals(claim.environmentName))
                    .filter(claim -> row.resourceName.equals(claim.resourceName))
                    .filter(claim -> row.sourceServer.equals(DatabaseServer.parse(claim.server).wire()))
                    .findFirst()
                    .map(
                        claim ->
                            new Claimed(
                                claim.id,
                                claim.applicationName,
                                claim.databaseName,
                                DatabaseServer.parse(claim.server),
                                claim.password)));
  }

  /** Whether no claim on the target server is the target role — so an abort may drop it. */
  private boolean targetRoleUnclaimed(PdDatabaseMove row) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                resources.list("roleName = ?1 and server = ?2", row.targetDatabase, row.targetServer).isEmpty());
  }

  // --- a restart, and the retention -------------------------------------------------------------

  /**
   * Settle every move a previous process left in a word it never leaves (boot, and every observation
   * tick after it for whatever boot could not finish): a batch with a move past the switch FINISHES,
   * every other batch ABORTS — the service scaled back up on its untouched source. An operator's move
   * still {@code QUEUED} lost the redeploy it was waiting for with the queue, and is refused. Runs on
   * the deploy worker, where no move of this process can be in flight.
   */
  public void settleInterrupted(boolean atBoot) {
    List<PdDatabaseMove> unfinished =
        QuarkusTransaction.requiringNew().call(() -> moves.listUnfinished());
    Set<String> batches = new LinkedHashSet<>();
    for (PdDatabaseMove row : unfinished) {
      if (row.state == PdDatabaseMoveState.QUEUED) {
        // Only at boot: between ticks, a QUEUED row is an operator's move waiting in the worker's
        // queue behind this very pass.
        if (atBoot) {
          refuse(row, "the deployer restarted before the redeploy this move was waiting for ran");
          // A restart between the space check and the start leaves the measured volume behind.
          containers.removeVolume(VOLUME_PREFIX + row.id);
        }
        continue;
      }
      batches.add(row.batchId);
    }
    for (String batch : batches) {
      List<PdDatabaseMove> rows =
          QuarkusTransaction.requiringNew().call(() -> moves.list("batchId = ?1 order by createdAt", batch));
      boolean switched = rows.stream().anyMatch(row -> row.state.switched());
      try {
        if (switched) {
          for (PdDatabaseMove row : rows) {
            if (row.state == PdDatabaseMoveState.VERIFIED || row.state == PdDatabaseMoveState.SWITCHING) {
              finishSwitch(row.id);
            }
          }
          // The service the move stopped is still at zero, and its running spec names the OLD
          // database — so it is not scaled back up, it is REDEPLOYED, here, the way the operator's
          // door redeploys. Nothing else reliably does it: the startup sweep marks the interrupted
          // row `interrupted` (sweepInFlight, V16), but only OwedReleaseSweep re-drives such a row,
          // and only for a release that came through the bus door and left a pd_owed_release
          // obligation — Door.MANUAL writes none, so an operator's move (whose redeploy is MANUAL)
          // or a hand-announced version would leave the service down with nobody to start it. A
          // bus release may therefore be re-driven twice; the second finds its claims already where
          // the spec says and is an ordinary, idempotent redeploy.
          redeployAfterSwitch(rows.get(0));
        } else {
          abortBatch(batch, "the deployer restarted mid-move, so the move was undone");
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not settle the interrupted database move %s; the next pass tries again", batch);
      }
    }
  }

  /**
   * Drop every kept copy whose retention is over — and, when no claim on that server still uses it,
   * the role that owned it — and stamp {@code dropped_at}. A failure is a WARN and the next tick
   * tries again; nothing is stamped that was not dropped.
   */
  public void sweepExpired() {
    List<PdDatabaseMove> expired =
        QuarkusTransaction.requiringNew().call(() -> moves.listExpired(Instant.now()));
    for (PdDatabaseMove row : expired) {
      DatabaseServer source = DatabaseServer.parse(row.sourceServer);
      boolean roleUnused =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      resources.list("roleName = ?1 and server = ?2", row.sourceDatabase, row.sourceServer).isEmpty());
      DatabaseContainers.Outcome dropped =
          safely(
              () ->
                  admin(
                      source,
                      row.environmentName,
                      DatabaseScripts.drop(row.keptDatabase, roleUnused ? row.sourceDatabase : null)));
      if (!dropped.ok()) {
        LOG.warnf(
            "Could not drop the kept copy %s of a database move (%s): %s",
            row.keptDatabase, row.id, tail(dropped.output()));
        continue;
      }
      update(row.id, moved -> moved.droppedAt = Instant.now());
      LOG.infof(
          "Dropped %s, the copy %s left behind when it moved to %s%s",
          where(source, row.keptDatabase),
          row.applicationName,
          where(DatabaseServer.parse(row.targetServer), row.targetDatabase),
          roleUnused ? ", and its role " + row.sourceDatabase : "");
    }
  }

  // --- the operator's door ----------------------------------------------------------------------

  /** What the door answers: the queued move, and a note when it overrides the spec. */
  public record Accepted(PdDatabaseMove move, String note) {}

  /**
   * An operator's move, queued: {@code POST /database-moves}. Decided here, on the request thread, so
   * every refusal is an answer rather than a log line under a 202 — then queued on the deploy worker
   * as a REDEPLOY of the version that is live, whose provisioning step runs the move against this
   * target and whose deployment then starts the service against it.
   *
   * <p><b>A redeploy, because a moved database is only half a move.</b> The running service's spec
   * holds the old address; nothing short of a deployment hands it the new one. And a deployment is
   * where a move already runs — so this door does not run a move of its own, it hands the deployment
   * a target ({@link #held}) for one resource.
   *
   * <p>The target defaults to the rule and {@code resolve()} of the LIVE version's spec, read from
   * the git host now. {@code server} and {@code database} override it; an override the spec disagrees
   * with is accepted, and the answer says the next deployment moves it back.
   */
  public Accepted request(
      String applicationName, String resourceName, String server, String database, String actor) {
    String application = PdIdentifiers.requireName(applicationName, "application name");
    String resource = resourceName == null ? null : PdIdentifiers.requireResourceName(resourceName);
    DatabaseServer overrideServer;
    try {
      overrideServer = server == null ? null : DatabaseServer.parse(server);
    } catch (IllegalArgumentException wrong) {
      throw new BadRequestException(wrong.getMessage());
    }
    String overrideDatabase = database == null ? null : PdIdentifiers.requireDatabaseName(database);
    if (BootResourceRegistration.APPLICATION.equals(application)) {
      throw new ConflictException(
          BootResourceRegistration.APPLICATION
              + "'s own databases move only with its own deployment, when its exposure changes:"
              + " its successor moves them before it starts (qits-2209)");
    }
    PdEnvironment tier =
        environments
            .platformEnvironment()
            .orElseThrow(
                () -> new ConflictException("no environment is designated, so nothing is deployed to move"));

    List<PdResource> claims =
        QuarkusTransaction.joiningExisting()
            .call(
                () ->
                    resources.listByApplication(application, tier.name).stream()
                        .filter(row -> ResourceProvisioning.RESOURCE_TYPE.equals(row.resourceType))
                        .filter(row -> row.databaseName != null)
                        .toList());
    if (claims.isEmpty()) {
      throw new NotFoundException(application + " holds no database in " + tier.name);
    }
    PdResource claim;
    if (resource != null) {
      claim =
          claims.stream()
              .filter(row -> row.resourceName.equals(resource))
              .findFirst()
              .orElseThrow(
                  () ->
                      new NotFoundException(
                          application + " holds no database as `" + resource + "` in " + tier.name));
    } else if (claims.size() == 1) {
      claim = claims.get(0);
    } else {
      throw new BadRequestException(
          application
              + " holds "
              + claims.size()
              + " databases ("
              + claims.stream().map(row -> row.resourceName).sorted().collect(Collectors.joining(", "))
              + ") — name one with `resource`");
    }

    Live live = live(application, tier);
    SpecSource.DeploymentSpec spec = liveSpec(live);
    DatabaseServer specServer = DatabaseServer.forExposure(spec.exposure());
    String specDatabase =
        ResourceProvisioning.resolve(application, spec.resources()).stream()
            .filter(r -> r.type() == ResourceSpec.Type.POSTGRESQL)
            .filter(r -> r.name().equals(claim.resourceName))
            .map(ResourceProvisioning.Resolved::database)
            .findFirst()
            .orElseThrow(
                () ->
                    new ConflictException(
                        application
                            + "@"
                            + live.version()
                            + " no longer declares `"
                            + claim.resourceName
                            + "`, so a redeploy would not provision it"));
    DatabaseServer targetServer = overrideServer != null ? overrideServer : specServer;
    String targetDatabase = overrideDatabase != null ? overrideDatabase : specDatabase;
    DatabaseServer claimServer = DatabaseServer.parse(claim.server);
    if (claimServer == targetServer && claim.databaseName.equals(targetDatabase)) {
      throw new ConflictException(
          "nothing to move: `" + claim.resourceName + "` of " + application + " already is " + where(targetServer, targetDatabase));
    }
    if (targetServer != claimServer && !admin.deployed(targetServer, tier.name)) {
      throw new ConflictException(
          "the move of `"
              + claim.resourceName
              + "` of "
              + application
              + " is "
              + deferral(targetServer, tier.name));
    }
    resources.listByDatabase(targetDatabase).stream()
        .filter(row -> !row.id.equals(claim.id))
        .findFirst()
        .ifPresent(
            row -> {
              throw new ConflictException(
                  "`" + targetDatabase + "` is already provisioned for " + row.applicationName);
            });

    PdDatabaseMove row = new PdDatabaseMove();
    row.id = UUID.randomUUID().toString();
    row.batchId = row.id;
    row.applicationName = application;
    row.environmentName = tier.name;
    row.resourceName = claim.resourceName;
    row.sourceServer = claimServer.wire();
    row.sourceDatabase = claim.databaseName;
    row.targetServer = targetServer.wire();
    row.targetDatabase = targetDatabase;
    row.specServer = specServer.wire();
    row.specDatabase = specDatabase;
    row.origin = PdDatabaseMove.Origin.OPERATOR;
    row.requestedBy = actor;
    row.state = PdDatabaseMoveState.QUEUED;
    row.createdAt = Instant.now();
    QuarkusTransaction.requiringNew().run(() -> moves.persist(row));

    String key = key(application, tier.name, claim.resourceName);
    String moveId = row.id;
    worker.enqueueOperation(
        "The database move " + moveId + " of " + application,
        () -> {
          held.put(key, moveId);
          try {
            worker.redeploy(live.repository(), live.releasedName(), live.version(), live.packageName());
          } finally {
            held.remove(key);
            settleUnconsumed(moveId, application, tier.id, live.version());
          }
        });
    LOG.infof(
        "%s asked to move `%s` of %s from %s to %s; queued as move %s with a redeploy of %s",
        actor == null ? "An operator" : actor,
        claim.resourceName,
        application,
        where(claimServer, claim.databaseName),
        where(targetServer, targetDatabase),
        moveId,
        live.version());
    String note =
        targetServer == specServer && targetDatabase.equals(specDatabase)
            ? null
            : "this overrides the spec: the next deployment of "
                + application
                + " moves it back to "
                + where(specServer, specDatabase)
                + ", which its spec resolves to";
    return new Accepted(row, note);
  }

  /** What is live for an application in a tier, and the coordinates to redeploy it by. */
  private record Live(
      String version,
      String variant,
      RepositoryRef repository,
      String releasedName,
      String packageName) {}

  /**
   * Redeploy, on this worker, the version the interrupted deployment of {@code move}'s application
   * was putting live — its newest deployment row in that tier, whatever the startup sweep settled it
   * as. A WARN and nothing else when no release is on record to redeploy.
   */
  private void redeployAfterSwitch(PdDatabaseMove move) {
    Live live;
    try {
      PdEnvironment tier =
          environments.list().stream()
              .filter(environment -> move.environmentName.equals(environment.name))
              .findFirst()
              .orElseThrow(() -> new ConflictException("the tier " + move.environmentName + " is gone"));
      live =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      coordinates(
                          move.applicationName,
                          deployments
                              .newestForPlace(move.applicationName, tier.id)
                              .orElseThrow(
                                  () ->
                                      new ConflictException(
                                          "nothing was ever deployed for "
                                              + move.applicationName
                                              + " in "
                                              + move.environmentName))));
    } catch (RuntimeException e) {
      LOG.errorf(
          "The database move %s of %s switched before a restart, its service is stopped, and it"
              + " cannot be redeployed: %s",
          move.batchId, move.applicationName, e.getMessage());
      return;
    }
    if (!live.repository().nameAddressed()) {
      LOG.warnf(
          "The name of %s's repository %s is unknown here — no release this deployer accepted names"
              + " it — so the redeploy after the database move %s reads its spec by id, which the"
              + " git host serves to qits-projects only",
          move.applicationName, live.repository().repoId(), move.batchId);
    }
    LOG.warnf(
        "Finished the interrupted database move %s past its switch; redeploying %s@%s so its stopped"
            + " service starts against the moved database",
        move.batchId, move.applicationName, live.version());
    worker.redeploy(live.repository(), live.releasedName(), live.version(), live.packageName());
  }

  /** The words of a deployment that served its place — what the door redeploys. */
  private static final Set<PdDeploymentStatus> SERVED =
      Set.of(PdDeploymentStatus.ACTIVE, PdDeploymentStatus.SCALED_TO_ZERO);

  /**
   * The words of an attempt that ended without going live — skipped, newest first, on the way to the
   * deployment that served before it. A deployment that failed AFTER its move switched leaves the
   * service stopped and a {@code FAILED} row on top (qits-orchestrator, 2026-11-13); the door has to
   * see past it, or the one recovery an operator has — a move back, redeploying the version that
   * served — would be a 409.
   */
  private static final Set<PdDeploymentStatus> DID_NOT_GO_LIVE =
      Set.of(
          PdDeploymentStatus.FAILED,
          PdDeploymentStatus.ROLLED_BACK,
          PdDeploymentStatus.IMAGE_MISSING);

  /**
   * The newest deployment of the place that served it: the newest row when it is {@code ACTIVE} or
   * {@code SCALED_TO_ZERO}, else the first such row under a run of attempts that did not go live.
   * Anything else on the way — a deployment in flight, a decommissioned or retired place — is a 409.
   */
  private Live live(String application, PdEnvironment tier) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              PdDeployment serving = null;
              for (PdDeployment row : deployments.listForPlaceNewestFirst(application, tier.id)) {
                if (SERVED.contains(row.status)) {
                  serving = row;
                  break;
                }
                if (!DID_NOT_GO_LIVE.contains(row.status)) {
                  break;
                }
              }
              if (serving == null) {
                throw new ConflictException(
                    application
                        + " has no deployment serving in "
                        + tier.name
                        + " to redeploy against the moved database");
              }
              return coordinates(application, serving);
            });
  }

  /** The coordinates to redeploy one deployment row's version by, off its deployment request. */
  private Live coordinates(String application, PdDeployment serving) {
    PdDeploymentRequest request =
        requests
            .find("deploymentId = ?1", serving.id)
            .firstResultOptional()
            .orElseThrow(
                () ->
                    new ConflictException(
                        "no release request is on record for "
                            + application
                            + "@"
                            + serving.version
                            + ", so there is nothing to redeploy"));
    if (request.repoId == null || serving.version == null) {
      throw new ConflictException(
          application + "'s live deployment names no released version of a repository");
    }
    PdOwedRelease named = named(request, serving.version);
    String packageName =
        request.packageName != null ? request.packageName : named == null ? null : named.packageName;
    String projectId =
        request.projectId != null ? request.projectId : named == null ? null : named.projectId;
    String released = PackageNames.applicationOf(packageName);
    return new Live(
        serving.version,
        request.variant,
        new RepositoryRef(request.repoId, projectId, named == null ? null : named.repoName),
        released != null ? released : application,
        packageName);
  }

  /**
   * The newest accepted release that names the live deployment's repository — the only place this
   * component learns a repository's NAME, which the spec read needs: the git host serves {@code
   * /git/<repoId>} to qits-projects alone, so an id-addressed read is a 403 for this caller.
   *
   * <p><b>Three tries, most exact first.</b> The release that put this very version live, by its
   * package. Then any release of the same repository: the request row of a redeploy through the
   * manual door ({@code POST /events/software-released} — qits-projects' "deploy again", a replay)
   * records no package and opens no obligation, so an exact match is absent for exactly the
   * deployments a person last touched (qits-2175, qits-orchestrator@2026.1113.141108). The rename
   * subscriber keeps every row of a repository on its current name, so the newest is as good as the
   * one that matched. Last, any release of the same package, for a repository whose own rows carry
   * no name. Null when the deployer has never been told a name for it.
   */
  private PdOwedRelease named(PdDeploymentRequest request, String version) {
    if (request.packageName != null) {
      Optional<PdOwedRelease> exact =
          owed.find(
                  "packageName = ?1 and version = ?2 and repoName is not null",
                  request.packageName,
                  version)
              .firstResultOptional();
      if (exact.isPresent()) {
        return exact.get();
      }
    }
    Optional<PdOwedRelease> sameRepository = owed.newestNamedOfRepository(request.repoId);
    if (sameRepository.isPresent()) {
      return sameRepository.get();
    }
    return request.packageName == null
        ? null
        : owed.newestNamedOfPackage(request.packageName).orElse(null);
  }

  private SpecSource.DeploymentSpec liveSpec(Live live) {
    try {
      String path =
          live.variant() == null ? SpecSource.SPEC_PATH : SpecSource.variantPath(live.variant());
      return specs.read(live.repository(), SpecSource.tagRev(live.version()), path).spec();
    } catch (RuntimeException unreadable) {
      if (!live.repository().nameAddressed()) {
        LOG.warnf(
            "The spec of %s@%s was asked for by repository id %s, having no name on record: %s",
            live.releasedName(), live.version(), live.repository().repoId(), unreadable.getMessage());
        throw new BadGatewayException(
            "could not read the spec of the live version "
                + live.version()
                + ": the name of its repository "
                + live.repository().repoId()
                + " is unknown here — no release this deployer accepted names it — and the git host"
                + " serves a spec by repository id to qits-projects only",
            unreadable);
      }
      throw new BadGatewayException(
          "could not read the spec of the live version " + live.version() + ": " + unreadable.getMessage(),
          unreadable);
    }
  }

  /** An operator's move its redeploy never reached: refused, naming what the deployment said. */
  private void settleUnconsumed(String moveId, String application, String environmentId, String version) {
    PdDatabaseMove row = read(moveId);
    if (row == null || row.state != PdDatabaseMoveState.QUEUED) {
      return;
    }
    String what =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    deployments
                        .newestForPlace(application, environmentId)
                        .map(d -> d.status + (d.detail == null ? "" : ": " + d.detail))
                        .orElse("no deployment"));
    refuse(row, "the redeploy of " + application + "@" + version + " never reached its provisioning — " + what);
  }

  /**
   * An operator's abort of a running move: {@code POST /database-moves/{id}/abort} (qits-2243). It
   * runs on the request thread, because the worker is the thread a running extract or ingest blocks:
   * it records who asked, then kills the one-shots of every move of the batch ({@link
   * DatabaseContainers#kill}). The worker sees the flag after its step — the killed one returns at
   * once — and aborts the batch the way every failure does ({@link #abortBatch}): the target dropped,
   * the source re-opened, the volume removed, the service scaled back, the row {@code ABORTED} with
   * {@code aborted by <actor>}.
   *
   * <p>Accepted from {@code STOPPING} to {@code VERIFIED}. Refused with a 409: a {@code QUEUED} move —
   * nothing of it runs yet, and its redeploy refuses it if it cannot run (refusing it here instead
   * would race that redeploy for the row); a terminal one; and one at {@code SWITCHING} or later,
   * where the claim already points at the target and an abort would strand it.
   *
   * @return the move as it was when the abort was asked
   */
  public PdDatabaseMove abort(String id, String actor) {
    String who = actor == null || actor.isBlank() ? "an operator" : actor;
    PdDatabaseMove row;
    List<String> batch;
    synchronized (abortLock) {
      row = QuarkusTransaction.requiringNew().call(() -> moves.findById(id));
      if (row == null) {
        throw new NotFoundException("no database move " + id);
      }
      if (row.state == PdDatabaseMoveState.QUEUED) {
        throw new ConflictException(
            "the move " + id + " is still QUEUED behind its redeploy: nothing of it runs yet to abort");
      }
      if (PdDatabaseMoveState.TERMINAL.contains(row.state)) {
        throw new ConflictException("the move " + id + " is already " + row.state);
      }
      if (row.state.switched() || switching.contains(id)) {
        throw new ConflictException(
            "the move "
                + id
                + " is past its switch: the claim already points at the target, so an abort would"
                + " strand it — it finishes");
      }
      aborts.putIfAbsent(id, who);
      String batchId = row.batchId;
      batch =
          QuarkusTransaction.requiringNew()
              .call(() -> moves.list("batchId = ?1", batchId).stream().map(r -> r.id).toList());
    }
    LOG.warnf(
        "%s asked to abort the database move %s of %s at %s", who, id, row.applicationName, row.state);
    for (String member : batch) {
      try {
        containers.kill(member);
      } catch (RuntimeException e) {
        LOG.warnf("Could not kill the one-shots of the move %s: %s", member, e.getMessage());
      }
    }
    return row;
  }

  // --- the read surface -------------------------------------------------------------------------

  public List<PdDatabaseMove> list(String applicationName) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                applicationName == null
                    ? moves.listAll(LIST_LIMIT)
                    : moves.listByApplication(applicationName, LIST_LIMIT));
  }

  public Optional<PdDatabaseMove> find(String id) {
    return QuarkusTransaction.joiningExisting().call(() -> moves.findByIdOptional(id));
  }

  // --- small things -----------------------------------------------------------------------------

  /** One statement against a server's {@code postgres} database, as the admin, in a one-shot. */
  private DatabaseContainers.Outcome admin(DatabaseServer server, String environmentName, String script) {
    String image = admin.image(server, environmentName);
    return containers.run(
        admin.shot("admin", server, environmentName, image, null, Map.of(), script),
        ContainerResourceProvisioner.TIMEOUT);
  }

  private static DatabaseContainers.Outcome safely(java.util.function.Supplier<DatabaseContainers.Outcome> step) {
    try {
      return step.get();
    } catch (RuntimeException e) {
      return new DatabaseContainers.Outcome(-1, String.valueOf(e.getMessage()), false);
    }
  }

  private void recordRefusal(Planned move, String refusal, String batch, MoveSpace.Measured measured) {
    if (move.operatorRow() != null) {
      if (measured != null) {
        update(
            move.operatorRow().id,
            row -> {
              row.sourceBytes = measured.sourceBytes();
              row.targetFreeBytes = measured.targetFreeBytes();
              row.volumeFreeBytes = measured.volumeFreeBytes();
            });
      }
      refuse(move.operatorRow(), refusal);
      return;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = new PdDatabaseMove();
              row.id = move.id();
              row.batchId = batch;
              row.applicationName = move.applicationName();
              row.environmentName = move.environmentName();
              row.resourceName = move.resourceName();
              row.sourceServer = move.sourceServer().wire();
              row.sourceDatabase = move.sourceDatabase();
              row.targetServer = move.targetServer().wire();
              row.targetDatabase = move.targetDatabase();
              row.origin = PdDatabaseMove.Origin.DEPLOYMENT;
              row.state = PdDatabaseMoveState.REFUSED;
              row.detail = refusal;
              if (measured != null) {
                row.sourceBytes = measured.sourceBytes();
                row.targetFreeBytes = measured.targetFreeBytes();
                row.volumeFreeBytes = measured.volumeFreeBytes();
              }
              row.createdAt = Instant.now();
              row.finishedAt = row.createdAt;
              moves.persist(row);
            });
  }

  private void refuse(PdDatabaseMove row, String detail) {
    update(
        row.id,
        moved -> {
          moved.state = PdDatabaseMoveState.REFUSED;
          moved.finishedAt = Instant.now();
          moved.detail = detail;
        });
    LOG.warnf("Database move %s of %s refused: %s", row.id, row.applicationName, detail);
  }

  private PdDatabaseMove read(String id) {
    return QuarkusTransaction.requiringNew().call(() -> moves.findById(id));
  }

  private void update(String id, java.util.function.Consumer<PdDatabaseMove> change) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDatabaseMove row = moves.findById(id);
              if (row != null) {
                change.accept(row);
              }
            });
  }

  private String write(Map<String, Long> counts) {
    try {
      return json.writeValueAsString(new TreeMap<>(counts));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a manifest that will not serialise", e);
    }
  }

  private long totalOf(String counts) {
    if (counts == null) {
      return 0;
    }
    try {
      return DatabaseScripts.total(json.readValue(counts, COUNTS));
    } catch (JsonProcessingException e) {
      return -1;
    }
  }

  static String key(String application, String environment, String resource) {
    return application + "|" + environment + "|" + resource;
  }

  static String where(DatabaseServer server, String database) {
    return server.wire() + "/" + database;
  }

  private static String verdict(DatabaseContainers.Outcome outcome) {
    return outcome.timedOut() ? "timed out" : "exit " + outcome.exitCode();
  }

  private static String tail(String output) {
    String text = output == null ? "" : output.strip();
    return text.length() <= 800 ? text : "…" + text.substring(text.length() - 800);
  }

  /** A step of the move failed; the batch aborts with this sentence. */
  private static final class MoveFailure extends RuntimeException {
    MoveFailure(String message) {
      super(message, null, false, false);
    }
  }
}
