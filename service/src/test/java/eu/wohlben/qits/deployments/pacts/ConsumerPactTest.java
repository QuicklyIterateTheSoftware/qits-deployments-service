package eu.wohlben.qits.deployments.pacts;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of every contract</b> (ticket qits-1149): for each row, this repository's
 * real client makes a real HTTP call to a pact-jvm mock server that answers what the row's
 * interaction promises, and the row asserts what the client made of it. One mock server per row
 * ({@code ConsumerPact.run}); a row whose call goes on to further rows ({@link
 * Contracts.Check#alongside}) gets one mock server holding all of them, and fails unless the call
 * made every one.
 *
 * <p>A row whose provider state is not recorded is SKIPPED, naming the state the provider has to
 * record. A row with no call here (the token mint) runs inside the application, in {@code
 * IdpTokenMintPactTest}.
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
                    contract.pact().masters().repository(),
                    contract.checks().stream()
                        .filter(check -> check.call() != null)
                        .map(
                            check ->
                                DynamicTest.dynamicTest(
                                    check.row().description() + " [" + check.row().state() + "]",
                                    () -> run(contract.pact(), check)))));
  }

  private static void run(ConsumerPact pact, Contracts.Check check) {
    for (GoldenInteraction row : check.rows()) {
      Assumptions.assumeTrue(pact.recorded().contains(row), pact.needs(row));
    }
    if (check.alongside().isEmpty()) {
      pact.run(check.row(), check.call());
      return;
    }
    var recorded = pact.masters().operation(check.row().state(), check.row().operationId());
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact.pact(check.rows()),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              check.call().run(mockServer.getUrl(), recorded);
              return null;
            });
    if (result instanceof PactVerificationResult.Error error
        && error.getError() instanceof AssertionError assertion) {
      throw assertion;
    }
    if (!(result instanceof PactVerificationResult.Ok)) {
      Assertions.fail(check.row().description() + ": " + result);
    }
  }
}
