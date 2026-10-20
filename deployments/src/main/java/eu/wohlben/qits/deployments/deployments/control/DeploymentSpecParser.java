package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.deployments.events.NavigationEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The strict reader of {@code .config/qits/deployments.yml}. Seventeen scalar keys, no nesting, no
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
 * update_order: start-first            # default | stop-first for anything single-writer
 * publish_mode: host                   # default | ingress for a port the routing mesh holds
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
 * <p><b>HOST BINDS ARE DELIBERATELY NOT IN THIS GRAMMAR, and completing it later would be a
 * mistake.</b> {@code bind:/var/run/docker.sock:…} stays in deployment config
 * ({@code qits.deployments.extras.<app>.mounts[i]}, see {@link ServiceExtras}) and belongs
 * there: a host path is a statement about the MACHINE — which socket exists, which directory the
 * operator laid out — and it is true of a platform rather than of an application. That is the line
 * this key draws. A repository that could name a host path could mount any of it.
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
  private static final String UPDATE_ORDER = "update_order";
  private static final String PUBLISH_MODE = "publish_mode";
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
    DeploymentDriver.UpdateOrder updateOrder = DeploymentDriver.UpdateOrder.START_FIRST;
    DeploymentDriver.PublishMode publishMode = DeploymentDriver.PublishMode.HOST;
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
        case UPDATE_ORDER -> updateOrder = updateOrder(value, source, lineNumber);
        case PUBLISH_MODE -> publishMode = publishMode(value, source, lineNumber);
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
                    + UPDATE_ORDER
                    + ", "
                    + PUBLISH_MODE
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
        renamedFrom);
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
   * <p><b>There is no {@code bind} word here and there must never be one.</b> A host path is
   * platform topology — a statement about the machine rather than about the application — and it
   * stays in deployment config, which is the trust domain that already holds the docker socket. See
   * the class javadoc.
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
                + "` and no others — a host path is deployment config rather than a repository's"
                + " own declaration, got: "
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
              + "` — a host path is deployment config, never a repository's own declaration");
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
