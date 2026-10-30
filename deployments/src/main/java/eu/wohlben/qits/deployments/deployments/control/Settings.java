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
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The settings store (qits-1390): one environment variable per (application, tier, key), stated by
 * an operator or a machine, read back MASKED.
 *
 * <p><b>Nothing deploys from it yet.</b> A deployment still takes its environment from {@link
 * DeploymentExtrasSource} alone; {@link SettingsPreview} is what shows what reading this store
 * would change.
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

  /** Every row of one place, UNMASKED — for {@link SettingsPreview}, never for a door. */
  List<PdSetting> rowsOf(String application, String environment) {
    return QuarkusTransaction.joiningExisting()
        .call(() -> settings.listFiltered(application, environment));
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
