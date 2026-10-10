package eu.wohlben.qits.deployments.contracts;

import eu.wohlben.qits.deployments.api.MachineGuardEnforcedProfile;
import eu.wohlben.qits.deployments.contracts.GoldenMasterRecordingTest.Interaction;
import eu.wohlben.qits.deployments.contracts.GoldenMasterRecordingTest.Recorded;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * <b>Records the golden masters only a gated application can answer</b> — the states in {@link
 * ProviderStates#GATED}, under {@link MachineGuardEnforcedProfile}, where a bearer no idp issued
 * answers 401.
 *
 * <p>{@link GoldenMasterRecordingTest} owns the index and writes these interactions' entries; this
 * test owns only their answer files. It compares by default and rewrites under {@code
 * -Dgolden.update=true}, as that test does.
 */
@QuarkusTest
@TestProfile(MachineGuardEnforcedProfile.class)
class GatedGoldenMasterRecordingTest {

  @Inject ProviderStates states;

  @Test
  void gatedGoldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    for (Interaction interaction : GoldenMasterRecordingTest.INTERACTIONS) {
      if (!GoldenMasterRecordingTest.gated(interaction)) {
        continue;
      }
      ProviderStates.Setup setup = states.setUp(interaction.state());
      Recorded recorded;
      try {
        recorded =
            GoldenMasterRecordingTest.call(
                interaction, setup, GoldenMasterRecordingTest.GATED_HEADERS);
      } finally {
        states.cleanUp();
      }
      String file =
          ProviderStates.slug(interaction.state()) + "/" + interaction.operationId() + ".json";
      String failure =
          GoldenFiles.check(
              dir.resolve(file),
              GoldenJson.render(recorded.body()),
              update,
              UnaryOperator.identity());
      if (failure != null) {
        failures.add(failure);
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }
}
