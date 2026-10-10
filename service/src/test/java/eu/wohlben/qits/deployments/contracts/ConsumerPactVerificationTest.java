package eu.wohlben.qits.deployments.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running provider</b>, the way qits-projects-service
 * does.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-deployments-service.json} (repository names on both sides), this repo
 * pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases
 * a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>No consumer pins a pact yet</b>, so {@code @IgnoreNoPactsToVerify} lets an empty classpath
 * pass and the loader logs that nothing was verified. When the first consumer's pact jar is pinned,
 * drop the annotation and set {@link ClasspathPactLoader#REQUIRED} to true.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, as the
 * {@code %test} synthetic user — exactly as {@link GoldenMasterRecordingTest}'s calls run. Every
 * {@code @State} method delegates to {@link ProviderStates}; {@link #target} fails an unknown state,
 * and an interaction without {@code comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-deployments-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // no pact to verify: @IgnoreNoPactsToVerify's single empty run
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-deployments does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  // --- the states: each one line into the registry -------------------------------------------

  @AfterEach
  void cleanUp() {
    states.cleanUp();
  }

  @State(ProviderStates.AN_ENVIRONMENT)
  Map<String, String> anEnvironment() {
    return states.params(ProviderStates.AN_ENVIRONMENT);
  }

  @State(ProviderStates.AN_APPLICATION_DEPLOYED)
  Map<String, String> anApplicationDeployed() {
    return states.params(ProviderStates.AN_APPLICATION_DEPLOYED);
  }

  @State(ProviderStates.THE_PLATFORM_ENVIRONMENT)
  Map<String, String> thePlatformEnvironment() {
    return states.params(ProviderStates.THE_PLATFORM_ENVIRONMENT);
  }

  @State(ProviderStates.NO_ENVIRONMENT_OF_THE_NAME)
  Map<String, String> noEnvironmentOfTheName() {
    return states.params(ProviderStates.NO_ENVIRONMENT_OF_THE_NAME);
  }

  @State(ProviderStates.A_RELEASED_VERSION)
  Map<String, String> aReleasedVersion() {
    return states.params(ProviderStates.A_RELEASED_VERSION);
  }

  @State(ProviderStates.AN_APPLICATION_WITH_A_ROLLBACK)
  Map<String, String> anApplicationWithARollback() {
    return states.params(ProviderStates.AN_APPLICATION_WITH_A_ROLLBACK);
  }

  @State(ProviderStates.AN_APPLICATION_HOLDING_A_SERVICE_CLIENT)
  Map<String, String> anApplicationHoldingAServiceClient() {
    return states.params(ProviderStates.AN_APPLICATION_HOLDING_A_SERVICE_CLIENT);
  }
}
