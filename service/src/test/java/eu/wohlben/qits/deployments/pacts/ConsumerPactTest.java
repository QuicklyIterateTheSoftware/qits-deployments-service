package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of every contract</b> (ticket qits-1149): for each row, this repository's
 * real client makes a real HTTP call to a pact-jvm mock server that answers what the row's
 * interaction promises, and the row asserts what the client made of it.
 *
 * <p>One mock server per row, as qits-maintenance-service does: rows that send the identical
 * request differ only by trigger, and one server for all would read all but the first as "never
 * called".
 *
 * <p><b>A row whose provider state is not recorded yet is SKIPPED, not dropped</b>: the skip names
 * the state the provider has to record, and the row runs the day the golden masters carry it.
 */
class ConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @TestFactory
  Stream<DynamicNode> everyRowIsWhatTheClientAsksAndUnderstands() {
    return Contracts.all().stream()
        .map(
            contract ->
                DynamicContainer.dynamicContainer(
                    contract.provider(),
                    contract.rows().stream()
                        .map(
                            row ->
                                DynamicTest.dynamicTest(
                                    row.description() + " [" + row.state() + "]",
                                    () -> run(contract, row)))));
  }

  private static void run(Contract contract, Contract.Row row) {
    Assumptions.assumeTrue(contract.ready(row), contract.waitingReason(row));
    GoldenMasters masters = contract.masters().orElseThrow();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            contract.pact(List.of(row)),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              row.call().run(mockServer.getUrl(), masters.params(row.state()));
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      List<String> lines = new ArrayList<>();
      lines.add(row.description() + " [" + row.state() + "]: " + describe(result));
      fail(String.join("\n", lines));
    }
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
