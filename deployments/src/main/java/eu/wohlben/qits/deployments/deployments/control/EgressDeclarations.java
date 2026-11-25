package eu.wohlben.qits.deployments.deployments.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.deployments.deployments.entity.PdEgressDeclaration;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Reads a release's {@code .config/qits/egress.yml} (epic qits-2523) and keeps what it says, per
 * (application, version), in {@code pd_egress_declaration}. {@link EgressGrantTables} turns the
 * declarations of the {@code ACTIVE} deployments into the table pushed to qits-egress.
 *
 * <p><b>Read where the release is read, at the same tag</b>: {@code DeployService} calls {@link
 * #record} for the MAIN application of a release, after {@code deployments.yml} answered and before
 * anything of the release is queued. A variant is an application of its own deployed from the same
 * repository, and the file is the main application's — a variant carries no routes.
 *
 * <p><b>Nothing here can fail a deployment.</b> No file (the git host's 404) is no routes. A file
 * {@link EgressDeclarationParser} refuses is stored REFUSED with the reason: that version carries no
 * routes and the table names it under {@code refused}. A read that never answered is stored refused
 * the same way — unless this version was already read, whose answer is then kept, since a tag never
 * changes. Every exception, the store's own included, stops here with a log line.
 */
@ApplicationScoped
public class EgressDeclarations {

  private static final Logger LOG = Logger.getLogger(EgressDeclarations.class);

  /** The routes column's JSON: a plain mapper, so the bytes do not follow application config. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final TypeReference<List<EgressDeclarationParser.Route>> ROUTES =
      new TypeReference<>() {};

  @Inject SpecSource specs;

  /**
   * Read, parse and keep what {@code applicationName@version} declares. Never throws.
   *
   * @param repository the address the release's files are read at
   */
  public void record(RepositoryRef repository, String applicationName, String version) {
    try {
      String source = SpecSource.EGRESS_PATH + " of " + applicationName + "@" + version;
      List<EgressDeclarationParser.Route> routes = null;
      String refused = null;
      try {
        Optional<String> text =
            specs.readText(repository, SpecSource.tagRev(version), SpecSource.EGRESS_PATH);
        routes =
            text.isEmpty() ? List.of() : EgressDeclarationParser.parse(text.get(), source);
      } catch (RuntimeException e) {
        if (SpecException.isRetryable(e) && exists(applicationName, version)) {
          LOG.warnf(
              "Could not read %s again; keeping what that version declared when it was first read:"
                  + " %s",
              source, e.getMessage());
          return;
        }
        refused = e.getMessage() == null ? e.toString() : e.getMessage();
        LOG.warnf("%s grants no egress routes — the deployment goes ahead without them: %s", source, refused);
      }
      store(applicationName, version, routes, refused);
      if (routes != null && !routes.isEmpty()) {
        LOG.infof(
            "%s grants %d egress route(s): %s",
            source,
            routes.size(),
            String.join(", ", routes.stream().map(EgressDeclarationParser.Route::name).toList()));
      }
    } catch (RuntimeException e) {
      LOG.errorf(
          e,
          "Could not record the egress declaration of %s@%s; it carries no routes",
          applicationName,
          version);
    }
  }

  /** The stored routes of one declaration; an unreadable column reads as none. */
  public static List<EgressDeclarationParser.Route> routesOf(PdEgressDeclaration declaration) {
    if (declaration.routes == null || declaration.routes.isBlank()) {
      return List.of();
    }
    try {
      return JSON.readValue(declaration.routes, ROUTES);
    } catch (JsonProcessingException e) {
      LOG.warnf("The stored egress routes of %s are unreadable: %s", declaration.id, e.getMessage());
      return List.of();
    }
  }

  private boolean exists(String applicationName, String version) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                PdEgressDeclaration.findById(PdEgressDeclaration.idOf(applicationName, version))
                    != null);
  }

  private void store(
      String applicationName,
      String version,
      List<EgressDeclarationParser.Route> routes,
      String refused) {
    String json;
    try {
      json = routes == null ? null : JSON.writeValueAsString(routes);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not write the egress routes", e);
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              String id = PdEgressDeclaration.idOf(applicationName, version);
              PdEgressDeclaration row = PdEgressDeclaration.findById(id);
              boolean fresh = row == null;
              if (fresh) {
                row = new PdEgressDeclaration();
                row.id = id;
                row.applicationName = applicationName;
                row.version = version;
              }
              row.routes = json;
              row.refused = refused;
              row.readAt = Instant.now();
              if (fresh) {
                row.persist();
              }
            });
  }
}
