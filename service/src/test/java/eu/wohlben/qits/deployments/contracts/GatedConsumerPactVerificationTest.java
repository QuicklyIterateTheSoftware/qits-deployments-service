package eu.wohlben.qits.deployments.contracts;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFilter;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import eu.wohlben.qits.deployments.api.MachineGuardEnforcedProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies the consumer interactions only a gated application can answer</b> — the states in
 * {@link ProviderStates#GATED}, against this application under {@link
 * MachineGuardEnforcedProfile}, where the machine gate is on and the dev user is off. {@link
 * ConsumerPactVerificationTest} verifies every other state.
 */
@QuarkusTest
@TestProfile(MachineGuardEnforcedProfile.class)
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@PactFilter(ProviderStates.THE_MACHINE_GATE_IS_ON)
@IgnoreNoPactsToVerify
class GatedConsumerPactVerificationTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context) {
    if (context != null) {
      context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
    }
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void gatedConsumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  @AfterEach
  void cleanUp() {
    states.cleanUp();
  }

  @State(ProviderStates.THE_MACHINE_GATE_IS_ON)
  Map<String, String> theMachineGateIsOn() {
    return states.params(ProviderStates.THE_MACHINE_GATE_IS_ON);
  }
}
