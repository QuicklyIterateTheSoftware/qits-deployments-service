package eu.wohlben.qits.deployments.deployments.dto;

import java.util.List;

/**
 * The last split check (epic qits-2174, task qits-2215): what {@code GET
 * /deployments/api/split-check} answers. See {@code SplitCheck} for when one runs and {@code
 * NetworkCheck} for the two findings.
 *
 * @param checkedAt when the check ran, ISO-8601
 * @param ok true when nothing is bridged or misplaced and every private application answered 404
 *     or 421 on the public edge
 * @param bridged every service on both the flat network and the private one
 * @param misplaced every service whose attached networks are not exactly its placement label
 * @param privateApps every application in the private route table with a browser host, as the
 *     public edge answered for it
 */
public record SplitCheckDto(
    String checkedAt,
    boolean ok,
    List<Bridged> bridged,
    List<Misplaced> misplaced,
    List<PrivateApp> privateApps) {

  public SplitCheckDto {
    bridged = bridged == null ? List.of() : List.copyOf(bridged);
    misplaced = misplaced == null ? List.of() : List.copyOf(misplaced);
    privateApps = privateApps == null ? List.of() : List.copyOf(privateApps);
  }

  /**
   * A service on both networks.
   *
   * @param service the swarm service's name
   * @param networks every network it is attached to, sorted
   */
  public record Bridged(String service, List<String> networks) {

    public Bridged {
      networks = networks == null ? List.of() : List.copyOf(networks);
    }
  }

  /**
   * A service whose attached networks differ from its placement label.
   *
   * @param service the swarm service's name
   * @param placement the {@code qits.platform.deployments.placement} label it carries
   * @param networks every network it is attached to, sorted
   */
  public record Misplaced(String service, String placement, List<String> networks) {

    public Misplaced {
      networks = networks == null ? List.of() : List.copyOf(networks);
    }
  }

  /**
   * One private application, probed on the public edge.
   *
   * @param application its application name
   * @param host its browser host label, {@code <host>.qits.<domain>}
   * @param status what the public edge answered — 404 or 421 is a pass — or null when the probe
   *     could not get an answer
   * @param error why the probe got no answer, or null
   */
  public record PrivateApp(String application, String host, Integer status, String error) {}
}
