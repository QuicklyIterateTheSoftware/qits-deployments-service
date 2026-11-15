package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The {@link ResourceProvisioner} for a postgres server this process is not on the network of
 * (qits-2175, generalised by qits-2275): {@code PgResourceProvisioner}'s idempotent statements, run
 * by {@code psql} in a one-shot container on THAT server's network only.
 *
 * <p><b>Why a container and not a socket.</b> A service sits on one network, never both (no service
 * bridges the flat network and qits-private), and each postgres server sits on one of them — so
 * whichever server is on the other network than the deployer's, no JDBC url from this process can
 * reach it. What can is a container started on that server's network through the docker socket this
 * process already holds ({@link DatabaseContainers}); the statements are the same, so is the matrix.
 * The PRIVATE server always goes this way; the PUBLIC one does whenever the deployer is not on the
 * flat network itself — which is every deployment since qits-deployments went private
 * ({@link ResourceProvisioning} decides, from the deployer's own live exposure).
 *
 * <p><b>The same four drift arms, decided the same way.</b> The role is created with the stored
 * password when one is on record (a reset server: running containers keep working) and with the
 * fresh one otherwise; a role that exists is left alone when the registry knows its password and
 * rotated to the fresh one when it does not (a reset registry). So the password in effect is always
 * {@code stored ?: fresh}, and the script is told whether to rotate rather than asked afterwards.
 *
 * <p>{@code @Typed} to its own class: {@link ResourceProvisioner} keeps exactly one bean of its type,
 * the public server's — which is what the suite's {@code FakeResourceProvisioner} replaces — and this
 * one is asked for by name, by {@link ResourceProvisioning}, for every claim it cannot reach itself.
 */
@ApplicationScoped
@Typed(ContainerResourceProvisioner.class)
public class ContainerResourceProvisioner implements ResourceProvisioner {

  private static final Logger LOG = Logger.getLogger(ContainerResourceProvisioner.class);

  /** A handful of statements; a minute is the patience of a server that is still starting. */
  static final Duration TIMEOUT = Duration.ofMinutes(2);

  @Inject DatabaseContainers containers;
  @Inject DeploymentDriver driver;
  @Inject PostgresAdmin admin;

  /** The PRIVATE server's provisioning — the form every caller before qits-2275 used. */
  @Override
  public Result ensure(Request request) {
    return ensureOn(DatabaseServer.PRIVATE, request);
  }

  /**
   * Provision on {@code server}: a one-shot on that server's network alone, in the image its live
   * service runs ({@code request.host()} IS that service's swarm name).
   */
  public Result ensureOn(DatabaseServer server, Request request) {
    String database;
    String role;
    try {
      // Re-validated at the last moment, the JDBC provisioner's stance: DDL has no bind variables.
      database = PdIdentifiers.requireDatabaseName(request.databaseName());
      role = PdIdentifiers.requireDatabaseName(request.roleName());
    } catch (RuntimeException e) {
      return new Result(false, null, "refused an identifier: " + e.getMessage());
    }
    // The host IS the server's swarm service name, so its own image is what runs psql.
    String image =
        driver.runningImage(request.host()).map(DeploymentDriver.RunningImage::imageRef).orElse(null);
    if (image == null || image.isBlank()) {
      return new Result(
          false,
          null,
          "the "
              + server.wire()
              + " postgres is not deployed (no service "
              + request.host()
              + " is running), so there is nowhere to provision the database");
    }
    String inEffect =
        request.storedPassword() != null ? request.storedPassword() : request.freshPassword();
    DatabaseContainers.Outcome outcome =
        containers.run(
            new DatabaseContainers.OneShot(
                "provision",
                image,
                admin.network(server),
                null,
                Map.of(
                    "PGHOST",
                    request.host(),
                    "PGPORT",
                    String.valueOf(request.port()),
                    "PGUSER",
                    request.adminUsername()),
                Map.of(
                    "PGPASSWORD",
                    request.adminPassword(),
                    DatabaseScripts.ROLE_PASSWORD,
                    inEffect),
                DatabaseScripts.provision(database, role, request.storedPassword() == null)),
            TIMEOUT);
    if (!outcome.ok()) {
      return new Result(
          false,
          null,
          "psql on the "
              + server.wire()
              + " server "
              + (outcome.timedOut() ? "timed out" : "exited " + outcome.exitCode())
              + ": "
              + tail(outcome.output()));
    }
    LOG.infof(
        "Ensured role and database %s on the %s postgres %s, from a one-shot on %s",
        database, server.wire(), request.host(), admin.network(server));
    return new Result(true, inEffect, null);
  }

  private static String tail(String output) {
    String text = output == null ? "" : output.strip();
    return text.length() <= 500 ? text : "…" + text.substring(text.length() - 500);
  }
}
