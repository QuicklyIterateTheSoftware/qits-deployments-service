package eu.wohlben.qits.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One environment variable the deployer holds for one application in one tier — a secret or an
 * installation fact that used to live in qits-configuration's extras (epic qits-765).
 *
 * <p><b>Every deployment reads it</b> (qits-1393): {@code SettingsExtrasSource} is the deployer's
 * one extras source, so this row is the container's {@code KEY=value} from the next deployment of
 * its place on, and its deletion is an {@code --env-rm} on that deployment.
 *
 * <p>{@code settingKey} is the env var name itself, validated at the door. {@code secret} decides how the
 * value is SHOWN — never, on any read — not how it is stored: this database already holds every
 * provisioned credential ({@link PdResource}).
 *
 * <p><b>{@code @Uncaused} by decision</b>, {@link PdResource}'s reason: a setting is a converging
 * entry an operator rewrites in place, not a record of an occurrence, so an insert-only causation
 * column would pin it to whichever write happened first. {@link #updatedBy} and {@link #updatedAt}
 * say who last stated it, which is the question a reader of a setting actually has.
 */
@Entity
@Table(
    name = "pd_setting",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_pd_setting",
            columnNames = {"application_name", "environment_name", "setting_key"}))
@Uncaused
public class PdSetting extends PanacheEntityBase {

  @Id public String id;

  /** The deployed application name, as {@code pd_deployment.application_name} spells it. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /** The tier's name. Never null: there is no plane left for a null to mean. */
  @Column(name = "environment_name", nullable = false, length = 64)
  public String environmentName;

  /** The environment variable's name — {@code [A-Z_][A-Z0-9_]*}. */
  @Column(name = "setting_key", nullable = false, length = 255)
  public String settingKey;

  /** The value, verbatim. Never answered on any door when {@link #secret} is set. */
  @Column(name = "value", nullable = false, columnDefinition = "text")
  public String value;

  /** Whether every read masks the value. */
  @Column(name = "secret", nullable = false)
  public boolean secret;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  /** The principal that last wrote the row, or null when the caller had none. */
  @Column(name = "updated_by", columnDefinition = "text")
  public String updatedBy;
}
