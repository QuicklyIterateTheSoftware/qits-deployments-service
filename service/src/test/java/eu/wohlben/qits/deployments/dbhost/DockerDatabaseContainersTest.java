package eu.wohlben.qits.deployments.dbhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.deployments.deployments.control.DatabaseContainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the database move's one-shot asks docker for (qits-2175) — plain JUnit over the package-private
 * {@code Cli} seam, the swarm driver test's shape: the argv IS the contract, and asserting it needs
 * no daemon.
 */
class DockerDatabaseContainersTest {

  private static final String ADMIN_PASSWORD = "s3cret-admin-password";
  private static final String ROLE_PASSWORD = "0123456789abcdef0123456789abcdef";

  private record Call(List<String> argv, Map<String, String> environment) {}

  private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

  private DockerDatabaseContainers containers(ContainerProcess.Result waited, String logs) {
    DockerDatabaseContainers containers = new DockerDatabaseContainers();
    containers.runtime = "docker";
    containers.outputMaxChars = 65536;
    containers.dockerConfigDir = Optional.empty();
    containers.scriptCli(
        (argv, environment, timeout) -> {
          calls.add(new Call(argv, environment));
          return switch (argv.get(1)) {
            case "wait" -> waited;
            case "logs" -> new ContainerProcess.Result(0, logs, false, false);
            default -> new ContainerProcess.Result(0, "", false, false);
          };
        });
    return containers;
  }

  private static DatabaseContainers.OneShot shot(String network) {
    return new DatabaseContainers.OneShot(
        "ingest",
        "registry.example/qits/qits-oci-postgresql:2026.1.1",
        network,
        "qits-dbmove-0b9c",
        Map.of("PGHOST", "dev-qits-oci-postgresql-private", "PGUSER", "postgres"),
        Map.of("PGPASSWORD", ADMIN_PASSWORD, "QITS_ROLE_PASSWORD", ROLE_PASSWORD),
        "set -eu\npsql -d postgres <<'QITS_SQL'\nSELECT 1;\nQITS_SQL\n");
  }

  @Test
  void aOneShotJoinsExactlyOneNetworkAndCarriesNoPasswordInItsArgv() {
    DockerDatabaseContainers containers =
        containers(new ContainerProcess.Result(0, "0\n", false, false), "done\n");

    DatabaseContainers.Outcome outcome = containers.run(shot("qits-private"), Duration.ofMinutes(1));

    assertTrue(outcome.ok(), outcome.toString());
    List<String> run = calls.get(0).argv();
    assertEquals(List.of("docker", "run", "-d"), run.subList(0, 3));
    assertEquals(1, Collections.frequency(run, "--network"), run.toString());
    assertEquals("qits-private", run.get(run.indexOf("--network") + 1));
    assertTrue(run.stream().noneMatch(element -> element.startsWith("--network-add")), run.toString());
    assertTrue(run.stream().noneMatch(element -> element.contains(ADMIN_PASSWORD)), run.toString());
    assertTrue(run.stream().noneMatch(element -> element.contains(ROLE_PASSWORD)), run.toString());
    // Passed BY NAME: docker reads the value from the CLI's own environment, which carries it.
    assertTrue(containsPair(run, "-e", "PGPASSWORD"), run.toString());
    assertTrue(containsPair(run, "-e", "QITS_ROLE_PASSWORD"), run.toString());
    assertEquals(ADMIN_PASSWORD, calls.get(0).environment().get("PGPASSWORD"));
    assertEquals(ROLE_PASSWORD, calls.get(0).environment().get("QITS_ROLE_PASSWORD"));
    // Plain variables stay ordinary values, and the volume is mounted where the scripts expect it.
    assertTrue(containsPair(run, "-e", "PGHOST=dev-qits-oci-postgresql-private"), run.toString());
    assertTrue(containsPair(run, "-v", "qits-dbmove-0b9c:" + DatabaseContainers.MOUNT), run.toString());
    // No restart policy: it runs once.
    assertFalse(run.contains("--restart"), run.toString());
    assertTrue(containsPair(run, "--entrypoint", "sh"), run.toString());

    // Then it is waited for, read, and removed — on every path.
    String name = run.get(run.indexOf("--name") + 1);
    assertTrue(name.startsWith("qits-dbmove-ingest-"), name);
    assertEquals(List.of("docker", "wait", name), calls.get(1).argv());
    assertEquals("logs", calls.get(2).argv().get(1));
    assertEquals(List.of("docker", "rm", "-f", name), calls.get(3).argv());
    assertTrue(calls.get(1).environment().isEmpty(), "only the run is handed the secrets");
  }

  @Test
  void theExitCodeIsTheScriptsAndTheOutputIsRedacted() {
    DockerDatabaseContainers containers =
        containers(
            new ContainerProcess.Result(0, "3\n", false, false),
            "ERROR: LINE 1: CREATE ROLE r LOGIN PASSWORD '" + ROLE_PASSWORD + "'\n");

    DatabaseContainers.Outcome outcome = containers.run(shot("qits-net"), Duration.ofMinutes(1));

    assertEquals(3, outcome.exitCode());
    assertFalse(outcome.ok());
    assertFalse(outcome.output().contains(ROLE_PASSWORD), outcome.output());
    assertTrue(outcome.output().contains("***"), outcome.output());
  }

  @Test
  void aContainerPastItsDeadlineIsRemovedAndReportedTimedOut() {
    DockerDatabaseContainers containers =
        containers(new ContainerProcess.Result(-1, "", true, false), "half a dump\n");

    DatabaseContainers.Outcome outcome = containers.run(shot("qits-net"), Duration.ofSeconds(1));

    assertTrue(outcome.timedOut());
    assertFalse(outcome.ok());
    assertEquals("rm", calls.get(calls.size() - 1).argv().get(1));
  }

  @Test
  void aServiceIsDrainedWhenNoTaskIsLeftOnItsWayToRunning() {
    assertEquals(
        DatabaseContainers.Drain.DRAINED,
        DockerDatabaseContainers.drainOf(
            new ContainerProcess.Result(0, "Shutdown 3 seconds ago\nComplete 2 days ago\n", false, false)));
    assertEquals(
        DatabaseContainers.Drain.RUNNING,
        DockerDatabaseContainers.drainOf(
            new ContainerProcess.Result(0, "Shutdown 1 second ago\nRunning 4 hours ago\n", false, false)));
    assertEquals(
        DatabaseContainers.Drain.DRAINED,
        DockerDatabaseContainers.drainOf(
            new ContainerProcess.Result(1, "Error: no such service: dev-x", false, false)));
    assertEquals(
        DatabaseContainers.Drain.UNKNOWN,
        DockerDatabaseContainers.drainOf(
            new ContainerProcess.Result(1, "Cannot connect to the Docker daemon", false, false)));
  }

  @Test
  void theMovesVolumeIsLabelledInTheDeployersOwnNamespace() {
    DockerDatabaseContainers containers =
        containers(new ContainerProcess.Result(0, "0", false, false), "");

    containers.createVolume("qits-dbmove-0b9c");
    containers.removeVolume("qits-dbmove-0b9c");

    List<String> create = calls.get(0).argv();
    assertEquals(List.of("docker", "volume", "create"), create.subList(0, 3));
    assertEquals("qits-dbmove-0b9c", create.get(create.size() - 1));
    assertTrue(
        create.stream().anyMatch(element -> element.startsWith(DockerDatabaseContainers.NAMESPACE_PREFIX)),
        create.toString());
    assertTrue(
        create.stream().noneMatch(element -> element.startsWith("qits.containers.")),
        "never qits-containers' namespace, whose volume sweep would claim it: " + create);
    assertEquals(List.of("docker", "volume", "rm", "qits-dbmove-0b9c"), calls.get(1).argv());
  }

  private static boolean containsPair(List<String> argv, String flag, String value) {
    for (int i = 0; i + 1 < argv.size(); i++) {
      if (argv.get(i).equals(flag) && argv.get(i + 1).equals(value)) {
        return true;
      }
    }
    return false;
  }
}
