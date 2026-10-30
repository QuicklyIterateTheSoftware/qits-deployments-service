package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.events.NavigationEntry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The strict reader of {@code .config/qits/deployments.yml}. Twenty-one scalar keys, no nesting, no
 * YAML lists — so this is a line reader rather than a YAML library, and being one is what makes every
 * rejection a sentence naming the file and the line.
 *
 * <pre>
 * application: qits-ci                 # optional; the name this deploys as, when it is not the repo's
 * renamed_from: qits-platform-ci       # optional; the application name this one used to carry
 * deployment_target: environment       # RETIRED, accepted and ignored — see below
 * available_on_env: false              # default; true = public node (bundle + hub joins)
 * health_path: /q/health/ready         # default: /&lt;name without the qits- prefix&gt;/q/health/ready
 * health_cmd: pg_isready -U postgres   # instead of health_path: the probe runs in the container
 * resources: postgresql:db             # a database of its own, injected as QITS_RESOURCE_DB_*
 * volumes: private:data:/data          # a volume of its own, mounted at /data
 * binds: /var/run/docker.sock:/var/run/docker.sock   # host paths, &lt;host&gt;:&lt;target&gt;[:ro]
 * publishes: 8080:8080, 443:8443       # host ports, [&lt;ipv4&gt;:]&lt;host-port&gt;:&lt;port&gt;[/tcp|/udp]
 * aliases: registry.${QITS_ENVIRONMENT}.localhost   # extra names on the shared network
 * update_order: start-first            # default | stop-first for anything single-writer
 * publish_mode: host                   # default | ingress for a port the routing mesh holds
 * stop_grace_period: 10m               # default 30s; SIGTERM to SIGKILL, for long transfers
 * routes: /artifacts,/v2               # optional public path prefixes, in navigation order
 * upstream_port: 8080                  # default; the service port behind every route
 * host: registry                       # optional DNS label this application is also served at
 * navigation-entries: system.Artifacts:3   # optional slot.Label:position[=subpath] list — see below
 * navigation: Artifacts:3              # the one-entry predecessor of the key above, kept forever
 * api-docs: /artifacts/q/swagger-ui    # optional; where the browsable API document lives
 * deploy_branches: environment/prod    # RETIRED, accepted and ignored — see below
 * </pre>
 *
 * <p><b>{@code application} is what this repository deploys AS, and it decouples the deployed
 * identity from the repository's name.</b> Absent — which is every file that exists today — the
 * application name is the repository's name and every derivation is byte-identical to what it
 * always was. Present, that string IS the application name everywhere the repository's would have
 * been: the swarm service and its wire alias, the container name, the image reference {@code
 * qits/<application>:<sha>}, the provisioned database ({@code qits_} plus the name without its
 * prefix), the derived {@code host} label, the catalogue key, the extras family {@code
 * qits.deployments.extras.<application>.*}, the {@code QITS_APPLICATION} the container
 * boots with, and the name every {@code Deployment*} event carries. The parser only reads and
 * validates it — the substitution happens in {@code DeployService.deploy}, which is the first place
 * that holds both the spec and the announcement it was read for.
 *
 * <p>It exists so a repository can be RENAMED without moving anything that is running: {@code
 * qits-ci} becomes the repository {@code qits-ci-service}, writes {@code application: qits-ci}, and
 * the platform does not notice. That is also why the image reference following it is a feature
 * rather than a leak — a renamed repository's pipeline keeps pushing {@code qits/qits-ci}, and the
 * deployer keeps pulling it.
 *
 * <p><b>It is validated as a stored name, not as free text</b> ({@link PdIdentifiers#requireName}):
 * lowercase letters, digits and inner dashes, at most 63 characters. Uppercase is refused outright,
 * so two spellings differing only in case cannot both exist; slashes, dots and spaces are refused
 * because this value becomes a docker service name, a DNS alias, an image path segment and half of
 * a postgres identifier.
 *
 * <p><b>Two repositories declaring one application name is LAST-WINS and this component cannot
 * refuse it.</b> {@code pd_service}, {@code pd_deployment} and {@code pd_resource} record an
 * application NAME and no repository identity at all — V1's header states that as the rule, not as
 * an omission — so there is nothing to compare a second claimant against. It is not a hazard this
 * key introduces either: two repositories in two projects may already carry one name, and they
 * already collapse the same way. What the key adds is that the claim is now written down, so every
 * deployment that takes an override logs which repository claimed which application. Refusing it
 * would take a repository identity on {@code pd_service} and a migration to hold it, which is a
 * decision about foreign identity in this schema rather than a detail of this key.
 *
 * <p><b>CHANGING an application name later is a decommission and a new application</b>, and that
 * half of the old rule is not softened by anything below: the old name keeps its service, its alias,
 * its rows and its routes, the new name gets fresh ones and deploys beside it, and editing this key
 * on a live application is that whether or not anybody meant it. A swarm service's name IS its
 * address and swarm cannot rename one, so there is no version of this where the running thing moves.
 *
 * <p><b>What the rule was wrong about is the word "nothing".</b> It used to end "and nothing here
 * helps with it", and for as long as an application's rename was a thing the runbook simply avoided,
 * that was an accurate description rather than a gap. It stopped being one the day six applications
 * had to drop a retired {@code qits-platform-} prefix: the successor deploys beside the predecessor,
 * declares the predecessor's database in {@code resources:} because losing the data is not on the
 * table, and is then refused by {@code ResourceProvisioning} — "the database `qits_platform_mirror`
 * is already provisioned for qits-platform-mirror". That refusal is <b>right</b>, and it stays: two
 * unrelated repositories sharing one store is exactly the silent takeover it was written to stop,
 * and the alternative it prevents is a container that boots green on an empty database. What was
 * missing was a way for a file to say <em>this application IS the one that used to be called X</em>,
 * so the check can tell a rename from a collision instead of having to guess which it is looking at.
 *
 * <p><b>{@code renamed_from} is that sentence, and the transfer it buys is ONE row.</b> Named, and
 * only when the conflicting {@code pd_resource} claim is that exact application's, the claim's
 * {@code application_name} is rewritten to this one — so the successor is provisioned against the
 * predecessor's database with the <b>stored</b> credential rather than a rotated one, which is the
 * half that keeps the predecessor's own connections working while both are running. Any other
 * mismatch throws exactly as it always did. Everything else about a rename is untouched — a fresh
 * service, a fresh alias, fresh deployment rows, and a predecessor an operator scales to zero when
 * they are ready — save one announcement: each successor cutover withdraws the predecessor's edge
 * routes, which nothing else would ever replace. See {@code ResourceProvisioning.ensureAll} for the
 * transfer's own argument.
 *
 * <p><b>It is a declaration and never an inference</b>, which is the whole reason it is a key. This
 * component could have guessed — a prefix rule, a similarity test, the previous holder of the
 * database — and every one of those guesses is "hand this application somebody else's store" with a
 * heuristic in front of it. A key is asked for in a commit, reviewed by a person and read at the
 * released tag, which is the same standard every other statement in this file is held to.
 *
 * <p><b>One value and not a list</b>, because a chain does not need one: A→B transfers the claim to
 * B, so when B→C deploys the claim it finds is B's, and C names B. A file that wants to name two
 * predecessors is a file describing a transfer that already happened. Stating the key twice is the
 * duplicate every key here is refused for.
 *
 * <p>It is validated as a stored name, exactly as {@code application} is, because it is compared
 * against one: a value outside that charset could match no row this schema ever wrote, so refusing
 * it at the line is cheaper than a deployment that fails at provisioning saying nothing useful.
 * Naming the same application both times is refused where this parser can see it — with {@code
 * application} present, the two keys then say "renamed from itself", which is a statement about
 * nothing. With {@code application} absent it cannot be seen here at all (the parser never knows
 * which repository it is reading for) and is inert downstream: the transfer arm is reached only for
 * a claim belonging to a <em>different</em> application.
 *
 * <p>And, like every key here: <b>it ships in this parser before any repository writes the line</b>
 * — a spec is read at the released tag, and an unknown key fails a deployment — and once it has
 * shipped it can never become unknown again, for the reason the paragraph on unknown keys below
 * gives. A rename is a one-off per application and the line will look inert a month later; leaving
 * it in the file is correct, and deleting the key from this parser is not.
 *
 * <p><b>{@code navigation-entries} is where an application asks to appear, and it is a list.</b>
 * One application shows up under several headings — a repository's Docs, CI and Workspaces are the
 * same three entries under all seven categories — so a single label could never have said it. An
 * entry is {@code <slot>.<Label>:<position>[=<subpath>]}, read from the RIGHT: the last colon
 * separates the tail, the tail's first {@code =} separates the position from the optional subpath,
 * the last dot of what remains separates the label, and everything before it is the slot. That
 * order is what lets a label contain a colon or an equals sign ({@code services.details.CI:v2:1})
 * while the slot vocabulary stays closed. A label may not contain a dot or a comma, which is what
 * makes both separators safe.
 *
 * <p><b>What one application may claim once is a {@code (slot, label)} pair, not a slot.</b> An
 * application contributes several ROWS to one heading — {@code project.detail.Workspaces:1,
 * project.detail.Editor:2=editor} is one application asking for two entries under the project node,
 * which is exactly what a list of placements is for. Keying the claim on the slot alone refused that
 * whole file with "claims the slot `project.detail` twice", and the deployment then failed as
 * "deployment spec unreadable" while the build that produced it stayed green. The same label twice
 * in one slot stays an error: it is one row asked for twice, and no consumer can draw two of it. A
 * repeated POSITION is not an error — a number is the repository's own and a tie is broken by
 * label, which is what the document already does between applications.
 *
 * <p><b>The subpath is the view the entry opens, and it is a client-side route segment.</b> The
 * shell composes every entry's address as origin + scope + subpath ({@code
 * /<project>/services/<repo>/api-docs}), so the subpath is relative, lowercase and slash-separated
 * — never a colon, dot or comma, which is what keeps every separator above safe by construction.
 * No published-route validation touches it: it names a view of the declaring application's SPA, not
 * an edge route.
 *
 * <p><b>{@code api-docs} is where this application's browsable API document lives</b> — the
 * swagger-ui a Quarkus service serves under its non-application root — and it must sit under one of
 * the published {@code routes}, because a document nobody can reach describes nothing. It is
 * application-level metadata rather than a navigation entry: the shell's Api Docs viewer looks the
 * path up by application name and frames it. Absent is a real answer — a service that documents no
 * HTTP surface.
 *
 * <p><b>{@code host} is one DNS label, and the platform derives it when the file says nothing.</b>
 * The default is the application name without its {@code qits-platform-} or {@code qits-} prefix
 * ({@code qits-ci} → {@code ci}), resolved where the name is known — {@code DeployService}, never
 * here. A file states the key only where the public name already differs from that derivation
 * (qits-artifacts is served at {@code registry}). A host is announced only for an application that
 * asked for one: {@code host:} explicitly, or {@code navigation-entries} at all. Both keys need a
 * {@code routes} entry, because a name that leads nowhere is not a topology.
 *
 * <p><b>{@code navigation} is the retired singular, and it is accepted forever.</b> It maps to one
 * {@code system.<Label>:<position>} entry and NO host — a file that has not been rewritten yet must
 * keep behaving exactly as it did. Naming both keys is an error: they are two answers to one
 * question. See the paragraph on unknown keys below for why the word can never be taken back.
 *
 * <p><b>{@code publish_mode} is the second key an orchestrator reads rather than this
 * component.</b> {@code publish_mode: host} is today's behaviour and the default: the task binds the
 * port on the node itself. {@code ingress} gives the port to swarm's routing mesh, which keeps holding it
 * while a replacement starts — the whole reason a front door can pull its own successor's image.
 * It reaches the orchestrator as {@code mode=} on the publish, and it means nothing at all to an
 * application that publishes no port. It does <b>not</b> touch {@code update_order}: the two are
 * independent statements and this component derives neither from the other.
 *
 * <p><b>{@code update_order} is the one key an orchestrator reads rather than this component.</b>
 * {@code start-first} overlaps the successor with the predecessor, which is what makes a rollback
 * lossless; {@code stop-first} is the opt-out for an application that cannot be two processes at
 * once — one binder per published host port, one writer per store, one holder of a config volume.
 * It reaches the orchestrator as {@code --update-order}. A repository that says nothing gets
 * {@code start-first}, so an application that must not overlap has to say so.
 *
 * <p><b>{@code stop_grace_period} is the third key an orchestrator reads rather than this
 * component</b>, and it reaches it as {@code --stop-grace-period} on the create and the update
 * alike. It is how long a task that has been told to stop keeps the transfers it is in the middle
 * of: swarm takes the task out of the load balancer on SIGTERM, so nothing NEW arrives, but what is
 * already flowing is cut the moment the grace runs out. Thirty seconds covers a request; it does
 * not cover a multi-gigabyte download through qits-edge or qits-mirror, which is why it is the
 * repository's to raise (qits-1170). The value is a duration in docker's own spelling — {@code
 * 90s}, {@code 10m}, {@code 9m30s}, {@code 1h} — with whole numbers, units in descending order and
 * each at most once, and it must be more than zero and at most an hour. The ceiling is not
 * docker's: under {@code stop-first} the deployer waits out the predecessor's grace before the
 * successor can even start, on the one worker every deployment queues behind, so an unbounded
 * value would be an unbounded stall of the whole platform's deployments.
 *
 * <p><b>{@code health_cmd} and {@code health_path} are alternatives, and setting both is an
 * error.</b> They are not two settings on one gate: the path names a URL a {@code curl} inside the
 * container fetches, and the command replaces that whole mechanism. A deployable image with no
 * HTTP surface — postgres, the first of them — can pass no path-shaped gate, having neither curl
 * nor anything on 8080, so it says how it is ready in its own words instead.
 *
 * <p><b>{@code resources} is a flat comma-separated list, and it will never be anything else.</b>
 * The grammar is {@code postgresql:<name>[:<database>]} because this file has no YAML sequences and
 * no nesting to give it — the same reason {@code deploy_branches} is comma-separated. What a
 * repository names there is a database of its own on the platform's shared postgres, so the two
 * names are allowlisted (see {@link PdIdentifiers#requireResourceName} and {@link
 * PdIdentifiers#requireDatabaseName}), and the {@code qits_} prefix is what keeps the namespace it
 * can reach disjoint from the instance's own.
 *
 * <p><b>{@code volumes} is the same statement about storage that {@code resources} is about
 * databases.</b> A volume mount is a property of what an application IS — it needs a place to keep
 * its data, and it needs it in every environment it is ever deployed into — so it belongs beside
 * the rest of what the repository declares about itself, not in an indexed list in a config store
 * authored somewhere else. The grammar is {@code private:<name>:<target>[:ro]} and {@code
 * shared:<volume>:<target>[:ro]}, comma-separated for the reason {@code resources} is.
 *
 * <p><b>A private volume's NAME is derived, exactly as a resource's database is</b>: {@code
 * private:data} on qits-workspaces is the volume {@code qits-workspaces-data}, and the repository
 * cannot state the whole name. That is the point rather than a convenience — a derived name is what
 * makes it impossible for one application to mount another's storage by writing its name down.
 * {@code shared:} is the opposite case and names its volume in full, because a platform-owned
 * volume is not this application's to derive; the vocabulary is closed ({@link #SHARED_VOLUMES}).
 *
 * <p><b>{@code volumes} still carries no host path, and that line stays where it is</b>: a volume
 * name is derived or drawn from a closed vocabulary, so nothing written there can address storage
 * that is not the application's own. A host path is a different statement and it got a different
 * KEY rather than a third scope here — {@code binds}, below.
 *
 * <p><b>{@code binds}, {@code publishes} and {@code aliases} are the shape that used to live only in
 * deployment config</b> ({@code qits.deployments.extras.<app>.mounts[i] bind:…}, {@code
 * publishes[i]}, {@code aliases[i]} — see {@link ServiceExtras}), authored in another repository
 * and identical everywhere. They moved here because qits-configuration is being retired as a
 * source of deployment shape (qits-765): what an application needs from the host — the docker
 * socket, a published port, a vhost name docker's DNS cannot synthesize — is reviewed in the
 * application's own commit and read at its released tag, like everything else in this file. The
 * price is stated rather than hidden: a repository that can write {@code binds} can mount any host
 * path, so the review of this file is the guard, exactly as it is for the image itself.
 *
 * <ul>
 *   <li>{@code binds: <abs-host-path>:<abs-target>[:ro], …} — a host bind mount. Both paths are
 *       absolute and take the mount-target charset; {@code :ro} is the one option. A repeated
 *       target is refused, and so is a target one of the file's own {@code volumes} already
 *       occupies. Binding {@code /var/run/docker.sock} also earns the container the socket's own
 *       group, read by the deployer off the socket it mounts itself — a gid is a fact about the
 *       host, so no file states it.
 *   <li>{@code publishes: [<ipv4>:]<host-port>:<port>[/tcp|/udp], …} — {@link ServiceExtras}'
 *       {@code publishes[n]} grammar, read by the same code. A repeated published port and protocol
 *       (an unnamed protocol is tcp) is refused. Where the port is held is still {@code
 *       publish_mode}.
 *   <li>{@code aliases: <dns-name>, …} — extra names on the shared network. {@code
 *       ${QITS_ENVIRONMENT}} inside one is replaced by the deployment's environment name at deploy
 *       time ({@code registry.${QITS_ENVIRONMENT}.localhost} is {@code registry.dev.localhost} in
 *       dev); no other {@code $} is allowed. No comma and no whitespace, checked here and again
 *       after the substitution. A repeated alias is refused.
 * </ul>
 *
 * <p>While deployment config still states the same things (until qits-1393), the driver takes the
 * union of both and the file wins: a mount by target, a publish by port and protocol, an alias by
 * name, a group by gid. Nothing reaches swarm twice. And none of the three is restated by an
 * update — a service keeps the mounts and ports it was created with, and a declared alias missing
 * from a live service forces a recreate exactly as a config-declared one does.
 *
 * <p><b>Once this key ships it can never become unknown again</b>, and that is worth writing down
 * in the commit that adds it rather than learning later. A spec is fetched at the BUILT sha, so a
 * rollback pin or a redeploy of an older commit presents whatever file that commit carried, and an
 * unknown key fails a deployment. {@code deploy_branches} below is the same lesson, learned the
 * expensive way; this is it applied in advance. Retiring the key later means keeping the tolerance
 * forever, exactly as that one did.
 *
 * <p><b>{@code deploy_branches} is RETIRED: accepted, validated, and acted on by nobody.</b> Where
 * a build deploys was always decided by the environment rows — a green build deploys wherever an
 * environment listens to its branch — so this component never read the key back. Its one reader was
 * qits-workspaces' release flow, which promoted a release onto every branch the list named; that is
 * a fan-out rather than a ladder, and with three tiers it would have shipped a release into all
 * three at once. A release now lands on one entry branch, from that component's own configuration,
 * and no repository states it.
 *
 * <p>It stays accepted for the reason every retired key here stays accepted, and the reason is
 * sharper for this one.
 * <b>A spec is fetched at the BUILT sha</b>, so a rollback pin, a redeploy of an older commit or a
 * repository nobody has edited yet still presents a file carrying the key — and this parser fails a
 * deployment on an unknown one. Making it unknown would turn every such deployment red. Do not
 * write it into a new file, and do not remove the tolerance.
 *
 * <p><b>Strict on purpose.</b> A typo in this file decides where a container runs and what can
 * reach it, and a lenient parser answers a typo with a default — silently deploying the wrong
 * topology and leaving nothing to read. So an unknown key, a repeated key, a value outside the enum
 * and a line that is not {@code key: value} are all errors, and the deployment fails on them. The
 * one thing that is <em>not</em> an error is the file's absence: no file means every default, which
 * is what every repository already behaves like.
 *
 * <p><b>{@code deployment_target} is RETIRED: accepted, ignored, and acted on by nobody.</b> There
 * is no platform-service concept left for it to choose between — every service is an ordinary
 * environment service in the one tier, and the enum it used to parse into no longer exists — so
 * there is no target for this file to state and nothing downstream that would read one. The key is
 * therefore not validated at all: {@code platform}, {@code environment}, {@code singleton} and any
 * word whatsoever are read and dropped, because a value nobody acts on is not a value worth refusing
 * a deployment over.
 *
 * <p>The tolerance is permanent, for the reason {@code deploy_branches} above records. <b>A spec is
 * fetched at the BUILT sha</b>, so a rollback pin, a redeploy of an older commit, or any of the
 * repositories that still carry the line presents a file containing the key — and this parser fails
 * a deployment on an unknown one. Making it unknown would turn every such deployment red
 * permanently, not merely for the length of a sweep. Do not write it into a new file, and do not
 * remove the tolerance.
 */
public final class DeploymentSpecParser {

  private static final String APPLICATION = "application";

  /**
   * The application name this one used to carry. Snake_case like twelve of the sixteen keys before
   * it: {@code navigation-entries} and {@code api-docs} are the file's two kebab-case outliers and
   * neither is defended anywhere, so the majority spelling is the one a new key joins.
   */
  private static final String RENAMED_FROM = "renamed_from";

  /** Retired, accepted and ignored. Any value at all parses. See the class javadoc. */
  private static final String RETIRED_TARGET = "deployment_target";
  private static final String AVAILABLE_ON_ENV = "available_on_env";
  private static final String DEPLOY_BRANCHES = "deploy_branches";
  private static final String HEALTH_PATH = "health_path";
  private static final String HEALTH_CMD = "health_cmd";
  private static final String RESOURCES = "resources";
  private static final String VOLUMES = "volumes";
  private static final String BINDS = "binds";
  private static final String PUBLISHES = "publishes";
  private static final String ALIASES = "aliases";
  private static final String UPDATE_ORDER = "update_order";
  private static final String PUBLISH_MODE = "publish_mode";
  private static final String STOP_GRACE_PERIOD = "stop_grace_period";

  /**
   * One whole number per unit, units descending and each at most once — {@code 1h}, {@code 9m30s},
   * {@code 1500ms}. A strict subset of Go's {@code time.ParseDuration}, which is what docker reads
   * the flag with, so anything this accepts docker accepts too. Fractions and the sub-millisecond
   * units are left out because no grace period wants them, and a parser that accepts less is one
   * whose every acceptance can be read back.
   */
  private static final Pattern GRACE_DURATION =
      Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+)s)?(?:(\\d+)ms)?");

  /**
   * The longest grace a repository may ask for. See the class javadoc: under {@code stop-first} the
   * deployer's single worker waits it out, so a ceiling is what keeps one repository's statement
   * from stalling every other deployment.
   */
  static final Duration MAX_STOP_GRACE_PERIOD = Duration.ofHours(1);
  private static final String ROUTES = "routes";
  private static final String UPSTREAM_PORT = "upstream_port";
  private static final String NAVIGATION = "navigation";
  private static final String NAVIGATION_ENTRIES = "navigation-entries";
  private static final String HOST = "host";
  private static final String API_DOCS = "api-docs";
  static final int DEFAULT_UPSTREAM_PORT = 8080;

  /**
   * The slot every navigation entry names. Closed, ordered, and quoted back at a typo.
   *
   * <p><b>The ORDER is the document's</b>, not an implementation detail: qits-edge's own {@code
   * EdgeRoutes.SLOTS} holds the same words and sorts the rendered navigation by index, so the two
   * lists have to stay identical. {@code apps.details} sits immediately before {@code
   * frontends.details} because an app is a standalone application — a sibling kind of a
   * microfrontend rather than a group of its own after it — which makes the archetype run
   * services, daemons, libs, apps, frontends, cli, images.
   */
  private static final Set<String> SLOTS =
      new LinkedHashSet<>(
          List.of(
              "services.details",
              "daemons.details",
              "libs.details",
              "apps.details",
              "frontends.details",
              "cli.details",
              "images.details",
              "project.detail",
              "platform",
              "system"));

  /** The slot the retired singular {@code navigation} key maps into. */
  private static final String LEGACY_SLOT = "system";

  /** One DNS label: no dots, no leading or trailing hyphen, 63 characters at the most. */
  private static final String HOST_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

  /** What a label may never contain: the two separators of the entry list, and a line break. */
  private static final int LABEL_MAX_CHARS = 64;

  /**
   * A navigation entry's subpath: relative, lowercase, slash-separated segments. The charset
   * excludes the colon, dot and comma by construction, which is what keeps the entry list's
   * separators and the right-to-left parse safe around it.
   */
  private static final String SUBPATH =
      "[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*";

  private static final int SUBPATH_MAX_CHARS = 128;

  /** The api-docs path: an absolute lowercase path, never the bare root. */
  private static final String API_DOCS_PATH =
      "/(?:[a-z0-9]+(?:-[a-z0-9]+)*)(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*";

  private static final int API_DOCS_MAX_CHARS = 255;

  /** The first resource type. It is spelled in the file so a second one can arrive. */
  private static final String POSTGRESQL = "postgresql";

  /**
   * The second resource type: {@code idp:client}, a qits-idp service client. Its resource NAME is
   * fixed to this same word rather than repository-chosen — there is one idp per platform, so there
   * is nothing to name — which is what makes it reserved against {@code postgresql:idp}.
   */
  private static final String IDP = "idp";

  /** The one word {@code idp:} may be followed by. */
  private static final String IDP_CLIENT = "client";

  /** The first volume scope: a volume of this application's own, whose name is DERIVED. */
  private static final String PRIVATE = "private";

  /** The second: a platform-owned volume, named in full because it is not this application's. */
  private static final String SHARED = "shared";

  /**
   * Every shared volume the platform has — {@code qits.containers.shared-volumes} in qits-containers,
   * spelled here because a repository naming one that does not exist would get a volume docker
   * creates empty, which is the silent-loss failure this whole family exists to refuse.
   *
   * <p>Closed and quoted back at a typo, like {@link #SLOTS}. A fourth shared volume is a line here
   * and a line there, in the same commit.
   */
  private static final Set<String> SHARED_VOLUMES =
      new LinkedHashSet<>(List.of("qits_shared_dot_claude", "qits_shared_m2", "qits_shared_pnpm"));

  /** The one option a mount may carry — {@link ServiceExtras.Mount}'s grammar, and no more. */
  private static final String READ_ONLY = "ro";

  private DeploymentSpecParser() {}

  /**
   * @param source how the file is named back to a reader — the whole point of the error messages
   * @throws SpecException on anything this schema does not describe
   */
  public static DeploymentSpec parse(String yaml, String source) {
    String application = null;
    String renamedFrom = null;
    boolean availableOnEnv = false;
    List<String> deployBranches = List.of();
    String healthPath = null;
    String healthCmd = null;
    List<DeploymentSpec.ResourceSpec> resources = List.of();
    List<DeploymentSpec.VolumeSpec> volumes = List.of();
    List<ServiceExtras.Mount> binds = List.of();
    int bindsLine = 0;
    List<String> publishes = List.of();
    List<String> aliases = List.of();
    DeploymentDriver.UpdateOrder updateOrder = DeploymentDriver.UpdateOrder.START_FIRST;
    DeploymentDriver.PublishMode publishMode = DeploymentDriver.PublishMode.HOST;
    Duration stopGracePeriod = DeploymentDriver.DEFAULT_STOP_GRACE_PERIOD;
    List<String> routes = List.of();
    int upstreamPort = DEFAULT_UPSTREAM_PORT;
    String host = null;
    List<NavigationEntry> navigationEntries = List.of();
    String apiDocs = null;
    Set<String> seen = new HashSet<>();

    String[] lines = (yaml == null ? "" : yaml).split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String raw = lines[i];
      int lineNumber = i + 1;
      String line = stripComment(raw);
      if (line.isBlank() || line.strip().equals("---")) {
        continue;
      }
      if (Character.isWhitespace(line.charAt(0))) {
        throw error(source, lineNumber, "indented lines — this file has no nesting");
      }
      int colon = line.indexOf(':');
      if (colon < 1) {
        throw error(source, lineNumber, "expected `key: value`, got: " + line.strip());
      }
      String key = line.substring(0, colon).strip();
      String value = unquote(line.substring(colon + 1).strip());
      if (!seen.add(key)) {
        throw error(source, lineNumber, "duplicate key `" + key + "`");
      }
      switch (key) {
        case APPLICATION -> application = application(value, source, lineNumber);
        case RENAMED_FROM -> renamedFrom = renamedFrom(value, source, lineNumber);
        // Retired: read, dropped, never validated. Any value at all is legal, because a file at an
        // older sha carries whichever word its author wrote and nothing acts on any of them.
        case RETIRED_TARGET -> {}
        case AVAILABLE_ON_ENV -> availableOnEnv = bool(key, value, source, lineNumber);
        case DEPLOY_BRANCHES -> deployBranches = deployBranches(value, source, lineNumber);
        case HEALTH_PATH -> healthPath = healthPath(value, source, lineNumber);
        case HEALTH_CMD -> healthCmd = healthCmd(value, source, lineNumber);
        case RESOURCES -> resources = resources(value, source, lineNumber);
        case VOLUMES -> volumes = volumes(value, source, lineNumber);
        case BINDS -> {
          binds = binds(value, source, lineNumber);
          bindsLine = lineNumber;
        }
        case PUBLISHES -> publishes = publishes(value, source, lineNumber);
        case ALIASES -> aliases = aliases(value, source, lineNumber);
        case UPDATE_ORDER -> updateOrder = updateOrder(value, source, lineNumber);
        case PUBLISH_MODE -> publishMode = publishMode(value, source, lineNumber);
        case STOP_GRACE_PERIOD -> stopGracePeriod = stopGracePeriod(value, source, lineNumber);
        case ROUTES -> routes = routes(value, source, lineNumber);
        case UPSTREAM_PORT -> upstreamPort = upstreamPort(value, source, lineNumber);
        case HOST -> host = host(value, source, lineNumber);
        case NAVIGATION_ENTRIES ->
            navigationEntries = navigationEntries(value, source, lineNumber);
        case NAVIGATION -> navigationEntries = List.of(navigation(value, source, lineNumber));
        case API_DOCS -> apiDocs = apiDocs(value, source, lineNumber);
        default ->
            throw error(
                source,
                lineNumber,
                "unknown key `"
                    + key
                    + "` — this file knows "
                    + APPLICATION
                    + ", "
                    + RENAMED_FROM
                    + ", "
                    + RETIRED_TARGET
                    + ", "
                    + AVAILABLE_ON_ENV
                    + ", "
                    + DEPLOY_BRANCHES
                    + ", "
                    + HEALTH_PATH
                    + ", "
                    + HEALTH_CMD
                    + ", "
                    + RESOURCES
                    + ", "
                    + VOLUMES
                    + ", "
                    + BINDS
                    + ", "
                    + PUBLISHES
                    + ", "
                    + ALIASES
                    + ", "
                    + UPDATE_ORDER
                    + ", "
                    + PUBLISH_MODE
                    + ", "
                    + STOP_GRACE_PERIOD
                    + ", "
                    + ROUTES
                    + ", "
                    + UPSTREAM_PORT
                    + ", "
                    + HOST
                    + ", "
                    + NAVIGATION_ENTRIES
                    + ", "
                    + NAVIGATION
                    + " and "
                    + API_DOCS);
      }
    }

    if (healthCmd != null && healthPath != null) {
      throw new SpecException(
          source
              + ": `"
              + HEALTH_CMD
              + "` and `"
              + HEALTH_PATH
              + "` are alternatives — the command replaces the HTTP probe rather than adjusting"
              + " it, so a file setting both says two things about one gate. Keep the one that"
              + " describes this image.");
    }
    for (ServiceExtras.Mount bind : binds) {
      for (DeploymentSpec.VolumeSpec volume : volumes) {
        if (volume.target().equals(bind.target())) {
          // Two statements about one directory, and swarm refuses the create outright. Neither
          // can win silently: the file contradicts itself, and only its author knows which half
          // was meant.
          throw error(
              source,
              bindsLine,
              "`"
                  + BINDS
                  + "` mounts `"
                  + bind.source()
                  + "` at `"
                  + bind.target()
                  + "`, which `"
                  + VOLUMES
                  + "` already mounts a volume at — one directory takes one mount");
        }
      }
    }
    if (renamedFrom != null && renamedFrom.equals(application)) {
      // Only visible when `application` is stated: with it absent the effective name is the
      // repository's, which this parser has never been told. The downstream arm is inert for that
      // case anyway — a claim is transferred only when it belongs to a DIFFERENT application — so
      // so this refusal is the half that CAN be seen here, answered where it can be seen.
      throw new SpecException(
          source
              + ": `"
              + RENAMED_FROM
              + "` names `"
              + renamedFrom
              + "`, which is what `"
              + APPLICATION
              + "` already says this deploys as — an application was not renamed from itself. Name"
              + " the application it USED to be called, or drop the key.");
    }
    if (seen.contains(NAVIGATION) && seen.contains(NAVIGATION_ENTRIES)) {
      throw new SpecException(
          source
              + ": `"
              + NAVIGATION
              + "` and `"
              + NAVIGATION_ENTRIES
              + "` are two answers to one question. `"
              + NAVIGATION_ENTRIES
              + "` is the one that can say more than one placement; keep it alone.");
    }
    if (!navigationEntries.isEmpty() && routes.isEmpty()) {
      throw new SpecException(
          source
              + ": `"
              + (seen.contains(NAVIGATION) ? NAVIGATION : NAVIGATION_ENTRIES)
              + "` needs at least one `"
              + ROUTES
              + "` entry — navigation has to lead to a published route");
    }
    if (host != null && routes.isEmpty()) {
      throw new SpecException(
          source
              + ": `"
              + HOST
              + "` needs at least one `"
              + ROUTES
              + "` entry — a host with nothing behind it serves nobody");
    }
    if (apiDocs != null && !underARoute(apiDocs, routes)) {
      throw new SpecException(
          source
              + ": `"
              + API_DOCS
              + "` must sit under one of the published `"
              + ROUTES
              + "` entries — a document nobody can reach describes nothing. Got `"
              + apiDocs
              + "` beside "
              + (routes.isEmpty() ? "no routes at all" : String.join(",", routes)));
    }
    return new DeploymentSpec(
        availableOnEnv,
        deployBranches,
        healthPath,
        healthCmd,
        resources,
        volumes,
        updateOrder,
        publishMode,
        routes,
        upstreamPort,
        host,
        // The retired singular asks for no host of its own: a file nobody has rewritten yet has to
        // keep behaving exactly as it did, and a host derived from it would put every application
        // on a vhost the release that ships this parser never promised.
        seen.contains(HOST) || seen.contains(NAVIGATION_ENTRIES),
        navigationEntries,
        apiDocs,
        application,
        renamedFrom,
        stopGracePeriod,
        new DeclaredShape(binds, publishes, aliases));
  }

  /**
   * The name this repository deploys as. Checked with {@link PdIdentifiers#requireName}, the rule
   * every STORED name of this platform already passes, because that is what this value becomes: a
   * catalogue row's unique name, a docker service name, a network alias, an image path segment and
   * the tail of a postgres identifier. Uppercase, dots, slashes and spaces are all outside that
   * charset, so two names differing only in case cannot both be written.
   */
  private static String application(String value, String source, int line) {
    try {
      return PdIdentifiers.requireName(value, APPLICATION);
    } catch (RuntimeException e) {
      throw error(
          source,
          line,
          "`"
              + APPLICATION
              + "` is one lowercase name — letters, digits and inner dashes, at most 63 characters"
              + " — because it becomes a service name, a network alias, an image path segment and"
              + " a database name. Got: "
              + value);
    }
  }

  /**
   * The application name this one used to carry — a <b>declared predecessor</b>, and the only thing
   * that turns "the database is already provisioned for somebody else" from a refusal into a
   * transfer. See the class javadoc for why it is a declaration rather than an inference.
   *
   * <p>Checked with {@link PdIdentifiers#requireName}, the same rule {@code application} takes, and
   * for a reason that is one step removed from that one: this value is never stored and never
   * becomes an argv — it is <b>compared</b> against a stored application name. A value the charset
   * refuses is therefore a value that could match no row this schema has ever written, so a file
   * carrying one is asking for a transfer that cannot happen. Saying so at the line beats a
   * deployment that fails at provisioning with the collision message and no hint that the key was
   * the problem.
   */
  private static String renamedFrom(String value, String source, int line) {
    try {
      return PdIdentifiers.requireName(value, RENAMED_FROM);
    } catch (RuntimeException e) {
      throw error(
          source,
          line,
          "`"
              + RENAMED_FROM
              + "` is one lowercase application name — letters, digits and inner dashes, at most 63"
              + " characters — because it is compared against the application name a resource claim"
              + " was recorded under. Got: "
              + value);
    }
  }

  /** Under a published route: the route itself, or a path below it. */
  private static boolean underARoute(String path, List<String> routes) {
    for (String route : routes) {
      if (path.equals(route) || path.startsWith(route.endsWith("/") ? route : route + "/")) {
        return true;
      }
    }
    return false;
  }

  /** Public path prefixes, one per comma-separated entry. Navigation belongs to the first route. */
  private static List<String> routes(String value, String source, int line) {
    List<String> declared = new ArrayList<>();
    Set<String> seenRoutes = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String path = candidate.strip();
      if (!path.matches("/(?:[a-z0-9]+(?:-[a-z0-9]+)*)?(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*")) {
        throw error(
            source,
            line,
            "`" + ROUTES + "` entries are absolute lowercase path prefixes, got: " + path);
      }
      if (!seenRoutes.add(path)) {
        throw error(source, line, "`" + ROUTES + "` names `" + path + "` twice");
      }
      declared.add(path);
    }
    return List.copyOf(declared);
  }

  private static int upstreamPort(String value, String source, int line) {
    try {
      int port = Integer.parseInt(value);
      if (port < 1 || port > 65535) {
        throw new NumberFormatException();
      }
      return port;
    } catch (NumberFormatException e) {
      throw error(
          source,
          line,
          "`" + UPSTREAM_PORT + "` must be an integer from 1 to 65535, got: " + value);
    }
  }

  /**
   * The retired singular, mapped onto the list that replaced it: one entry in the {@code system}
   * slot, which is where a flat menu's options always were. The final colon is the separator, so a
   * visible label may itself contain one.
   */
  private static NavigationEntry navigation(String value, String source, int line) {
    int separator = value.lastIndexOf(':');
    String label = separator < 1 ? "" : value.substring(0, separator).strip();
    String positionText = separator < 0 ? "" : value.substring(separator + 1).strip();
    if (label.isBlank() || label.length() > 120 || label.indexOf('\n') >= 0 || label.indexOf('\r') >= 0) {
      throw error(
          source,
          line,
          "`" + NAVIGATION + "` needs a non-blank label of at most 120 characters");
    }
    try {
      int position = Integer.parseInt(positionText);
      if (position < 1) {
        throw new NumberFormatException();
      }
      return new NavigationEntry(LEGACY_SLOT, label, position);
    } catch (NumberFormatException e) {
      throw error(
          source,
          line,
          "`" + NAVIGATION + "` ends with a positive position, got: " + value);
    }
  }

  /**
   * Where an application asks to appear: {@code <slot>.<Label>:<position>[=<subpath>]},
   * comma-separated, <b>read from the right</b> — the last colon separates the tail, the tail's
   * first {@code =} separates the position from the optional subpath, the last dot of what remains
   * separates the label. That order is the grammar's whole trick: a label may carry a colon or an
   * equals sign ({@code services.details.CI:v2:1}) because the tail was already taken off the end,
   * and the slot stays a closed word because the label was already taken off the front of nothing.
   * The subpath's own charset has no colon, dot or comma, which is what keeps every separator here
   * safe around it.
   *
   * <p>A blank entry, an unknown slot and a second entry for one {@code (slot, label)} pair are all
   * errors. The slot is quoted back with the whole vocabulary, because a typo here is an application
   * that silently appears nowhere.
   *
   * <p><b>The claim is the pair, not the slot.</b> One application legitimately contributes several
   * ROWS to one heading — qits-workspaces hangs Workspaces and Editor under the project node — and
   * the slot alone as the key refused that spec outright. What stays refused is the same label
   * twice in one slot, which is one row asked for twice and no shell can draw two of. Positions are
   * NOT part of the key: {@link NavigationEntry} says a repeated number is an ordinary tie the
   * consumer breaks by label, and it already breaks that tie between applications, so refusing it
   * within one would be a rule the document does not have.
   */
  private static List<NavigationEntry> navigationEntries(String value, String source, int line) {
    List<NavigationEntry> declared = new ArrayList<>();
    Set<String> claimed = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String entry = candidate.strip();
      if (entry.isBlank()) {
        throw error(source, line, "`" + NAVIGATION_ENTRIES + "` has a blank entry");
      }
      int colon = entry.lastIndexOf(':');
      String tail = colon < 0 ? "" : entry.substring(colon + 1);
      int equals = tail.indexOf('=');
      String positionText = (equals < 0 ? tail : tail.substring(0, equals)).strip();
      String subpath = equals < 0 ? null : tail.substring(equals + 1).strip();
      int position;
      try {
        position = Integer.parseInt(positionText);
        if (colon < 1 || position < 1) {
          throw new NumberFormatException();
        }
      } catch (RuntimeException e) {
        throw error(
            source,
            line,
            "`"
                + NAVIGATION_ENTRIES
                + "` entries are `<slot>.<Label>:<position>[=<subpath>]` and the position is a"
                + " positive integer, got: "
                + entry);
      }
      if (subpath != null
          && (subpath.length() > SUBPATH_MAX_CHARS || !subpath.matches(SUBPATH))) {
        throw error(
            source,
            line,
            "`"
                + NAVIGATION_ENTRIES
                + "` subpaths are relative lowercase paths — segments of letters, digits and inner"
                + " dashes joined by `/`, no leading or trailing slash, at most "
                + SUBPATH_MAX_CHARS
                + " characters — got: "
                + entry);
      }
      String placement = entry.substring(0, colon).strip();
      int dot = placement.lastIndexOf('.');
      if (dot < 0) {
        // No dot at all: the entry named no slot, so the vocabulary is what it is missing.
        throw unknownSlot(entry, entry, source, line);
      }
      String label = placement.substring(dot + 1).strip();
      String slot = placement.substring(0, dot).strip();
      if (label.isBlank()
          || label.length() > LABEL_MAX_CHARS
          || label.indexOf('\n') >= 0
          || label.indexOf('\r') >= 0) {
        throw error(
            source,
            line,
            "`"
                + NAVIGATION_ENTRIES
                + "` needs a non-blank label of at most "
                + LABEL_MAX_CHARS
                + " characters after the slot, got: "
                + entry);
      }
      if (!SLOTS.contains(slot)) {
        throw unknownSlot(slot, entry, source, line);
      }
      if (!claimed.add(slot + "." + label)) {
        throw error(
            source,
            line,
            "`" + NAVIGATION_ENTRIES + "` claims `" + slot + "." + label + "` twice");
      }
      declared.add(new NavigationEntry(slot, label, position, subpath));
    }
    return List.copyOf(declared);
  }

  /** The vocabulary, quoted back at whatever was written instead of one of its words. */
  private static SpecException unknownSlot(String got, String entry, String source, int line) {
    return error(
        source,
        line,
        "`"
            + NAVIGATION_ENTRIES
            + "` knows the slots "
            + String.join(", ", SLOTS)
            + ", and an entry is `<slot>.<Label>:<position>[=<subpath>]`; got: "
            + (got.isBlank() ? entry : got));
  }

  /**
   * Where the browsable API document lives: an absolute lowercase path, never the bare root. That
   * it also sits under one of the published routes is a cross-key question, answered after the
   * whole file is read.
   */
  private static String apiDocs(String value, String source, int line) {
    if (value.length() > API_DOCS_MAX_CHARS || !value.matches(API_DOCS_PATH)) {
      throw error(
          source,
          line,
          "`"
              + API_DOCS
              + "` is an absolute lowercase path of at most "
              + API_DOCS_MAX_CHARS
              + " characters — segments of letters, digits and inner dashes, never `/` alone —"
              + " got: "
              + value);
    }
    return value;
  }

  /**
   * The one DNS label this application is served at. A label and never a name: the edge builds the
   * authority around it, so the same value describes the application on every environment and every
   * domain the platform has.
   */
  private static String host(String value, String source, int line) {
    if (!value.matches(HOST_LABEL)) {
      throw error(
          source,
          line,
          "`"
              + HOST
              + "` is one DNS label — lowercase letters, digits and inner dashes, at most 63"
              + " characters — got: "
              + value);
    }
    return value;
  }

  /**
   * The entries in the spelling a row stores them in, which is the file's own. One spelling for the
   * file, the column and psql means the snapshot a self-update announces from its successor's boot
   * cannot describe the deployment differently from the file it was read from.
   */
  public static String joinEntries(List<NavigationEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return null;
    }
    List<String> spelled = new ArrayList<>();
    for (NavigationEntry entry : entries) {
      spelled.add(
          entry.slot()
              + "."
              + entry.label()
              + ":"
              + entry.position()
              + (entry.subpath() == null ? "" : "=" + entry.subpath()));
    }
    return String.join(",", spelled);
  }

  /**
   * The stored spelling back into the list it was joined from, by the same rule the file is read
   * with. Blank is no entries — the answer most applications give.
   *
   * @throws SpecException on a value this parser did not write, which is a corrupted row rather
   *     than a repository's mistake
   */
  public static List<NavigationEntry> parseEntries(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    return navigationEntries(stored, "the stored navigation entries", 1);
  }

  /**
   * Where a published host port is held. Two values and no third, and the file spells them the way
   * docker does ({@code host}, {@code ingress}) so neither side has to translate.
   *
   * <p>Refused rather than defaulted, like every other value here. The difference between the two
   * is whether a replacement can start while its predecessor still serves, and a typo answered with
   * a silent default is exactly what this parser exists not to do.
   */
  private static DeploymentDriver.PublishMode publishMode(String value, String source, int line) {
    for (DeploymentDriver.PublishMode candidate : DeploymentDriver.PublishMode.values()) {
      if (candidate.spelling().equals(value)) {
        return candidate;
      }
    }
    throw error(
        source,
        line,
        "`"
            + PUBLISH_MODE
            + "` must be `"
            + DeploymentDriver.PublishMode.HOST.spelling()
            + "` or `"
            + DeploymentDriver.PublishMode.INGRESS.spelling()
            + "`, got: "
            + value);
  }

  /**
   * How long a stopped task keeps what it is serving: a duration in docker's spelling, more than
   * zero and at most {@link #MAX_STOP_GRACE_PERIOD}. See {@link #GRACE_DURATION} for the grammar.
   *
   * <p>Refused rather than defaulted, like every other value here: a typo answered with thirty
   * seconds is exactly the cut download this key exists to stop, with nothing to read about why.
   * Zero is refused as well, because it is not "no grace" to docker but an immediate SIGKILL.
   */
  private static Duration stopGracePeriod(String value, String source, int line) {
    Matcher matcher = GRACE_DURATION.matcher(value);
    Duration grace = null;
    if (!value.isEmpty() && matcher.matches()) {
      try {
        grace =
            Duration.ofHours(group(matcher, 1))
                .plusMinutes(group(matcher, 2))
                .plusSeconds(group(matcher, 3))
                .plusMillis(group(matcher, 4));
      } catch (ArithmeticException e) {
        grace = null;
      }
    }
    if (grace == null || grace.isZero() || grace.isNegative()) {
      throw error(
          source,
          line,
          "`"
              + STOP_GRACE_PERIOD
              + "` must be a duration above zero in docker's spelling — `90s`, `10m`, `9m30s`,"
              + " `1h` — got: "
              + value);
    }
    if (grace.compareTo(MAX_STOP_GRACE_PERIOD) > 0) {
      throw error(
          source,
          line,
          "`"
              + STOP_GRACE_PERIOD
              + "` may be at most 1h — under stop-first every other deployment waits it out —"
              + " got: "
              + value);
    }
    return grace;
  }

  /** One unit's count, or zero for a unit the value did not name. */
  private static long group(Matcher matcher, int group) {
    String digits = matcher.group(group);
    if (digits == null) {
      return 0;
    }
    if (digits.length() > 9) {
      // Far past the ceiling in any unit, and short of overflowing the arithmetic above.
      throw new ArithmeticException(digits);
    }
    return Long.parseLong(digits);
  }

  /**
   * How a replacement may overlap what it replaces. Two values and no third: the file is written by
   * a person in the orchestrator's own spelling ({@code start-first}, {@code stop-first}) and the
   * enum is read by a machine, so neither has to spell the other's convention.
   *
   * <p>An unrecognised value is refused rather than defaulted, like every other value here: the
   * difference between the two is whether an application is ever two processes at once, and
   * answering that with a silent default is exactly what this parser exists not to do.
   */
  private static DeploymentDriver.UpdateOrder updateOrder(String value, String source, int line) {
    for (DeploymentDriver.UpdateOrder candidate : DeploymentDriver.UpdateOrder.values()) {
      if (candidate.spelling().equals(value)) {
        return candidate;
      }
    }
    throw error(
        source,
        line,
        "`"
            + UPDATE_ORDER
            + "` must be `"
            + DeploymentDriver.UpdateOrder.START_FIRST.spelling()
            + "` or `"
            + DeploymentDriver.UpdateOrder.STOP_FIRST.spelling()
            + "`, got: "
            + value);
  }

  /**
   * The resources a repository asks to have provisioned before its container starts:
   * {@code postgresql:<name>[:<database>]} or {@code idp:client}, comma-separated. One line,
   * because this file has no YAML sequences — and neither a type, a name nor a database may
   * contain a comma or a colon, which is what makes both separators safe.
   *
   * <p>A missing third segment on a {@code postgresql} entry means "the convention", not "no
   * database": the default is {@code qits_} plus the application name without its {@code qits-}
   * prefix, and it is resolved by {@code DeployService.register}, which is the first caller that
   * knows the application's name. Null travels out of here as that statement.
   *
   * <p>{@code idp:client} names no resource of its own — there is one idp per platform, so there is
   * nothing to name — and its resource name is the fixed word {@code idp}, which is why {@code
   * postgresql:idp} is refused: a repository cannot shadow the reserved name with the other type.
   *
   * <p>Both duplicates are refused, and only one of the two postgres cases can be caught here. A
   * repeated <b>name</b> would make one env triple silently win over another (this also catches
   * {@code idp:client} written twice, since its name is always {@code idp}); a repeated
   * <b>literal database</b> would point two of a repository's own resources at one store. The
   * second form a defaulted database can also take — two resources whose names both default to the
   * same thing — is caught after resolution, where the defaults exist.
   */
  private static List<DeploymentSpec.ResourceSpec> resources(String value, String source, int line) {
    List<DeploymentSpec.ResourceSpec> declared = new ArrayList<>();
    Set<String> names = new HashSet<>();
    Set<String> databases = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String entry = candidate.strip();
      if (entry.isBlank()) {
        // `resources:` with nothing after it, or a trailing comma: a writer who meant to say
        // something. A silent empty answer is exactly what this parser exists to refuse.
        throw error(source, line, "`" + RESOURCES + "` has a blank entry");
      }
      String[] parts = entry.split(":", -1);
      if (parts.length < 2 || parts.length > 3) {
        throw error(
            source,
            line,
            "`"
                + RESOURCES
                + "` entries are `"
                + POSTGRESQL
                + ":<name>[:<database>]` or `"
                + IDP
                + ":"
                + IDP_CLIENT
                + "`, got: "
                + entry);
      }
      if (IDP.equals(parts[0])) {
        if (parts.length != 2 || !IDP_CLIENT.equals(parts[1])) {
          throw error(
              source,
              line,
              "`" + RESOURCES + "` knows only `" + IDP + ":" + IDP_CLIENT + "` for the type `" + IDP
                  + "`, got: " + entry);
        }
        if (!names.add(IDP)) {
          throw error(source, line, "`" + RESOURCES + "` names `" + IDP + "` twice");
        }
        declared.add(
            new DeploymentSpec.ResourceSpec(IDP, null, DeploymentSpec.ResourceSpec.Type.IDP_CLIENT));
        continue;
      }
      if (!POSTGRESQL.equals(parts[0])) {
        throw error(
            source,
            line,
            "`"
                + RESOURCES
                + "` knows the types `"
                + POSTGRESQL
                + "` and `"
                + IDP
                + "` and no others, got: "
                + parts[0]);
      }
      String name;
      try {
        name = PdIdentifiers.requireResourceName(parts[1]);
      } catch (RuntimeException e) {
        throw error(
            source,
            line,
            "`"
                + RESOURCES
                + "` names are lowercase letters, digits and inner dashes (max 32), got: "
                + parts[1]);
      }
      if (IDP.equals(name)) {
        throw error(
            source,
            line,
            "`" + RESOURCES + "` name `" + IDP + "` is reserved for `" + IDP + ":" + IDP_CLIENT + "`");
      }
      String database = null;
      if (parts.length == 3) {
        try {
          database = PdIdentifiers.requireDatabaseName(parts[2]);
        } catch (RuntimeException e) {
          throw error(
              source,
              line,
              "`"
                  + RESOURCES
                  + "` databases are `qits_` followed by lowercase letters, digits and underscores"
                  + " (max 63), got: "
                  + parts[2]);
        }
      }
      if (!names.add(name)) {
        throw error(source, line, "`" + RESOURCES + "` names `" + name + "` twice");
      }
      if (database != null && !databases.add(database)) {
        throw error(source, line, "`" + RESOURCES + "` names the database `" + database + "` twice");
      }
      declared.add(new DeploymentSpec.ResourceSpec(name, database));
    }
    return List.copyOf(declared);
  }

  /**
   * The volumes a repository asks to have mounted into its container: {@code
   * private:<name>:<target>[:ro]} or {@code shared:<volume>:<target>[:ro]}, comma-separated. One
   * line, because this file has no YAML sequences — and neither a scope, a name nor a target may
   * contain a comma or a colon, which is what makes both separators safe.
   *
   * <p><b>A {@code private} volume's name is DERIVED and this parser deliberately cannot finish
   * it.</b> What travels out of here is the {@code <name>} segment alone; the volume is {@code
   * <application>-<name>}, resolved by {@code DeployService.register} — the first caller that knows
   * the application's name — exactly as a resource's defaulted database is. That derivation is the
   * isolation: a repository that could write a literal volume name could write a sibling's.
   *
   * <p><b>A {@code shared} volume is named in full, out of a closed vocabulary.</b> It is the
   * platform's rather than this application's, so there is nothing to derive it from — and an
   * unknown one is refused with the whole list quoted back, because docker answers an unknown
   * volume name by creating an empty one, and a container that boots with an empty volume passes
   * its health gate and has lost its data.
   *
   * <p><b>There is no {@code bind} word here.</b> A host path is a different statement from a
   * volume — its name is not derived and not drawn from a closed list — so it has a key of its own,
   * {@link #binds}, rather than a third scope here. See the class javadoc.
   *
   * <p>{@code :ro} is the only option, which is {@link ServiceExtras.Mount}'s grammar and not a
   * narrowing of it. Both duplicates are refused: a repeated <b>target</b> would be two volumes over
   * one directory, which no orchestrator can honour, and a repeated <b>name</b> would be one volume
   * asked for twice — the second mount point a person meant to write elsewhere.
   */
  private static List<DeploymentSpec.VolumeSpec> volumes(String value, String source, int line) {
    List<DeploymentSpec.VolumeSpec> declared = new ArrayList<>();
    Set<String> names = new HashSet<>();
    Set<String> targets = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String entry = candidate.strip();
      if (entry.isBlank()) {
        // `volumes:` with nothing after it, or a trailing comma: a writer who meant to say
        // something. A silent empty answer is exactly what this parser exists to refuse.
        throw error(source, line, "`" + VOLUMES + "` has a blank entry");
      }
      String[] parts = entry.split(":", -1);
      if (parts.length < 3 || parts.length > 4) {
        throw error(source, line, "`" + VOLUMES + "` entries are " + grammar() + ", got: " + entry);
      }
      boolean shared;
      if (SHARED.equals(parts[0])) {
        shared = true;
      } else if (PRIVATE.equals(parts[0])) {
        shared = false;
      } else {
        throw error(
            source,
            line,
            "`"
                + VOLUMES
                + "` knows the types `"
                + PRIVATE
                + "` and `"
                + SHARED
                + "` and no others — a host path is a `"
                + BINDS
                + "` entry rather than a volume, got: "
                + parts[0]);
      }
      String name = shared ? sharedVolume(parts[1], entry, source, line)
          : privateVolume(parts[1], entry, source, line);
      String target;
      try {
        target = DeploymentIdentifiers.requireMountTarget(parts[2]);
      } catch (RuntimeException e) {
        throw error(
            source,
            line,
            "`"
                + VOLUMES
                + "` targets are absolute paths inside the container — segments of letters, digits,"
                + " dots, underscores and inner dashes, never `/` alone — got: "
                + parts[2]);
      }
      if (parts.length == 4 && !READ_ONLY.equals(parts[3])) {
        throw error(
            source,
            line,
            "`" + parts[3] + "` is not a `" + VOLUMES + "` option here — only `" + READ_ONLY
                + "` is");
      }
      // The scope is part of the key: `private:m2` and `shared:qits_shared_m2` are two volumes and
      // not one, whatever the segment after the colon happens to spell.
      if (!names.add(parts[0] + ":" + name)) {
        throw error(source, line, "`" + VOLUMES + "` names `" + name + "` twice");
      }
      if (!targets.add(target)) {
        throw error(source, line, "`" + VOLUMES + "` names the target `" + target + "` twice");
      }
      declared.add(
          new DeploymentSpec.VolumeSpec(
              shared
                  ? DeploymentSpec.VolumeSpec.Scope.SHARED
                  : DeploymentSpec.VolumeSpec.Scope.PRIVATE,
              name,
              target,
              parts.length == 4));
    }
    return List.copyOf(declared);
  }

  /**
   * Host bind mounts: {@code <abs-host-path>:<abs-target>[:ro]}, comma-separated — deployment
   * config's {@code mounts[n] bind:…}, minus the word {@code bind}, because this key holds nothing
   * else.
   *
   * <p>Both paths take {@link DeploymentIdentifiers#requireMountTarget}'s charset, which is
   * stricter than deployment config's ever was: this value is repository-authored and spliced into
   * a comma-separated {@code --mount} argument, so a stray comma or space would forge a field. A
   * repeated TARGET is refused (two mounts on one directory is a create swarm refuses); a repeated
   * host path at two targets is allowed, because that is a thing a person can mean.
   */
  private static List<ServiceExtras.Mount> binds(String value, String source, int line) {
    List<ServiceExtras.Mount> declared = new ArrayList<>();
    Set<String> targets = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String entry = candidate.strip();
      if (entry.isBlank()) {
        throw error(source, line, "`" + BINDS + "` has a blank entry");
      }
      String[] parts = entry.split(":", -1);
      if (parts.length < 2 || parts.length > 3) {
        throw error(
            source,
            line,
            "`"
                + BINDS
                + "` entries are `<abs-host-path>:<abs-target>[:"
                + READ_ONLY
                + "]`, got: "
                + entry);
      }
      String hostPath = mountPath(parts[0], "host paths", entry, source, line);
      String target = mountPath(parts[1], "targets", entry, source, line);
      if (parts.length == 3 && !READ_ONLY.equals(parts[2])) {
        throw error(
            source,
            line,
            "`" + parts[2] + "` is not a `" + BINDS + "` option here — only `" + READ_ONLY + "` is");
      }
      if (!targets.add(target)) {
        throw error(source, line, "`" + BINDS + "` names the target `" + target + "` twice");
      }
      declared.add(
          new ServiceExtras.Mount(ServiceExtras.MountKind.BIND, hostPath, target, parts.length == 3));
    }
    return List.copyOf(declared);
  }

  /** One absolute path of a bind, held to the mount-target charset. */
  private static String mountPath(
      String value, String what, String entry, String source, int line) {
    try {
      return DeploymentIdentifiers.requireMountTarget(value);
    } catch (RuntimeException e) {
      throw error(
          source,
          line,
          "`"
              + BINDS
              + "` "
              + what
              + " are absolute paths — segments of letters, digits, dots, underscores and inner"
              + " dashes, never `/` alone — got: "
              + (value.isBlank() ? entry : value));
    }
  }

  /**
   * Published host ports: {@code [<ipv4>:]<host-port>:<port>[/tcp|/udp]}, comma-separated — read by
   * {@link ServiceExtras#publish}, the one spelling of the grammar, so the file and deployment
   * config can never disagree about what a publish is. A port named with an ip that is not {@code
   * 0.0.0.0} parses here and is refused by the swarm driver, as config's is: swarm's publish has no
   * ip field. A repeated published port and protocol is refused — swarm would refuse the create.
   *
   * <p>Kept AS WRITTEN, {@link DeclaredShape#QITS_PORT_TOKEN} included — the aliases' arrangement:
   * the host HTTP port is an installation's choice this parser cannot know, so the substitution is
   * {@link DeclaredShape#publishesFor}'s, at deploy time, and the grammar is checked here with a
   * stand-in port and again after the real one. The token is accepted as the whole HOST port and
   * nowhere else (a container port is the image's own fact, not the installation's), and any other
   * {@code $} is refused: a placeholder nothing replaces is a publish swarm cannot parse.
   */
  private static List<String> publishes(String value, String source, int line) {
    List<String> declared = new ArrayList<>();
    Set<String> ports = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String entry = candidate.strip();
      if (entry.isBlank()) {
        throw error(source, line, "`" + PUBLISHES + "` has a blank entry");
      }
      boolean placeholder = entry.contains(DeclaredShape.QITS_PORT_TOKEN);
      if (placeholder && !hostPortIsTheToken(entry)) {
        throw error(
            source,
            line,
            "`"
                + PUBLISHES
                + "` takes "
                + DeclaredShape.QITS_PORT_TOKEN
                + " as the whole host port and nowhere else, got: "
                + entry);
      }
      String substituted = entry.replace(DeclaredShape.QITS_PORT_TOKEN, "8080");
      if (substituted.indexOf('$') >= 0) {
        throw error(
            source,
            line,
            "`"
                + PUBLISHES
                + "` knows the one placeholder "
                + DeclaredShape.QITS_PORT_TOKEN
                + " and no other, got: "
                + entry);
      }
      ServiceExtras.Publish publish;
      try {
        publish = ServiceExtras.publish("`" + PUBLISHES + "`", substituted);
      } catch (ServiceExtras.Refused e) {
        throw error(source, line, e.getMessage());
      }
      // The placeholder is its own port here: two of it is one port twice whatever it resolves to,
      // and a clash with a literal port is the substitution's to find, against the real value.
      String protocol = publish.protocol() == null ? "tcp" : publish.protocol();
      String portKey =
          placeholder ? DeclaredShape.QITS_PORT_TOKEN + "/" + protocol : publish.portKey();
      if (!ports.add(portKey)) {
        throw error(source, line, "`" + PUBLISHES + "` publishes `" + portKey + "` twice");
      }
      declared.add(entry);
    }
    return List.copyOf(declared);
  }

  /** Whether {@code entry}'s host-port segment is exactly the token, and the token appears once. */
  private static boolean hostPortIsTheToken(String entry) {
    String token = DeclaredShape.QITS_PORT_TOKEN;
    if (entry.indexOf(token) != entry.lastIndexOf(token)) {
      return false;
    }
    int slash = entry.indexOf('/');
    String ports = slash >= 0 ? entry.substring(0, slash) : entry;
    String[] parts = ports.split(":", -1);
    return parts.length >= 2 && token.equals(parts[parts.length - 2]);
  }

  /**
   * Extra names on the shared network, comma-separated, kept AS WRITTEN — {@link
   * DeclaredShape#ENVIRONMENT_TOKEN} included, because this parser never knows which tier it reads
   * for. Each is checked here by {@link ServiceExtras}' alias rule with the token stood in for (no
   * comma, no whitespace), and again after the real substitution at deploy time. Any {@code $} that
   * is not part of the token is refused: a placeholder nothing replaces would be registered
   * literally, a name nothing dials.
   */
  private static List<String> aliases(String value, String source, int line) {
    List<String> declared = new ArrayList<>();
    Set<String> seenAliases = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String alias = candidate.strip();
      if (alias.isBlank()) {
        throw error(source, line, "`" + ALIASES + "` has a blank entry");
      }
      String substituted = alias.replace(DeclaredShape.ENVIRONMENT_TOKEN, "env");
      if (substituted.indexOf('$') >= 0) {
        throw error(
            source,
            line,
            "`"
                + ALIASES
                + "` knows the one placeholder "
                + DeclaredShape.ENVIRONMENT_TOKEN
                + " and no other, got: "
                + alias);
      }
      try {
        ServiceExtras.alias("`" + ALIASES + "`", substituted);
      } catch (ServiceExtras.Refused e) {
        throw error(source, line, "`" + ALIASES + "`: '" + alias + "' is not one name");
      }
      if (!seenAliases.add(alias)) {
        throw error(source, line, "`" + ALIASES + "` names `" + alias + "` twice");
      }
      declared.add(alias);
    }
    return List.copyOf(declared);
  }

  /**
   * The {@code <name>} half of a private volume — the segment the application's own name is
   * prefixed to. An absolute path here is the mistake worth its own sentence: it is a repository
   * reaching for a host bind, which this grammar does not have.
   */
  private static String privateVolume(String value, String entry, String source, int line) {
    if (value.startsWith("/")) {
      throw error(
          source,
          line,
          "`"
              + VOLUMES
              + "` takes a volume name after `"
              + PRIVATE
              + ":`, not the path `"
              + value
              + "` — a host path is a `"
              + BINDS
              + "` entry, never a volume");
    }
    try {
      return DeploymentIdentifiers.requireVolumeName(value);
    } catch (RuntimeException e) {
      throw error(
          source,
          line,
          "`"
              + VOLUMES
              + "` names are lowercase letters, digits and inner dashes (max 32) — the volume is"
              + " `<application>-<name>` — got: "
              + value);
    }
  }

  /** One of the platform's own volumes, quoted back with the whole vocabulary at a typo. */
  private static String sharedVolume(String value, String entry, String source, int line) {
    if (!SHARED_VOLUMES.contains(value)) {
      throw error(
          source,
          line,
          "`"
              + VOLUMES
              + "` knows the shared volumes "
              + String.join(", ", SHARED_VOLUMES)
              + " and no others — a volume this platform does not have is one docker creates empty"
              + " — got: "
              + (value.isBlank() ? entry : value));
    }
    return value;
  }

  /** The two entry shapes, spelled once so every refusal quotes the same sentence back. */
  private static String grammar() {
    return "`"
        + PRIVATE
        + ":<name>:<target>[:"
        + READ_ONLY
        + "]` or `"
        + SHARED
        + ":<volume>:<target>[:"
        + READ_ONLY
        + "]`";
  }

  private static boolean bool(String key, String value, String source, int line) {
    if ("true".equals(value)) {
      return true;
    }
    if ("false".equals(value)) {
      return false;
    }
    throw error(source, line, "`" + key + "` must be `true` or `false`, got: " + value);
  }

  /**
   * The comma-separated ref list. A YAML sequence would need a parser this file deliberately does
   * not have, so the refs share one line — and a ref cannot contain a comma, which is what makes
   * the separator safe.
   *
   * <p>Every element is validated, and a blank one fails: {@code deploy_branches:} with nothing
   * after it, or a trailing comma, is a writer who meant to say something. A silent empty answer is
   * exactly the shape this parser exists to refuse.
   */
  private static List<String> deployBranches(String value, String source, int line) {
    List<String> refs = new ArrayList<>();
    for (String candidate : value.split(",", -1)) {
      String ref = candidate.strip();
      try {
        refs.add(PdIdentifiers.requireBranch(ref));
      } catch (RuntimeException e) {
        throw error(source, line, "`" + DEPLOY_BRANCHES + "` is not a plain ref name: " + ref);
      }
    }
    return List.copyOf(refs);
  }

  /**
   * The health gate's URL path inside the container, checked here with the same rule the API uses —
   * an absolute path and nothing a shell or an argv would read as punctuation, because this value
   * ends up in a {@code --health-cmd}.
   */
  private static String healthPath(String value, String source, int line) {
    try {
      return PdIdentifiers.requireHealthPath(value);
    } catch (RuntimeException e) {
      throw error(source, line, "`" + HEALTH_PATH + "` is not an absolute URL path: " + value);
    }
  }

  /**
   * The readiness probe an image declares for itself, passed to docker verbatim and run by a shell
   * inside the container. Unlike {@link #healthPath} it gets no charset — a probe worth writing is
   * a command ({@code pg_isready -U postgres || exit 1}), and an allowlist would refuse it. What
   * makes that safe is that the command grants the repository nothing it does not already have: it
   * runs in that repository's own container, and it is one argv element rather than a string this
   * component splits. See {@link DeploymentIdentifiers#requireHealthCmd}.
   */
  private static String healthCmd(String value, String source, int line) {
    try {
      return DeploymentIdentifiers.requireHealthCmd(value);
    } catch (RuntimeException e) {
      throw error(
          source,
          line,
          "`"
              + HEALTH_CMD
              + "` must be one non-blank line of at most "
              + DeploymentIdentifiers.HEALTH_CMD_MAX_CHARS
              + " characters, got: "
              + value);
    }
  }

  /**
   * Drops a {@code #} comment. A {@code #} only starts one at the beginning of the line or after
   * whitespace, which is YAML's own rule and the reason {@code deploy_branches: fix#123} keeps its
   * hash.
   */
  private static String stripComment(String line) {
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
        && value.charAt(value.length() - 1) == value.charAt(0)) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static SpecException error(String source, int line, String what) {
    return new SpecException(source + " line " + line + ": " + what);
  }
}
