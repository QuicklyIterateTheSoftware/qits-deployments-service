package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec.VolumeSpec;
import eu.wohlben.qits.deployments.events.NavigationEntry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The strict reader of {@code .config/qits/deployments.yml}, plain JUnit — the file decides where a
 * container runs and what may reach it, so what it rejects matters as much as what it accepts, and
 * every rejection has to name the file a person has to go and fix.
 */
class DeploymentSpecParserTest {

  private static final String SOURCE = ".config/qits/deployments.yml of qits-idp@abc1234";

  private DeploymentSpec parse(String yaml) {
    return DeploymentSpecParser.parse(yaml, SOURCE);
  }

  private String messageOf(String yaml) {
    SpecException thrown = assertThrows(SpecException.class, () -> parse(yaml));
    assertTrue(
        thrown.getMessage().startsWith(SOURCE), "every error names the file: " + thrown.getMessage());
    return thrown.getMessage();
  }

  @Test
  void anEmptyFileIsEveryDefault() {
    // The whole of backward compatibility: a repository that says nothing is an ordinary
    // environment application, exactly as every repository was before this file existed.
    assertEquals(DeploymentSpec.DEFAULTS, parse(""));
    assertEquals(DeploymentSpec.DEFAULTS, parse("# nothing to say yet\n"));
  }

  @Test
  void aRepositoryAsksForADatabaseOfItsOwnWithOneLine() {
    // The three shapes the grammar has, and all three are in the plan's own examples: the bare
    // default, an explicit database, and two resources on one line. The type is spelled out so a
    // second one can arrive without changing the shape.
    assertEquals(
        List.of(new ResourceSpec("db", null)), parse("resources: postgresql:db\n").resources());
    assertEquals(
        List.of(new ResourceSpec("db", "qits_artifacts")),
        parse("resources: postgresql:db:qits_artifacts\n").resources());
    assertEquals(
        List.of(new ResourceSpec("projects", null), new ResourceSpec("epics", "qits_epics")),
        parse("resources: postgresql:projects, postgresql:epics:qits_epics\n").resources());
  }

  @Test
  void anOmittedDatabaseIsNullBecauseTheParserDoesNotKnowTheApplication() {
    // Null is "the convention", not "no database": the default is qits_ plus the application name
    // without its qits- prefix, and this parser has never been told which application it is
    // reading for. DeployService.register resolves it.
    assertNull(parse("resources: postgresql:db\n").resources().get(0).database());
  }

  @Test
  void aFileThatNamesNoResourcesGetsAnEmptyListAndNotANull() {
    assertEquals(List.of(), parse("available_on_env: true\n").resources());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.resources());
  }

  @Test
  void routesNavigationAndPortAreAnOrderedServiceOwnedPublicSurface() {
    DeploymentSpec spec =
        parse(
            """
            routes: /refinement,/refinement/api
            upstream_port: 8181
            host: refinement
            navigation-entries: services.details.Refinement:12, platform.Refinement:1
            """);

    assertEquals(List.of("/refinement", "/refinement/api"), spec.routes());
    assertEquals(8181, spec.upstreamPort());
    assertEquals("refinement", spec.host());
    assertTrue(spec.browserHostDeclared());
    assertEquals(
        List.of(
            new NavigationEntry("services.details", "Refinement", 12),
            new NavigationEntry("platform", "Refinement", 1)),
        spec.navigationEntries());
  }

  @Test
  void anEntryIsReadFromTheRightSoALabelMayCarryAColon() {
    // The whole grammar in one line: the last colon takes the position off the end, the last dot of
    // what remains takes the label off, and everything before it is the slot. That order is what
    // lets a label contain a colon while the slot vocabulary stays closed.
    assertEquals(
        List.of(new NavigationEntry("services.details", "CI:v2", 1)),
        parse("routes: /ci\nnavigation-entries: services.details.CI:v2:1\n").navigationEntries());
  }

  @Test
  void aHostIsDerivedElsewhereButAskedForHere() {
    // The parser cannot derive the default — it never knows which application it reads for — so it
    // answers the question that decides whether there is anything to derive.
    assertNull(parse("routes: /ci\nnavigation-entries: system.CI:1\n").host());
    assertTrue(parse("routes: /ci\nnavigation-entries: system.CI:1\n").browserHostDeclared());
    assertTrue(parse("routes: /ci\nhost: ci\n").browserHostDeclared());
    // And the retired singular asks for none: a file nobody has rewritten keeps its path prefix.
    assertFalse(parse("routes: /ci\nnavigation: CI:1\n").browserHostDeclared());
  }

  @Test
  void theRetiredNavigationKeyMapsOntoOneSystemEntry() {
    // A spec is read at the BUILT sha, so every unrewritten file and every rollback pin still
    // presents this key. It maps onto the list that replaced it rather than being tolerated twice.
    assertEquals(
        List.of(new NavigationEntry("system", "Refinement", 12)),
        parse("routes: /refinement\nnavigation: Refinement:12\n").navigationEntries());
    // Two answers to one question is an error, and the message names the key to keep.
    assertTrue(
        messageOf("routes: /ci\nnavigation: CI:1\nnavigation-entries: system.CI:1\n")
            .contains("navigation-entries"));
  }

  @Test
  void noRouteDeclarationIsTheCompatibleEmptySnapshot() {
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.routes());
    assertEquals(8080, DeploymentSpec.DEFAULTS.upstreamPort());
    assertNull(DeploymentSpec.DEFAULTS.host());
    assertFalse(DeploymentSpec.DEFAULTS.browserHostDeclared());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.navigationEntries());
  }

  @Test
  void routesAndNavigationAreStrictBecauseTheyBecomeTheEdgesPublicSurface() {
    assertTrue(messageOf("routes: refinement\n").contains("routes"));
    assertTrue(messageOf("routes: /refinement,/refinement\n").contains("twice"));
    assertTrue(messageOf("routes: /refinement\nupstream_port: 0\n").contains("upstream_port"));
    // Navigation has to lead to a published route, whichever key asks for it.
    assertTrue(messageOf("navigation: Refinement:3\n").contains("navigation"));
    assertTrue(messageOf("routes: /refinement\nnavigation: Refinement:0\n").contains("navigation"));
    assertTrue(
        messageOf("navigation-entries: system.Refinement:3\n").contains("navigation-entries"));
    assertTrue(messageOf("host: refinement\n").contains("host"));
  }

  @Test
  void aSlotOutsideTheVocabularyNamesTheWholeVocabulary() {
    // The strictness that matters here: a typo answered with a default is an application that
    // silently appears nowhere, and nothing in the file says why.
    String message = messageOf("routes: /ci\nnavigation-entries: services.CI:1\n");
    assertTrue(message.contains("services.details"), message);
    assertTrue(message.contains("project.detail"), message);
    assertTrue(message.contains("apps.details"), message);
    assertTrue(messageOf("routes: /ci\nnavigation-entries: CI:1\n").contains("slots"));
    // A word that merely looks like the seventh archetype group is still outside the vocabulary:
    // admitting `apps.details` admits one word, not a family of them.
    assertTrue(messageOf("routes: /ci\nnavigation-entries: apps.detail.CI:1\n").contains("slots"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: app.details.CI:1\n").contains("slots"));
  }

  @Test
  void theSeventhArchetypeGroupIsASlotOfItsOwn() {
    // A standalone app is an application like any other and asks to appear the same way. The word
    // is in the guard's ordered list between `libs.details` and `frontends.details` — an app is a
    // sibling kind of a microfrontend, not a group after it — and qits-edge's EdgeRoutes.SLOTS
    // holds the same words in the same order, because it sorts the rendered navigation by index.
    assertEquals(
        List.of(
            new NavigationEntry("apps.details", "Docs", 1),
            new NavigationEntry("apps.details", "API", 2, "api-docs")),
        parse(
                "application: qits-shop-app\n"
                    + "routes: /shop\n"
                    + "navigation-entries: apps.details.Docs:1, apps.details.API:2=api-docs\n")
            .navigationEntries());
  }

  @Test
  void anAbsentApplicationKeyIsTheRepositorysOwnName() {
    // The whole of the compatibility contract for this key: every file that exists today says
    // nothing, and null is what "the repository's own name" is spelled as here — the parser never
    // knows which repository it reads for, so it cannot fill the value in.
    assertNull(DeploymentSpec.DEFAULTS.application());
    assertNull(parse("").application());
    assertNull(parse("deployment_target: platform\nroutes: /ci\n").application());
  }

  @Test
  void aStatedApplicationIsTheNameThisRepositoryDeploysAs() {
    // The rename: the repository is qits-ci-service and the application stays qits-ci, so nothing
    // that is running moves. What the name then drives is DeployService's, not this parser's.
    assertEquals("qits-ci", parse("application: qits-ci\n").application());
    assertEquals(
        "qits-ci",
        parse("application: qits-ci\ndeployment_target: platform\nroutes: /ci\n").application(),
        "it sits beside every other key and changes none of them");
    // Naming the repository's own name is a no-op rather than an error — a file may say it out loud.
    assertEquals("qits-ci", parse("application: 'qits-ci'\n").application(), "quoted like any value");
  }

  @Test
  void anApplicationNameIsCheckedLikeEveryOtherStoredName() {
    // It becomes a service name, a network alias, an image path segment and half a postgres
    // identifier, so it takes PdIdentifiers' stored-name rule: lowercase, digits, inner dashes.
    // Uppercase is refused outright, which is what makes two spellings differing only in case
    // impossible rather than merely discouraged.
    assertTrue(messageOf("application: Qits-CI\n").contains("application"));
    assertTrue(messageOf("application: qits/ci\n").contains("application"));
    assertTrue(messageOf("application: qits ci\n").contains("application"));
    assertTrue(messageOf("application: qits.ci\n").contains("application"));
    assertTrue(messageOf("application: -qits-ci\n").contains("application"));
    assertTrue(messageOf("application: qits-ci-\n").contains("application"));
    assertTrue(messageOf("application:\n").contains("application"));
    assertTrue(messageOf("application: " + "c".repeat(64) + "\n").contains("application"));
    // And it is one key like any other: stating it twice is the duplicate every key is refused for.
    assertTrue(messageOf("application: qits-ci\napplication: qits-ci\n").contains("duplicate"));
  }

  @Test
  void aRenamedApplicationNamesWhatItUsedToBeCalled() {
    // The key's whole job: the six qits-platform-* applications drop a retired prefix, and each
    // successor says whose resource claim it is inheriting. What that then licenses is exactly one
    // transfer, in ResourceProvisioning — this parser reads the name and validates it.
    assertEquals("qits-platform-idp", parse("renamed_from: qits-platform-idp\n").renamedFrom());
    assertEquals(
        "qits-platform-idp",
        parse("application: qits-idp\nrenamed_from: qits-platform-idp\nroutes: /idp\n")
            .renamedFrom(),
        "it sits beside every other key and changes none of them");
    assertEquals("qits-idp", parse("application: qits-idp\nrenamed_from: qits-platform-idp\n").application());
    assertEquals(
        "qits-platform-idp", parse("renamed_from: 'qits-platform-idp'\n").renamedFrom(),
        "quoted like any value");
  }

  @Test
  void aFileThatWasNeverRenamedSaysNothingAndThatIsTheAnswer() {
    // The whole of the compatibility contract for this key, and it is `application`'s word for word:
    // every file that exists today says nothing, and null is "this application has always been
    // called what it is called". Null is also what keeps the provisioning refusal byte-identical.
    assertNull(DeploymentSpec.DEFAULTS.renamedFrom());
    assertNull(parse("").renamedFrom());
    assertNull(parse("application: qits-idp\nresources: postgresql:db:qits_idp\n").renamedFrom());
  }

  @Test
  void aPredecessorNameIsCheckedLikeEveryOtherStoredName() {
    // It is never stored and never reaches an argv — it is COMPARED against a stored application
    // name, so a value outside that charset is a value that could match no row this schema ever
    // wrote. Refusing it at the line beats a deployment that fails at provisioning saying only that
    // some database belongs to somebody else.
    assertTrue(messageOf("renamed_from: Qits-Platform-Idp\n").contains("renamed_from"));
    assertTrue(messageOf("renamed_from: qits/idp\n").contains("renamed_from"));
    assertTrue(messageOf("renamed_from: qits idp\n").contains("renamed_from"));
    assertTrue(messageOf("renamed_from: qits.idp\n").contains("renamed_from"));
    assertTrue(messageOf("renamed_from: -qits-idp\n").contains("renamed_from"));
    assertTrue(messageOf("renamed_from: qits-idp-\n").contains("renamed_from"));
    assertTrue(messageOf("renamed_from:\n").contains("renamed_from"));
    assertTrue(messageOf("renamed_from: " + "c".repeat(64) + "\n").contains("renamed_from"));
    // One value and not a list: a chain does not need one — A→B moves the claim to B, so C names B.
    assertTrue(messageOf("renamed_from: qits-a, qits-b\n").contains("renamed_from"));
    // And it is one key like any other: stating it twice is the duplicate every key is refused for.
    assertTrue(
        messageOf("renamed_from: qits-platform-idp\nrenamed_from: qits-platform-idp\n")
            .contains("duplicate"));
  }

  @Test
  void anApplicationCannotBeRenamedFromItself() {
    // Two keys saying one thing, which is the shape `navigation`/`navigation-entries` is refused
    // for. It is caught only where this parser can see both halves: with `application` absent the
    // effective name is the repository's, which this parser has never been told — and the statement
    // is then inert downstream, because a claim moves only when it belongs to another application.
    String message = messageOf("application: qits-idp\nrenamed_from: qits-idp\n");
    assertTrue(message.contains("renamed from itself"), message);
    assertTrue(message.contains("qits-idp"), message);
    // Absent `application`, there is nothing here to compare it against, so it parses.
    assertEquals("qits-idp", parse("renamed_from: qits-idp\n").renamedFrom());
  }

  @Test
  void oneApplicationHangsSeveralRowsUnderOneHeading() {
    // The workspaces shape, byte for byte: one application, one container, two rows under the
    // project node. The claim is the (slot, label) pair — keyed on the slot alone this file was
    // refused whole ("claims the slot `project.detail` twice"), so the deployment failed as
    // "deployment spec unreadable" while the build that produced it stayed green.
    assertEquals(
        List.of(
            new NavigationEntry("project.detail", "Workspaces", 1),
            new NavigationEntry("project.detail", "Editor", 2, "editor")),
        parse(
                "routes: /workspaces\n"
                    + "navigation-entries: project.detail.Workspaces:1,"
                    + " project.detail.Editor:2=editor\n")
            .navigationEntries());
    // And a label that is only a label away from another one is still two rows: what makes them
    // distinct is the pair, not how different the words are.
    assertEquals(
        2,
        parse("routes: /ci\nnavigation-entries: system.CI:1, system.Runs:2\n")
            .navigationEntries()
            .size());
  }

  @Test
  void oneApplicationClaimsEachSlotAndLabelOnce() {
    // One row asked for twice, which no consumer can draw two of. The message names the pair rather
    // than the slot, so a file with four project.detail entries says which one is the duplicate.
    String message = messageOf("routes: /ci\nnavigation-entries: system.CI:1, system.CI:2\n");
    assertTrue(message.contains("twice"), message);
    assertTrue(message.contains("system.CI"), message);
    // The subpath is not part of the claim either: two views of one application under one heading
    // are two ROWS and need two labels to be told apart in the tree.
    assertTrue(
        messageOf(
                "routes: /ci\nnavigation-entries: project.detail.Editor:1=editor,"
                    + " project.detail.Editor:2=terminal\n")
            .contains("twice"));
  }

  @Test
  void twoRowsOfOneApplicationMayNameOnePosition() {
    // A position is the repository's own number and a tie is broken by label — the consumer already
    // does that between applications (NavigationEntry's javadoc says so, and EdgeRoutes sorts slot,
    // position, label, application). Refusing the tie within one application would be a rule the
    // navigation document does not have, and the two rows would still both render.
    assertEquals(
        List.of(
            new NavigationEntry("project.detail", "Workspaces", 1),
            new NavigationEntry("project.detail", "Editor", 1, "editor")),
        parse(
                "routes: /workspaces\n"
                    + "navigation-entries: project.detail.Workspaces:1,"
                    + " project.detail.Editor:1=editor\n")
            .navigationEntries());
  }

  @Test
  void anEntryNeedsANonBlankLabelAndAPositivePosition() {
    assertTrue(messageOf("routes: /ci\nnavigation-entries:\n").contains("blank"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:1,\n").contains("blank"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.:1\n").contains("label"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:0\n").contains("position"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI\n").contains("position"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system." + "C".repeat(65) + ":1\n")
            .contains("64"));
  }

  @Test
  void anEntryMayNameTheViewItOpensWithASubpath() {
    // The tail after the last colon splits at its first `=`: position on the left, subpath on the
    // right. The subpath is a client-side route segment the shell appends after the scope — it is
    // not an edge route, so no published-route rule touches it beyond the entry's own.
    assertEquals(
        List.of(new NavigationEntry("services.details", "Api Docs", 6, "api-docs")),
        parse("routes: /projects\nnavigation-entries: services.details.Api Docs:6=api-docs\n")
            .navigationEntries());
    // A label's right to carry a colon survives, because the tail was already taken off the end.
    assertEquals(
        List.of(new NavigationEntry("services.details", "CI:v2", 1, "runs/latest")),
        parse("routes: /ci\nnavigation-entries: services.details.CI:v2:1=runs/latest\n")
            .navigationEntries());
  }

  @Test
  void aSubpathOutsideItsCharsetIsAnError() {
    // The charset has no colon, dot or comma by construction — that is what keeps the entry list's
    // separators and the right-to-left parse safe around it — and no slash at either end, because
    // the shell composes the joint.
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:1=\n").contains("subpath"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:1=/api\n").contains("subpath"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system.CI:1=api-docs/\n").contains("subpath"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system.CI:1=Api Docs\n").contains("subpath"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system.CI:1=" + "a".repeat(129) + "\n")
            .contains("128"));
  }

  @Test
  void theStoredSpellingRoundTripsExactly() {
    // adoptedSnapshot re-announces a self-update from the stored column alone, so the join and the
    // parse are one serialization format: what the file said is what the row says, byte for byte.
    for (String spelled :
        List.of(
            "services.details.CI:2,platform.Deployments:4",
            "services.details.Api Docs:6=api-docs",
            "project.detail.Workspaces:1,project.detail.Editor:2=editor",
            "services.details.CI:v2:1=runs/latest,system.CI:1")) {
      assertEquals(
          spelled,
          DeploymentSpecParser.joinEntries(DeploymentSpecParser.parseEntries(spelled)));
    }
  }

  @Test
  void apiDocsMustSitUnderAPublishedRoute() {
    // A document nobody can reach describes nothing: the edge only proxies what `routes` declares.
    assertEquals(
        "/ci/q/swagger-ui", parse("routes: /ci\napi-docs: /ci/q/swagger-ui\n").apiDocs());
    assertEquals("/ci", parse("routes: /ci\napi-docs: /ci\n").apiDocs(), "the route itself is in");
    assertTrue(messageOf("routes: /ci\napi-docs: /docs/q/swagger-ui\n").contains("api-docs"));
    assertTrue(
        messageOf("routes: /cd\napi-docs: /cdn\n").contains("api-docs"),
        "a sibling prefix is not under the route");
    assertTrue(messageOf("api-docs: /ci/q/swagger-ui\n").contains("api-docs"));
    assertNull(DeploymentSpec.DEFAULTS.apiDocs());
  }

  @Test
  void anApiDocsValueOutsideItsCharsetIsAnError() {
    assertTrue(messageOf("routes: /ci\napi-docs: ci/q/swagger-ui\n").contains("api-docs"));
    assertTrue(messageOf("routes: /ci\napi-docs: /\n").contains("api-docs"));
    assertTrue(messageOf("routes: /ci\napi-docs: /ci/Q/Swagger\n").contains("api-docs"));
    assertTrue(messageOf("routes: /ci\napi-docs:\n").contains("api-docs"));
    assertTrue(
        messageOf("routes: /ci\napi-docs: /ci/" + "a".repeat(255) + "\n").contains("api-docs"));
  }

  @Test
  void aHostIsOneDnsLabelBecauseTheEdgeBuildsTheAuthorityAroundIt() {
    assertEquals("registry", parse("routes: /artifacts\nhost: registry\n").host());
    assertTrue(messageOf("routes: /ci\nhost: CI\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: -ci\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: ci-\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: ci.dev\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: " + "c".repeat(64) + "\n").contains("host"));
  }

  @Test
  void aResourceTypeThisComponentCannotProvisionIsAnError() {
    // The strictness that matters most here: a typo answered with a default would be a deployment
    // whose application boots without the credential it declared it needs.
    String message = messageOf("resources: mysql:db\n");
    assertTrue(message.contains("postgresql"), message);
    assertTrue(messageOf("resources: db\n").contains("resources"), "a bare name is not an entry");
    assertTrue(
        messageOf("resources: postgresql:db:qits_a:extra\n").contains("resources"),
        "and neither is a fourth segment");
  }

  @Test
  void anIdpClientResourceIsTheReservedNameWithNoDatabase() {
    // idp:client names no resource of its own — there is one idp per platform, so there is nothing
    // for a repository to call it — and it carries the second Type.
    assertEquals(
        List.of(new ResourceSpec("idp", null, ResourceSpec.Type.IDP_CLIENT)),
        parse("resources: idp:client\n").resources());
    // Alongside a postgres resource, on one line, exactly like two postgres resources are.
    assertEquals(
        List.of(
            new ResourceSpec("db", null),
            new ResourceSpec("idp", null, ResourceSpec.Type.IDP_CLIENT)),
        parse("resources: postgresql:db, idp:client\n").resources());
  }

  @Test
  void idpAloneOrWithTheWrongSecondSegmentIsAnError() {
    assertTrue(messageOf("resources: idp\n").contains("resources"), "idp with no colon at all");
    assertTrue(messageOf("resources: idp:secret\n").contains("idp"), "idp knows only client");
    assertTrue(
        messageOf("resources: idp:client:extra\n").contains("idp"),
        "idp:client refuses a third segment — there is nothing to name");
  }

  @Test
  void idpClientTwiceIsAnErrorTheSameWayARepeatedNameIs() {
    assertTrue(messageOf("resources: idp:client, idp:client\n").contains("twice"));
  }

  @Test
  void postgresqlIdpIsRefusedBecauseTheNameIsReserved() {
    // A repository cannot shadow the idp:client resource's fixed name with a postgres resource of
    // its own — the reservation is the point, not an accident of the charset.
    assertTrue(messageOf("resources: postgresql:idp\n").contains("reserved"));
    assertTrue(messageOf("resources: postgresql:idp:qits_something\n").contains("reserved"));
  }

  @Test
  void aResourceNameOrDatabaseOutsideItsCharsetIsAnError() {
    // Both values are repository-authored and both end up somewhere that cannot be parametrized —
    // the name in an env key on a docker run, the database in DDL against the platform's shared
    // postgres. So they are allowlists, and this is the parser end of the three checkpoints.
    assertTrue(messageOf("resources: postgresql:DB\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db name\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:-db\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db-\n").contains("resources"));
    // The qits_ prefix is what structurally excludes postgres, template0/1 and every pg_* name.
    assertTrue(messageOf("resources: postgresql:db:postgres\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:template1\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:pg_catalog\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:qits_a; drop database x\n").contains("qits_"));
  }

  @Test
  void aBlankEntryIsAnError() {
    // `resources:` with nothing after it, and a trailing comma: a writer who meant to say
    // something. The deploy_branches rule, applied to the key that provisions.
    assertTrue(messageOf("resources:\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db,\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db, ,postgresql:other\n").contains("resources"));
  }

  @Test
  void namingOneThingTwiceIsAnError() {
    // A repeated name would make one env triple silently overwrite another; a repeated literal
    // database would point two of a repository's own resources at one store.
    assertTrue(
        messageOf("resources: postgresql:db, postgresql:db\n").contains("twice"),
        "the same resource name");
    assertTrue(
        messageOf("resources: postgresql:a:qits_x, postgresql:b:qits_x\n").contains("twice"),
        "the same database under two names");
  }

  @Test
  void aRepositoryDeclaresTheStorageItNeedsWithOneLine() {
    // The shapes the grammar has: a volume of the application's own, one of the platform's, the
    // read-only option, and two entries on one line — the `resources` arrangement exactly, because
    // a volume mount is the same kind of statement about what an application IS.
    assertEquals(
        List.of(new VolumeSpec(VolumeSpec.Scope.PRIVATE, "data", "/data", false)),
        parse("volumes: private:data:/data\n").volumes());
    assertEquals(
        List.of(new VolumeSpec(VolumeSpec.Scope.SHARED, "qits_shared_m2", "/home/qits/.m2", false)),
        parse("volumes: shared:qits_shared_m2:/home/qits/.m2\n").volumes());
    assertEquals(
        List.of(
            new VolumeSpec(VolumeSpec.Scope.PRIVATE, "config", "/work/config", false),
            new VolumeSpec(VolumeSpec.Scope.SHARED, "qits_shared_pnpm", "/pnpm", true)),
        parse("volumes: private:config:/work/config, shared:qits_shared_pnpm:/pnpm:ro\n")
            .volumes());
  }

  @Test
  void aPrivateVolumesNameIsDerivedFromTheApplicationAndARepositoryCannotWriteTheWholeThing() {
    // The isolation, and the reason the name is a segment rather than a volume: the prefix is not
    // the repository's to choose, so nothing it can write addresses a sibling's storage. It is the
    // defaulted-database arrangement — the parser never knows which application it is reading for.
    VolumeSpec declared = parse("volumes: private:data:/data\n").volumes().get(0);

    assertEquals("data", declared.name(), "the file states a segment");
    assertEquals("qits-workspaces-data", declared.source("qits-workspaces"));
    assertEquals("qits-containers-data", declared.source("qits-containers"));
    // A shared volume is the platform's and is named in full, so there is nothing to derive.
    assertEquals(
        "qits_shared_m2",
        parse("volumes: shared:qits_shared_m2:/m2\n").volumes().get(0).source("qits-ci"));
  }

  @Test
  void aHostBindIsNotAVolumeScopeItIsTheBindsKey() {
    // A host path is not a volume: its name is neither derived nor drawn from a closed list, so it
    // has a key of its own (`binds:`) rather than a third scope here. The refusal names the two
    // words that do exist, and the key a host path belongs under.
    String message = messageOf("volumes: bind:/var/run/docker.sock:/var/run/docker.sock\n");
    assertTrue(message.contains("private"), message);
    assertTrue(message.contains("shared"), message);
    assertTrue(message.contains("binds"), message);
    // And a path written where a private volume's NAME belongs is the same reach, said differently.
    assertTrue(
        messageOf("volumes: private:/var/run:/var/run\n").contains("host path"),
        "a path is not a volume name");
  }

  @Test
  void anUnknownSharedVolumeIsRefusedWithTheWholeVocabulary() {
    // Docker answers an unknown volume name by creating an empty one, so a typo here is a container
    // that boots, passes its health gate and has lost its data. The vocabulary is quoted back.
    String message = messageOf("volumes: shared:qits_shared_maven:/m2\n");
    assertTrue(message.contains("qits_shared_m2"), message);
    assertTrue(message.contains("qits_shared_dot_claude"), message);
    assertTrue(message.contains("qits_shared_pnpm"), message);
  }

  @Test
  void aVolumeTargetIsAnAbsolutePathAndRoIsTheOnlyOption() {
    assertTrue(messageOf("volumes: private:data:data\n").contains("absolute"));
    assertTrue(messageOf("volumes: private:data:/\n").contains("absolute"), "never the bare root");
    // The one option ServiceExtras.Mount has, and this grammar has no others: `rw` is not a word
    // here, and neither is anything else.
    assertTrue(messageOf("volumes: private:data:/data:rw\n").contains("ro"));
    assertEquals(true, parse("volumes: private:data:/data:ro\n").volumes().get(0).readOnly());
  }

  @Test
  void aVolumeNameOutsideItsCharsetIsAnError() {
    // The parser end of the two checkpoints, the resource name's rule: the value is half of a
    // docker volume name spliced into a comma-separated --mount, so the charset is the one every
    // derived name on this platform already passes.
    assertTrue(messageOf("volumes: private:DATA:/data\n").contains("volumes"));
    assertTrue(messageOf("volumes: private:my data:/data\n").contains("volumes"));
    assertTrue(messageOf("volumes: private:-data:/data\n").contains("volumes"));
    assertTrue(messageOf("volumes: private:data-:/data\n").contains("volumes"));
  }

  @Test
  void aBlankVolumeEntryOrAMalformedOneIsAnError() {
    // `volumes:` with nothing after it, and a trailing comma: a writer who meant to say something.
    assertTrue(messageOf("volumes:\n").contains("volumes"));
    assertTrue(messageOf("volumes: private:data:/data,\n").contains("volumes"));
    assertTrue(messageOf("volumes: private:data:/data, ,shared:qits_shared_m2:/m2\n")
        .contains("volumes"));
    // Too few segments and too many are both the grammar being quoted back.
    assertTrue(messageOf("volumes: private:data\n").contains("private:<name>:<target>"));
    assertTrue(messageOf("volumes: private:data:/data:ro:extra\n").contains("<target>"));
  }

  @Test
  void namingOneVolumeOrOneTargetTwiceIsAnError() {
    // Two volumes over one directory is a thing no orchestrator can honour; one volume asked for
    // twice is the second mount point somebody meant to write elsewhere.
    assertTrue(
        messageOf("volumes: private:data:/data, shared:qits_shared_m2:/data\n").contains("twice"),
        "the same target from two volumes");
    assertTrue(
        messageOf("volumes: private:data:/data, private:data:/other\n").contains("twice"),
        "the same volume at two targets");
    // The scope is part of the key: these are two different volumes that happen to share a word.
    assertEquals(
        2,
        parse("volumes: private:m2:/private-m2, shared:qits_shared_m2:/m2\n").volumes().size());
  }

  @Test
  void aFileThatNamesNoVolumesGetsAnEmptyListAndNotANull() {
    assertEquals(List.of(), parse("available_on_env: true\n").volumes());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.volumes());
  }

  @Test
  void anIndentedResourceListIsStillNesting() {
    // The grammar is flat because the file is. A YAML sequence would need a parser this file
    // deliberately does not have, which is why the entries share one comma-separated line.
    assertTrue(
        messageOf("resources: postgresql:db\n  - postgresql:other\n").contains("nesting"),
        "an indented list is refused before it can look like it works");
  }

  @Test
  void theScalarKeysAreReadAndCommentsAndQuotesAreNot() {
    DeploymentSpec spec =
        parse(
            """
            ---
            deployment_target: platform   # cross-environment

            deploy_branches: "environment/prod"
            health_path: /idp/q/health/ready
            """);
    assertEquals(List.of("environment/prod"), spec.deployBranches());
    assertFalse(spec.availableOnEnv());
    assertEquals("/idp/q/health/ready", spec.healthPath());
    assertNull(spec.healthCmd());
  }

  @Test
  void aNonHttpImageDeclaresItsOwnProbeSpacesAndAll() {
    // The deployable-image case, and the reason the value gets no charset: postgres has neither
    // curl nor anything on 8080, so a path-shaped gate can never pass. The command is one argv
    // element that docker runs with /bin/sh -c, so its spaces, flags and || are the shell's.
    assertEquals(
        "pg_isready -U postgres || exit 1",
        parse("health_cmd: pg_isready -U postgres || exit 1\n").healthCmd());
    assertEquals(
        "test -f /var/lib/ready", parse("health_cmd: \"test -f /var/lib/ready\"\n").healthCmd());
  }

  @Test
  void aFileThatNamesNoHealthCmdGetsTheHttpProbe() {
    // Null is the statement "this image has an HTTP surface", which is every service the platform
    // had before deployable images existed.
    assertNull(parse("health_path: /idp/q/health/ready\n").healthCmd());
    assertNull(DeploymentSpec.DEFAULTS.healthCmd());
  }

  @Test
  void aHealthCmdAndAHealthPathTogetherAreAnError() {
    // Not two settings on one gate: the command replaces the whole HTTP mechanism, so a file with
    // both says two things about one thing and the writer has to pick.
    String message = messageOf("health_path: /q/health/ready\nhealth_cmd: pg_isready\n");
    assertTrue(message.contains("health_cmd"), message);
    assertTrue(message.contains("health_path"), message);
    // Either order, since neither is the one that "came second".
    assertTrue(
        messageOf("health_cmd: pg_isready\nhealth_path: /q/health/ready\n").contains("health_cmd"));
  }

  @Test
  void aBlankOrOversizedHealthCmdIsAnError() {
    // What is left to check once the charset is deliberately open: a probe that says nothing, and
    // one long enough to be a mistake.
    assertTrue(messageOf("health_cmd:\n").contains("health_cmd"));
    assertTrue(messageOf("health_cmd: \"   \"\n").contains("health_cmd"));
    assertTrue(messageOf("health_cmd: " + "x".repeat(513) + "\n").contains("health_cmd"));
    assertEquals("x".repeat(512), parse("health_cmd: " + "x".repeat(512) + "\n").healthCmd());
  }

  @Test
  void aFileThatNamesNoDeploymentTargetIsTheOrdinaryCase() {
    // The key is retired, so saying nothing is what every rewritten file says. It is the whole of
    // the defaults, exactly as it was before the key existed.
    assertEquals(DeploymentSpec.DEFAULTS, parse(""));
    assertEquals(List.of(), parse("health_path: /ci/q/health/ready\n").deployBranches());
  }

  @Test
  void theRetiredDeploymentTargetKeyIsAcceptedAndReachesNothing() {
    // RETIRED, and the tolerance is permanent: a spec is fetched at the BUILT sha, so a rollback
    // pin, a redeploy of an older commit and every repository that still carries the line present a
    // file containing the key — and an unknown key fails a deployment. Making it unknown would turn
    // all of those red for good. `deploy_branches` is the same lesson, learned the expensive way.
    assertEquals(DeploymentSpec.DEFAULTS, parse("deployment_target: platform\n"));
  }

  @Test
  void theRetiredEnvironmentSpellingIsAcceptedTheSameWay() {
    // The other word the closed vocabulary used to have. It decides nothing now either, and it is
    // what most unedited files at an older sha carry.
    assertEquals(DeploymentSpec.DEFAULTS, parse("deployment_target: environment\n"));
  }

  @Test
  void theRetiredSingletonAliasIsStillAccepted() {
    // The alias of a vocabulary that is itself retired. A repository nobody has edited still carries
    // the word, and its spec is read at the BUILT sha forever — so refusing it would fail a
    // deployment over a value nothing reads.
    assertEquals(DeploymentSpec.DEFAULTS, parse("deployment_target: singleton\n"));
  }

  @Test
  void anyValueAtAllIsAcceptedBecauseNothingReadsTheKey() {
    // Deliberately no enum lookup and no validation: an older sha may carry any word its author
    // wrote, and a value acted on by nobody is not worth failing a deployment over.
    assertEquals(DeploymentSpec.DEFAULTS, parse("deployment_target: anything-at-all\n"));
    assertEquals(DeploymentSpec.DEFAULTS, parse("deployment_target: Platform\n"));
    assertEquals(DeploymentSpec.DEFAULTS, parse("deployment_target:\n"));
  }

  @Test
  void aRetiredTargetBesideAvailableOnEnvIsNoLongerAContradiction() {
    // There is no platform service left for `available_on_env: true` to contradict — every service
    // is an ordinary environment service in the one tier — so the cross-check is gone. The file
    // still has to parse, because a commit built before the sweep carries exactly this pair.
    assertTrue(parse("deployment_target: platform\navailable_on_env: true\n").availableOnEnv());
    assertTrue(parse("deployment_target: singleton\navailable_on_env: true\n").availableOnEnv());
  }

  @Test
  void aFileThatNamesNoHealthPathLeavesItToTheConvention() {
    // Null is the statement "this repository said nothing", and registration turns that into the
    // derived path. The parser must not invent one here — it does not know the service's name.
    assertNull(parse("available_on_env: true\n").healthPath());
  }

  @Test
  void aHealthPathThatIsNotAnAbsolutePathIsAnError() {
    // The value ends up in a container's --health-cmd, so it is checked as strictly as the API's.
    assertTrue(messageOf("health_path: q/health/ready\n").contains("health_path"));
    assertTrue(messageOf("health_path: /q/health/ready?x=1\n").contains("health_path"));
    assertTrue(messageOf("health_path:\n").contains("health_path"));
  }

  @Test
  void aFileThatNamesNoDeployBranchesSaysSoWithAnEmptyList() {
    // The deployer decides nothing on this key — a build deploys wherever an environment listens to
    // its branch, on either plane — so "said nothing" has to stay distinguishable from "said none"
    // for the reader that does use it, the release flow.
    assertEquals(List.of(), parse("deployment_target: platform\n").deployBranches());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.deployBranches());
  }

  @Test
  void deployBranchesIsACommaSeparatedRefListAndEveryRefIsChecked() {
    // One line, because this file has no YAML sequences; a comma cannot occur in a ref name, which
    // is what makes the separator safe.
    assertEquals(
        List.of("environment/prod", "environment/dev"),
        parse("deploy_branches: environment/prod, environment/dev\n").deployBranches());
    assertTrue(messageOf("deploy_branches: ../../etc\n").contains("deploy_branches"));
    // A trailing comma, or the key with nothing after it, is a writer who meant to say something.
    assertTrue(messageOf("deploy_branches: environment/prod,\n").contains("deploy_branches"));
    assertTrue(messageOf("deploy_branches:\n").contains("deploy_branches"));
  }

  @Test
  void theRetiredBranchKeyIsNoLongerKnown() {
    // It named the platform plane's own deploy ref, and the plane has none: `environment/<name>` is
    // the whole set. A repository still carrying the key is corrected rather than half-obeyed.
    assertTrue(messageOf("deployment_target: platform\nbranch: release\n").contains("unknown key"));
  }

  @Test
  void aPublicNodeSaysSoAndNothingElseDoes() {
    assertTrue(parse("available_on_env: true\n").availableOnEnv());
    assertFalse(parse("available_on_env: false\n").availableOnEnv());
  }

  @Test
  void anApplicationThatCannotOverlapItselfSaysStopFirst() {
    // The default is the lossless one — the successor starts beside the predecessor, so an
    // orchestrator that fails it reverts to something that never stopped serving. What a repository
    // says here is that it cannot be two processes at once: one binder per published host port, one
    // writer per store, one holder of a config volume.
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST, parse("").updateOrder(), "the default");
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST,
        parse("update_order: start-first\n").updateOrder());
    assertEquals(
        DeploymentDriver.UpdateOrder.STOP_FIRST, parse("update_order: stop-first\n").updateOrder());
  }

  @Test
  void anUpdateOrderOutsideThePairIsAnError() {
    // Refused rather than defaulted, like every other value here: the difference between the two is
    // whether an application is ever two processes at once, and a silent default answers that
    // question for a repository that was trying to answer it itself.
    String message = messageOf("update_order: rolling\n");
    assertTrue(message.contains("start-first"), message);
    assertTrue(message.contains("stop-first"), message);
    assertTrue(message.contains("rolling"), "the message names what was written: " + message);
    // The enum's own spelling is the file's, so neither side has to translate.
    assertTrue(messageOf("update_order: STOP_FIRST\n").contains("update_order"));
  }

  @Test
  void aServiceWhosePortMustSurviveItsOwnReplacementSaysIngress() {
    // The default is what every publishing service does today — the task binds the port on the
    // node — so a file that says nothing is deployed byte-for-byte as before. `ingress` gives the
    // port to swarm's routing mesh, which keeps holding it while the successor starts.
    assertEquals(DeploymentDriver.PublishMode.HOST, parse("").publishMode(), "the default");
    assertEquals(
        DeploymentDriver.PublishMode.HOST, parse("publish_mode: host\n").publishMode());
    assertEquals(
        DeploymentDriver.PublishMode.INGRESS, parse("publish_mode: ingress\n").publishMode());
  }

  @Test
  void aPublishModeOutsideThePairIsAnError() {
    // Refused rather than defaulted: the difference is whether a replacement can start while the
    // predecessor still holds the port, which is the whole reason the key exists.
    String message = messageOf("publish_mode: mesh\n");
    assertTrue(message.contains("host"), message);
    assertTrue(message.contains("ingress"), message);
    assertTrue(message.contains("mesh"), "the message names what was written: " + message);
    // Docker's own spelling is the file's, so neither side has to translate.
    assertTrue(messageOf("publish_mode: INGRESS\n").contains("publish_mode"));
  }

  @Test
  void thePublishModeAndTheUpdateOrderAreIndependentStatements() {
    // Nothing here derives one from the other. An ingress-mode service keeps the default
    // start-first — that is the point of ingress — and one that declares stop-first gets it.
    DeploymentSpec ingress = parse("publish_mode: ingress\n");
    assertEquals(DeploymentDriver.UpdateOrder.START_FIRST, ingress.updateOrder());
    DeploymentSpec both = parse("publish_mode: ingress\nupdate_order: stop-first\n");
    assertEquals(DeploymentDriver.UpdateOrder.STOP_FIRST, both.updateOrder());
    assertEquals(DeploymentDriver.PublishMode.INGRESS, both.publishMode());
    // ...and a host-mode service is not pushed onto stop-first either: the repository says so.
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST, parse("publish_mode: host\n").updateOrder());
  }

  @Test
  void anUnknownKeyIsAnError() {
    // A lenient parser answers a typo with a default, which deploys the wrong topology in silence.
    String message = messageOf("deployment_targets: platform\n");
    assertTrue(message.contains("unknown key"), message);
    // ...and the message lists every key there is, so a typo is answered with the vocabulary.
    assertTrue(message.contains("resources"), message);
    assertTrue(message.contains("update_order"), message);
    assertTrue(message.contains("publish_mode"), message);
    assertTrue(message.contains("navigation-entries"), message);
    assertTrue(message.contains("application"), message);
    assertTrue(message.contains("renamed_from"), message);
    assertTrue(message.contains("stop_grace_period"), message);
  }

  @Test
  void aRepositoryWhoseTransfersOutliveThirtySecondsRaisesItsStopGrace() {
    // qits-1170: a long download through qits-edge or qits-mirror was cut on every redeploy, because
    // the grace was one fixed 30s for every service. Absent, it is still exactly that 30s, so a file
    // that says nothing deploys byte-for-byte as before.
    assertEquals(Duration.ofSeconds(30), parse("").stopGracePeriod(), "the default");
    assertEquals(DeploymentDriver.DEFAULT_STOP_GRACE_PERIOD, parse("").stopGracePeriod());
    // Docker's own spelling, the way a repository will write it.
    assertEquals(Duration.ofMinutes(10), parse("stop_grace_period: 10m\n").stopGracePeriod());
    assertEquals(Duration.ofSeconds(90), parse("stop_grace_period: 90s\n").stopGracePeriod());
    assertEquals(
        Duration.ofSeconds(570), parse("stop_grace_period: 9m30s\n").stopGracePeriod());
    assertEquals(Duration.ofHours(1), parse("stop_grace_period: 1h\n").stopGracePeriod());
    assertEquals(Duration.ofMillis(1500), parse("stop_grace_period: 1500ms\n").stopGracePeriod());
    assertEquals(
        Duration.ofMinutes(5), parse("stop_grace_period: \"5m\"\n").stopGracePeriod(), "quoted");
  }

  @Test
  void aStopGraceThatIsNotAPositiveDurationIsAnError() {
    // Refused rather than defaulted: thirty seconds in answer to a typo is the cut download the key
    // exists to prevent, with nothing to read about why.
    for (String garbage :
        List.of("ten minutes", "10", "10M", "1.5m", "-30s", "30s9m", "1m1m", "10 m", "1d", "")) {
      String message = messageOf("stop_grace_period: " + garbage + "\n");
      assertTrue(message.contains("stop_grace_period"), message);
      assertTrue(message.contains("line 1"), "it names the line to fix: " + message);
    }
    // Zero is not "no grace" to docker but an immediate SIGKILL, which is the opposite of asking.
    assertTrue(messageOf("stop_grace_period: 0s\n").contains("above zero"));
    assertTrue(messageOf("stop_grace_period: 0m0s\n").contains("above zero"));
    // ...and the ceiling: under stop-first the deployer's one worker waits the grace out, so an
    // unbounded value would stall every other deployment behind this one.
    String tooLong = messageOf("stop_grace_period: 61m\n");
    assertTrue(tooLong.contains("at most 1h"), tooLong);
    assertTrue(tooLong.contains("61m"), "the message names what was written: " + tooLong);
    assertTrue(messageOf("stop_grace_period: 99999999999s\n").contains("stop_grace_period"));
  }

  @Test
  void theStopGraceIsIndependentOfTheUpdateOrder() {
    DeploymentSpec spec = parse("update_order: stop-first\nstop_grace_period: 2m\n");
    assertEquals(DeploymentDriver.UpdateOrder.STOP_FIRST, spec.updateOrder());
    assertEquals(Duration.ofMinutes(2), spec.stopGracePeriod());
  }

  @Test
  void aDuplicateKeyIsAnError() {
    assertTrue(
        messageOf("routes: /ci\nroutes: /cd\n").contains("duplicate key"));
  }

  @Test
  void theRetiredTargetKeyStillCountsAsADuplicateWhenItIsWrittenTwice() {
    // Accepting any value is not the same as accepting the key twice: the duplicate rule is about
    // the FILE saying one thing once, and it is unchanged by a key being ignored. A spec at an
    // older sha that states the key twice was always a mistake and still is.
    assertTrue(
        messageOf("deployment_target: environment\ndeployment_target: platform\n")
            .contains("duplicate key"));
    assertTrue(
        messageOf("deployment_target: platform\ndeployment_target: platform\n")
            .contains("duplicate key"));
  }

  @Test
  void aValueOutsideTheEnumIsAnError() {
    assertTrue(messageOf("available_on_env: yes\n").contains("true"));
  }

  @Test
  void nestingAndNonMappingLinesAreErrors() {
    assertTrue(
        messageOf("available_on_env: false\n  deployment_target: platform\n").contains("nesting"));
    assertTrue(messageOf("just a sentence\n").contains("key: value"));
  }

  @Test
  void deployBranchesIsReadForSomebodyElseAndSitsBesideTheOtherRetiredKey() {
    // The key belonged to the release flow, which read the same file for its promotion targets.
    // This parser is strict, so a key another reader needs is a key this reader has to know —
    // accepted-and-unused rather than a second file. The two retired keys sit beside each other in
    // a file at an older sha, and neither one's tolerance may depend on the other's value.
    assertEquals(
        List.of("environment/prod"),
        parse("deployment_target: environment\ndeploy_branches: environment/prod\n")
            .deployBranches());
    assertEquals(
        List.of("environment/prod"),
        parse("deployment_target: platform\ndeploy_branches: environment/prod\n").deployBranches());
  }
  // --- binds, publishes, aliases: the host-facing shape (qits-765) -----------------------------

  @Test
  void aFileThatNamesNoneOfTheThreeDeclaresNoShape() {
    // Every file that exists today, and DEFAULTS with it: nothing reaches the argv from here.
    assertEquals(DeclaredShape.NONE, parse("available_on_env: false\n").shape());
    assertEquals(DeclaredShape.NONE, DeploymentSpec.DEFAULTS.shape());
  }

  @Test
  void bindsAreHostPathsMountedAtTargetsAndRoSurvives() {
    DeclaredShape shape =
        parse("binds: /var/run/docker.sock:/var/run/docker.sock, /srv/certs:/certs:ro\n").shape();

    assertEquals(
        List.of(
            new ServiceExtras.Mount(
                ServiceExtras.MountKind.BIND,
                "/var/run/docker.sock",
                "/var/run/docker.sock",
                false),
            new ServiceExtras.Mount(ServiceExtras.MountKind.BIND, "/srv/certs", "/certs", true)),
        shape.binds());
    assertTrue(shape.bindsDockerSocket());
    assertFalse(parse("binds: /srv/certs:/certs\n").shape().bindsDockerSocket());
  }

  @Test
  void aBindIsTwoAbsolutePathsAndRoIsTheOnlyOption() {
    assertTrue(messageOf("binds: var/run/docker.sock:/var/run/docker.sock\n").contains("absolute"));
    assertTrue(messageOf("binds: /var/run/docker.sock:docker.sock\n").contains("absolute"));
    assertTrue(messageOf("binds: /var/run/docker.sock\n").contains("<abs-host-path>"));
    assertTrue(messageOf("binds: /a:/b:rw\n").contains("only `ro`"));
    assertTrue(messageOf("binds: /my dir:/b\n").contains("absolute"), "a space forges nothing");
    assertTrue(messageOf("binds: /a:/b,\n").contains("blank entry"));
  }

  @Test
  void aBindTargetTwiceOrOverADeclaredVolumeIsRefused() {
    // Two mounts on one directory is a create swarm refuses outright, and the file contradicts
    // itself: only its author knows which half was meant.
    assertTrue(messageOf("binds: /a:/data, /b:/data\n").contains("twice"));
    String message = messageOf("volumes: private:data:/data\nbinds: /srv/data:/data\n");
    assertTrue(message.contains("volumes"), message);
    assertTrue(message.contains("line 2"), message);
    // One host path at two targets is a thing a person can mean.
    assertEquals(2, parse("binds: /srv/a:/a, /srv/a:/b\n").shape().binds().size());
  }

  @Test
  void publishesAreTheExtrasGrammarReadByTheSameCode() {
    DeclaredShape shape = parse("publishes: 8080:8080, 443:8443/tcp, 0.0.0.0:5353:53/UDP\n").shape();

    // Kept as written, and read by the extras' own code once the deployment knows its port.
    assertEquals(List.of("8080:8080", "443:8443/tcp", "0.0.0.0:5353:53/UDP"), shape.publishes());
    assertEquals(
        List.of(
            new ServiceExtras.Publish(null, 8080, 8080, null),
            new ServiceExtras.Publish(null, 443, 8443, "tcp"),
            new ServiceExtras.Publish("0.0.0.0", 5353, 53, "udp")),
        shape.publishesFor(8080));
  }

  @Test
  void theHostPortMayBeTheInstallationsQitsPortAndIsKeptAsWritten() {
    DeclaredShape shape = parse("publishes: ${QITS_PORT}:8080, 443:8443\n").shape();

    assertEquals(List.of("${QITS_PORT}:8080", "443:8443"), shape.publishes());
    assertEquals(
        List.of(
            new ServiceExtras.Publish(null, 9080, 8080, null),
            new ServiceExtras.Publish(null, 443, 8443, null)),
        shape.publishesFor(9080));
    // With an ip and a protocol around it, it is still the host port.
    assertEquals(
        new ServiceExtras.Publish("0.0.0.0", 80, 8080, "tcp"),
        parse("publishes: 0.0.0.0:${QITS_PORT}:8080/tcp\n").shape().publishesFor(80).get(0));
  }

  @Test
  void qitsPortIsRefusedAnywhereButTheHostPortAndNoOtherPlaceholderIsKnown() {
    assertTrue(
        messageOf("publishes: 8080:${QITS_PORT}\n").contains("as the whole host port"),
        messageOf("publishes: 8080:${QITS_PORT}\n"));
    assertTrue(messageOf("publishes: ${QITS_PORT}0:8080\n").contains("as the whole host port"));
    assertTrue(
        messageOf("publishes: ${QITS_PORT}:${QITS_PORT}\n").contains("as the whole host port"));
    assertTrue(messageOf("publishes: ${QITS_PORT}\n").contains("as the whole host port"));
    assertTrue(
        messageOf("publishes: ${QITS_HTTPS_PORT}:8443\n").contains("the one placeholder"),
        messageOf("publishes: ${QITS_HTTPS_PORT}:8443\n"));
    assertTrue(messageOf("publishes: $PORT:8080\n").contains("the one placeholder"));
    assertTrue(
        messageOf("publishes: ${QITS_PORT}:8080, ${QITS_PORT}:9090\n").contains("twice"));
    // Over the other protocol it is another port, as a literal one is.
    assertEquals(
        2, parse("publishes: ${QITS_PORT}:53/tcp, ${QITS_PORT}:53/udp\n").shape().publishes().size());
  }

  @Test
  void aQitsPortThatLandsOnAnotherDeclaredPortIsRefusedOnceSubstituted() {
    DeclaredShape shape = parse("publishes: ${QITS_PORT}:8080, 9090:9090\n").shape();

    assertEquals(2, shape.publishesFor(8080).size());
    ServiceExtras.Refused refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            ServiceExtras.Refused.class, () -> shape.publishesFor(9090));
    assertTrue(refused.getMessage().contains("9090/tcp"), refused.getMessage());
  }

  @Test
  void aMalformedPublishIsRefusedWithTheExtrasSentence() {
    assertTrue(messageOf("publishes: 8080\n").contains("[<ip>:]<host-port>:<port>"));
    assertTrue(messageOf("publishes: 8080:8080/sctp\n").contains("neither tcp nor udp"));
    assertTrue(messageOf("publishes: 70000:8080\n").contains("not a port number"));
    assertTrue(messageOf("publishes: localhost:8080:8080\n").contains("IPv4"));
    assertTrue(messageOf("publishes: 8080:8080,\n").contains("blank entry"));
    // An ip that is not 0.0.0.0 PARSES here, as deployment config's does: it is the swarm driver
    // that refuses it, because swarm's publish has no ip field.
    assertEquals(
        "127.0.0.1",
        parse("publishes: 127.0.0.1:8080:8080\n").shape().publishesFor(8080).get(0).ip());
  }

  @Test
  void aPublishedPortTwiceIsRefusedAndAnUnnamedProtocolIsTcp() {
    assertTrue(messageOf("publishes: 8080:8080, 8080:9090\n").contains("twice"));
    assertTrue(messageOf("publishes: 8080:8080, 8080:9090/tcp\n").contains("8080/tcp"));
    // The same number over the other protocol is another port to swarm.
    assertEquals(2, parse("publishes: 53:53/tcp, 53:53/udp\n").shape().publishes().size());
  }

  @Test
  void aliasesAreKeptAsWrittenAndTheEnvironmentIsPutInAtDeployTime() {
    DeclaredShape shape =
        parse(
                "aliases: registry.${QITS_ENVIRONMENT}.localhost, githost.${QITS_ENVIRONMENT}.internal,"
                    + " plain.example\n")
            .shape();

    assertEquals(
        List.of(
            "registry.${QITS_ENVIRONMENT}.localhost",
            "githost.${QITS_ENVIRONMENT}.internal",
            "plain.example"),
        shape.aliases(),
        "the parser never knows the tier, so it keeps the token");
    assertEquals(
        List.of("registry.dev.localhost", "githost.dev.internal", "plain.example"),
        shape.aliasesFor("dev"));
    assertEquals(
        List.of("registry.prod.localhost", "githost.prod.internal", "plain.example"),
        shape.aliasesFor("prod"));
  }

  @Test
  void anAliasIsOneNameAndOnlyTheOnePlaceholderIsKnown() {
    assertTrue(messageOf("aliases: my alias\n").contains("not one name"));
    assertTrue(messageOf("aliases: a.${QITS_DOMAIN}\n").contains("placeholder"));
    assertTrue(messageOf("aliases: $HOME.localhost\n").contains("placeholder"));
    assertTrue(messageOf("aliases: a,\n").contains("blank entry"));
    assertTrue(messageOf("aliases: a.localhost, a.localhost\n").contains("twice"));
  }

  @Test
  void anAliasIsCheckedAgainAfterTheSubstitution() {
    // The substituted value is what reaches the argv, so it is held to the rule a second time — and
    // a token with no tier to put there is a refusal, never a literal `${…}` registered as a name.
    DeclaredShape shape = new DeclaredShape(List.of(), List.of(), List.of("r.${QITS_ENVIRONMENT}"));
    assertThrows(ServiceExtras.Refused.class, () -> shape.aliasesFor("bad tier"));
    assertThrows(ServiceExtras.Refused.class, () -> shape.aliasesFor(null));
    assertEquals(List.of("plain"), new DeclaredShape(null, null, List.of("plain")).aliasesFor(null));
  }

  @Test
  void eachOfTheThreeKeysIsStatedOnce() {
    assertTrue(messageOf("binds: /a:/a\nbinds: /b:/b\n").contains("duplicate key"));
    assertTrue(messageOf("publishes: 1:1\npublishes: 2:2\n").contains("duplicate key"));
    assertTrue(messageOf("aliases: a\naliases: b\n").contains("duplicate key"));
  }

  @Test
  void theUnknownKeySentenceNamesTheThreeNewKeys() {
    String message = messageOf("bind: /a:/a\n");
    assertTrue(message.contains("binds"), message);
    assertTrue(message.contains("publishes"), message);
    assertTrue(message.contains("aliases"), message);
  }
}
