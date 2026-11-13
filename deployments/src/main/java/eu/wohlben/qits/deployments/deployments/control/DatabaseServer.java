package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import java.util.Locale;

/**
 * Which of the platform's two postgres servers a claim lives on (qits-2175) — {@code pd_resource.server}
 * and both sides of a {@code pd_database_move}.
 *
 * <p><b>Two servers, one deployer, and the address is still derived.</b> {@link #PUBLIC} is the shared
 * {@code qits-oci-postgresql} on the flat network; {@link #PRIVATE} is its variant {@code
 * qits-oci-postgresql-private}, on qits-private only. Each answers at {@code PdNetworks.alias(<tier>,
 * <application>)}, the wire alias every service has, so there is still no postgres-host config key and
 * there should not be one. One admin credential serves both: the private variant is initialised with
 * the same {@code qits.deployments.postgres.admin-password}.
 *
 * <p><b>The rule is {@link #forExposure}, and it is the only place it is spelled.</b> An application
 * declared {@code exposure: private} keeps its data on the private server; {@code public} and {@code
 * both} keep it on the public one. It decides a NEW claim's server, and it is the target a deployment
 * compares an existing claim against — a difference is a move, never a quiet re-provision.
 */
public enum DatabaseServer {
  PUBLIC(ResourceProvisioning.POSTGRES_APPLICATION),
  PRIVATE(ResourceProvisioning.PRIVATE_POSTGRES_APPLICATION);

  private final String application;

  DatabaseServer(String application) {
    this.application = application;
  }

  /** The rule: {@code private} → {@link #PRIVATE}; {@code public}, {@code both} and none → {@link #PUBLIC}. */
  public static DatabaseServer forExposure(DeploymentDriver.Exposure exposure) {
    return exposure == DeploymentDriver.Exposure.PRIVATE ? PRIVATE : PUBLIC;
  }

  /**
   * The stored spelling back to a server. Null and blank are {@link #PUBLIC} — what every row written
   * before the column existed means (V20's default). Anything else unknown is refused rather than
   * routed: a row the code cannot address must not be handed out as an address.
   */
  public static DatabaseServer parse(String stored) {
    if (stored == null || stored.isBlank()) {
      return PUBLIC;
    }
    return switch (stored.strip().toLowerCase(Locale.ROOT)) {
      case "public" -> PUBLIC;
      case "private" -> PRIVATE;
      default ->
          throw new IllegalArgumentException(
              "`" + stored + "` is not a database server — it is public or private");
    };
  }

  /** {@code public} or {@code private} — the column's and the wire's spelling. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The application this server deploys as — the main {@code qits-oci-postgresql} or its variant. */
  public String application() {
    return application;
  }

  /** The host a container dials it at in that tier: {@code <tier>-<application>}. */
  public String host(String environmentName) {
    return PdNetworks.alias(environmentName, application);
  }
}
