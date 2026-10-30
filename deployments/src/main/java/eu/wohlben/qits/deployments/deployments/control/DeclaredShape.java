package eu.wohlben.qits.deployments.deployments.control;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The host-facing shape a repository declares for itself in {@code .config/qits/deployments.yml}:
 * {@code binds:}, {@code publishes:} and {@code aliases:}. The same three statements {@link
 * ServiceExtras} reads from deployment config as {@code mounts[n] bind:…}, {@code publishes[n]} and
 * {@code aliases[n]}, in the same records, so a driver renders both through one path.
 *
 * <p><b>Both sources are live at once until qits-1393</b> retires the extras source, and they may
 * say the same thing. The driver takes the union and the declaration wins: a mount by target, a
 * publish by published port and protocol, an alias by name. Nothing is ever emitted twice — swarm
 * refuses two mounts on one target and two publishes of one port outright.
 *
 * <p>{@code aliases} are kept as WRITTEN, {@link #ENVIRONMENT_TOKEN} included: the parser never knows
 * which tier it reads for, so the substitution is {@link #aliasesFor}'s, at deploy time. {@code
 * publishes} are kept as written for the same reason, {@link #QITS_PORT_TOKEN} included: the host
 * HTTP port is an installation's choice, known to the deployer and not to the file, so the
 * substitution is {@link #publishesFor}'s.
 */
public record DeclaredShape(
    List<ServiceExtras.Mount> binds, List<String> publishes, List<String> aliases) {

  /**
   * The one placeholder an alias may carry. Replaced by the deployment's environment name, so one
   * line ({@code registry.${QITS_ENVIRONMENT}.localhost}) says the right thing in every tier.
   */
  public static final String ENVIRONMENT_TOKEN = "${QITS_ENVIRONMENT}";

  /**
   * The one placeholder a publish may carry, and only as the whole HOST port: {@code
   * ${QITS_PORT}:8080}. Replaced by the deployer's {@code qits.deployments.qits-port} — the host HTTP
   * port the bootstrap installed the platform on — so the edge's line says the right thing on every
   * installation without the file knowing which.
   */
  public static final String QITS_PORT_TOKEN = "${QITS_PORT}";

  /** The host path whose bind makes the deployer add the socket's own group — see the driver. */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /** A file that declared none of the three — every file that exists today. */
  public static final DeclaredShape NONE = new DeclaredShape(List.of(), List.of(), List.of());

  public DeclaredShape {
    binds = binds == null ? List.of() : List.copyOf(binds);
    publishes = publishes == null ? List.of() : List.copyOf(publishes);
    aliases = aliases == null ? List.of() : List.copyOf(aliases);
  }

  /**
   * The publishes as this installation renders them: {@link #QITS_PORT_TOKEN} replaced by {@code
   * qitsPort}, and each result read again by {@link ServiceExtras#publish} — the one spelling of the
   * grammar — because the substituted value is what reaches the argv. A port and protocol twice
   * AFTER the substitution ({@code ${QITS_PORT}:8080, 8080:9090} with the port at 8080) is refused:
   * swarm would refuse the create.
   *
   * @throws ServiceExtras.Refused when a substituted publish is not one, or names a port twice
   */
  public List<ServiceExtras.Publish> publishesFor(int qitsPort) {
    List<ServiceExtras.Publish> resolved = new ArrayList<>();
    Set<String> ports = new HashSet<>();
    for (String publish : publishes) {
      String value = publish.replace(QITS_PORT_TOKEN, String.valueOf(qitsPort));
      ServiceExtras.Publish parsed = ServiceExtras.publish("publishes", value);
      if (!ports.add(parsed.portKey())) {
        throw new ServiceExtras.Refused(
            "publishes: `"
                + parsed.portKey()
                + "` is published twice once "
                + QITS_PORT_TOKEN
                + " is "
                + qitsPort);
      }
      resolved.add(parsed);
    }
    return List.copyOf(resolved);
  }

  /** Whether the declaration binds the docker socket, which is what earns the socket's group. */
  public boolean bindsDockerSocket() {
    return binds.stream().anyMatch(bind -> DOCKER_SOCKET.equals(bind.source()));
  }

  /**
   * The aliases as the deployment in {@code environment} answers to them: {@link
   * #ENVIRONMENT_TOKEN} replaced, and each result checked again by {@link ServiceExtras}' own alias
   * rule — no comma, no whitespace — because the substituted value is what reaches the argv.
   *
   * @throws ServiceExtras.Refused when an alias needs an environment and none is known, or the
   *     substituted value is not one name
   */
  public List<String> aliasesFor(String environment) {
    List<String> resolved = new ArrayList<>();
    for (String alias : aliases) {
      String value = alias;
      if (value.contains(ENVIRONMENT_TOKEN)) {
        if (environment == null || environment.isBlank()) {
          throw new ServiceExtras.Refused(
              "aliases: '" + alias + "' names " + ENVIRONMENT_TOKEN + " and this deployment has no"
                  + " environment to put there");
        }
        value = value.replace(ENVIRONMENT_TOKEN, environment);
      }
      resolved.add(ServiceExtras.alias("aliases", value));
    }
    return List.copyOf(resolved);
  }
}
