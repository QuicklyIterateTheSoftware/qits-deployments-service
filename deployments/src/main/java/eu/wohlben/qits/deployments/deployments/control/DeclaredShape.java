package eu.wohlben.qits.deployments.deployments.control;

import java.util.ArrayList;
import java.util.List;

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
 * which tier it reads for, so the substitution is {@link #aliasesFor}'s, at deploy time.
 */
public record DeclaredShape(
    List<ServiceExtras.Mount> binds, List<ServiceExtras.Publish> publishes, List<String> aliases) {

  /**
   * The one placeholder an alias may carry. Replaced by the deployment's environment name, so one
   * line ({@code registry.${QITS_ENVIRONMENT}.localhost}) says the right thing in every tier.
   */
  public static final String ENVIRONMENT_TOKEN = "${QITS_ENVIRONMENT}";

  /** The host path whose bind makes the deployer add the socket's own group — see the driver. */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /** A file that declared none of the three — every file that exists today. */
  public static final DeclaredShape NONE = new DeclaredShape(List.of(), List.of(), List.of());

  public DeclaredShape {
    binds = binds == null ? List.of() : List.copyOf(binds);
    publishes = publishes == null ? List.of() : List.copyOf(publishes);
    aliases = aliases == null ? List.of() : List.copyOf(aliases);
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
