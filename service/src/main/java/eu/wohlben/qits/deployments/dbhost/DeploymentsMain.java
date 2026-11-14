package eu.wohlben.qits.deployments.dbhost;

import eu.wohlben.qits.deployments.deployments.control.SelfMoveBoot;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.util.Map;

/**
 * The deployer's {@code main} (qits-2209): before Quarkus starts — and so before any datasource
 * opens or Flyway runs — it moves the deployer's own databases when its self-update handed it a
 * move, and undoes one a rolled-back update left. See {@link SelfMoveBoot}.
 *
 * <p>A failed move exits 1 without starting Quarkus. The task fails, and swarm's {@code
 * --update-failure-action rollback} starts the old deployer on its untouched source.
 *
 * <p>With nothing to move this costs one {@code docker volume ls}. Tests never come here: a
 * {@code @QuarkusTest} starts the application without {@code main}.
 */
@QuarkusMain
public class DeploymentsMain {

  /** {@code qits.deployments.container-runtime}'s env spelling; config is not read yet. */
  static final String RUNTIME_VARIABLE = "QITS_PLATFORM_DEPLOYMENTS_CONTAINER_RUNTIME";

  /** The output bound every docker call reads back — {@code qits.deployments.output-max-chars}. */
  private static final int OUTPUT_MAX_CHARS = 65536;

  public static void main(String... args) {
    Map<String, String> env = System.getenv();
    String runtime = env.getOrDefault(RUNTIME_VARIABLE, "docker").strip();
    if (!SelfMoveBoot.run(
        env, DockerDatabaseContainers.standalone(runtime.isEmpty() ? "docker" : runtime, OUTPUT_MAX_CHARS))) {
      System.exit(1);
    }
    Quarkus.run(args);
  }
}
