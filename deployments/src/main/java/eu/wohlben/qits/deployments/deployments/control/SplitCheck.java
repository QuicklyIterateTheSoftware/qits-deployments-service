package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.dto.EdgeRouteTableDto;
import eu.wohlben.qits.deployments.deployments.dto.SplitCheckDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The split check</b> (epic qits-2174, task qits-2215): that the public/private split holds, as
 * the runtime and the public edge show it — not as {@code pd_deployment} says it should.
 *
 * <ol>
 *   <li><b>Networks</b> ({@link NetworkCheck}): no service on both qits-net and qits-private, and
 *       every labelled service on exactly the network its placement label names. One ERROR per
 *       finding: {@code network bridge: <svc> on [<nets>]} and {@code network misplaced: <svc>
 *       labelled <net> but on [<nets>]}.
 *   <li><b>The public edge</b> ({@link PublicEdgeProbe}): every application in the private route
 *       table with a browser host is asked for on the public edge; a pass is {@code 404} or {@code
 *       421} (qits-2223: the public edge answers 421 for a private deployment's name). Anything
 *       else — a 2xx or 3xx, a 401/403 from an auth gate it was routed to, a 5xx — is an ERROR
 *       {@code private app <app> answers <status> on the public edge}.
 * </ol>
 *
 * <p><b>When.</b> On the observation pass, at most every {@code qits.deployments.split-check.every}
 * (10 minutes shipped), and once after each deploy of either edge reaches healthy. Both on the
 * deploy worker, right after the route tables' own rebuild, whose label read this reuses ({@link
 * EdgeRouteTables#lastRead}) — so the check adds no {@code service ls} of its own. A pass whose
 * label read failed checks nothing and keeps the last result; the next pass tries again.
 *
 * <p>{@code GET /deployments/api/split-check} answers {@link #last()} and never runs a check.
 */
@ApplicationScoped
public class SplitCheck {

  private static final Logger LOG = Logger.getLogger(SplitCheck.class);

  /** The probed path under an application's primary route: its readiness, which every app has. */
  static final String PROBE_SUFFIX = "/q/health/ready";

  @Inject EdgeRouteTables tables;

  @Inject DeploymentDriver driver;

  @Inject PublicEdgeProbe probe;

  @ConfigProperty(name = "qits.deployments.split-check.every")
  Duration every;

  @ConfigProperty(name = "qits.deployments.swarm.flat-network")
  String flatNetwork;

  @ConfigProperty(name = "qits.deployments.swarm.private-network")
  String privateNetwork;

  @ConfigProperty(name = "qits.deployments.platform-domain")
  Optional<String> platformDomain;

  private volatile SplitCheckDto last;

  /** When the last check ran, for the cadence; null before the first. */
  private volatile Instant lastRun;

  /** The last result, or empty before the first check. */
  public Optional<SplitCheckDto> last() {
    return Optional.ofNullable(last);
  }

  /** Forget the last result and the cadence, as a fresh process has neither. For the suite. */
  public void forget() {
    last = null;
    lastRun = null;
  }

  /** The observation pass's call: a check when none ran within {@link #every}. Never throws. */
  public void observe() {
    observe(Instant.now());
  }

  /** {@link #observe()} at {@code now} — public so the suite drives the cadence. */
  public void observe(Instant now) {
    Instant previous = lastRun;
    if (previous != null && now.isBefore(previous.plus(every))) {
      return;
    }
    runSafely(now);
  }

  /** After a deploy of {@code application} reached healthy: a check when it is either edge. */
  public void afterDeploy(String application) {
    if (EdgeRouteTables.edgeOf(application) != null) {
      runSafely(Instant.now());
    }
  }

  private void runSafely(Instant now) {
    try {
      run(now);
    } catch (RuntimeException e) {
      LOG.warnf(e, "The split check failed to run; the next pass tries again");
    }
  }

  /**
   * One check at {@code now}, whatever the cadence: stored as {@link #last()} and returned. Empty —
   * and nothing stored — when the services' labels could not be read. Public so the suite and the
   * golden-master states pin the instant.
   */
  public synchronized Optional<SplitCheckDto> run(Instant now) {
    Optional<List<DeploymentDriver.LabelledService>> services = tables.lastRead();
    if (services.isEmpty()) {
      LOG.debug("The services' labels could not be read; the split check waits for the next pass");
      return Optional.empty();
    }
    List<String> names = services.get().stream().map(DeploymentDriver.LabelledService::service).toList();
    Map<String, Set<String>> attached = driver.attachedNetworks(names);
    NetworkCheck.Findings findings =
        NetworkCheck.check(services.get(), attached, flatNetwork, privateNetwork);
    for (SplitCheckDto.Bridged bridged : findings.bridged()) {
      LOG.errorf("network bridge: %s on %s", bridged.service(), bridged.networks());
    }
    for (SplitCheckDto.Misplaced misplaced : findings.misplaced()) {
      LOG.errorf(
          "network misplaced: %s labelled %s but on %s",
          misplaced.service(), misplaced.placement(), misplaced.networks());
    }
    List<SplitCheckDto.PrivateApp> privateApps = probeAll();
    boolean ok = findings.clean() && privateApps.stream().allMatch(SplitCheck::passes);
    SplitCheckDto result =
        new SplitCheckDto(
            now.truncatedTo(ChronoUnit.MILLIS).toString(),
            ok,
            findings.bridged(),
            findings.misplaced(),
            privateApps);
    last = result;
    lastRun = now;
    if (ok) {
      LOG.infof(
          "split check ok: %d services on their placement, %d private apps not served on the public"
              + " edge",
          names.size(),
          privateApps.size());
    } else {
      LOG.errorf(
          "split check FAILED: %d bridged, %d misplaced, %d of %d private apps not refused on the"
              + " public edge",
          findings.bridged().size(),
          findings.misplaced().size(),
          privateApps.stream().filter(app -> !passes(app)).count(),
          privateApps.size());
    }
    return Optional.of(result);
  }

  /** A private application passes when the public edge does not serve it: 404 or 421. */
  public static boolean passes(SplitCheckDto.PrivateApp app) {
    return app.status() != null && notServed(app.status());
  }

  /** 404 (no such route) and 421 (qits-2223: a private deployment's name) — nothing else. */
  public static boolean notServed(int status) {
    return status == 404 || status == 421;
  }

  private List<SplitCheckDto.PrivateApp> probeAll() {
    List<EdgeRouteTableDto.Application> applications = privateApplications();
    List<SplitCheckDto.PrivateApp> answers = new ArrayList<>();
    String domain = platformDomain.map(String::strip).filter(d -> !d.isEmpty()).orElse(null);
    for (EdgeRouteTableDto.Application application : applications) {
      String host = application.browserHost();
      if (domain == null) {
        LOG.errorf(
            "private app %s cannot be probed on the public edge: qits.deployments.platform-domain is"
                + " not set",
            application.application());
        answers.add(
            new SplitCheckDto.PrivateApp(
                application.application(), host, null, "qits.deployments.platform-domain is not set"));
        continue;
      }
      String path = primaryPath(application) + PROBE_SUFFIX;
      try {
        int status = probe.status("qits." + domain, host + ".qits." + domain, path);
        if (!notServed(status)) {
          LOG.errorf("private app %s answers %d on the public edge", application.application(), status);
        }
        answers.add(new SplitCheckDto.PrivateApp(application.application(), host, status, null));
      } catch (IOException | RuntimeException e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        LOG.errorf(
            "private app %s could not be probed on the public edge: %s",
            application.application(), reason);
        answers.add(new SplitCheckDto.PrivateApp(application.application(), host, null, reason));
      }
    }
    return answers;
  }

  /** Every application in the stored private table that has a browser host. */
  private List<EdgeRouteTableDto.Application> privateApplications() {
    return tables
        .storedRow(EdgeRouteTables.PRIVATE)
        .map(row -> EdgeRouteTableDto.of(row, EdgeRouteTables.mapper()).applications())
        .orElse(List.of())
        .stream()
        .filter(application -> application.browserHost() != null && !application.browserHost().isBlank())
        .toList();
  }

  private static String primaryPath(EdgeRouteTableDto.Application application) {
    if (application.endpoints().isEmpty()) {
      return "";
    }
    String path = application.endpoints().get(0).path();
    if (path == null) {
      return "";
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }
}
