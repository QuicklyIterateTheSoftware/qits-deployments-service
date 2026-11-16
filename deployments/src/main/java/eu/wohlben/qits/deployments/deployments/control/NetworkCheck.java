package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.dto.SplitCheckDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>The network half of the split check</b> (epic qits-2174, task qits-2215): which services sit
 * where they must not. Pure — the labels come from {@link DeploymentDriver#labelledServices()} (the
 * route tables' own read, so a check costs no second listing) and the attachments from {@link
 * DeploymentDriver#attachedNetworks}, every network but swarm's {@code ingress}.
 *
 * <ul>
 *   <li><b>{@code bridged}</b>: a service on both the flat network ({@code qits-net}) and the
 *       private one ({@code qits-private}). Nothing is exempt: there is no break-glass.
 *   <li><b>{@code misplaced}</b>: a service carrying {@link DeploymentDriver#PLACEMENT_LABEL} whose
 *       attached networks are not exactly that one network. A service on both is reported under
 *       both findings; a service without a placement label is only ever {@code bridged}.
 * </ul>
 *
 * A service whose networks could not be read is absent from {@code attached} and is in neither
 * list: the check states what it saw, never a guess.
 */
public final class NetworkCheck {

  private NetworkCheck() {}

  /** Both findings, each sorted by service. */
  public record Findings(List<SplitCheckDto.Bridged> bridged, List<SplitCheckDto.Misplaced> misplaced) {

    public Findings {
      bridged = List.copyOf(bridged);
      misplaced = List.copyOf(misplaced);
    }

    public boolean clean() {
      return bridged.isEmpty() && misplaced.isEmpty();
    }
  }

  /**
   * @param services every labelled service
   * @param attached each service's attached networks, by service name
   * @param flatNetwork {@code qits.deployments.swarm.flat-network}
   * @param privateNetwork {@code qits.deployments.swarm.private-network}
   */
  public static Findings check(
      List<DeploymentDriver.LabelledService> services,
      Map<String, Set<String>> attached,
      String flatNetwork,
      String privateNetwork) {
    List<SplitCheckDto.Bridged> bridged = new ArrayList<>();
    List<SplitCheckDto.Misplaced> misplaced = new ArrayList<>();
    for (DeploymentDriver.LabelledService service : services) {
      Set<String> networks = attached.get(service.service());
      if (networks == null) {
        continue;
      }
      List<String> sorted = List.copyOf(new TreeSet<>(networks));
      if (networks.contains(flatNetwork) && networks.contains(privateNetwork)) {
        bridged.add(new SplitCheckDto.Bridged(service.service(), sorted));
      }
      String placement = service.label(DeploymentDriver.PLACEMENT_LABEL);
      if (placement != null && !networks.equals(Set.of(placement))) {
        misplaced.add(new SplitCheckDto.Misplaced(service.service(), placement, sorted));
      }
    }
    bridged.sort(Comparator.comparing(SplitCheckDto.Bridged::service));
    misplaced.sort(Comparator.comparing(SplitCheckDto.Misplaced::service));
    return new Findings(bridged, misplaced);
  }
}
