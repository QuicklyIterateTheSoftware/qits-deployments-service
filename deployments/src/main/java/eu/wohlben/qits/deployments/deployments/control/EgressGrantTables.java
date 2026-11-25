package eu.wohlben.qits.deployments.deployments.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.dto.EgressGrantTableDto;
import eu.wohlben.qits.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressDeclaration;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressGrantTable;
import eu.wohlben.qits.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jboss.logging.Logger;

/**
 * <b>The egress grant table in force</b> (epic qits-2523): the routes of each application's {@code
 * ACTIVE} deployment, as its released version's {@code egress.yml} declared them ({@link
 * EgressDeclarations}), kept in {@code pd_egress_grant_table}, answered by {@code GET
 * /deployments/api/egress-grants} and pushed to qits-egress by {@link EgressGrantPusher} (qits-egress
 * is public and cannot reach this private service, so it never polls).
 *
 * <ul>
 *   <li><b>One deployment per place</b>: the newest {@code ACTIVE} row of each (application, tier)
 *       by {@code seq} — by invariant the only one.
 *   <li><b>Its declaration is its VERSION's</b>, joined on (application, version). No declaration
 *       (a version released before this, a variant, a release still being read) grants nothing.
 *   <li><b>A refused declaration grants nothing and is listed</b> under {@code refused}, once per
 *       (application, version) however many tiers run it.
 *   <li><b>The projects' routes join them</b> (qits-2659): what {@link ProjectEgressGrants} last
 *       read from each project's wrapper, as {@code principal: "project:<projectId>"} with no
 *       application and no environment, at the wrapper's {@code main} commit. A refused project
 *       file is listed under {@code refused} with its principal.
 *   <li><b>Every route and refusal names its principal</b>: {@code application:<app>} or {@code
 *       project:<projectId>}. Routes sort by environment (none first), principal and name.
 * </ul>
 *
 * <p><b>The version moves only when the content does</b>, {@link EdgeRouteTables#nextVersion}'s
 * rule: a changed content hash gives {@code max(stored + 1, epoch millis now)}, an unchanged one
 * keeps it. The table is built when it is ASKED for ({@link #current}) — cheap, two queries — so it
 * can never be behind the rows, and asking twice changes nothing; that idempotence is what lets the
 * door re-run it under {@code PdReadPatience}.
 */
@ApplicationScoped
public class EgressGrantTables {

  private static final Logger LOG = Logger.getLogger(EgressGrantTables.class);

  /** The hash and the body: a plain mapper, so the bytes do not follow application config. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PdDeploymentRepository deployments;

  @Inject EnvironmentService environments;

  @Inject ProjectEgressGrants projects;

  /** What the table holds, without its version: what the content hash is over. */
  public record Content(
      List<EgressGrantTableDto.Route> routes, List<EgressGrantTableDto.Refused> refused) {}

  /** {@link #current(Instant)} at the current instant. */
  public EgressGrantTableDto current() {
    return current(Instant.now());
  }

  /**
   * The table in force, built from the rows and stored as a new version when its content changed
   * since the stored one. Public, with the instant as an argument, so the suite and the
   * golden-master states can pin the version.
   */
  public synchronized EgressGrantTableDto current(Instant now) {
    Instant builtAt = now.truncatedTo(ChronoUnit.MILLIS);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<PdDeployment> active = deployments.listByStatus(PdDeploymentStatus.ACTIVE);
              Map<String, PdEgressDeclaration> declarations = declarationsOf(active);
              Content content =
                  contentOf(active, declarations, environmentNames(), projects.current());
              return store(content, builtAt);
            });
  }

  private EgressGrantTableDto store(Content content, Instant builtAt) {
    String hash = hash(content);
    PdEgressGrantTable row = PdEgressGrantTable.findById(PdEgressGrantTable.ID);
    if (row != null && hash.equals(row.contentHash)) {
      return EgressGrantTableDto.of(row, JSON);
    }
    Long previous = row == null ? null : row.version;
    long version = EdgeRouteTables.nextVersion(previous, builtAt);
    EgressGrantTableDto table = new EgressGrantTableDto(version, content.routes(), content.refused());
    String body = json(table);
    if (row == null) {
      row = new PdEgressGrantTable();
      row.id = PdEgressGrantTable.ID;
      row.version = version;
      row.contentHash = hash;
      row.body = body;
      row.builtAt = builtAt;
      row.persist();
    } else {
      row.version = version;
      row.contentHash = hash;
      row.body = body;
      row.builtAt = builtAt;
    }
    LOG.infof(
        "egress grant table %s -> %d (%d routes, %d refused)",
        previous == null ? "0" : previous.toString(),
        version,
        content.routes().size(),
        content.refused().size());
    return table;
  }

  private static Map<String, PdEgressDeclaration> declarationsOf(List<PdDeployment> active) {
    List<String> ids =
        active.stream()
            .filter(row -> row.version != null)
            .map(row -> PdEgressDeclaration.idOf(row.applicationName, row.version))
            .distinct()
            .toList();
    Map<String, PdEgressDeclaration> byId = new HashMap<>();
    if (!ids.isEmpty()) {
      for (PdEgressDeclaration declaration :
          PdEgressDeclaration.<PdEgressDeclaration>list("id in ?1", ids)) {
        byId.put(declaration.id, declaration);
      }
    }
    return byId;
  }

  private Map<String, String> environmentNames() {
    Map<String, String> names = new HashMap<>();
    for (PdEnvironment environment : environments.list()) {
      names.put(environment.id, environment.name);
    }
    return names;
  }

  /**
   * The table's content from the rows — static, so every rule is asserted without a boot.
   *
   * @param active every {@code ACTIVE} deployment row
   * @param declarations by {@link PdEgressDeclaration#idOf}
   * @param environmentNames environment id → name
   */
  public static Content contentOf(
      List<PdDeployment> active,
      Map<String, PdEgressDeclaration> declarations,
      Map<String, String> environmentNames) {
    return contentOf(active, declarations, environmentNames, List.of());
  }

  /**
   * {@link #contentOf(List, Map, Map)} with the projects' grants (qits-2659) beside the
   * applications'.
   */
  public static Content contentOf(
      List<PdDeployment> active,
      Map<String, PdEgressDeclaration> declarations,
      Map<String, String> environmentNames,
      java.util.Collection<ProjectEgressGrants.Grants> projectGrants) {
    Map<String, PdDeployment> newestPerPlace = new LinkedHashMap<>();
    for (PdDeployment row : active) {
      String place = row.environmentId + "\n" + row.applicationName;
      PdDeployment held = newestPerPlace.get(place);
      if (held == null || seq(row) > seq(held)) {
        newestPerPlace.put(place, row);
      }
    }
    List<EgressGrantTableDto.Route> routes = new ArrayList<>();
    Map<String, EgressGrantTableDto.Refused> refused = new LinkedHashMap<>();
    for (PdDeployment row : newestPerPlace.values()) {
      if (row.version == null) {
        continue;
      }
      PdEgressDeclaration declaration =
          declarations.get(PdEgressDeclaration.idOf(row.applicationName, row.version));
      if (declaration == null) {
        continue;
      }
      if (declaration.refused != null) {
        refused.putIfAbsent(
            declaration.id,
            new EgressGrantTableDto.Refused(
                EgressGrantTableDto.applicationPrincipal(row.applicationName),
                row.applicationName,
                row.version,
                declaration.refused));
        continue;
      }
      String environment =
          row.environmentId == null ? null : environmentNames.get(row.environmentId);
      for (EgressDeclarationParser.Route route : EgressDeclarations.routesOf(declaration)) {
        routes.add(
            new EgressGrantTableDto.Route(
                EgressGrantTableDto.applicationPrincipal(row.applicationName),
                row.applicationName,
                environment,
                row.version,
                route.name(),
                route.target(),
                route.secret(),
                route.inject(),
                route.methods()));
      }
    }
    List<EgressGrantTableDto.Refused> refusals = new ArrayList<>(refused.values());
    for (ProjectEgressGrants.Grants project : projectGrants) {
      String principal = EgressGrantTableDto.projectPrincipal(project.projectId());
      if (project.refused() != null) {
        refusals.add(
            new EgressGrantTableDto.Refused(
                principal, null, project.deployedVersion(), project.refused()));
        continue;
      }
      for (EgressDeclarationParser.Route route : project.routes()) {
        routes.add(
            new EgressGrantTableDto.Route(
                principal,
                null,
                null,
                project.deployedVersion(),
                route.name(),
                route.target(),
                route.secret(),
                route.inject(),
                route.methods()));
      }
    }
    Comparator<String> nullsFirst = Comparator.nullsFirst(Comparator.<String>naturalOrder());
    routes.sort(
        Comparator.comparing(EgressGrantTableDto.Route::environment, nullsFirst)
            .thenComparing(EgressGrantTableDto.Route::principal)
            .thenComparing(EgressGrantTableDto.Route::name));
    refusals.sort(
        Comparator.comparing(EgressGrantTableDto.Refused::principal)
            .thenComparing(EgressGrantTableDto.Refused::deployedVersion, nullsFirst));
    return new Content(List.copyOf(routes), List.copyOf(refusals));
  }

  private static long seq(PdDeployment row) {
    return Objects.requireNonNullElse(row.seq, Long.MIN_VALUE);
  }

  /**
   * The table's JSON as it is stored and served — and pushed to qits-egress verbatim ({@link
   * EgressGrantPusher}): a plain mapper, {@code {version, routes, refused}}.
   */
  static String json(EgressGrantTableDto table) {
    try {
      return JSON.writeValueAsString(table);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not write the egress grant table", e);
    }
  }

  static String hash(Content content) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(JSON.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("could not hash the egress grant table", e);
    }
  }
}
