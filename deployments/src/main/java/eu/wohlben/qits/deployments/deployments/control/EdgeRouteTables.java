package eu.wohlben.qits.deployments.deployments.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.dto.EdgeRouteTableDto;
import eu.wohlben.qits.deployments.deployments.entity.PdEdgeRouteTable;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.events.NavigationEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>The route table of each edge, built from the services' own labels</b> (epic qits-2174, task
 * qits-2198) and kept in {@code pd_edge_route_table}. {@link EdgeRoutePusher} carries a new version
 * to its edge; {@code GET /deployments/api/edge-routes/{edge}} answers the stored one.
 *
 * <p><b>The input is the runtime's, never {@code pd_deployment}.</b> One {@link
 * DeploymentDriver#labelledServices()} — a {@code service ls} and one {@code service inspect} of all
 * of them — and the labels C1 writes on every create, update and backfill: {@link
 * DeploymentDriver#EXPOSURE_LABEL}, {@link DeploymentDriver#ROUTES_LABEL}, {@link
 * DeploymentDriver#HOSTS_LABEL}, {@link DeploymentDriver#UPSTREAM_PORT_LABEL}, {@link
 * DeploymentDriver#API_DOCS_LABEL} and {@link DeploymentDriver#NAVIGATION_LABEL}. A read that fails
 * builds nothing: a table built from it would withdraw every route the edges serve.
 *
 * <ul>
 *   <li><b>{@code public}</b> (qits-edge): every service with exposure {@code public} and a
 *       non-empty {@code routes} label; every private one with routes is in {@code withdrawn}, by
 *       its host, so the public edge's {@code /main-navigation} leaves it out.
 *   <li><b>{@code private}</b> (qits-edge-private and qits-edge-overlay): every service with
 *       exposure {@code private} and routes; {@code withdrawn} is empty.
 *   <li><b>Never in a table:</b> the edges themselves, and services with no routes.
 * </ul>
 *
 * <p><b>Two tables, three push targets</b> (qits-2304). A table is what is built and stored — one
 * {@code pd_edge_route_table} row each, {@link #TABLES}. A target is an edge it is pushed to,
 * {@link #TARGETS}: {@code public} carries the public table, and both private-kind edges carry the
 * private one — {@code private}, the WireGuard tunnel edge, and {@code overlay}, the edge that
 * serves {@code qits-private} alone and never leaves a gap on redeploy ({@link #tableOf}). The
 * overlay edge holds the same table the private edge does, row, version and body, so a new private
 * version is pushed to both and each is asked separately what it holds; one being unreachable (the
 * overlay edge before its first deployment) costs the other nothing.
 *
 * <p><b>The version only goes up and survives a lost database.</b> A changed content hash gives
 * {@code max(stored + 1, epoch millis now)} ({@link #nextVersion}); an unchanged one keeps it. The
 * millis floor is what makes a deployer whose table row is gone still answer above whatever an edge
 * already holds. Rebuilding is cheap and idempotent, so it runs after every apply, retirement and
 * teardown and on every observation pass.
 */
@ApplicationScoped
public class EdgeRouteTables {

  private static final Logger LOG = Logger.getLogger(EdgeRouteTables.class);

  /** The public table, and the push target that carries it: qits-edge. */
  public static final String PUBLIC = "public";

  /** The private table, and the push target that carries it to qits-edge-private. */
  public static final String PRIVATE = "private";

  /**
   * The push target qits-edge-overlay (qits-2304). It is no table: it carries {@link #PRIVATE}'s.
   */
  public static final String OVERLAY = "overlay";

  /** The tables that are built and stored, one row each, in push order — and the door's names. */
  public static final List<String> TABLES = List.of(PUBLIC, PRIVATE);

  /** The edges a table is pushed to, in push order; {@link #tableOf} names what each carries. */
  public static final List<String> TARGETS = List.of(PUBLIC, PRIVATE, OVERLAY);

  /** The public edge's application, which is in no table. */
  public static final String PUBLIC_EDGE_APPLICATION = "qits-edge";

  /** The private edge's application — the WireGuard tunnel edge — which is in no table. */
  public static final String PRIVATE_EDGE_APPLICATION = "qits-edge-private";

  /** The overlay edge's application (qits-2304), which is in no table. */
  public static final String OVERLAY_EDGE_APPLICATION = "qits-edge-overlay";

  private static final Set<String> EDGE_APPLICATIONS =
      Set.of(PUBLIC_EDGE_APPLICATION, PRIVATE_EDGE_APPLICATION, OVERLAY_EDGE_APPLICATION);

  /**
   * The hash and the body: a plain mapper, so the bytes do not depend on the application's Jackson
   * customisation. Records serialise in declaration order, nulls included.
   */
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject DeploymentDriver driver;

  @Inject EnvironmentService environments;

  /** What the last {@link #rebuild} read off the runtime; null before the first and after a failure. */
  private volatile List<DeploymentDriver.LabelledService> lastRead;

  /** The push target an application's deploy reaching healthy must push its table to, or null. */
  public static String edgeOf(String application) {
    if (PUBLIC_EDGE_APPLICATION.equals(application)) {
      return PUBLIC;
    }
    if (PRIVATE_EDGE_APPLICATION.equals(application)) {
      return PRIVATE;
    }
    if (OVERLAY_EDGE_APPLICATION.equals(application)) {
      return OVERLAY;
    }
    return null;
  }

  /** Whether {@code application} is one of the three edges, which no table ever routes. */
  public static boolean isEdgeApplication(String application) {
    return application != null && EDGE_APPLICATIONS.contains(application);
  }

  /** The table a push target carries: {@code overlay} carries the private one. */
  public static String tableOf(String target) {
    return switch (target) {
      case PUBLIC -> PUBLIC;
      case PRIVATE, OVERLAY -> PRIVATE;
      default -> throw new IllegalArgumentException("there is no `" + target + "` edge");
    };
  }

  /** The push targets that carry {@code table}, in {@link #TARGETS} order. */
  public static List<String> targetsOf(String table) {
    return TARGETS.stream().filter(target -> tableOf(target).equals(table)).toList();
  }

  /** What one table holds, without its version: what the content hash is over. */
  public record Content(
      List<EdgeRouteTableDto.Application> applications, List<EdgeRouteTableDto.Withdrawn> withdrawn) {}

  /** {@link #rebuild(Instant)} at the current instant. */
  public List<String> rebuild() {
    return rebuild(Instant.now());
  }

  /**
   * Build both tables from the labels and store each whose content changed, as a new version.
   * Returns the TABLES whose version moved, in {@link #TABLES} order — never a push target; empty when nothing changed or
   * the runtime could not be read. Public, with the instant as an argument, so the suite and the
   * golden-master state can pin the version.
   */
  public synchronized List<String> rebuild(Instant now) {
    Optional<List<DeploymentDriver.LabelledService>> services = driver.labelledServices();
    lastRead = services.orElse(null);
    if (services.isEmpty()) {
      LOG.debug("The services' labels could not be read; the route tables stay as they are");
      return List.of();
    }
    Instant builtAt = now.truncatedTo(ChronoUnit.MILLIS);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Map<String, Content> tables = contentsOf(services.get(), environmentNames());
              List<String> moved = new ArrayList<>();
              for (String edge : TABLES) {
                if (store(edge, tables.get(edge), builtAt)) {
                  moved.add(edge);
                }
              }
              return List.copyOf(moved);
            });
  }

  /**
   * The services and labels the last {@link #rebuild} read, or empty before the first and after a
   * read that failed — the split check's input (qits-2215), so a check costs no second listing.
   */
  public Optional<List<DeploymentDriver.LabelledService>> lastRead() {
    return Optional.ofNullable(lastRead);
  }

  /** The stored row itself — the door's read, so its DTO is built from the entity. */
  public Optional<PdEdgeRouteTable> storedRow(String edge) {
    return QuarkusTransaction.joiningExisting()
        .call(() -> Optional.ofNullable(PdEdgeRouteTable.<PdEdgeRouteTable>findById(edge)));
  }

  /** The JSON both the push and the door carry, for a table. */
  public static String json(EdgeRouteTableDto table) {
    try {
      return JSON.writeValueAsString(table);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not write the route table", e);
    }
  }

  /** The mapper {@link EdgeRouteTableDto#of} reads a stored body with. */
  public static ObjectMapper mapper() {
    return JSON;
  }

  /**
   * The version a changed table gets: one above the stored one, and never below the epoch millis
   * of {@code now} — so a lost table row still never answers below what an edge holds.
   */
  static long nextVersion(Long stored, Instant now) {
    long floor = Math.max(1, now.toEpochMilli());
    return stored == null ? floor : Math.max(stored + 1, floor);
  }

  private boolean store(String edge, Content content, Instant builtAt) {
    String hash = hash(content);
    PdEdgeRouteTable row = PdEdgeRouteTable.findById(edge);
    if (row != null && hash.equals(row.contentHash)) {
      return false;
    }
    Long previous = row == null ? null : row.version;
    long version = nextVersion(previous, builtAt);
    EdgeRouteTableDto table =
        new EdgeRouteTableDto(
            edge, version, builtAt.toString(), content.applications(), content.withdrawn());
    if (row == null) {
      row = new PdEdgeRouteTable();
      row.edge = edge;
      row.version = version;
      row.contentHash = hash;
      row.body = json(table);
      row.builtAt = builtAt;
      row.persist();
    } else {
      row.version = version;
      row.contentHash = hash;
      row.body = json(table);
      row.builtAt = builtAt;
    }
    LOG.infof(
        "route table %s %s -> %d (%d applications, %d withdrawn)",
        edge,
        previous == null ? "0" : previous.toString(),
        version,
        content.applications().size(),
        content.withdrawn().size());
    return true;
  }

  private Map<String, String> environmentNames() {
    Map<String, String> names = new HashMap<>();
    for (PdEnvironment environment : environments.list()) {
      names.put(environment.id, environment.name);
    }
    return names;
  }

  /**
   * Both tables' contents from the labels — static, so every rule is asserted without a boot.
   *
   * @param environmentNames environment id → name, which the {@code environment} label does not
   *     carry; a service whose id is unknown falls back to its own name minus {@code -<app>}
   */
  public static Map<String, Content> contentsOf(
      List<DeploymentDriver.LabelledService> services, Map<String, String> environmentNames) {
    Map<String, EdgeRouteTableDto.Application> publicApps = new LinkedHashMap<>();
    Map<String, EdgeRouteTableDto.Application> privateApps = new LinkedHashMap<>();
    Map<String, EdgeRouteTableDto.Withdrawn> withdrawn = new LinkedHashMap<>();
    // By name, so two services of one application (a seed-stack twin, a rename in flight) settle
    // the same way every time: the one whose name is the wire alias wins, else the first by name.
    List<DeploymentDriver.LabelledService> ordered = new ArrayList<>(services);
    ordered.sort(Comparator.comparing(DeploymentDriver.LabelledService::service));
    List<DeploymentDriver.LabelledService> byAlias = new ArrayList<>();
    List<DeploymentDriver.LabelledService> others = new ArrayList<>();
    for (DeploymentDriver.LabelledService service : ordered) {
      String app = service.label(DeploymentDriver.APP_NAME_LABEL);
      String environment = environmentOf(service, app, environmentNames);
      if (app != null
          && environment != null
          && PdNetworks.alias(environment, app).equals(service.service())) {
        byAlias.add(service);
      } else {
        others.add(service);
      }
    }
    List<DeploymentDriver.LabelledService> candidates = new ArrayList<>(byAlias);
    candidates.addAll(others);
    for (DeploymentDriver.LabelledService service : candidates) {
      String app = service.label(DeploymentDriver.APP_NAME_LABEL);
      if (app == null || EDGE_APPLICATIONS.contains(app)) {
        continue;
      }
      List<String> routes = split(service.label(DeploymentDriver.ROUTES_LABEL));
      if (routes.isEmpty()) {
        continue;
      }
      String exposure = service.label(DeploymentDriver.EXPOSURE_LABEL);
      if (!PUBLIC.equals(exposure) && !PRIVATE.equals(exposure)) {
        // Not placed yet (the backfill has not reached it) or a value nobody wrote: no table can
        // say which edge serves it, so neither does.
        LOG.debugf("%s carries no usable exposure label (%s); it is in no route table", service.service(), exposure);
        continue;
      }
      String environment = environmentOf(service, app, environmentNames);
      if (environment == null) {
        LOG.debugf("%s names no environment this component knows; it is in no route table", service.service());
        continue;
      }
      String key = environment + "\n" + app;
      if (publicApps.containsKey(key) || privateApps.containsKey(key)) {
        continue;
      }
      List<String> hosts = split(service.label(DeploymentDriver.HOSTS_LABEL));
      String host = hosts.isEmpty() ? null : hosts.get(0);
      int port = port(service.label(DeploymentDriver.UPSTREAM_PORT_LABEL));
      String upstreamHost = PdNetworks.alias(environment, app);
      List<EdgeRouteTableDto.Endpoint> endpoints = new ArrayList<>();
      for (String route : routes) {
        endpoints.add(new EdgeRouteTableDto.Endpoint(route, upstreamHost, port));
      }
      EdgeRouteTableDto.Application application =
          new EdgeRouteTableDto.Application(
              environment,
              app,
              exposure,
              host,
              service.label(DeploymentDriver.API_DOCS_LABEL),
              endpoints,
              navigation(service));
      if (PUBLIC.equals(exposure)) {
        publicApps.put(key, application);
      } else {
        privateApps.put(key, application);
        withdrawn.put(key, new EdgeRouteTableDto.Withdrawn(environment, app, host, exposure));
      }
    }
    Map<String, Content> contents = new LinkedHashMap<>();
    contents.put(PUBLIC, new Content(sorted(publicApps), sortedWithdrawn(withdrawn)));
    contents.put(PRIVATE, new Content(sorted(privateApps), List.of()));
    return contents;
  }

  private static String environmentOf(
      DeploymentDriver.LabelledService service, String app, Map<String, String> environmentNames) {
    String id = service.label(DeploymentDriver.ENVIRONMENT_LABEL);
    String name = id == null ? null : environmentNames.get(id);
    if (name != null) {
      return name;
    }
    String suffix = "-" + app;
    if (app != null && service.service().endsWith(suffix) && service.service().length() > suffix.length()) {
      return service.service().substring(0, service.service().length() - suffix.length());
    }
    return null;
  }

  private static List<EdgeRouteTableDto.Navigation> navigation(DeploymentDriver.LabelledService service) {
    String label = service.label(DeploymentDriver.NAVIGATION_LABEL);
    List<NavigationEntry> entries;
    try {
      entries = DeploymentSpecParser.parseEntries(label);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s carries a navigation label this component did not write (%s): %s; it is routed without"
              + " navigation",
          service.service(), label, unreadable.getMessage());
      return List.of();
    }
    return entries.stream()
        .map(e -> new EdgeRouteTableDto.Navigation(e.slot(), e.label(), e.position(), e.subpath()))
        .toList();
  }

  private static List<EdgeRouteTableDto.Application> sorted(
      Map<String, EdgeRouteTableDto.Application> applications) {
    return applications.values().stream()
        .sorted(
            Comparator.comparing(EdgeRouteTableDto.Application::environment)
                .thenComparing(EdgeRouteTableDto.Application::application))
        .toList();
  }

  private static List<EdgeRouteTableDto.Withdrawn> sortedWithdrawn(
      Map<String, EdgeRouteTableDto.Withdrawn> withdrawn) {
    return withdrawn.values().stream()
        .sorted(
            Comparator.comparing(EdgeRouteTableDto.Withdrawn::environment)
                .thenComparing(EdgeRouteTableDto.Withdrawn::application))
        .toList();
  }

  private static List<String> split(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  private static int port(String value) {
    if (value == null) {
      return DeploymentDriver.DEFAULT_UPSTREAM_PORT;
    }
    try {
      int port = Integer.parseInt(value.strip());
      return port > 0 && port <= 65535 ? port : DeploymentDriver.DEFAULT_UPSTREAM_PORT;
    } catch (NumberFormatException e) {
      return DeploymentDriver.DEFAULT_UPSTREAM_PORT;
    }
  }

  static String hash(Content content) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(JSON.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("could not hash the route table", e);
    }
  }
}
