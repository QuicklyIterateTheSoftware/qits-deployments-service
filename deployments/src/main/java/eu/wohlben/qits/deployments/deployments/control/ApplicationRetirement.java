package eu.wohlben.qits.deployments.deployments.control;

import static eu.wohlben.qits.deployments.deployments.control.DeployService.CUTOVER_BUDGET;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.deployments.environments.error.ConflictException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The operator's third lever: <b>this application is over</b>.
 *
 * <p><b>Why it exists.</b> `application:` in a repository's spec decoupled the deployed identity
 * from the repository name, and the section of AGENTS.md that introduced it says plainly what
 * happens when the value changes: "CHANGING the value later is a decommission and a new
 * application, and nothing here helps. The old name keeps its service, alias, database, rows and
 * routes." Three of them were left behind on this install — `qits-workspaces-service`,
 * `qits-projects-service` and `qits-gateway` — and each one's newest row still said {@code FAILED},
 * from an attempt made under a name nothing has deployed since. An operator reading the deployment
 * listing saw three red rows for three applications that do not exist, beside the successors that
 * were plainly serving. "Nothing here helps" is the gap this closes.
 *
 * <p><b>It writes a word, it does not delete a row.</b> {@code DECOMMISSIONED} is this component's
 * own vocabulary for "this deployment's place was taken", written by {@link DeployService} on every
 * successful cutover and by {@link DeploymentObserver} on adoption; what it says here is the same
 * thing with the successor being an application rather than a deployment. The history stays whole
 * — the failure text under the stamp is untouched, the shas, the timestamps and the run ids are
 * exactly as they were — because a deployment row is a record of an attempt and an attempt that
 * happened does not stop having happened. There is deliberately no delete on this surface: {@code
 * RollbackPins} reads history, the id a client cached still resolves, and nothing about a
 * retirement is a reason to lose an audit trail.
 *
 * <p><b>What it touches, and it is deliberately little:</b>
 *
 * <ul>
 *   <li><b>the newest row of the place becomes {@code DECOMMISSIONED}</b>, because the newest row
 *       IS what the listing calls the application's current state — the whole point of the door is
 *       that the word a reader sees there is a decision somebody made rather than the residue of a
 *       build in August;
 *   <li><b>every {@code SPEC_UNREADABLE} row of the place becomes {@code DECOMMISSIONED} too</b>,
 *       which is the one word in this vocabulary that is not terminal: {@code DeployService}
 *       re-reads such a row on the observation's cadence until the file answers. A retired
 *       application's spec is never going to answer, so leaving one would be a retry that runs
 *       forever against a git address nobody maintains;
 *   <li><b>nothing else.</b> Older terminal rows keep the word they earned. A retirement is not a
 *       rewrite of what happened, and a listing that showed six identical {@code DECOMMISSIONED}
 *       rows would have destroyed exactly the history this door refuses to delete.
 * </ul>
 *
 * <p><b>It runs on the request thread, and that is the difference from {@link
 * ApplicationScaling}.</b> Both of that class's levers issue a {@code docker service update} and
 * therefore must queue behind whatever the deploy worker is cutting over; this one calls no
 * orchestrator at all. There is nothing running to act on — that is the precondition, enforced
 * below — so there is nothing a concurrent cutover could collide with, and an operator who is told
 * 200 has been told the truth rather than 202 and a promise.
 *
 * <p><b>What it refuses, and why each refusal is the safety this door needs.</b> The id names a
 * place by string; a typo names a live application just as well as a dead one. So:
 *
 * <ul>
 *   <li>{@code ACTIVE} or {@code SCALED_TO_ZERO} on the newest row is a <b>409</b>: something is
 *       deployed there. A serving application is retired by taking it out of its repository's spec
 *       and letting the deployment stop, not by relabelling the row that says it is up; a stopped
 *       one still has a swarm service holding its ports, its volumes and its alias, and calling it
 *       decommissioned would leave that service with nothing pointing at it.
 *   <li>{@code QUEUED} or {@code STARTING} is a <b>409</b>: the deploy worker owns those words and
 *       is about to write the next one. A row settled out from under the worker would be overwritten
 *       a second later, and the operator would never learn that their action did nothing.
 *   <li>A place nothing ever deployed is a <b>404</b>, and a malformed id a <b>400</b> — the same
 *       two answers {@link ApplicationScaling} gives, for the same reasons.
 * </ul>
 *
 * <p><b>It does not remove the catalogue row, and that is a decision.</b> {@code pd_service} is
 * derived: a green build states it, and {@code DELETE /services/{name}} is the operator's deliberate
 * act on it — a separate door with a separate argument, already shipped. Folding it in here would
 * mean this door quietly turned a retired application into one the listing labels "no longer
 * tracked", which is a worse row than the one it was asked to fix, not a better one.
 *
 * <p><b>The fourth lever, {@link #retire}, is the opposite case and a separate door on purpose</b>
 * (qits-765: {@code POST /applications/{applicationId}/retire}). Decommission is for a name that is
 * already dead — it writes a word, issues no orchestrator call and answers 200, and every one of
 * those is part of its documented contract. Retire is for an application that is still deployed and
 * has no successor to take its place (qits-configuration, retired with nothing replacing it): it
 * does the whole of what a rename cutover does to its predecessor — rows {@code DECOMMISSIONED},
 * routes and navigation withdrawn, swarm service removed, catalogue row, settings and idp client
 * deleted — and keeps the databases. Folding that into decommission would have turned a door that
 * "deletes nothing" into one that removes a running service, behind the same path somebody's
 * script already calls.
 */
@ApplicationScoped
public class ApplicationRetirement {

  private static final Logger LOG = Logger.getLogger(ApplicationRetirement.class);

  /**
   * The words that mean something is deployed in this place — the two the door refuses over.
   *
   * <p>{@code SCALED_TO_ZERO} is in it for the reason its own javadoc gives: it is not terminal in
   * the sense the others are. The service exists, holds its ports and its volumes, and one scale
   * back up makes the row {@code ACTIVE} again.
   */
  private static final Set<PdDeploymentStatus> SERVING =
      Set.of(PdDeploymentStatus.ACTIVE, PdDeploymentStatus.SCALED_TO_ZERO);

  /** The worker's own two words. Nothing outside the worker may settle a row that says one. */
  private static final Set<PdDeploymentStatus> IN_FLIGHT =
      Set.of(PdDeploymentStatus.QUEUED, PdDeploymentStatus.STARTING);

  @Inject PdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;
  @Inject EnvironmentService environments;
  @Inject DeployService worker;

  /**
   * What the door answers with — an outcome rather than a promise, since the write has happened by
   * the time this is returned.
   *
   * @param decommissionedIds every row this call settled, newest first: the current one, and any
   *     {@code SPEC_UNREADABLE} row whose retry it stopped
   */
  public record Retired(
      String applicationId,
      String applicationName,
      String environmentId,
      String currentDeploymentId,
      String previousStatus,
      List<String> decommissionedIds) {}

  /**
   * Retire one application: settle its current row and stop any retry still running for it.
   *
   * @param applicationId the derived key the read surface carries, {@code <environmentId>:<name>}
   *     or {@code platform:<name>}
   * @param actor who asked, for the row's stamp; null reads as "an operator"
   */
  public Retired decommission(String applicationId, String actor) {
    ApplicationKeys.Key key =
        ApplicationKeys.parse(applicationId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "'"
                            + applicationId
                            + "' is not an application id: it reads <environmentId>:<name>, or"
                            + " platform:<name> for an application on no tier"));
    // The name reaches a query — checked where every other stored identifier is, at the boundary.
    PdIdentifiers.requireName(key.applicationName(), "application name");

    String stamp =
        "[decommissioned by "
            + who(actor)
            + " at "
            + Instant.now()
            + ": this application is retired — nothing deploys under this name any more, and the"
            + " rows below are what it did while it existed]";

    Retired retired =
        DbRetry.inNewTx(
            "The retirement of " + applicationId,
            () -> settle(applicationId, key, stamp),
            CUTOVER_BUDGET);
    LOG.infof(
        "Decommissioned %s: %d row(s) settled, the current one was %s",
        applicationId, retired.decommissionedIds().size(), retired.previousStatus());
    return retired;
  }

  /**
   * The whole of the write, in one transaction: read the place, refuse or settle.
   *
   * <p>The refusals are raised from inside it on purpose. They are decided on the rows this
   * transaction read, so deciding them anywhere else would be deciding them on a state that could
   * have changed — and the only cost of raising them here is a transaction that rolls back having
   * written nothing, which is what a refusal means.
   */
  private Retired settle(String applicationId, ApplicationKeys.Key key, String stamp) {
    List<PdDeployment> place =
        deployments.listForPlaceNewestFirst(key.applicationName(), key.environmentId());
    if (place.isEmpty()) {
      throw new NotFoundException(
          "nothing has ever been deployed for "
              + applicationId
              + ", so there is nothing to retire");
    }
    PdDeployment current = place.get(0);
    if (SERVING.contains(current.status)) {
      throw new ConflictException(
          "the newest deployment of "
              + applicationId
              + " is "
              + current.status
              + ", so this application is still deployed: take it out of its repository's"
              + " deployments.yml and let the deployment stop, or scale it to 0 and remove the"
              + " service, before retiring the name");
    }
    if (IN_FLIGHT.contains(current.status)) {
      throw new ConflictException(
          "the newest deployment of "
              + applicationId
              + " is "
              + current.status
              + ", so the deploy worker still has work for this application: retire it once that"
              + " deployment has settled");
    }

    String previous = current.status.name();
    List<String> settled = new ArrayList<>();
    for (PdDeployment row : place) {
      boolean isCurrent = row == current;
      // The current row always, because the current row is what the listing calls the application's
      // state. An older one only when its word is the one that keeps re-asking a question nobody
      // will answer.
      if (!isCurrent && row.status != PdDeploymentStatus.SPEC_UNREADABLE) {
        continue;
      }
      if (row.status == PdDeploymentStatus.DECOMMISSIONED && !isCurrent) {
        continue;
      }
      row.status = PdDeploymentStatus.DECOMMISSIONED;
      row.detail = ApplicationScaling.stamped(stamp, row.detail);
      settled.add(row.id);
    }
    // Flushed rather than left to the commit, so a lost connection is a body failure the retry can
    // safely repeat — the same bracket every other settle in this package uses.
    deployments.flush();
    return new Retired(
        applicationId,
        current.applicationName,
        current.environmentId,
        current.id,
        previous,
        List.copyOf(settled));
  }

  /**
   * What the retire door answers with: what it resolved and what it queued — never an outcome,
   * because nothing has been issued when this is written. The deployment listing is where the
   * result is read (the row says {@code DECOMMISSIONED} with the stamp), and the catalogue listing
   * stops naming the application.
   *
   * @param serviceName the swarm service the retirement removes, off the newest row
   * @param deploymentId the newest row, the one the retirement settles
   * @param previousStatus the word that row carried when the request was resolved
   */
  public record Queued(
      String applicationId,
      String applicationName,
      String environmentId,
      String serviceName,
      String deploymentId,
      String previousStatus) {}

  /**
   * Retire an application that has <b>no successor</b>: everything a rename cutover does to its
   * predecessor, with nobody taking its place (qits-765). Validated here, on the request thread, so
   * a bad id, an unknown application, a place the worker still owns or the deployer's own service is
   * an answer rather than a log line under a 202; performed on the deploy worker by {@link
   * DeployService#retireApplication}, which runs the rename retirement's own steps — the newest row
   * and every {@code SPEC_UNREADABLE} one {@code DECOMMISSIONED} (stamped {@code [decommissioned by
   * <actor> …]}), the empty {@code DeploymentActive} that makes the edge withdraw the routes and
   * navigation entries, the swarm service removed, the {@code pd_service} row and its links deleted,
   * the {@code pd_setting} rows deleted, the idp client retired unless a setting still names it.
   *
   * <p><b>The data is kept on purpose.</b> The application's postgres claim rows ({@code
   * pd_resource}) are left exactly as they are and nothing is dropped: with no successor to hand
   * them to, the rows are what keeps each database claimed by the retired name, so no other
   * application can declare it and be handed somebody else's data, and nothing in this component
   * ever issues a {@code DROP}. Deleting the data is a person's decision, made somewhere else.
   *
   * <p>Any settled newest row is accepted — {@code ACTIVE} and {@code SCALED_TO_ZERO}, the two
   * decommission refuses, and equally a {@code FAILED}, {@code GONE} or already {@code
   * DECOMMISSIONED} one whose service, catalogue row or settings were left behind — because every
   * step is idempotent, and calling the door again is how a retirement a restart forgot is
   * finished.
   *
   * @param applicationId the derived key, {@code <environmentId>:<name>}
   * @param actor who asked, for the row's stamp; null reads as "an operator"
   * @throws ConflictException while a deployment of the place is {@code QUEUED}/{@code STARTING},
   *     or when the place is this deployer's own service — retiring it would remove the service the
   *     door answers on, with nothing left to finish the steps after that one
   */
  public Queued retire(String applicationId, String actor) {
    ApplicationKeys.Key key =
        ApplicationKeys.parse(applicationId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "'"
                            + applicationId
                            + "' is not an application id: it reads <environmentId>:<name>"));
    PdIdentifiers.requireName(key.applicationName(), "application name");
    PdDeployment current =
        QuarkusTransaction.joiningExisting()
            .call(
                () -> {
                  List<PdDeployment> place =
                      deployments.listForPlaceNewestFirst(
                          key.applicationName(), key.environmentId());
                  if (place.isEmpty()) {
                    return null;
                  }
                  PdDeployment row = place.get(0);
                  PdDeployment copy = new PdDeployment();
                  copy.id = row.id;
                  copy.applicationName = row.applicationName;
                  copy.environmentId = row.environmentId;
                  copy.containerName = row.containerName;
                  copy.status = row.status;
                  return copy;
                });
    if (current == null) {
      throw new NotFoundException(
          "nothing has ever been deployed for " + applicationId + ", so there is nothing to retire");
    }
    if (IN_FLIGHT.contains(current.status)) {
      throw new ConflictException(
          "the newest deployment of "
              + applicationId
              + " is "
              + current.status
              + ", so the deploy worker still has work for this application: retire it once that"
              + " deployment has settled");
    }
    String environmentName = environments.require(current.environmentId).name;
    String service =
        current.containerName == null || current.containerName.isBlank()
            ? PdNetworks.alias(environmentName, current.applicationName)
            : current.containerName;
    if (driver.isOwnService(service)) {
      throw new ConflictException(
          applicationId
              + " is this deployer's own service ("
              + service
              + "): retiring it would remove the service this door answers on, with the rest of"
              + " the retirement still to run");
    }
    String applicationName = current.applicationName;
    String environmentId = current.environmentId;
    String deploymentId = current.id;
    worker.enqueueOperation(
        "Retiring " + applicationId,
        () ->
            worker.retireApplication(
                applicationName, environmentId, environmentName, deploymentId, actor));
    LOG.infof(
        "Queued the retirement of %s (service %s, newest deployment %s was %s) for %s",
        applicationId, service, deploymentId, current.status, who(actor));
    return new Queued(
        applicationId,
        applicationName,
        environmentId,
        service,
        deploymentId,
        current.status.name());
  }

  private static String who(String actor) {
    return actor == null || actor.isBlank() ? "an operator" : actor;
  }
}
