package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The {@link ResourceProvisioner} for the PRIVATE postgres (qits-2175): {@code PgResourceProvisioner}'s
 * idempotent statements, run by {@code psql} in a one-shot container on the private network only.
 *
 * <p><b>Why a container and not a socket.</b> This deployer is public — on the flat network alone —
 * and {@code qits-oci-postgresql-private} is on qits-private alone, so no JDBC url from this process
 * can reach it. What can is a container started on that network through the docker socket this
 * process already holds ({@link DatabaseContainers}); the statements are the same, so is the matrix.
 *
 * <p><b>The same four drift arms, decided the same way.</b> The role is created with the stored
 * password when one is on record (a reset server: running containers keep working) and with the
 * fresh one otherwise; a role that exists is left alone when the registry knows its password and
 * rotated to the fresh one when it does not (a reset registry). So the password in effect is always
 * {@code stored ?: fresh}, and the script is told whether to rotate rather than asked afterwards.
 *
 * <p>{@code @Typed} to its own class: {@link ResourceProvisioner} keeps exactly one bean of its type,
 * the public server's — which is what the suite's {@code FakeResourceProvisioner} replaces — and this
 * one is asked for by name, by {@link ResourceProvisioning}, for a private claim.
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

  @Override
  public Result ensure(Request request) {
    String database;
    String role;
    try {
      // Re-validated at the last moment, the JDBC provisioner's stance: DDL has no bind variables.
      database = PdIdentifiers.requireDatabaseName(request.databaseName());
      role = PdIdentifiers.requireDatabaseName(request.roleName());
    } catch (RuntimeException e) {
      return new Result(false, null, "refused an identifier: " + e.getMessage());
    }
    // The host IS the private server's swarm service name, so its own image is what runs psql.
    String image =
        driver.runningImage(request.host()).map(DeploymentDriver.RunningImage::imageRef).orElse(null);
    if (image == null || image.isBlank()) {
      return new Result(
          false,
          null,
          "the private postgres is not deployed (no service "
              + request.host()
              + " is running), so there is nowhere to provision a database for a private"
              + " application");
    }
    String inEffect =
        request.storedPassword() != null ? request.storedPassword() : request.freshPassword();
    DatabaseContainers.Outcome outcome =
        containers.run(
            new DatabaseContainers.OneShot(
                "provision",
                image,
                admin.network(DatabaseServer.PRIVATE),
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
          "psql on the private server "
              + (outcome.timedOut() ? "timed out" : "exited " + outcome.exitCode())
              + ": "
              + tail(outcome.output()));
    }
    LOG.infof("Ensured role and database %s on the private postgres %s", database, request.host());
    return new Result(true, inEffect, null);
  }

  private static String tail(String output) {
    String text = output == null ? "" : output.strip();
    return text.length() <= 500 ? text : "…" + text.substring(text.length() - 500);
  }
}
