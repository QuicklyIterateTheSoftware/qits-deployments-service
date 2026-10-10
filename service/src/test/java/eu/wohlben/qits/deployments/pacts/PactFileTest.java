package eu.wohlben.qits.deployments.pacts;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenFiles;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The committed consumer pacts, {@code pacts/qits-deployments-service_<provider>.json}</b>
 * (ticket qits-1149), one per provider with at least one recorded row: compared by default,
 * rewritten under {@code -Dgolden.update=true}.
 *
 * <p>Written here rather than by {@code ConsumerPact.compareOrWritePactFile} for one reason: the
 * discovery row binds {@code issuer} BY VALUE. quarkus-oidc takes the issuer it checks every token's
 * {@code iss} against from that document, so a provider answering another issuer breaks every bearer
 * this service accepts; matched by type, the pact would let it. The type rule the library puts on
 * {@code $.issuer} is removed, and pact then compares the recorded value.
 */
class PactFileTest {

  @TestFactory
  Stream<DynamicTest> theCommittedPactIsWhatTheRowsWrite() {
    return Contracts.all().stream()
        .map(contract -> DynamicTest.dynamicTest(contract.pact().file(), () -> check(contract.pact())));
  }

  private static void check(ConsumerPact pact) throws Exception {
    Path committed = ConsumerPact.pactsDirectory().resolve(pact.file());
    if (pact.recorded().isEmpty()) {
      Assertions.assertFalse(Files.exists(committed), committed + " is committed, but no row of it is recorded");
      return;
    }
    pact.assertEveryInteractionCarriesBothReferences();
    V4Pact written = pact.pact();
    for (Interaction interaction : written.getInteractions()) {
      if (is(interaction, Contracts.DISCOVERY)) {
        ((V4Interaction.SynchronousHttp) interaction)
            .getResponse()
            .getMatchingRules()
            .rulesForCategory("body")
            .getMatchingRules()
            .remove("$.issuer");
      }
    }
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(written, writer, PactSpecVersion.V4);
    }
    String normalised = ConsumerPact.normalise(out.toString());
    if (pact.recorded().contains(Contracts.DISCOVERY)) {
      Assertions.assertFalse(
          normalised.contains("\"$.issuer\""), "the discovery row binds issuer by value");
    }
    GoldenFiles.compareOrWrite(committed, normalised);
  }

  private static boolean is(Interaction interaction, GoldenInteraction row) {
    return interaction.getDescription().equals(row.description())
        && !interaction.getProviderStates().isEmpty()
        && row.state().equals(interaction.getProviderStates().get(0).getName());
  }
}
