package eu.wohlben.qits.deployments.deployments.control;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Writes the docker CLI's {@code config.json} from the idp client this container was handed as a
 * resource, so the pulls this component performs present ITS OWN identity.
 *
 * <p><b>Why it exists.</b> The registry credential used to be env wiring only: the bootstrap writes
 * a {@code config.json} onto the config volume ({@code /work/config}) naming the deployer's
 * bootstrap client, and {@code DOCKER_CONFIG} points the CLI at it. Once this component declares
 * {@code idp:client} for itself it is started with {@code QITS_RESOURCE_IDP_CLIENT_ID / _SECRET}
 * for its DERIVED client, and a volume file naming the bootstrap client would keep presenting a
 * credential the platform is moving away from — or one that no longer works. The file is therefore
 * derived from the pair the container actually holds, at every boot, the {@link
 * BootResourceRegistration} stance: the environment is the truth.
 *
 * <p><b>Nothing happens without the pair.</b> With either variable unset — today's deployer, which
 * declares no idp:client of its own, and every developer's jar — no file is written, {@link
 * PdProcess#dockerConfig} finds none, and every docker child keeps the {@code DOCKER_CONFIG} the
 * container was started with. The volume file is never touched, read or deleted.
 *
 * <p><b>The format is the bootstrap's {@code dockerConfigJson}</b> (qits-bootstrap-cli {@code
 * SeedPhases}): one {@code auths} entry per host, each {@code {"auth": base64("<id>:<secret>")}} —
 * the bytes {@code docker login} would store. <b>The hosts</b> are the registry host every image
 * reference is qualified with ({@code qits.artifacts.registry-host}, the value {@code DeployService}
 * builds image refs from) and the mirror vhost, which is the same edge's sibling name — see {@link
 * #hosts}. The CLI matches an entry by host and nothing else, so a second line is no widening.
 *
 * <p><b>The secret is never logged</b>, nor the base64 that carries it. The client id is not a
 * secret and is named, so an operator can tell whose credential the pulls present.
 *
 * <p><b>Warn-only and skipped under TEST</b>, the {@link BootResourceRegistration} shape: a
 * deployer that cannot write a credential file must still start (the mounted file still works for
 * as long as the bootstrap client does), and the suite drives {@link #writeOwnCredential} directly.
 */
@ApplicationScoped
public class DockerCredentialFile {

  private static final Logger LOG = Logger.getLogger(DockerCredentialFile.class);

  /** What the docker CLI opens inside the directory {@code DOCKER_CONFIG} names. */
  public static final String FILE_NAME = "config.json";

  static final String CLIENT_ID_VARIABLE = "QITS_RESOURCE_IDP_CLIENT_ID";

  static final String CLIENT_SECRET_VARIABLE = "QITS_RESOURCE_IDP_CLIENT_SECRET";

  private static final String REGISTRY_LABEL = "registry.";

  private static final String MIRROR_LABEL = "mirror.";

  private static final Set<PosixFilePermission> DIRECTORY_MODE =
      PosixFilePermissions.fromString("rwx------");

  private static final Set<PosixFilePermission> FILE_MODE =
      PosixFilePermissions.fromString("rw-------");

  /** The directory the file is written into, and the value docker children get as DOCKER_CONFIG. */
  @ConfigProperty(name = "qits.deployments.docker-config-dir")
  String directory;

  @ConfigProperty(name = "qits.artifacts.registry-host")
  String registryHost;

  /**
   * Early — before the deploy worker's own startup sweep may shell out — though nothing depends on
   * winning that race: {@link PdProcess} decides per child, so a docker call made before the file
   * exists simply keeps the mounted credential.
   */
  void onStart(@Observes @Priority(1) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    writeOwnCredential(
        value(CLIENT_ID_VARIABLE), value(CLIENT_SECRET_VARIABLE), Path.of(directory), registryHost);
  }

  /**
   * The boot's decision and its log lines, apart from the launch-mode check so the suite can hold
   * them — including that no line it writes carries the secret. True when a file was written.
   */
  static boolean writeOwnCredential(
      Optional<String> clientId, Optional<String> secret, Path directory, String registryHost) {
    if (clientId.isEmpty() || secret.isEmpty()) {
      LOG.debugf(
          "No docker credential of its own: this instance was started without %s/%s, so docker"
              + " children keep the DOCKER_CONFIG the container was started with",
          CLIENT_ID_VARIABLE, CLIENT_SECRET_VARIABLE);
      return false;
    }
    List<String> hosts = hosts(registryHost);
    try {
      write(directory, hosts, clientId.get(), secret.get());
      LOG.infof(
          "Wrote the docker credential of the idp client %s for %s into %s; docker children are"
              + " started with DOCKER_CONFIG=%s",
          clientId.get(), String.join(" and ", hosts), directory, directory);
      return true;
    } catch (IOException | RuntimeException e) {
      // Never the exception itself: it names a path at most today, but a stack trace is not a
      // place to bet a credential on. Its type and message are what an operator needs.
      LOG.warnf(
          "Could not write the docker credential of the idp client %s into %s (%s: %s); docker"
              + " children keep the DOCKER_CONFIG the container was started with",
          clientId.get(),
          directory,
          e.getClass().getSimpleName(),
          e.getMessage());
      return false;
    }
  }

  /**
   * The registry host, then the mirror vhost derived from it. Both are names on the same edge and
   * differ only in their first label — the bootstrap's own {@code registryVhost()} / {@code
   * mirrorVhost()} are {@code registry.<env>.localhost:<port>} / {@code mirror.<env>.localhost:<port>}
   * — so the mirror is read off the one stated fact rather than configured as a second one that
   * could drift from it. A registry host that does not start with {@code registry.} (an operator
   * pointing at something else entirely) gets its own entry alone, because there is then nothing to
   * derive a sibling from.
   */
  static List<String> hosts(String registryHost) {
    List<String> hosts = new ArrayList<>();
    String registry = registryHost == null ? "" : registryHost.strip();
    if (registry.isEmpty()) {
      return hosts;
    }
    hosts.add(registry);
    if (registry.startsWith(REGISTRY_LABEL) && registry.length() > REGISTRY_LABEL.length()) {
      hosts.add(MIRROR_LABEL + registry.substring(REGISTRY_LABEL.length()));
    }
    return hosts;
  }

  /**
   * The CLI's own {@code config.json} — byte for byte the bootstrap's {@code dockerConfigJson}
   * shape, including the trailing newline. Hand-written for the reason given there: base64 has no
   * character JSON escapes, and the hosts are escaped anyway.
   */
  static String json(List<String> hosts, String clientId, String secret) {
    String auth =
        Base64.getEncoder()
            .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    return hosts.stream()
        .map(
            host ->
                "\""
                    + host.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\":{\"auth\":\""
                    + auth
                    + "\"}")
        .collect(Collectors.joining(",", "{\"auths\":{", "}}\n"));
  }

  /**
   * The directory at 0700 and the file at 0600, written to a temporary sibling and moved into place
   * so a docker child never reads half a file. The modes are set explicitly rather than trusted to
   * the umask, and the temporary file is CREATED at 0600, so the secret never exists on disk under
   * a wider mode, not even for the length of a write.
   */
  static void write(Path directory, List<String> hosts, String clientId, String secret)
      throws IOException {
    if (hosts.isEmpty()) {
      throw new IllegalStateException("no registry host to write a docker credential for");
    }
    Files.createDirectories(directory);
    Files.setPosixFilePermissions(directory, DIRECTORY_MODE);
    Path temporary =
        Files.createTempFile(
            directory, FILE_NAME, ".tmp", PosixFilePermissions.asFileAttribute(FILE_MODE));
    try {
      Files.writeString(temporary, json(hosts, clientId, secret), StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(temporary, FILE_MODE);
      Files.move(
          temporary,
          directory.resolve(FILE_NAME),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Optional<String> value(String name) {
    return ConfigProvider.getConfig()
        .getOptionalValue(name, String.class)
        .map(String::strip)
        .filter(v -> !v.isEmpty());
  }
}
