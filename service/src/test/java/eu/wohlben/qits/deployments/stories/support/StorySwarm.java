package eu.wohlben.qits.deployments.stories.support;

import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * <b>A recording docker stand-in</b>, and the tap that draws what a deployment asked the swarm
 * manager for.
 *
 * <h2>Why an executable and not an HTTP mock</h2>
 *
 * <p>This component does not speak to dockerd over a socket: {@code deployments/control/PdProcess}
 * <b>spawns the docker CLI</b> and reads its pipes, and which binary that is arrives as one runtime
 * key, {@code qits.deployments.container-runtime}. So the honest stand-in for the one
 * dependency a deployment has is not a stubbed endpoint — it is an executable. Pointing that key at
 * the script this class writes makes the orchestrator hop <b>observable</b> rather than declared:
 * every call lands in a file with the exit code it answered, and enough state lives under {@code
 * state/} that {@code SwarmDeploymentDriver}'s own reasoning runs for real against it — does the
 * service exist, is this update the one I issued, what environment does the live service carry.
 *
 * <p>That matters more than the convenience. A story that reached for a real daemon would break
 * this repository's first rule (a clone builds and tests green with no docker, and this suite's
 * container has no socket to reach); a story that only <i>declared</i> the swarm edge would document
 * the dependency this component exists for as a claim rather than as evidence. The choice was never
 * between a daemon and a stand-in — it was between a claim and a recording, and this is the
 * recording.
 *
 * <h2>Everything shared is a file or a system property</h2>
 *
 * <p>{@link #install()} is called from the {@code QuarkusTestProfile}, because the launched process
 * needs the binary's path before it boots — and a test profile is instantiated in more than one
 * classloader, so the directory travels in a system property exactly as the embedded postgres' url
 * does. The stand-in itself runs as a <b>separate process</b>, a child of the launched artifact,
 * which is why its recording is a file and not a list in a field: no two of the three JVMs and
 * processes involved share a heap.
 *
 * <h2>The recording has no floor, and the boot makes no calls</h2>
 *
 * <p>The source is registered at zero rather than at the current end of the file, the way {@code
 * qits-containers}' does — and here that is a claim rather than a convenience. This component's
 * boot touches the orchestrator <b>not at all</b>: the startup sweep iterates the {@code
 * QUEUED}/{@code STARTING} rows and a fresh database has none, and the observation ticker is switched
 * off in the story profile (see {@link StoryProfile}). So the first story to drain owns everything
 * from process start, and if that is ever not nothing its edge count fails loudly — which is the
 * right way for the assumption to break.
 *
 * <h2>What a line becomes</h2>
 *
 * <p>The script records the exit code and the whole argv, tab separated. {@link #summarize} reduces
 * the argv to the shape a reader needs — {@code service create story-tier-story-web}, {@code service
 * update-status story-tier-story-web}, {@code pull …/story-web:{sha}} — and never the whole command
 * line, for the two reasons a label is a summary at all: a {@code service create} carries {@code
 * --label qits.platform.deployments.deployment=<uuid>}, which is generated per run and would move
 * the {@code networkHash} on every one, and a Go {@code --format} template is full of braces that
 * mermaid reads as syntax. The exit code follows as {@code -> 0}, in the shape an HTTP label's
 * status has, because it is the same half of the evidence: that the call was <i>answered</i>, not
 * merely made.
 *
 * <p><b>Four {@code service inspect} shapes are told apart by their intent</b>, not by their flags —
 * "does it exist" ({@code {{.ID}}}), "how did my update go" ({@code UpdateStatus}), "what is it
 * running" ({@code ContainerSpec.Image}) and "what environment does it carry" ({@code
 * ContainerSpec.Env}). They are four different questions in the driver and reading them as one
 * arrow would hide the whole update path.
 */
public final class StorySwarm {

  /** How a diagram names the orchestrator on the far side of the pipes. */
  public static final String ORCHESTRATOR = "docker-swarm";

  /**
   * The kind these edges carry. {@code process} rather than {@code socket}: this component talks to
   * a spawned CLI over its pipes and never opens {@code /var/run/docker.sock} itself — which is a
   * real property of the design (the argv <i>is</i> the contract, assertable element for element
   * with no daemon anywhere) and not an accident of the stand-in.
   */
  public static final String KIND = NetworkEdge.PROCESS;

  /** Where the directory is parked, for the profile's other classloader to find. */
  private static final String DIR_PROPERTY = "qits.test.story-swarm.dir";

  private static final String SOURCE_ID = "story-swarm";

  /**
   * The application whose image the registry refuses with docker's own words for it. {@code
   * manifest unknown} is one of {@code SwarmDeploymentDriver.IMAGE_MISSING_MARKERS}, which is what
   * turns a failed pull into an {@code IMAGE_MISSING} row rather than a generic {@code FAILED}.
   */
  public static final String UNPUBLISHED_MARKER = "story-unpublished";

  private static final Object LOCK = new Object();

  private static boolean registered;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StorySwarm() {}

  // --- the binary --------------------------------------------------------------------------------

  /**
   * Write the stand-in, wipe whatever an earlier run left, and answer the path to hand {@code
   * qits.deployments.container-runtime}.
   *
   * <p>Idempotent per JVM through the parked property: the profile is instantiated more than once
   * and only the first copy has any business truncating the recording, since by the time the second
   * asks, the launched process may already be booting against it.
   */
  public static synchronized String install() {
    String parked = System.getProperty(DIR_PROPERTY);
    if (parked != null) {
      return Path.of(parked).resolve("docker").toString();
    }
    Path dir = Path.of(System.getProperty("user.dir"), "target", "story-swarm").toAbsolutePath();
    try {
      deleteRecursively(dir);
      Files.createDirectories(dir.resolve("state").resolve("services"));
      Files.createDirectories(dir.resolve("state").resolve("env"));
      Files.createDirectories(dir.resolve("state").resolve("updates"));
      Path networks = dir.resolve("state").resolve("networks");
      Files.createDirectories(networks);
      // The two overlays the swarm collapse keeps. Both already exist on a real platform — the
      // bootstrap makes them — so a deployment ASKS about them and creates neither, and that
      // asymmetry is what the boot edge of every deploy story shows.
      Files.writeString(networks.resolve("qits-net"), "");
      Files.writeString(networks.resolve("qits-platform"), "");
      Path binary = dir.resolve("docker");
      Files.writeString(binary, SCRIPT, StandardCharsets.UTF_8);
      Set<PosixFilePermission> executable =
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(binary, executable);
      System.setProperty(DIR_PROPERTY, dir.toString());
      return binary.toString();
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the swarm stand-in under " + dir, e);
    }
  }

  /** Where the calls are recorded. Resolved through the parked directory, never recomputed. */
  public static Path callLog() {
    String parked = System.getProperty(DIR_PROPERTY);
    Path dir =
        parked != null
            ? Path.of(parked)
            : Path.of(System.getProperty("user.dir"), "target", "story-swarm").toAbsolutePath();
    return dir.resolve("calls.log");
  }

  // --- what a story class calls -----------------------------------------------------------------

  /**
   * Register the recording as a cumulative {@link NetworkCapture} source, once per JVM.
   *
   * <p>At zero rather than at a floor — see the class javadoc. Called from every story class's
   * {@code @BeforeAll} so that each class is self-contained; whichever runs first does the work.
   */
  public static void installSource() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      EDGES.clear();
      NetworkCapture.source(SOURCE_ID, StorySwarm::edges);
      registered = true;
    }
  }

  /**
   * Every call the stand-in has answered so far, summarized and with its exit code — the same
   * strings the diagram carries.
   *
   * <p>A story reads it to <b>assert</b> what a deployment asked the orchestrator for, rather than
   * inferring it from the diagram it also emits. The two are the same evidence; one of them is
   * documentation and the other is a test, and a story that only drew it would be a story nothing
   * could fail.
   */
  public static List<String> calls() {
    return callsSince(0);
  }

  /**
   * How many calls the stand-in has answered so far — a story's own <b>starting line</b>.
   *
   * <p>The recording is cumulative and one launched process serves the whole catalogue, so {@link
   * #calls()} holds every deployment any story ever made. That makes a bare {@code
   * assertFalse(calls.contains("service create …"))} a claim about the RUN rather than about the
   * story, and it is wrong in exactly the case worth asserting: the story about a replace shares its
   * service with the story that created it. Take a mark before acting and read {@link
   * #callsSince(int)} afterwards, and the question becomes "what did THIS deployment ask for", which
   * is what the sentence in the story says.
   */
  public static int mark() {
    return recorded().size();
  }

  /** Every call answered since {@code mark} — one story's own conversation with the orchestrator. */
  public static List<String> callsSince(int mark) {
    List<String> summarized = new ArrayList<>();
    List<List<String>> lines = recorded();
    for (List<String> fields : lines.subList(Math.min(mark, lines.size()), lines.size())) {
      summarized.add(label(summarize(argv(fields)), fields.getFirst()));
    }
    return List.copyOf(summarized);
  }

  /**
   * The whole argv of the last call whose summary matches, or an empty list.
   *
   * <p>The diagram carries summaries, so an assertion about a <b>flag</b> — the repository's {@code
   * update_order} reaching {@code --update-order}, an extras key reaching {@code --env} — reads the
   * argv instead. Same recording, finer question.
   */
  public static List<String> argvOf(String summary) {
    List<String> found = List.of();
    for (List<String> fields : recorded()) {
      List<String> argv = argv(fields);
      if (summarize(argv).equals(summary)) {
        found = argv;
      }
    }
    return found;
  }

  /** The value that follows {@code flag} in an argv, or null — how one element of a claim is read. */
  public static String flagValue(List<String> argv, String flag) {
    for (int i = 0; i + 1 < argv.size(); i++) {
      if (flag.equals(argv.get(i))) {
        return argv.get(i + 1);
      }
    }
    return null;
  }

  /** Every value that follows a repeated {@code flag} — {@code --env} is stated once per variable. */
  public static List<String> flagValues(List<String> argv, String flag) {
    List<String> values = new ArrayList<>();
    for (int i = 0; i + 1 < argv.size(); i++) {
      if (flag.equals(argv.get(i))) {
        values.add(argv.get(i + 1));
      }
    }
    return List.copyOf(values);
  }

  /** The label one answered call renders as — what an assertion has to spell. */
  public static String label(String summary, String exitCode) {
    return summary + " -> " + exitCode;
  }

  /** The seed stack's twin of one service — asked about at every cutover, and never there here. */
  public static String seedTwin(String service) {
    return "qits_" + service;
  }

  /**
   * The eight questions a first deployment of an application asks, in the order the driver asks
   * them.
   *
   * <p>Reading them as a list is the point: every one of them is a decision written down in {@code
   * SwarmDeploymentDriver}, and a deployment that stopped asking one would be a deployment that had
   * stopped checking something. It lives here rather than in a story class because two of them tell
   * this same walk about different applications, and one spelling of it is what keeps them
   * comparable.
   */
  public static List<String> createCalls(String service, String application, String sha) {
    return List.of(
        // Ours, not the orchestrator's: a task that never starts is a much worse way to learn that
        // nothing published this build.
        label("pull " + image(StoryTarget.imageRef(application, sha)), "0"),
        // Asked about, never created — the shared overlay is the platform's and already exists.
        label("network inspect qits-net", "0"),
        // "Am I the service being replaced?" — asked before the apply, because after it this
        // process may not exist to ask anything.
        label("inspect self", "0"),
        label("service inspect " + service, "1"),
        // The seed stack's twin holds the wire alias and any host-mode ports, so it is asked about
        // at every cutover and removed when it is there.
        label("service inspect " + seedTwin(service), "1"),
        label("service create " + service, "0"),
        label("service update-status " + service, "0"),
        // A service nothing has updated yet has no update status at all, so a first deployment's
        // verdict is the task itself — Running only once its healthcheck has passed.
        label("service ps " + service, "0"));
  }

  /** …and the nine a replace asks: the create's, less the create, plus the live env and the drift check's overlay. */
  public static List<String> updateCalls(String service, String application, String sha) {
    return List.of(
        label("pull " + image(StoryTarget.imageRef(application, sha)), "0"),
        label("network inspect qits-net", "0"),
        label("inspect self", "0"),
        // 0 rather than 1: the service is there, which is what makes this an update.
        label("service inspect " + service, "0"),
        // The question only an update asks — what environment does the live service carry — so that
        // this argv can state a REMOVAL as well as an addition.
        label("service env " + service, "0"),
        label("service inspect " + seedTwin(service), "1"),
        // The network-drift check (epic qits-1976) resolves both managed overlays, so it can tell a
        // service on the wrong one; the private overlay does not exist until something private asks.
        label("network inspect qits-private", "1"),
        label("service update " + service, "0"),
        // …and the verdict is read off UpdateStatus, matched against when this process issued it.
        label("service update-status " + service, "0"));
  }

  // --- the source --------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<List<String>> lines = recorded();
    if (harvested > lines.size()) {
      // The file was truncated under us. Start over rather than mis-slice a prefix.
      harvested = 0;
      EDGES.clear();
    }
    for (List<String> fields : lines.subList(harvested, lines.size())) {
      EDGES.add(
          new NetworkEdge(
              KIND,
              StoryTarget.SERVICE,
              ORCHESTRATOR,
              label(summarize(argv(fields)), fields.getFirst())));
    }
    harvested = lines.size();
  }

  private static List<String> argv(List<String> fields) {
    return fields.subList(1, fields.size());
  }

  /**
   * One argv as the label a reader wants: which docker verb, against which service, network or
   * image — and, for the four {@code service inspect} shapes, which QUESTION the format asks.
   *
   * <p>Flags, label sets, environment and Go templates are all dropped; see the class javadoc for
   * why keeping them would move the hash and break the mermaid.
   */
  static String summarize(List<String> argv) {
    if (argv.isEmpty()) {
      return "docker";
    }
    String command = argv.getFirst();
    String last = argv.getLast();
    return switch (command) {
      case "pull" -> "pull " + image(last);
      // The one bare inspect this component makes: "which swarm service is my own task", asked of
      // this process's own hostname. The hostname is a container id, so the question is the label.
      case "inspect" -> "inspect self";
      case "network" -> "network " + at(argv, 1) + (isListing(argv, 1) ? "" : " " + last);
      case "service" -> service(argv, last);
      case "ps" -> "ps by label";
      default -> command;
    };
  }

  private static String service(List<String> argv, String last) {
    String verb = at(argv, 1);
    return switch (verb) {
      case "create" -> "service create " + nameFlag(argv, last);
      case "update", "rm", "logs" -> "service " + verb + " " + last;
      case "ps" -> "service ps " + at(argv, 2);
      case "ls" -> "service ls";
      case "inspect" -> inspect(argv, last);
      default -> "service " + verb;
    };
  }

  /**
   * The four questions a {@code service inspect} asks, named by what the driver does with the
   * answer rather than by the template that spells it.
   */
  private static String inspect(List<String> argv, String last) {
    String format = flagValue(argv, "--format");
    String template = format == null ? "" : format;
    if (template.contains("ContainerSpec.Image")) {
      return "service running-image " + last;
    }
    if (template.contains("ContainerSpec.Env")) {
      return "service env " + last;
    }
    if (template.contains("UpdateStatus")) {
      return "service update-status " + last;
    }
    return "service inspect " + last;
  }

  /**
   * An image reference with its tag templated. The tag IS the commit sha — that is the whole
   * convention this component deploys by — so a literal one would move the {@code networkHash} on
   * every story that changed a commit, and the reader learns nothing from forty hex characters that
   * {@code :{sha}} does not tell them.
   */
  public static String image(String reference) {
    int colon = reference.lastIndexOf(':');
    if (colon < 0 || colon < reference.lastIndexOf('/')) {
      return reference;
    }
    String tag = reference.substring(colon + 1);
    return tag.matches("[0-9a-f]{7,64}")
        ? reference.substring(0, colon) + ":{sha}"
        : reference;
  }

  private static boolean isListing(List<String> argv, int index) {
    return "ls".equals(at(argv, index));
  }

  private static String at(List<String> argv, int index) {
    return argv.size() > index ? argv.get(index) : "";
  }

  /** The value of {@code --name}, which is the only part of a service create a diagram is about. */
  private static String nameFlag(List<String> argv, String fallback) {
    String name = flagValue(argv, "--name");
    return name == null ? fallback : name;
  }

  /**
   * The recording's complete lines, split into {@code [exitCode, argv…]}. A missing file is an empty
   * recording rather than a failure, and an <b>unterminated tail is dropped</b>: the stand-in appends
   * while this reads, and half a line would shape half an edge. The next harvest sees it whole.
   */
  private static List<List<String>> recorded() {
    Path log = callLog();
    if (!Files.isRegularFile(log)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(log, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    List<List<String>> lines = new ArrayList<>();
    for (String line : text.substring(0, lastComplete).split("\n")) {
      String[] fields = line.split("\t", -1);
      if (fields.length >= 2) {
        lines.add(List.of(fields));
      }
    }
    return List.copyOf(lines);
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  // --- the stand-in itself -----------------------------------------------------------------------

  /**
   * The script. POSIX {@code sh} and nothing else — this repository's suite runs on the platform's
   * Alpine build image, and a bashism here would be a failure a developer's machine never sees.
   *
   * <p>Every answer below is one docker really gives, in docker's own words where the wording is
   * read: {@code manifest unknown} is what {@code SwarmDeploymentDriver.IMAGE_MISSING_MARKERS}
   * matches to turn a refused pull into an {@code IMAGE_MISSING} row, and {@code No such service} is
   * what tells "swarm has no such service" from "swarm did not answer". Getting either wrong would
   * make the stories pass against a daemon that behaves differently from every real one.
   *
   * <p><b>The update stamp is the one piece of real state that matters.</b> {@code service update
   * --detach} returns before the daemon has replaced {@code UpdateStatus}, so the driver records
   * when it issued the update and matches {@code .UpdateStatus.StartedAt} against it — a defect that
   * once declared a deployment live 43ms after issuing it. The stand-in therefore stamps the update
   * in <b>Go's own {@code time.Time.String()} spelling</b>, which is what {@code service inspect
   * --format} prints, so the driver's parser and its five-second skew tolerance are exercised rather
   * than bypassed.
   */
  private static final String SCRIPT =
      """
      #!/bin/sh
      # A RECORDING DOCKER/SWARM STAND-IN. Written by StorySwarm; never edited by hand.
      #
      # qits-platform-deployments shells out to whatever `qits.deployments.container-runtime`
      # names, element by element, through PdProcess. Pointing that key here makes the orchestrator
      # hop observable: every call is appended to calls.log with the exit code it answered, and
      # enough state lives under state/ for SwarmDeploymentDriver's own reasoning to run for real.
      set -u

      dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
      services="$dir/state/services"
      envs="$dir/state/env"
      updates="$dir/state/updates"
      networks="$dir/state/networks"
      calls="$dir/calls.log"
      mkdir -p "$services" "$envs" "$updates" "$networks"
      tab=$(printf '\\t')

      # A service id has to look like one. It reaches no wire field and no label, so one constant is
      # the whole of what is needed.
      sid=x7q2ph41e0nk9vas3mdt6ycbu

      # One pass over the argv: the value of --name, of --format and of --image, and the last
      # positional, which is what every service and network command is addressed to.
      name=''
      fmt=''
      image=''
      last=''
      prev=''
      for a in "$@"; do
        case $prev in
          --name) name=$a ;;
          --format) fmt=$a ;;
          --image) image=$a ;;
        esac
        prev=$a
        last=$a
      done

      code=0
      out=''
      # Go's time.Time.String(), which is what `service inspect --format` prints for StartedAt —
      # measured on docker 29.7.2. The JSON body of the same inspect says RFC3339 instead, and the
      # driver's parser takes both; this is the spelling a --format read has to survive.
      stamp=$(date -u '+%Y-%m-%d %H:%M:%S.000000000 +0000 UTC')

      case "${1:-}" in
        pull)
          case $last in
            *story-unpublished*)
              # Docker's own line for a reference no registry serves. `manifest unknown` is the
              # marker this component reads to record IMAGE_MISSING rather than a generic failure.
              code=1
              out="Error response from daemon: manifest unknown: manifest unknown."
              ;;
            *)
              out="Status: Downloaded newer image for $last"
              ;;
          esac
          ;;
        inspect)
          # "Which swarm service is this container's own task?" — asked of this process's hostname.
          # Outside a swarm task there is no such label, and `<no value>` is what docker prints.
          out='<no value>'
          ;;
        network)
          case "${2:-}" in
            inspect)
              if [ -e "$networks/$last" ]; then
                out='[]'
              else
                code=1
                out="Error response from daemon: network $last not found"
              fi
              ;;
            create)
              : > "$networks/$last"
              out=$last
              ;;
            rm)
              rm -f "$networks/$last"
              out=$last
              ;;
            ls)
              # Nothing labelled: this component's own bookkeeping read finds no network of its own,
              # which is true of a platform whose overlays the bootstrap made.
              out=''
              ;;
            *)
              code=1
              out="unknown network command: ${2:-}"
              ;;
          esac
          ;;
        service)
          case "${2:-}" in
            create)
              if [ -e "$services/$name" ]; then
                code=1
                out="Error response from daemon: rpc error: code = AlreadyExists desc = service $name already exists"
              else
                printf '%s\\n' "$last" > "$services/$name"
                : > "$envs/$name"
                prev=''
                for a in "$@"; do
                  if [ "$prev" = '--env' ]; then printf '%s\\n' "$a" >> "$envs/$name"; fi
                  prev=$a
                done
                # A create has no update to be asked about, and a service removed and made again
                # must not inherit the removed one's stamp.
                rm -f "$updates/$name"
                out=$sid
              fi
              ;;
            update)
              if [ -e "$services/$last" ]; then
                if [ -n "$image" ]; then printf '%s\\n' "$image" > "$services/$last"; fi
                printf '%s\\n' "$stamp" > "$updates/$last"
                out=$last
              else
                code=1
                out="Error: No such service: $last"
              fi
              ;;
            inspect)
              if [ -e "$services/$last" ]; then
                running=$(cat "$services/$last")
                state=''
                started=''
                if [ -e "$updates/$last" ]; then
                  state='completed'
                  started=$(cat "$updates/$last")
                fi
                case $fmt in
                  *ContainerSpec.Image*) out="$running|$state|$started|" ;;
                  *ContainerSpec.Env*)   out=$(cat "$envs/$last" 2>/dev/null) ;;
                  *UpdateStatus*)        out="$state|$started|" ;;
                  *)                     out=$sid ;;
                esac
              else
                code=1
                out="Error: No such service: $last"
              fi
              ;;
            ps)
              # A task is Running only once its healthcheck has passed, which is the statement the
              # driver reads for the first deployment of an application.
              if [ -e "$services/${3:-}" ]; then
                out='Running 2 seconds ago'
              else
                code=1
                out="Error: No such service: ${3:-}"
              fi
              ;;
            rm)
              if [ -e "$services/$last" ]; then
                rm -f "$services/$last" "$envs/$last" "$updates/$last"
                out=$last
              else
                code=1
                out="Error: No such service: $last"
              fi
              ;;
            ls)
              out=''
              ;;
            logs)
              out=''
              ;;
            *)
              code=1
              out="unknown service command: ${2:-}"
              ;;
          esac
          ;;
        ps)
          out=''
          ;;
        *)
          code=1
          out="qits story swarm: no such command: ${1:-}"
          ;;
      esac

      # Recorded before the answer leaves, so a caller that observed an effect can rely on the line
      # for it already being on disk. One printf, so concurrent calls interleave by line.
      line=$code
      for a in "$@"; do line="$line$tab$a"; done
      printf '%s\\n' "$line" >> "$calls"

      if [ -n "$out" ]; then printf '%s\\n' "$out"; fi
      exit "$code"
      """;
}
