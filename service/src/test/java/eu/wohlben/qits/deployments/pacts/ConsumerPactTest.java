package eu.wohlben.qits.deployments.pacts;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of every contract</b> (ticket qits-1149): for each row, this repository's
 * real client makes a real HTTP call to a pact-jvm mock server that answers what the row's
 * interaction promises, and the row asserts what the client made of it. One mock server per row
 * ({@code ConsumerPact.run}).
 *
 * <p>A row whose provider state is not recorded is SKIPPED, naming the state the provider has to
 * record.
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
                        .map(
                            check ->
                                DynamicTest.dynamicTest(
                                    check.row().description() + " [" + check.row().state() + "]",
                                    () -> {
                                      Assumptions.assumeTrue(
                                          contract.pact().recorded().contains(check.row()),
                                          contract.pact().needs(check.row()));
                                      contract.pact().run(check.row(), check.call());
                                    }))));
  }
}
