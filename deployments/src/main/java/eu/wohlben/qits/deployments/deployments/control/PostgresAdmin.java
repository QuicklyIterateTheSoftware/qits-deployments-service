package eu.wohlben.qits.deployments.deployments.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The administrator's view of both postgres servers, through {@link DatabaseContainers} (qits-2175):
 * which network each is reached on, which image a container talking to it runs, and the one-shot that
 * carries the admin credential to it.
 *
 * <p><b>The networks are the swarm driver's own keys</b> — {@code qits.deployments.swarm.flat-network}
 * for {@link DatabaseServer#PUBLIC}, {@code qits.deployments.swarm.private-network} for {@link
 * DatabaseServer#PRIVATE} — so a container is put on exactly the network the server it talks to was
 * put on, and on nothing else. Never literals: the names are deployment config.
 *
 * <p><b>The image is the server's own.</b> A container that dumps, restores or provisions runs the
 * image the live postgres service runs ({@link DeploymentDriver#runningImage}), so its {@code
 * pg_dump} is never older than the server it reads and nothing is pulled. No server running is a
 * refusal naming it — there is nothing to talk to.
 *
 * <p><b>One admin credential serves both servers</b>: the private variant is initialised with the
 * same {@code qits.deployments.postgres.admin-password}. It reaches a container as {@code PGPASSWORD},
 * a {@link DatabaseContainers.OneShot#secrets secret}, and nowhere else.
 */
@ApplicationScoped
public class PostgresAdmin {

  @Inject DatabaseContainers containers;
  @Inject DeploymentDriver driver;

  @ConfigProperty(name = "qits.deployments.swarm.flat-network")
  String flatNetwork;

  @ConfigProperty(name = "qits.deployments.swarm.private-network")
  String privateNetwork;

  @ConfigProperty(name = "qits.deployments.postgres.admin-username")
  String adminUsername;

  /** See {@code ResourceProvisioning#adminPassword}: no default, demanded only when used. */
  @ConfigProperty(name = "qits.deployments.postgres.admin-password")
  Optional<String> adminPassword;

  /** The ONE network a container talking to {@code server} joins. */
  public String network(DatabaseServer server) {
    return server == DatabaseServer.PRIVATE ? privateNetwork : flatNetwork;
  }

  /** The admin role's name — not a secret, and the same on both servers. */
  public String adminUsername() {
    return adminUsername;
  }

  /**
   * The image the server's live service runs, read off the orchestrator.
   *
   * @throws ResourceException naming the service when nothing runs under its name
   */
  public String image(DatabaseServer server, String environmentName) {
    String service = server.host(environmentName);
    return driver
        .runningImage(service)
        .map(DeploymentDriver.RunningImage::imageRef)
        .filter(image -> !image.isBlank())
        .orElseThrow(
            () ->
                new ResourceException(
                    "no service "
                        + service
                        + " is running, so there is no "
                        + server.wire()
                        + " postgres to reach"));
  }

  /**
   * A one-shot against {@code server}: on its network, as the admin, with {@code PGHOST} its address
   * and {@code PGPASSWORD} a secret, running {@code script} in {@code image}.
   */
  public DatabaseContainers.OneShot shot(
      String purpose,
      DatabaseServer server,
      String environmentName,
      String image,
      String volume,
      Map<String, String> secrets,
      String script) {
    Map<String, String> allSecrets = new HashMap<>(secrets == null ? Map.of() : secrets);
    allSecrets.put("PGPASSWORD", requireAdminPassword());
    return new DatabaseContainers.OneShot(
        purpose,
        image,
        network(server),
        volume,
        Map.of(
            "PGHOST",
            server.host(environmentName),
            "PGPORT",
            String.valueOf(ResourceProvisioning.POSTGRES_PORT),
            "PGUSER",
            adminUsername),
        allSecrets,
        script);
  }

  String requireAdminPassword() {
    return adminPassword
        .map(String::strip)
        .filter(password -> !password.isEmpty())
        .orElseThrow(
            () ->
                new ResourceException(
                    "a database statement is due and nothing configured"
                        + " qits.deployments.postgres.admin-password"));
  }
}
