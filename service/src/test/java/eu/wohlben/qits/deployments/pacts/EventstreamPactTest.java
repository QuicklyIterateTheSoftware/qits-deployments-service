package eu.wohlben.qits.deployments.pacts;

import eu.wohlben.qits.deployments.bus.PdRepositoryRenamedSubscriber;
import eu.wohlben.qits.deployments.bus.PdSoftwareReleaseSubscriber;
import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on this service's behalf</b> (qits-1163): the outbox's
 * publish ({@code DeployEventAnnouncer}, {@code EnvironmentEventAnnouncer}) and both durable
 * subscribers' catch-up reads, pacted by this service against the library version it pins and
 * committed as {@code
 * pacts/qits-deployments-service_qits-eventstream-javalib_qits-events-service.json}. The guard fails
 * naming any durable or raw listener, or {@code QitsEventBus} user, in the modules that this class
 * leaves out.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-deployments-service")
          .listeners(new PdRepositoryRenamedSubscriber(), new PdSoftwareReleaseSubscriber())
          .publishes(true)
          .guard(
              Path.of("target/classes"),
              Path.of("../deployments/target/classes"),
              Path.of("../environments/target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
