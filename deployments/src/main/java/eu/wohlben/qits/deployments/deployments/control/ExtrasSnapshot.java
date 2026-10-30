package eu.wohlben.qits.deployments.deployments.control;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Deployment config as it reads NOW, for one argv: the extras one source stated, over the boot
 * config with every extras key filtered out of it.
 *
 * <p><b>The boot Config is a snapshot and it goes stale.</b> Quarkus' config-dir convention reads
 * {@code <user.dir>/config/application.properties} exactly once, at boot (ordinal 260), so on a
 * deployment host the boot config carries whatever that file said the last time the process
 * started, extras keys included. Every deployment used to re-stamp that snapshot onto the service it
 * updated, reverting live fixes (2026-08-16); and a key deleted from the authoritative source came
 * straight back out of the stale boot layer (2026-08-17, and qits-1468, where {@code
 * QUARKUS_OIDC_AUTH_SERVER_URL} reached qits-ci with no file read anywhere in the path).
 *
 * <p><b>So the boot layer FILTERS.</b> Any property under {@link DeploymentDriver#EXTRAS_PREFIX} or
 * {@link DeploymentDriver#LEGACY_EXTRAS_PREFIX} is invisible in it — not listed, not valued — so an
 * extras key the source does not state is absent, full stop. Everything else falls through to the
 * boot config exactly as it answers. That is what makes the source SOLE rather than merely first.
 *
 * <p><b>One snapshot per argv build, and that is the invariant {@link ServiceExtras} rests on.</b>
 * The stated map is copied into the snapshot, so it answers the same thing however often it is
 * asked. A caller takes one and hands it to every reading of one deployment.
 */
public final class ExtrasSnapshot {

  /** Above every source the boot config carries — system properties are 400, the environment 300. */
  private static final int STATED_ORDINAL = 2000;

  private static final int BOOT_ORDINAL = 100;

  private ExtrasSnapshot() {}

  /**
   * {@code stated} — in the full prefixed spelling, {@code qits.deployments.extras.<app>.<key>} —
   * over {@code boot} with its extras keys filtered out. Layered rather than translated, so {@link
   * ServiceExtras} stays the single parser of that grammar.
   *
   * @param source what the snapshot names this layer in a config dump
   */
  public static Config over(Config boot, Map<String, String> stated, String source) {
    return new SmallRyeConfigBuilder()
        .withSources(
            new PropertiesConfigSource(Map.copyOf(stated), source, STATED_ORDINAL),
            new BootConfigSource(boot))
        .build();
  }

  /** The boot config as a source, with every extras key hidden — see the class javadoc. */
  private record BootConfigSource(Config boot) implements ConfigSource {

    @Override
    public Set<String> getPropertyNames() {
      Set<String> names = new HashSet<>();
      boot.getPropertyNames().forEach(names::add);
      names.removeIf(BootConfigSource::isExtras);
      return names;
    }

    @Override
    public String getValue(String name) {
      if (isExtras(name)) {
        // Neither spelling reaches through this layer: a key under either prefix is exactly what
        // the stated map above is the sole authority for.
        return null;
      }
      // An empty value is absent to the conversion, which is what the boot config already answered
      // for `env.FLAG=`; the name is still listed, and the reader states what an absence means.
      return boot.getOptionalValue(name, String.class).orElse(null);
    }

    private static boolean isExtras(String name) {
      return name.startsWith(DeploymentDriver.EXTRAS_PREFIX)
          || name.startsWith(DeploymentDriver.LEGACY_EXTRAS_PREFIX);
    }

    @Override
    public String getName() {
      return "boot config";
    }

    @Override
    public int getOrdinal() {
      return BOOT_ORDINAL;
    }
  }
}
