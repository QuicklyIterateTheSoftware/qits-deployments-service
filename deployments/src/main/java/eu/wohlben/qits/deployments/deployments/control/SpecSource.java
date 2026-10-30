package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.events.NavigationEntry;
import java.time.Duration;
import java.util.List;

/**
 * The seam that fetches a repository's deployment spec at a commit — the {@link DeploymentDriver}
 * arrangement again: this module owns the port and the state machine that calls it, {@code service}
 * owns the one implementation that speaks HTTP, and the suites install a scripted fake so a clone's
 * {@code mvn verify} reaches no network.
 *
 * <p>The seam exists because this is the component's <b>one outbound HTTP call</b>. Keeping the
 * client out of a domain module is the same rule that keeps docker out of one: the orchestration
 * must be testable without either. The merge removed the second such client — the topology used to
 * be another service and is now a repository query — so this is the only one left.
 */
public interface SpecSource {

  /** The file every repository may carry, at the path this reads it from. */
  String SPEC_PATH = ".config/qits/deployments.yml";

  /**
   * The second file a repository may carry: what its application DECLARES about its own
   * configuration — the defaults, the keys it needs, and what a platform may override.
   *
   * <p>It sits beside {@link #SPEC_PATH} and is read the same way at the same rev, and that is the
   * whole of what the two have in common. {@code deployments.yml} is read BY this component and
   * decides where a container runs; this one is read THROUGH it and belongs to qits-configuration,
   * which is the only thing on the platform that knows the grammar. See {@link #readDeclaration}.
   */
  String DECLARATION_PATH = ".config/qits/configuration.yml";

  /**
   * The rev a released version is read at — <b>fully qualified, and that is the point</b>.
   *
   * <p>The git host resolves a bare {@code 2026.903.113443} perfectly well, but a bare name is
   * whatever the repository happens to hold under it: a branch of that name would win, and a
   * repository is free to have one. {@code refs/tags/<version>} can only ever be the tag the
   * release pushed, which is the ref whose contents this deployment is supposed to be.
   */
  static String tagRev(String version) {
    return "refs/tags/" + version;
  }

  /**
   * Read the spec a repository declares at {@code rev}.
   *
   * <p><b>It takes the whole {@link RepositoryRef} rather than one id</b>, because the git host
   * serves the same blob under two addresses and only the caller knows which one this release has:
   * the public {@code /git/<projectId>/<repoName>} when the announcement carried the name pair, and
   * the internal {@code /git/<repoId>} when it did not. Both arms are live on the release path: a
   * {@code SoftwareRelease} has carried {@code repoName} since 2026-09-04, and one published or
   * replayed from before it still carries none.
   *
   * <p><b>{@code rev} is a git rev and not a sha</b>, and the ordinary caller passes {@link
   * #tagRev}. The startup sweep's one legacy path passes a sha, because that is what the row it is
   * adopting recorded.
   *
   * @return the file's contents, or {@link SpecRead#undeclared()} — {@link DeploymentSpec#DEFAULTS}
   *     with {@link SpecRead#declared()} false — when the repository carries no such file at that
   *     rev. The two are a different answer and the caller may treat them differently; see {@link
   *     SpecRead#declared()}.
   * @throws SpecException when the file exists but could not be fetched or understood — the
   *     deployment fails on it rather than guessing a topology
   */
  SpecRead read(RepositoryRef repository, String rev);

  /**
   * Read the configuration DECLARATION a repository carries at {@code rev} — {@link
   * #DECLARATION_PATH}, raw.
   *
   * <p><b>The body is never parsed here, and that is the point of the seam rather than an
   * omission.</b> qits-configuration owns the declaration grammar: it stores the file, validates it,
   * layers the platform's overrides over it and answers the resolved document this component reads
   * back one layer down. A parser here would be a second opinion about somebody else's document, and
   * the two would disagree on the day the grammar grows a key — which is the day a deployment would
   * refuse a file the store accepts perfectly well. So this hands the bytes on untouched and lets
   * the store be the one thing that has read them.
   *
   * <p><b>It is a second method on this seam rather than a seam of its own</b> because it is the
   * same read: the same git host, the same address pair, the same rev, the same 404-is-an-answer
   * classification, and the same retryable-versus-permanent verdict that decides whether a release
   * is held or failed. A second interface would be a second implementation of all of it.
   *
   * <p><b>{@code rev} is the released tag</b>, exactly as {@link #read}'s is, and for the identical
   * reason: the declaration a deployment seeds has to be the declaration the version was cut from.
   *
   * @return the file's bytes, or {@link DeclarationRead#absent()} when the repository carries no
   *     such file at that rev — a clean answer meaning "not yet migrated", the {@link
   *     SpecRead#undeclared()} shape one file over
   * @throws SpecException when the file exists but could not be fetched — classified exactly as
   *     {@link #read}'s failures are, so a declaration the git host would not serve holds the
   *     release rather than failing it
   */
  DeclarationRead readDeclaration(RepositoryRef repository, String rev);

  /**
   * A repository's configuration declaration as it was served: bytes, and whether there were any.
   *
   * <p><b>{@code yaml} is the file verbatim and is never parsed on this side</b> — see {@link
   * #readDeclaration}. It travels to qits-configuration as the raw body of one POST, so anything
   * done to it here would be a difference between what a repository wrote and what the platform
   * stores.
   *
   * <p><b>{@code present} false is "not yet migrated", and it is a clean answer.</b> Every
   * repository on this platform predates the file, so the ordinary case for a long while is a
   * release that carries none: it seeds nothing, deploys exactly as it did, and says nothing about
   * whether its application has configuration — only that it does not declare it here yet. It is
   * deliberately not a refusal and deliberately not an empty declaration, which would be a
   * repository stating that it needs nothing.
   */
  record DeclarationRead(String yaml, boolean present) {

    /** The ordinary answer: the repository carries the file, and these are its bytes. */
    public DeclarationRead(String yaml) {
      this(yaml, true);
    }

    /**
     * The 404 arm: no file at that rev, so no bytes and nothing to seed. A factory rather than a
     * literal so the two values are stated once, where the reasoning is.
     */
    public static DeclarationRead absent() {
      return new DeclarationRead(null, false);
    }
  }

  /**
   * A spec, and the commit the rev it was read at resolved to.
   *
   * <p><b>The commit comes back because the read already knows it and nothing else does.</b> A
   * release names a version and no sha, so without this the deployment row could record no commit
   * at all and there would be no edge from a running container back to a diff. The git host answers
   * every blob read with the resolved commit in a header, so this costs no second request and no
   * ref-resolution endpoint — which is just as well, because it has none.
   *
   * <p><b>{@code commitSha} is null when the rev could not be resolved to one</b>, which is the
   * 404 case: a repository that carries no {@code deployments.yml} gets the defaults and no commit,
   * because a missing blob says nothing about where a tag points. It is a real answer, and {@code
   * pd_deployment.commit_sha} is nullable for it.
   *
   * <p><b>{@code declared} separates "the file says nothing" from "there is no file"</b>, which
   * used to be one answer because for years they meant the same thing — see {@link #declared()}.
   */
  record SpecRead(DeploymentSpec spec, String commitSha, boolean declared) {

    /** The ordinary answer: the repository carries the file, and this is what it says. */
    public SpecRead(DeploymentSpec spec, String commitSha) {
      this(spec, commitSha, true);
    }

    /**
     * Whether the repository carries {@link #SPEC_PATH} at that rev at all.
     *
     * <p><b>{@code spec} is {@link DeploymentSpec#DEFAULTS} either way, and that is exactly why
     * this flag exists.</b> A file present and empty and a file absent produce the identical spec,
     * so for as long as the only announcements this component heard were green builds of services
     * the platform already deployed, the two were the same statement: "deploy it the conventional
     * way". The release door ended that. It hears one event per published DOCKER PACKAGE, and a
     * repository can publish an image that is not a service at all — a workspace container image, a
     * build image — which the defaults then launch as a swarm service that can never come up. So
     * the intake asks the question the old door never had to: did the repository ASK to be
     * deployed? See {@code DeployService.deployReadSpec}.
     *
     * <p>It says nothing about whether a deployment should happen; it reports what the git host
     * answered. Every policy built on it lives at the caller, and there is exactly one today.
     */
    public boolean declared() {
      return declared;
    }

    /**
     * The 404 arm: no file at that rev, so the defaults, no commit, and nothing declared. A factory
     * rather than a literal so the three values are stated once, where the reasoning is.
     */
    public static SpecRead undeclared() {
      return new SpecRead(DeploymentSpec.DEFAULTS, null, false);
    }
  }

  /**
   * What a repository declares about how it is deployed. Every key optional, and the shape a
   * repository with no file at all gets is {@link #DEFAULTS}.
   *
   * <p>{@code healthPath} is the exception rather than the rule: a service that says nothing gets
   * the convention path derived from its name, and only a service whose path does not follow the
   * convention (the gateway owns the root path space) has to name one.
   *
   * <p>{@code healthCmd} <b>replaces</b> that HTTP probe rather than adjusting it: a plain image
   * with no HTTP surface — postgres is the first — declares the command that says it is ready, and
   * the parser refuses a file that sets both. Null means the HTTP probe, which is every service
   * this platform had before deployable images existed.
   *
   * <p>{@code resources} is what the repository asks to have provisioned before its container
   * starts — a database of its own, whose credential arrives as {@code QITS_RESOURCE_<NAME>_*}. An
   * empty list is every application that stores nothing, which is most of them.
   *
   * <p>{@code volumes} is the same statement about storage: where this application keeps its data,
   * declared because a volume mount is a property of what the application IS rather than of the
   * platform it happens to run on. A private volume's name is derived from the application's, so
   * what is carried here is only the segment — see {@link VolumeSpec}. Empty is most applications,
   * and empty stays empty while a platform's deployment config still supplies the same mount: the
   * driver renders a declared volume and drops the config mount that names the same target.
   *
   * <p>{@code updateOrder} is how a replacement may overlap what it replaces — {@code start-first}
   * unless the repository says otherwise. It is a repository's answer rather than a platform-wide
   * one because only the repository knows whether two of its processes may run at once: a public
   * host port, a single-writer store or a held config volume each make the overlap impossible. See
   * {@link DeploymentDriver.UpdateOrder}.
   *
   * <p>{@code publishMode} is where a published host port is held — {@code host} unless the
   * repository says {@code ingress}. Only the repository knows, for the same reason: a front door
   * that must survive its own replacement wants the routing mesh holding its port, and everything
   * else keeps the per-node bind it has today. See {@link DeploymentDriver.PublishMode}.
   *
   * <p>{@code stopGracePeriod} is how long a stopped task is given to finish what it is serving
   * before it is killed — {@link DeploymentDriver#DEFAULT_STOP_GRACE_PERIOD} unless the repository
   * says otherwise. Only the repository knows how long its longest response runs: a proxy carrying
   * a multi-gigabyte download has a different answer from a service that answers in milliseconds,
   * and the transfer is cut wherever the grace runs out.
   *
   * <p>{@code host} is the DNS label this application is also served at, and null means "derive
   * it": the parser does not know the application's name, exactly as it does not for a resource's
   * database. {@code browserHostDeclared} is the question that decides whether there is anything to
   * derive — a file that named {@code host} or {@code navigation-entries} asks for a host of its
   * own, and a file carrying only the retired {@code navigation} key asks for none.
   *
   * <p>{@code navigationEntries} is where the application asks to appear. Application-level and a
   * LIST, because one application sits under several headings; empty is an application that creates
   * no navigation option, which is most of them.
   *
   * <p>{@code apiDocs} is where the application's browsable API document lives, under one of its
   * published routes ({@code /ci/q/swagger-ui}). Null is a real answer — a service that documents
   * no HTTP surface — and the parser has already refused a path that sits under no route.
   *
   * <p>{@code application} is the name this repository deploys AS, and <b>null is the answer every
   * file gives today</b>: the application name is the repository's own. A file that states it
   * decouples the deployed identity from the repository name, so a repository can be renamed
   * without moving the service, the alias, the image, the database or the routes that are running.
   * The substitution is {@code DeployService.deploy}'s, the first place holding both this spec and
   * the announcement it was read for — see {@code DeploymentSpecParser} for what it costs and what
   * it cannot refuse.
   *
   * <p>{@code renamedFrom} is the application name this one used to carry, and null is the answer
   * every file gives today. It is the OTHER half of the sentence above: {@code application} says a
   * repository may be renamed without the platform noticing, and this one says what to do when the
   * <b>application</b> is the thing being renamed — which is a new application to every keyed thing
   * on the platform, and cannot be anything else. What it buys is one transfer: the successor
   * inherits the predecessor's {@code pd_resource} claim, so the database the predecessor is still
   * serving from is the database the successor is provisioned against, with the stored credential
   * rather than a rotated one. Everything else about a rename stays a new application beside the
   * old one — see {@code ResourceProvisioning.ensureAll} — save one announcement: every successor
   * cutover withdraws the predecessor's edge routes, which nothing else would ever replace.
   *
   * <p>{@code shape} is the host-facing half of the file — {@code binds:}, {@code publishes:} and
   * {@code aliases:} — and {@link DeclaredShape#NONE} is the answer every file gives today. It is
   * what deployment config's {@code mounts[n] bind:}, {@code publishes[n]} and {@code aliases[n]}
   * state, moving into the repository; while both sources are live the driver takes their union and
   * the declaration wins. See {@link DeclaredShape}.
   *
   * <p><b>{@code deployBranches} is read and not used here</b>, and that is deliberate — see {@link
   * #deployBranches()}.
   */
  record DeploymentSpec(
      boolean availableOnEnv,
      List<String> deployBranches,
      String healthPath,
      String healthCmd,
      List<ResourceSpec> resources,
      List<VolumeSpec> volumes,
      DeploymentDriver.UpdateOrder updateOrder,
      DeploymentDriver.PublishMode publishMode,
      List<String> routes,
      int upstreamPort,
      String host,
      boolean browserHostDeclared,
      List<NavigationEntry> navigationEntries,
      String apiDocs,
      String application,
      String renamedFrom,
      Duration stopGracePeriod,
      DeclaredShape shape) {

    /** A null list and an empty one are the same statement: the file named none. */
    public DeploymentSpec {
      shape = shape == null ? DeclaredShape.NONE : shape;
      deployBranches = deployBranches == null ? List.of() : List.copyOf(deployBranches);
      resources = resources == null ? List.of() : List.copyOf(resources);
      volumes = volumes == null ? List.of() : List.copyOf(volumes);
      updateOrder = updateOrder == null ? DeploymentDriver.UpdateOrder.START_FIRST : updateOrder;
      publishMode = publishMode == null ? DeploymentDriver.PublishMode.HOST : publishMode;
      routes = routes == null ? List.of() : List.copyOf(routes);
      navigationEntries =
          navigationEntries == null ? List.of() : List.copyOf(navigationEntries);
      stopGracePeriod =
          stopGracePeriod == null ? DeploymentDriver.DEFAULT_STOP_GRACE_PERIOD : stopGracePeriod;
    }

    /**
     * The shape before {@code binds}, {@code publishes} and {@code aliases}: a file that names none
     * of the three declares {@link DeclaredShape#NONE}.
     */
    public DeploymentSpec(
        boolean availableOnEnv,
        List<String> deployBranches,
        String healthPath,
        String healthCmd,
        List<ResourceSpec> resources,
        List<VolumeSpec> volumes,
        DeploymentDriver.UpdateOrder updateOrder,
        DeploymentDriver.PublishMode publishMode,
        List<String> routes,
        int upstreamPort,
        String host,
        boolean browserHostDeclared,
        List<NavigationEntry> navigationEntries,
        String apiDocs,
        String application,
        String renamedFrom,
        Duration stopGracePeriod) {
      this(
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
          browserHostDeclared,
          navigationEntries,
          apiDocs,
          application,
          renamedFrom,
          stopGracePeriod,
          null);
    }

    /** The shape before {@code stop_grace_period}: a file that names none takes the default. */
    public DeploymentSpec(
        boolean availableOnEnv,
        List<String> deployBranches,
        String healthPath,
        String healthCmd,
        List<ResourceSpec> resources,
        List<VolumeSpec> volumes,
        DeploymentDriver.UpdateOrder updateOrder,
        DeploymentDriver.PublishMode publishMode,
        List<String> routes,
        int upstreamPort,
        String host,
        boolean browserHostDeclared,
        List<NavigationEntry> navigationEntries,
        String apiDocs,
        String application,
        String renamedFrom) {
      this(
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
          browserHostDeclared,
          navigationEntries,
          apiDocs,
          application,
          renamedFrom,
          null);
    }

    /**
     * A spec that says nothing about the order or the publish mode takes both defaults, which is
     * most of them.
     */
    public DeploymentSpec(
        boolean availableOnEnv,
        List<String> deployBranches,
        String healthPath,
        String healthCmd,
        List<ResourceSpec> resources) {
      this(availableOnEnv, deployBranches, healthPath, healthCmd, resources, null, null);
    }

    /**
     * The pre-routing shape: no routes is the compatible, empty endpoint declaration, and no
     * {@code application} is the repository's own name — both of them what a file that says
     * nothing has always meant. No {@code renamedFrom} is the third of exactly the same kind: this
     * application has always been called what it is called.
     */
    public DeploymentSpec(
        boolean availableOnEnv,
        List<String> deployBranches,
        String healthPath,
        String healthCmd,
        List<ResourceSpec> resources,
        DeploymentDriver.UpdateOrder updateOrder,
        DeploymentDriver.PublishMode publishMode) {
      this(
          availableOnEnv,
          deployBranches,
          healthPath,
          healthCmd,
          resources,
          List.of(),
          updateOrder,
          publishMode,
          List.of(),
          8080,
          null,
          false,
          List.of(),
          null,
          null,
          null);
    }

    /**
     * One resource a repository declares — {@code postgresql:<name>[:<database>]} or {@code
     * idp:client}.
     *
     * <p>{@code database} is <b>null when the file omitted it, and always null for {@link
     * Type#IDP_CLIENT}</b>: for postgres that is not a default this record could fill in — the
     * convention is {@code qits_} plus the application name without its {@code qits-} prefix, and
     * the parser does not know the application name, so {@code DeployService.register} resolves it,
     * where the repository id is in hand. An idp client has no database at all.
     *
     * <p>{@code type} arrived with the second resource type; the two-argument constructor keeps
     * defaulting to {@link Type#POSTGRESQL} so every existing caller of the postgres shape is
     * unaffected.
     */
    public record ResourceSpec(String name, String database, Type type) {

      public ResourceSpec(String name, String database) {
        this(name, database, Type.POSTGRESQL);
      }

      /** The resource types this component knows how to provision. */
      public enum Type {
        POSTGRESQL,
        IDP_CLIENT
      }
    }

    /**
     * One volume a repository declares — {@code private:<name>:<target>[:ro]} or {@code
     * shared:<volume>:<target>[:ro]}.
     *
     * <p><b>{@code name} is not the volume for a {@link Scope#PRIVATE} entry</b>: it is the segment
     * the application's own name is prefixed to, and the volume is {@code <application>-<name>}.
     * The parser does not know the application name — exactly as it does not for a resource's
     * defaulted database — so {@code DeployService.register} resolves it. That is the isolation
     * rather than a convenience: a repository cannot write a literal volume name, so it cannot
     * write a sibling's.
     *
     * <p>For {@link Scope#SHARED} the name IS the volume, out of the closed vocabulary the parser
     * holds: a platform-owned volume is not this application's to derive.
     *
     * <p><b>There is no host-bind scope, deliberately.</b> A host path is a statement about the
     * machine rather than about the application, and it stays in deployment config — see {@code
     * DeploymentSpecParser}'s class javadoc for the whole argument.
     */
    public record VolumeSpec(Scope scope, String name, String target, boolean readOnly) {

      /** Whose volume it is, which decides whether the name is derived or stated. */
      public enum Scope {
        PRIVATE,
        SHARED
      }

      /**
       * The volume this mounts, once the application's name is known: {@code <application>-<name>}
       * for a private one, and the stated name for a shared one.
       *
       * <p>One spelling of the derivation, here, because everything that has to agree about which
       * volume an application holds takes it from this method — the argv that mounts it, and the
       * comparison against a live service that decides whether the mount is there at all.
       */
      public String source(String applicationName) {
        return scope == Scope.SHARED ? name : applicationName + "-" + name;
      }
    }

    /** No file, or a file that sets nothing: an ordinary application in the tier it lands in. */
    public static final DeploymentSpec DEFAULTS =
        new DeploymentSpec(false, List.of(), null, null, List.of());

    /**
     * The refs the repository declares itself deployable from — {@code deploy_branches:} in the
     * file.
     *
     * <p><b>Nothing in this component matches on it.</b> Where a build deploys is decided by the
     * environment rows: a release lands in the tier the platform designates. The key is parsed and validated because the <b>release flow</b> reads the same
     * file for its promotion targets, and this parser is strict — an unknown key fails a
     * deployment, so a key another reader needs has to be one this reader knows. Reading it and
     * ignoring it is cheaper than two files, and far cheaper than a lenient parser.
     */
    public List<String> deployBranches() {
      return deployBranches;
    }
  }
}
