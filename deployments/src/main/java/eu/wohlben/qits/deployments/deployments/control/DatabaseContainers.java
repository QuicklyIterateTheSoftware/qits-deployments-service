package eu.wohlben.qits.deployments.deployments.control;

import java.time.Duration;
import java.util.Map;

/**
 * The seam for the containers a database move and the private server's provisioning run (qits-2175):
 * a {@code postgres} image on ONE network, running one script to its end, and the volume a dump
 * travels between two of them in.
 *
 * <p><b>The seam rule once more, and the reason it is a seam rather than a driver verb.</b> The
 * deployer is public, on qits-net only, and cannot open a socket to a postgres that lives only on
 * qits-private; what it can do is start a container ON that network through the docker socket it
 * already holds, and let the container speak to postgres. That is a docker call, so it is an
 * interface here and an implementation in {@code service/dbhost} — built on the platform's shared
 * docker layer, {@code qits-containers-driver}, by the owner's ruling — with a scripted fake in the
 * suite. It is not on {@link DeploymentDriver}: nothing here is a statement about a swarm service,
 * and that seam's verbs are deliberately outcomes rather than mechanics.
 *
 * <p><b>Three rules every implementation keeps, and the domain relies on all three.</b>
 *
 * <ul>
 *   <li><b>Exactly one network.</b> {@link OneShot#network} is the container's whole membership —
 *       no join after the run, no second network, no private edge. A move between the two servers
 *       is therefore two containers, one on each side, sharing a volume rather than a network.
 *   <li><b>No secret in an argv.</b> {@link OneShot#secrets} reach the container's environment
 *       without their values ever being an element of the {@code docker run} command line or a log
 *       line; {@link OneShot#script} carries none and reads them from its environment.
 *   <li><b>The output is redacted before it is returned</b>: every secret value is replaced, so a
 *       postgres error that echoes a statement cannot carry a password into a row's {@code detail}.
 * </ul>
 */
public interface DatabaseContainers {

  /**
   * One container that runs a shell script to its end.
   *
   * @param purpose a short dns-label word naming what it is for — {@code provision}, {@code extract},
   *     {@code ingest}, {@code admin} — part of the container's name, so a person reading {@code
   *     docker ps} or the deployer's log knows what it was
   * @param image the {@code postgres} image to run; it carries {@code sh}, {@code psql}, {@code
   *     pg_dump} and {@code pg_restore}
   * @param network the ONE network it joins
   * @param volume a named volume mounted at {@link #MOUNT}, or null for none
   * @param environment plain variables — {@code PGHOST}, {@code PGUSER} — that may be read by anyone
   * @param secrets variables whose VALUES never reach an argv or a log: the admin's {@code
   *     PGPASSWORD} and the role's password
   * @param script what {@code sh -c} runs, with {@code set -eu}; it names no secret value
   * @param moveId the database move this one-shot is a step of, or null — what {@link #kill} finds
   *     it by (qits-2243)
   */
  record OneShot(
      String purpose,
      String image,
      String network,
      String volume,
      Map<String, String> environment,
      Map<String, String> secrets,
      String script,
      String moveId) {

    public OneShot {
      environment = environment == null ? Map.of() : Map.copyOf(environment);
      secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    /** A one-shot that is no step of a move. */
    public OneShot(
        String purpose,
        String image,
        String network,
        String volume,
        Map<String, String> environment,
        Map<String, String> secrets,
        String script) {
      this(purpose, image, network, volume, environment, secrets, script, null);
    }

    /** This one-shot as a step of the move {@code id}, so an abort can {@link #kill} it. */
    public OneShot forMove(String id) {
      return new OneShot(purpose, image, network, volume, environment, secrets, script, id);
    }
  }

  /** Where {@link OneShot#volume} is mounted inside the container. */
  String MOUNT = "/move";

  /**
   * How a one-shot ended: the script's exit code ({@code -1} when it never ran or the deadline killed
   * it), its redacted output tail, and whether the deadline expired.
   */
  record Outcome(int exitCode, String output, boolean timedOut) {

    public boolean ok() {
      return exitCode == 0 && !timedOut;
    }
  }

  /** Run the one-shot to its end, or to {@code timeout}, and remove the container either way. */
  Outcome run(OneShot shot, Duration timeout);

  /** Create the named volume a move's two containers share. Creating one that exists is a no-op. */
  boolean createVolume(String volume);

  /**
   * {@link #createVolume(String)}, with {@code labels} added (qits-2209). The keys are short — {@code
   * kind}, {@code self-move} — and the implementation puts them in its own namespace.
   */
  boolean createVolume(String volume, Map<String, String> labels);

  /**
   * Every volume that carries the label {@code key=value}, with all of its labels in the short form
   * {@link #createVolume(String, Map)} took them in (qits-2209). Empty when there is none.
   *
   * @throws IllegalStateException when the runtime cannot answer — "none" and "could not ask" must
   *     not read the same
   */
  Map<String, Map<String, String>> labelledVolumes(String key, String value);

  /** Remove the named volume. Removing one that is already gone counts as done. */
  boolean removeVolume(String volume);

  /**
   * Remove, running or not, every one-shot of the move {@code moveId} ({@link OneShot#moveId}) — an
   * operator's abort (qits-2243). Its {@link #run} then returns a failed {@link Outcome}. Nothing
   * found counts as done.
   */
  boolean kill(String moveId);

  /** Whether a swarm service still has a task that is not terminal — see {@link Drain}. */
  Drain drained(String service);

  /** What {@link #drained} could say. */
  enum Drain {
    /** No task of the service is running or on its way to running — or there is no such service. */
    DRAINED,
    /** At least one task has not reached a terminal state yet. */
    RUNNING,
    /** The runtime could not answer. */
    UNKNOWN
  }

  /** Replace every secret value in {@code output} — the one redaction both sides of the seam share. */
  static String redact(String output, Map<String, String> secrets) {
    if (output == null) {
      return "";
    }
    String redacted = output;
    for (String value : secrets.values()) {
      if (value != null && !value.isEmpty()) {
        redacted = redacted.replace(value, "***");
      }
    }
    return redacted;
  }
}
