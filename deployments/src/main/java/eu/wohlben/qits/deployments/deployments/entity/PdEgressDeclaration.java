package eu.wohlben.qits.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnTransformer;

/**
 * What one released version of an application declares in {@code .config/qits/egress.yml} (epic
 * qits-2523) — see {@code V27__egress_declaration.sql} and {@code EgressDeclarations}. The grant
 * table joins it to each application's {@code ACTIVE} deployment on (application, version).
 *
 * <p><b>{@code @Uncaused} by decision</b>, {@link PdEdgeRouteTable}'s reason: a re-read of the same
 * version replaces the row in place, so a cause column would record whichever read wrote it last.
 */
@Uncaused
@Entity
@Table(name = "pd_egress_declaration")
public class PdEgressDeclaration extends PanacheEntityBase {

  /** {@code <application>@<version>}: the natural key, spelled once. */
  @Id public String id;

  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  @Column(nullable = false, length = 64)
  public String version;

  /** The parsed routes as JSON text, {@code []} for none; null exactly when {@link #refused}. */
  @Column(columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  public String routes;

  /** Why the file was refused, or null when it parsed. */
  @Column(columnDefinition = "text")
  public String refused;

  @Column(name = "read_at", nullable = false)
  public Instant readAt;

  /** The id of an application's version. */
  public static String idOf(String applicationName, String version) {
    return applicationName + "@" + version;
  }
}
