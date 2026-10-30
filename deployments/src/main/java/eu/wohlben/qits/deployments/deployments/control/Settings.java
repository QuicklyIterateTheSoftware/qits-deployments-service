package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.dto.PdSettingDto;
import eu.wohlben.qits.deployments.deployments.entity.PdSetting;
import eu.wohlben.qits.deployments.deployments.persistence.PdSettingRepository;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * The settings store (qits-1390): one environment variable per (application, tier, key), stated by
 * an operator or a machine, read back MASKED.
 *
 * <p><b>Every deployment takes its environment from it</b> (qits-1393): {@link
 * SettingsExtrasSource} renders the place's rows as the extras {@code env} family on every argv
 * build, so a written row reaches the service on its next deployment and a deleted one is removed
 * from it. {@link SettingsPreview} shows what that next deployment would change.
 *
 * <p><b>The mask is applied here, once</b>, so no door can forget it: every {@link PdSettingDto}
 * this class hands out carries a null value when the row is secret — the answer to the write that
 * stored it included.
 *
 * <p>Transactions are programmatic, as everywhere in {@code control}: a {@code this.}-call never
 * crosses an interceptor, and a lost bracket fails quietly.
 */
@ApplicationScoped
public class Settings {

  private static final Logger LOG = Logger.getLogger(Settings.class);

  /** An environment variable's name, as a shell and docker both accept it. */
  static final Pattern KEY = Pattern.compile("[A-Z_][A-Z0-9_]*");

  /** {@code pd_setting.setting_key}'s width. */
  static final int KEY_MAX_LENGTH = 255;

  @Inject PdSettingRepository settings;

  /** Every row matching the optional filters, masked. */
  public List<PdSettingDto> list(String application, String environment) {
    String app = optionalName(application, "application");
    String env = optionalName(environment, "environment");
    return QuarkusTransaction.joiningExisting()
        .call(() -> settings.listFiltered(app, env).stream().map(Settings::toDto).toList());
  }

  /** Insert or replace one row; answers it, masked. */
  public PdSettingDto put(
      String application,
      String environment,
      String key,
      String value,
      boolean secret,
      String actor) {
    String app = PdIdentifiers.requireName(application, "application");
    String env = PdIdentifiers.requireName(environment, "environment");
    String settingKey = requireKey(key);
    if (value == null) {
      throw new BadRequestException("value is required");
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdSetting row =
                  settings
                      .findOne(app, env, settingKey)
                      .orElseGet(
                          () -> {
                            PdSetting fresh = new PdSetting();
                            fresh.id = UUID.randomUUID().toString();
                            fresh.applicationName = app;
                            fresh.environmentName = env;
                            fresh.settingKey = settingKey;
                            return fresh;
                          });
              row.value = value;
              row.secret = secret;
              row.updatedAt = Instant.now();
              row.updatedBy = actor;
              if (!settings.isPersistent(row)) {
                settings.persist(row);
              }
              return toDto(row);
            });
  }

  /** Remove one row; 404 when there is none. */
  public void delete(String application, String environment, String key) {
    String app = PdIdentifiers.requireName(application, "application");
    String env = PdIdentifiers.requireName(environment, "environment");
    String settingKey = requireKey(key);
    boolean removed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    settings
                        .findOne(app, env, settingKey)
                        .map(
                            row -> {
                              settings.delete(row);
                              return true;
                            })
                        .orElse(false));
    if (!removed) {
      throw new NotFoundException(
          "no setting " + settingKey + " for " + app + " in " + env);
    }
  }

  /**
   * Every row of one place, UNMASKED — for {@link SettingsExtrasSource} and {@link SettingsPreview},
   * never for a door.
   */
  List<PdSetting> rowsOf(String application, String environment) {
    return QuarkusTransaction.joiningExisting()
        .call(() -> settings.listFiltered(application, environment));
  }

  /**
   * The rename's settings half: copy every setting {@code renamedFrom} holds in {@code
   * environmentName} onto {@code applicationName}, skipping any key the successor already has.
   * {@code ResourceProvisioning.ensureAll}'s {@code renamed_from} transfer is the pattern this
   * follows — a predecessor's state is carried over rather than abandoned — applied to a table that
   * holds no credential of its own to protect, only values an operator or a machine already wrote.
   *
   * <p><b>Callable inside an existing transaction</b>, because the cutover that calls it also moves
   * the resource claim (see {@code ResourceProvisioning}) and the two belong in one bracket: either
   * both land or neither does, so a rename never leaves the successor half-provisioned.
   *
   * <p><b>A second run copies nothing.</b> "The successor does not have it yet" is read fresh, as
   * part of the same query, so a key the first run copied is no longer a candidate the second time —
   * there is no separate "already ran" flag to keep in step with the table.
   *
   * <p><b>{@code value} and {@code secret} travel unchanged.</b> This is a copy, not a review: a
   * secret stays secret, a plain value stays plain, and nothing here reads what either one says —
   * see the class javadoc's rule about never logging a value.
   *
   * <p>{@code renamedFrom} equal to {@code applicationName}, or null, is a no-op: an application is
   * never its own predecessor, and a null predecessor is every file that does not use the key.
   *
   * @return the keys copied, sorted — a count a caller only needs the size of
   */
  public List<String> copyForRename(
      String renamedFrom, String applicationName, String environmentName) {
    if (renamedFrom == null || renamedFrom.equals(applicationName)) {
      return List.of();
    }
    String env = PdIdentifiers.requireName(environmentName, "environment");
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              List<PdSetting> predecessorRows = settings.listFiltered(renamedFrom, env);
              if (predecessorRows.isEmpty()) {
                return List.of();
              }
              List<String> existingKeys =
                  settings.listFiltered(applicationName, env).stream()
                      .map(row -> row.settingKey)
                      .toList();
              List<String> copied = new ArrayList<>();
              Instant now = Instant.now();
              for (PdSetting predecessor : predecessorRows) {
                if (existingKeys.contains(predecessor.settingKey)) {
                  continue;
                }
                PdSetting row = new PdSetting();
                row.id = UUID.randomUUID().toString();
                row.applicationName = applicationName;
                row.environmentName = env;
                row.settingKey = predecessor.settingKey;
                row.value = predecessor.value;
                row.secret = predecessor.secret;
                row.updatedAt = now;
                row.updatedBy = "rename from " + renamedFrom;
                settings.persist(row);
                copied.add(predecessor.settingKey);
              }
              copied.sort(String::compareTo);
              LOG.infof(
                  "Copied %d setting(s) from %s to %s (%s) for a rename: %s",
                  copied.size(), renamedFrom, applicationName, env, copied);
              return List.copyOf(copied);
            });
  }

  /**
   * The retirement's settings half: delete every setting {@code applicationName} holds in {@code
   * environmentName}. The companion to {@link #copyForRename} rather than its mirror — a rename
   * copies the predecessor's settings onto the successor and this is what later clears the
   * predecessor's own rows once it is decommissioned, leaving nothing behind for an abandoned name
   * to still answer for.
   *
   * <p>Idempotent: a second call finds no rows and deletes nothing.
   *
   * @return the number of rows deleted
   */
  public int retire(String applicationName, String environmentName) {
    String app = PdIdentifiers.requireName(applicationName, "application");
    String env = PdIdentifiers.requireName(environmentName, "environment");
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              List<PdSetting> rows = settings.listFiltered(app, env);
              for (PdSetting row : rows) {
                settings.delete(row);
              }
              LOG.infof("Retired %d setting(s) of %s (%s)", rows.size(), app, env);
              return rows.size();
            });
  }

  /**
   * Which settings in {@code environmentName} — any application — hold exactly {@code value}, as
   * {@code <application>:<key>}, sorted. NON-SECRET rows only: a secret never names a client id, and
   * a secret value is never read for a comparison that ends in a log line. The rename retirement's
   * guard: a predecessor's idp client that something still names is not deleted. The answer carries
   * names and keys, never a value.
   */
  public List<String> referencesTo(String environmentName, String value) {
    if (value == null || value.isEmpty()) {
      return List.of();
    }
    String env = PdIdentifiers.requireName(environmentName, "environment");
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                settings.listFiltered(null, env).stream()
                    .filter(row -> !row.secret && value.equals(row.value))
                    .map(row -> row.applicationName + ":" + row.settingKey)
                    .sorted()
                    .toList());
  }

  /** The key, or a 400 naming the rule. */
  static String requireKey(String key) {
    if (key == null || key.length() > KEY_MAX_LENGTH || !KEY.matcher(key).matches()) {
      throw new BadRequestException(
          "'"
              + key
              + "' is not an environment variable name: it must match [A-Z_][A-Z0-9_]* and be at"
              + " most "
              + KEY_MAX_LENGTH
              + " characters");
    }
    return key;
  }

  private static String optionalName(String name, String what) {
    return name == null || name.isBlank() ? null : PdIdentifiers.requireName(name, what);
  }

  private static PdSettingDto toDto(PdSetting row) {
    return new PdSettingDto(
        row.applicationName,
        row.environmentName,
        row.settingKey,
        row.secret ? null : row.value,
        row.secret,
        row.updatedAt,
        row.updatedBy);
  }
}
