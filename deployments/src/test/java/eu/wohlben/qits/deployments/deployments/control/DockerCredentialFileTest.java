package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * This component's own docker credential: the file {@link DockerCredentialFile} writes and the
 * {@code DOCKER_CONFIG} decision {@link PdProcess#dockerConfig} takes over it. Plain JUnit over the
 * static halves — the startup observer is skipped under TEST, the {@code BootResourceRegistration}
 * arrangement.
 */
class DockerCredentialFileTest {

  private static final String SECRET = "a-secret-that-must-never-be-logged";

  private static final String REGISTRY = "registry.dev.localhost:8080";

  @TempDir Path tmp;

  @Test
  void theHostsAreTheRegistryAndItsMirrorSibling() {
    assertEquals(
        List.of("registry.dev.localhost:8080", "mirror.dev.localhost:8080"),
        DockerCredentialFile.hosts(REGISTRY));
    assertEquals(
        List.of("registry.qits.example.eu", "mirror.qits.example.eu"),
        DockerCredentialFile.hosts(" registry.qits.example.eu "));
    // Nothing to derive a sibling from: the stated host alone, never an invented second one.
    assertEquals(List.of("oci.example.eu:5000"), DockerCredentialFile.hosts("oci.example.eu:5000"));
    assertEquals(List.of(), DockerCredentialFile.hosts(""));
  }

  @Test
  void theFileIsTheBootstrapsDockerConfigJsonShape() {
    String json =
        DockerCredentialFile.json(
            DockerCredentialFile.hosts(REGISTRY), "dev-qits-deployments", SECRET);

    String auth =
        Base64.getEncoder()
            .encodeToString(("dev-qits-deployments:" + SECRET).getBytes(StandardCharsets.UTF_8));
    // Byte for byte what qits-bootstrap-cli's SeedPhases.dockerConfigJson produces for these hosts.
    assertEquals(
        "{\"auths\":{\"registry.dev.localhost:8080\":{\"auth\":\""
            + auth
            + "\"},\"mirror.dev.localhost:8080\":{\"auth\":\""
            + auth
            + "\"}}}\n",
        json);
  }

  @Test
  void theDirectoryIs0700AndTheFile0600() throws IOException {
    Path dir = tmp.resolve("qits-docker");

    assertTrue(
        DockerCredentialFile.writeOwnCredential(
            Optional.of("dev-qits-deployments"), Optional.of(SECRET), dir, REGISTRY));

    Path file = dir.resolve("config.json");
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertEquals(
        DockerCredentialFile.json(
            List.of("registry.dev.localhost:8080", "mirror.dev.localhost:8080"),
            "dev-qits-deployments",
            SECRET),
        Files.readString(file));
    try (Stream<Path> entries = Files.list(dir)) {
      assertEquals(List.of(file), entries.toList(), "no temporary file left behind");
    }
  }

  @Test
  void aWiderDirectoryIsNarrowedAndAnOldFileReplaced() throws IOException {
    Path dir = Files.createDirectory(tmp.resolve("wide"));
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    Files.writeString(dir.resolve("config.json"), "{\"auths\":{}}");

    DockerCredentialFile.write(dir, List.of(REGISTRY), "dev-qits-deployments", SECRET);

    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("config.json"))));
    assertTrue(Files.readString(dir.resolve("config.json")).contains(REGISTRY));
  }

  @Test
  void withoutThePairNothingIsWritten() {
    Path dir = tmp.resolve("absent");

    assertFalse(
        DockerCredentialFile.writeOwnCredential(
            Optional.empty(), Optional.of(SECRET), dir, REGISTRY));
    assertFalse(
        DockerCredentialFile.writeOwnCredential(
            Optional.of("dev-qits-deployments"), Optional.empty(), dir, REGISTRY));
    assertFalse(Files.exists(dir));
  }

  @Test
  void noLineCarriesTheSecretOrItsBase64() throws IOException {
    String auth =
        Base64.getEncoder()
            .encodeToString(("dev-qits-deployments:" + SECRET).getBytes(StandardCharsets.UTF_8));
    // A file where the directory should be: the write fails and the WARN arm runs too.
    Path blocked = Files.writeString(tmp.resolve("blocked"), "not a directory");

    List<String> lines =
        logsOf(
            () -> {
              DockerCredentialFile.writeOwnCredential(
                  Optional.of("dev-qits-deployments"),
                  Optional.of(SECRET),
                  tmp.resolve("ok"),
                  REGISTRY);
              DockerCredentialFile.writeOwnCredential(
                  Optional.of("dev-qits-deployments"), Optional.of(SECRET), blocked, REGISTRY);
            });

    assertTrue(lines.size() >= 2, String.valueOf(lines));
    assertTrue(lines.stream().anyMatch(l -> l.contains("dev-qits-deployments")), "names the client");
    for (String line : lines) {
      assertFalse(line.contains(SECRET), "the secret was logged: " + line);
      assertFalse(line.contains(auth), "the base64 credential was logged: " + line);
    }
  }

  @Test
  void dockerConfigIsSetOnlyWhenTheFileExists() throws IOException {
    Path dir = tmp.resolve("decide");

    assertEquals(Optional.empty(), PdProcess.dockerConfig(null), "no directory configured");
    assertEquals(Optional.empty(), PdProcess.dockerConfig(dir), "no directory at all");
    Files.createDirectories(dir);
    assertEquals(Optional.empty(), PdProcess.dockerConfig(dir), "an empty directory is no config");

    DockerCredentialFile.write(dir, List.of(REGISTRY), "dev-qits-deployments", SECRET);

    assertEquals(Optional.of(dir.toString()), PdProcess.dockerConfig(dir));
  }

  @Test
  void aDockerChildSeesTheDirectoryOnlyWhenTheFileExists() throws IOException {
    // The decision reaching a real child: `sh -c 'echo $DOCKER_CONFIG'` as the command.
    Path dir = Files.createDirectories(tmp.resolve("child"));
    List<String> echo = List.of("sh", "-c", "echo \"DC=${DOCKER_CONFIG:-unset}\"");
    String inherited = "DC=" + Optional.ofNullable(System.getenv("DOCKER_CONFIG")).orElse("unset");

    PdProcess.Result before = PdProcess.run(null, echo, dir, java.time.Duration.ofSeconds(10), 4096);
    assertEquals(inherited, before.output().strip(), "no file: the inherited value, untouched");

    DockerCredentialFile.write(dir, List.of(REGISTRY), "dev-qits-deployments", SECRET);

    PdProcess.Result after = PdProcess.run(null, echo, dir, java.time.Duration.ofSeconds(10), 4096);
    assertEquals("DC=" + dir, after.output().strip());
  }

  private static List<String> logsOf(Runnable body) {
    List<String> lines = new ArrayList<>();
    Logger logger = Logger.getLogger(DockerCredentialFile.class.getName());
    Level level = logger.getLevel();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            lines.add(
                record.getMessage()
                    + " "
                    + Arrays.toString(record.getParameters())
                    + (record.getThrown() == null ? "" : " " + record.getThrown()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    logger.setLevel(Level.ALL);
    try {
      body.run();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(level);
    }
    return lines;
  }
}
