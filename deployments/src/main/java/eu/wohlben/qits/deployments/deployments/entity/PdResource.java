package eu.wohlben.qits.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One backing resource this component has provisioned for one application in one tier — a postgres
 * role and database on the platform's own instance, or a qits-idp service client — and the
 * credential it injects for it.
 *
 * <p><b>This row is the single authority for that credential.</b> Nothing else records it: no
 * generated file carries it, the bootstrap does not know it, and the application receives it only
 * as an environment variable at {@code docker run}. Which is what makes the registry's own database
 * credential-bearing, and what decides the two ways a mismatch is repaired — a row without a role
 * (the postgres volume was reset) is a self-heal that recreates the role with the stored password,
 * and a role without a row (this database was reset) is a reconcile that rotates it. Never a drop,
 * in either direction.
 *
 * <p><b>{@code environmentName} is a plain string with no FK, and null is the platform plane.</b>
 * The {@code pd_deployment} stance: a resource outlives the tier row that described it, because
 * nothing is ever dropped and the registry has to keep answering after a teardown. The uniqueness
 * over {@code (application, environment, resource)} is declared {@code nulls not distinct} in V1 —
 * without that, every platform-plane deployment would insert a row of its own.
 *
 * <p>{@code databaseName} and {@code roleName} are one identity for a postgres row: the role IS the
 * database name, one login per database. They are two columns so that a resource type could
 * separate them without a migration — which is exactly what {@link #clientId} is: an idp-client row
 * leaves both of them null and carries this instead, qits-idp's own key for the client. A row
 * carries only the columns its type has a fact for; the others stay null rather than holding a
 * value that means nothing.
 *
 * <p><b>{@code @Uncaused} by decision, and the reason is what this row is.</b> It is not a record
 * of something that happened — it is the converging registry entry for a database that exists, read
 * and rewritten by every later deployment. The causation column is insert-only on purpose, so it
 * would pin this row forever to whichever deployment happened to be the first one, saying nothing
 * about the credential the row holds today. Its two writers agree: {@code ResourceProvisioning}
 * runs on {@code pd-deploy-worker}, where no scope stands and the deployment that owns the pass
 * already records the cause; and {@code BootResourceRegistration} writes at startup from the
 * environment variables a bootstrap set, with no event anywhere behind it.
 */
@Entity
@Table(name = "pd_resource")
@Uncaused
public class PdResource extends PanacheEntityBase {

  @Id public String id;

  /** The application the resource belongs to — the repository id, as the intake announced it. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /** The tier it was provisioned for, or null for the platform plane. */
  @Column(name = "environment_name", length = 64)
  public String environmentName;

  /** What the repository called it in {@code resources:} — the {@code <NAME>} of the env triple. */
  @Column(name = "resource_name", nullable = false, length = 64)
  public String resourceName;

  /** {@code postgresql} or {@code idp-client}. */
  @Column(name = "resource_type", nullable = false, length = 32)
  public String resourceType;

  /** Null for an {@code idp-client} row — an idp client has no database. */
  @Column(name = "database_name", length = 64)
  public String databaseName;

  /** Null for an {@code idp-client} row — an idp client has no role. */
  @Column(name = "role_name", length = 64)
  public String roleName;

  /**
   * qits-idp's own key for the client — the wire alias this component derives ({@code
   * PdNetworks.alias}). Null for a postgres row, which has nothing to key an idp client by.
   */
  @Column(name = "client_id", length = 128)
  public String clientId;

  /**
   * Which postgres the database is on — {@code public} or {@code private}, {@code
   * DatabaseServer.wire()} (V20, qits-2175). Every row before the column is {@code public}, the one
   * server there was. A new claim takes the rule's server and an existing one changes only through a
   * {@code pd_database_move}. Meaningless for an idp-client row, which keeps the default.
   */
  @Column(name = "server", nullable = false, length = 16)
  public String server = "public";

  /**
   * Generated here for a postgres row, issued by qits-idp for an idp-client row — either way stored
   * only here, and never logged. See the class javadoc.
   */
  @Column(name = "password", nullable = false, length = 128)
  public String password;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /**
   * When the provisioner last confirmed the role and the database exist. Null on a row written by
   * boot self-registration, which records what a bootstrap already created rather than doing it.
   */
  @Column(name = "last_provisioned_at")
  public Instant lastProvisionedAt;
}
