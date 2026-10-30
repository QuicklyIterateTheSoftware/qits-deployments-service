package eu.wohlben.qits.deployments.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.Pact;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link ClasspathPactLoader}'s package-private seam directly, with an explicit directory
 * value rather than the {@code QITS_DEPLOYED_PACTS_DIR} environment variable — an env var is hard
 * to set in-JVM, which is the whole reason the seam exists.
 *
 * <p>The provider name here, {@code test-provider}, names no real provider and matches none of the
 * consumer pact jars pinned on this module's classpath, so the loader's classpath scan always
 * finds nothing for it — which is what isolates these cases to the deployed-pacts directory alone,
 * rather than a mix of the two sources.
 */
class ClasspathPactLoaderDeployedPactsTest {

  private static final String PROVIDER = "test-provider";
  private static final String CONSUMER = "a-consumer-repo";

  @TempDir Path deployedPactsDir;

  @Test
  void twoVersionsOfOneConsumersPactAreLoadedAsSeparateSources() {
    writePact("1.0.0", "a first request");
    writePact("2.0.0", "a second request");

    List<Pact> loaded = ClasspathPactLoader.load(PROVIDER, deployedPactsDir.toString());

    assertEquals(2, loaded.size(), "both versions should load as two distinct sources");
    Set<String> consumers =
        loaded.stream().map(pact -> pact.getConsumer().getName()).collect(Collectors.toSet());
    assertEquals(Set.of(CONSUMER), consumers);
    Set<String> descriptions =
        loaded.stream()
            .flatMap(pact -> pact.getInteractions().stream())
            .map(interaction -> interaction.getDescription())
            .collect(Collectors.toSet());
    assertEquals(Set.of("a first request", "a second request"), descriptions);
  }

  @Test
  void aMissingDirectoryFailsRatherThanLoadingNothing() {
    Path missing = deployedPactsDir.resolve("does-not-exist");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> ClasspathPactLoader.load(PROVIDER, missing.toString()));

    assertTrue(thrown.getMessage().contains("QITS_DEPLOYED_PACTS_DIR"));
  }

  @Test
  void blankOrUnsetReadsOnlyTheClasspath() {
    // No deployed directory and no matching classpath entry for this provider name: the required
    // check still fires, exactly as it would with the environment variable unset.
    assertThrows(IllegalStateException.class, () -> ClasspathPactLoader.load(PROVIDER, null));
    assertThrows(IllegalStateException.class, () -> ClasspathPactLoader.load(PROVIDER, "   "));
  }

  private void writePact(String version, String description) {
    Path versionDir = deployedPactsDir.resolve(CONSUMER).resolve(version);
    try {
      Files.createDirectories(versionDir);
      Path pact = versionDir.resolve(CONSUMER + "_" + PROVIDER + ".json");
      Files.writeString(pact, pactJson(description), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String pactJson(String description) {
    return "{\n"
        + "  \"consumer\": {\"name\": \""
        + CONSUMER
        + "\"},\n"
        + "  \"provider\": {\"name\": \""
        + PROVIDER
        + "\"},\n"
        + "  \"interactions\": [\n"
        + "    {\n"
        + "      \"description\": \""
        + description
        + "\",\n"
        + "      \"request\": {\"method\": \"GET\", \"path\": \"/ping\"},\n"
        + "      \"response\": {\"status\": 200},\n"
        + "      \"type\": \"Synchronous/HTTP\"\n"
        + "    }\n"
        + "  ],\n"
        + "  \"metadata\": {\"pactSpecification\": {\"version\": \"4.0\"}}\n"
        + "}\n";
  }
}
