package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The deployer moving its own databases (qits-2209): the plan one step is, and what the three
 * places that act on it share.
 *
 * <p><b>Why the successor moves, not the running deployer.</b> A move stops the service that holds
 * the database, and here that service is the mover. So the running deployer only DECIDES: {@link
 * DatabaseMoves#plan} turns the move into a {@link Step}, and the self-update hands it to the
 * successor as {@code QITS_RESOURCE_<NAME>_MOVE}, next to a binding that already names the target.
 * The deployer is {@code stop-first}, so when the successor starts the old process is gone and
 * nothing writes. The successor then moves the data in {@code main}, before Quarkus opens a
 * datasource ({@link SelfMoveBoot}), and finishes the move once swarm has accepted it ({@link
 * SelfMoveFinish}).
 *
 * <p><b>Where the state lives.</b> Nothing in either database can be trusted to say how far a move
 * got, because the old and the new process each read a different one. So the state is kept where
 * both can read it:
 *
 * <ul>
 *   <li>a <b>marker volume</b>, {@code qits-dbmove-<id>}, labelled with the encoded step. It exists
 *       from before the first change until the move is finished. A copy also writes its dump there.
 *   <li>the <b>target database's comment</b>, {@code qits-self-move:<id>:<stage>}: {@code copying}
 *       while a copy restores, {@code verified} once the counts match, {@code renaming} on a rename
 *       (set on the source before it is renamed, so it travels with it), and {@code done} once the
 *       move is finished. A database with any other comment, or none, is never dropped.
 * </ul>
 *
 * <p><b>Which side a process is on is its own binding.</b> A process bound to a step's SOURCE is the
 * old deployer after swarm rolled the update back: it undoes the step. A process bound to the
 * TARGET is the successor, or a later deployer: the step is in effect, and it is finished.
 */
public final class SelfMove {

  /** The binding value that carries a step: {@code QITS_RESOURCE_<NAME>_MOVE}. */
  public static final String SUFFIX = "MOVE";

  /** The label every marker volume carries, and the one it is found by. */
  static final String KIND_LABEL = "kind";

  static final String KIND = "self-move";

  /** The label that carries the encoded step. */
  static final String PLAN_LABEL = "self-move";

  static final String MARK_PREFIX = "qits-self-move:";

  /** The variables the admin password may arrive in — the config key's two env spellings. */
  static final List<String> ADMIN_PASSWORD_VARIABLES =
      List.of(
          "QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD", "QITS_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD");

  private static final String SEPARATOR = "|";

  private static final String WORD = "[A-Za-z0-9._-]{1,200}";

  private static final String IMAGE = "[A-Za-z0-9._:/@-]{1,400}";

  private SelfMove() {}

  /** How far a step got, as its target database's comment says it. */
  enum Stage {
    COPYING,
    VERIFIED,
    RENAMING,
    DONE;

    String spelling() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One database the deployer moves for itself.
   *
   * @param id the move's id: its volume, its marks and its {@code pd_database_move} row
   * @param image the postgres image both one-shots run — the source server's, as a copy uses
   * @param timeoutSeconds the deadline of the extract and of the ingest
   */
  public record Step(
      String id,
      String environment,
      String resource,
      DatabaseServer sourceServer,
      String sourceHost,
      String sourceNetwork,
      String sourceDatabase,
      DatabaseServer targetServer,
      String targetHost,
      String targetNetwork,
      String targetDatabase,
      String image,
      String adminUser,
      long timeoutSeconds) {

    public Step {
      word("id", id);
      word("environment", environment);
      PdIdentifiers.requireResourceName(resource);
      word("source host", sourceHost);
      word("source network", sourceNetwork);
      PdIdentifiers.requireDatabaseName(sourceDatabase);
      word("target host", targetHost);
      word("target network", targetNetwork);
      PdIdentifiers.requireDatabaseName(targetDatabase);
      if (image == null || !image.matches(IMAGE)) {
        throw new IllegalArgumentException("not an image: " + image);
      }
      word("admin user", adminUser);
      if (sourceServer == null || targetServer == null) {
        throw new IllegalArgumentException("a step names both servers");
      }
      if (sourceServer == targetServer && sourceDatabase.equals(targetDatabase)) {
        throw new IllegalArgumentException("a step moves something: source and target are the same");
      }
      if (timeoutSeconds <= 0) {
        throw new IllegalArgumentException("a step needs a timeout");
      }
    }

    /** Two names on one server: a rename in place, as for every other application (qits-2193). */
    public boolean inPlace() {
      return sourceServer == targetServer;
    }

    /** The marker volume, which is also the copy's dump volume. */
    public String volume() {
      return DatabaseMoves.VOLUME_PREFIX + id;
    }

    /** {@code qits-self-move:<id>:<stage>}. */
    String mark(Stage stage) {
      return markPrefix() + stage.spelling();
    }

    /** What every mark of this step starts with. */
    String markPrefix() {
      return MARK_PREFIX + id + ":";
    }

    String source() {
      return sourceServer.wire() + "/" + sourceDatabase;
    }

    String target() {
      return targetServer.wire() + "/" + targetDatabase;
    }

    /** One line, the fields in order, {@code |} between them. Every field is checked not to hold one. */
    public String encode() {
      return String.join(
          SEPARATOR,
          id,
          environment,
          resource,
          sourceServer.wire(),
          sourceHost,
          sourceNetwork,
          sourceDatabase,
          targetServer.wire(),
          targetHost,
          targetNetwork,
          targetDatabase,
          image,
          adminUser,
          Long.toString(timeoutSeconds));
    }

    /** {@link #encode}'s inverse; every field is checked again. */
    public static Step parse(String encoded) {
      if (encoded == null) {
        throw new IllegalArgumentException("no step");
      }
      String[] f = encoded.strip().split("\\" + SEPARATOR, -1);
      if (f.length != 14) {
        throw new IllegalArgumentException("a step has 14 fields, this one " + f.length);
      }
      long timeout;
      try {
        timeout = Long.parseLong(f[13]);
      } catch (NumberFormatException wrong) {
        throw new IllegalArgumentException("not a timeout: " + f[13]);
      }
      return new Step(
          f[0],
          f[1],
          f[2],
          DatabaseServer.parse(f[3]),
          f[4],
          f[5],
          f[6],
          DatabaseServer.parse(f[7]),
          f[8],
          f[9],
          f[10],
          f[11],
          f[12],
          timeout);
    }
  }

  private static void word(String what, String value) {
    if (value == null || !value.matches(WORD)) {
      throw new IllegalArgumentException("not a " + what + ": " + value);
    }
  }

  /** Whether {@code mark} is a self-move mark of a move that did not finish — a leftover. */
  static boolean leftover(String mark) {
    return mark != null && mark.startsWith(MARK_PREFIX) && !mark.endsWith(":" + Stage.DONE.spelling());
  }

  /** Every step this process was handed, one per {@code QITS_RESOURCE_<NAME>_MOVE}. */
  static List<Step> fromEnvironment(Map<String, String> env) {
    List<Step> steps = new ArrayList<>();
    List<String> keys =
        env.keySet().stream()
            .filter(key -> key.startsWith("QITS_RESOURCE_") && key.endsWith("_" + SUFFIX))
            .sorted()
            .toList();
    for (String key : keys) {
      Step step = Step.parse(env.get(key));
      if (!key.equals(BootResourceRegistration.variable(step.resource(), SUFFIX))) {
        throw new IllegalArgumentException(key + " carries the step of `" + step.resource() + "`");
      }
      steps.add(step);
    }
    return List.copyOf(steps);
  }

  /** The variable, stripped, when it is set and not blank. */
  static Optional<String> e(Map<String, String> env, String name) {
    return Optional.ofNullable(env.get(name)).map(String::strip).filter(v -> !v.isEmpty());
  }

  /** Where this process is bound for {@code resource}: {@code <host>/<database>}, from its url. */
  static Optional<String> boundTo(Map<String, String> env, String resource) {
    return e(env, BootResourceRegistration.variable(resource, "URL")).map(SelfMove::hostAndDatabase);
  }

  /** {@code jdbc:postgresql://<host>:<port>/<db>?…} → {@code <host>/<db>}; empty on anything else. */
  static String hostAndDatabase(String url) {
    int start = url.indexOf("//");
    if (start < 0) {
      return "";
    }
    String rest = url.substring(start + 2);
    int slash = rest.indexOf('/');
    if (slash < 0) {
      return "";
    }
    String host = rest.substring(0, slash);
    int colon = host.indexOf(':');
    if (colon >= 0) {
      host = host.substring(0, colon);
    }
    String database = rest.substring(slash + 1);
    int query = database.indexOf('?');
    if (query >= 0) {
      database = database.substring(0, query);
    }
    return host + "/" + database;
  }

  /** Whether {@code env} binds the step's resource to its SOURCE. */
  static boolean boundToSource(Map<String, String> env, Step step) {
    return boundTo(env, step.resource()).map(b -> b.equals(step.sourceHost() + "/" + step.sourceDatabase())).orElse(false);
  }

  /** Whether {@code env} binds the step's resource to its TARGET. */
  static boolean boundToTarget(Map<String, String> env, Step step) {
    return boundTo(env, step.resource()).map(b -> b.equals(step.targetHost() + "/" + step.targetDatabase())).orElse(false);
  }

  /** The role password this process holds for the step's resource, if any. */
  static Optional<String> rolePassword(Map<String, String> env, Step step) {
    return e(env, BootResourceRegistration.variable(step.resource(), "PASSWORD"));
  }

  /** The admin password, from either env spelling. */
  static Optional<String> adminPassword(Map<String, String> env) {
    for (String name : ADMIN_PASSWORD_VARIABLES) {
      Optional<String> value = e(env, name);
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }

  /**
   * A one-shot against the step's source ({@code target} false) or target server: on that server's
   * one network, as the admin, the admin password and {@code secrets} as secrets.
   */
  static DatabaseContainers.OneShot shot(
      String purpose,
      Step step,
      boolean target,
      String adminPassword,
      Map<String, String> secrets,
      String volume,
      String script) {
    Map<String, String> all = new HashMap<>(secrets);
    all.put("PGPASSWORD", adminPassword);
    return new DatabaseContainers.OneShot(
            purpose,
            step.image(),
            target ? step.targetNetwork() : step.sourceNetwork(),
            volume,
            Map.of(
                "PGHOST",
                target ? step.targetHost() : step.sourceHost(),
                "PGPORT",
                String.valueOf(ResourceProvisioning.POSTGRES_PORT),
                "PGUSER",
                step.adminUser()),
            all,
            script)
        .forMove(step.id());
  }
}
