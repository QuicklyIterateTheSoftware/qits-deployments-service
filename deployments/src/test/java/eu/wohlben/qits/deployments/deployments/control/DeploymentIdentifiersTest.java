package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.deployments.environments.control.ServiceCatalog.LinkedService;
import eu.wohlben.qits.deployments.environments.entity.PdService;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The boundary validation of everything that reaches an argv but is never stored — plus the one
 * intake field that reaches neither and is bounded anyway (the run id, whose length is all that
 * could hurt). The names, branches and health paths the topology keeps are checked by {@code
 * PdIdentifiersTest} next door.
 *
 * <p>The two derived conventions are pinned here too, because they are the same kind of claim: an
 * image reference and a container name are strings this component composes and a publisher, an
 * operator or a bootstrap grep has to be able to predict.
 */
class DeploymentIdentifiersTest {

  @Test
  void shasAreHexObjectIds() {
    assertEquals("a".repeat(40), DeploymentIdentifiers.requireSha("a".repeat(40)));
    assertEquals("1234abc", DeploymentIdentifiers.requireSha("1234abc"));
    for (String hostile : new String[] {null, "", "latest", "HEAD", "a".repeat(65), "12345g7"}) {
      assertThrows(BadRequestException.class, () -> DeploymentIdentifiers.requireSha(hostile));
    }
  }

  @Test
  void repositoryIdsAreSlugs() {
    assertEquals("qits-workspaces", DeploymentIdentifiers.requireRepoId("qits-workspaces"));
    for (String hostile : new String[] {null, "", "-leads", "has space", "a/b", "id;rm"}) {
      assertThrows(
          BadRequestException.class,
          () -> DeploymentIdentifiers.requireRepoId(hostile),
          String.valueOf(hostile));
    }
  }

  @Test
  void runIdsAreOptionalAndBounded() {
    // Absent is a first-class answer: it is what a sender that names no run records.
    assertNull(DeploymentIdentifiers.requireRunId(null));
    // What qits-ci actually sends, and the shape of a hand-replayed one.
    assertEquals(
        "6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61",
        DeploymentIdentifiers.requireRunId("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61"));
    assertEquals("run-1", DeploymentIdentifiers.requireRunId("run-1"));
    // The boundary is the point of the check — the column is varchar(255) and an oversized value
    // would fail the insert of a fire-and-forget delivery instead of answering the sender.
    assertEquals("a".repeat(64), DeploymentIdentifiers.requireRunId("a".repeat(64)));
    for (String hostile : new String[] {"", "a".repeat(65), "-leads", "has space", "id;rm", "a/b"}) {
      assertThrows(
          BadRequestException.class, () -> DeploymentIdentifiers.requireRunId(hostile), hostile);
    }
  }

  @Test
  void theImageReferenceConventionIsTheOneSpelled() {
    // Pins the exact shape a publisher has to tag: <registry>/<repository>/<application>:<sha>.
    assertEquals(
        "qits-artifacts:8080/qits/qits-gateway:" + "a".repeat(40),
        ImageRefs.imageRef("qits-artifacts:8080", "qits", "qits-gateway", "a".repeat(40)));
  }

  @Test
  void containerNamesCarryTheTierTheApplicationAndTheDeployment() {
    // The prefix is the ancestor's `qits-cd-` renamed with everything else, and a bootstrap that
    // greps for containers greps for this.
    assertEquals(
        "qits-pd-some-epic-qits-gateway-0123abcd",
        ContainerNames.of("some-epic", "qits-gateway", "0123abcd-ffff-4000-8000-0000"));
  }

  @Test
  void containerNamesAreQUALIFIEDForTheApplicationsThatUsedToBeThePlane() {
    // What replaced the second half of the test above, which held that a platform deployment's name
    // DROPS the tier segment (`qits-pd-qits-platform-idp-…`). The plane is deleted, so a name that
    // dropped it would be a name with nothing where the tier goes — and the plane's own applications
    // are exactly the ones whose names move.
    assertEquals(
        "qits-pd-dev-qits-platform-idp-0123abcd",
        ContainerNames.of("dev", "qits-platform-idp", "0123abcd-ffff-4000-8000-0000"));
  }

  @Test
  void aNameWithNoTierSkipsTheSegmentRatherThanComposingADoubleDash() {
    // The one shape left with no tier to name: the refusal row a mid-bootstrap install records
    // before any environment is designated. `qits-pd--qits-gateway-…` would be the alternative.
    assertEquals(
        "qits-pd-qits-gateway-0123abcd",
        ContainerNames.of(null, "qits-gateway", "0123abcd-ffff-4000-8000-0000"));
  }

  @Test
  void theWireAliasCarriesTheTierForEveryService() {
    // What peers dial, and under swarm the service's own NAME. The qualifier is what lets two tiers
    // hold one application's address on the shared flat network without colliding.
    assertEquals("prod-qits-gateway", PdNetworks.alias("prod", "qits-gateway"));
    // ...including the applications that were the platform plane, which is the whole of this change
    // and the one cutover in it. Their alias WAS bare, because a peer in any tier reached them by
    // writing the bare name; swarm cannot rename a service, so the first deployment under this
    // derivation creates `dev-qits-platform-idp` beside the `qits-platform-idp` that was serving.
    // What makes that survivable is that the qualified name was granted as an extra network alias
    // one release earlier, on every one of the nine.
    assertEquals("dev-qits-platform-idp", PdNetworks.alias("dev", "qits-platform-idp"));
  }

  @Test
  void theHealthPathConventionStripsTheQitsPrefixAndNothingElse() {
    // The debt this closed: registration had no source for a path, every row was written null, and
    // every service mounted under its own prefix failed a gate against a URL that 404s.
    assertEquals(
        "/observability/q/health/ready", DeployService.conventionHealthPath("qits-observability"));
    assertEquals(
        "/deployments/q/health/ready",
        DeployService.conventionHealthPath("qits-deployments"));
    // A name without the prefix keeps the whole name...
    assertEquals("/mongrel/q/health/ready", DeployService.conventionHealthPath("mongrel"));
    // ...and so does one that is nothing BUT the prefix, rather than composing an empty segment.
    assertEquals("/qits-/q/health/ready", DeployService.conventionHealthPath("qits-"));
  }

  /** A spec stating only what each test cares about; every other field takes the parser's default. */
  private static DeploymentSpec specWith(String healthPath, List<String> routes) {
    return new DeploymentSpec(
        false, null, healthPath, null, null, null, null, null, routes, 8080, null, false, null,
        null, null, null, null, null);
  }

  /** The catalogue's stored row, with nothing linked — `resolveHealthPath` never reads the links. */
  private static Optional<LinkedService> stored(String healthPath) {
    PdService service = new PdService();
    service.healthPath = healthPath;
    return Optional.of(new LinkedService(service, List.of()));
  }

  @Test
  void routeHealthPathIsTheFirstRouteWithTheProbeAppended() {
    // qits-1773: the route a repository actually serves, not its deployed name.
    assertEquals(Optional.of("/stt/q/health/ready"), DeployService.routeHealthPath(List.of("/stt")));
    // Several routes: the first is the one that matters, exactly as navigation treats it.
    assertEquals(
        Optional.of("/artifacts/q/health/ready"),
        DeployService.routeHealthPath(List.of("/artifacts", "/v2")));
    // The root route composes no double slash...
    assertEquals(Optional.of("/q/health/ready"), DeployService.routeHealthPath(List.of("/")));
    // ...and nor would any other trailing slash, though the parser never hands one down today.
    assertEquals(Optional.of("/stt/q/health/ready"), DeployService.routeHealthPath(List.of("/stt/")));
    // No routes at all is a real, empty answer — the caller falls back to the name convention.
    assertEquals(Optional.empty(), DeployService.routeHealthPath(List.of()));
    assertEquals(Optional.empty(), DeployService.routeHealthPath(null));
  }

  @Test
  void resolveHealthPathFollowsTheFirstRouteBeforeTheNameConvention() {
    // qits-766's qits-stt -> qits-speech rename: the renamed application's name-derived path would
    // have been /speech/..., while the container goes on serving /stt because that is what the
    // repository declares in routes:.
    assertEquals(
        "/stt/q/health/ready",
        DeployService.resolveHealthPath(
            "qits-speech", specWith(null, List.of("/stt")), Optional.empty()));
  }

  @Test
  void resolveHealthPathFallsBackToTheNameConventionWithNoRoute() {
    // Unchanged behaviour for the common case: a spec declaring no routes: at all.
    assertEquals(
        "/observability/q/health/ready",
        DeployService.resolveHealthPath(
            "qits-observability", specWith(null, List.of()), Optional.empty()));
  }

  @Test
  void resolveHealthPathPrefersTheSpecsOwnHealthPathOverTheRoute() {
    assertEquals(
        "/custom/ready",
        DeployService.resolveHealthPath(
            "qits-speech", specWith("/custom/ready", List.of("/stt")), Optional.empty()));
  }

  @Test
  void resolveHealthPathPrefersTheStoredRowOverTheRouteDefault() {
    // The stored value is an operator's fix and must survive a green build that carries only the
    // route default, exactly as it already survived the name convention.
    assertEquals(
        "/stored/ready",
        DeployService.resolveHealthPath(
            "qits-speech", specWith(null, List.of("/stt")), stored("/stored/ready")));
  }

  @Test
  void resourceAttributeValuesCarryNoListPunctuation() {
    // The three values that go into OTEL_RESOURCE_ATTRIBUTES pass as they are today: a sha, an
    // environment name, a container name composed from both.
    assertEquals(
        "a".repeat(40), DeploymentIdentifiers.requireAttributeValue("a".repeat(40), "sha"));
    assertEquals(
        "some-epic", DeploymentIdentifiers.requireAttributeValue("some-epic", "environment"));
    assertEquals(
        "qits-pd-some-epic-qits-gateway-0123abcd",
        DeploymentIdentifiers.requireAttributeValue(
            "qits-pd-some-epic-qits-gateway-0123abcd", "container name"));
    // The list's own punctuation is what the check exists for: a comma starts a second pair and an
    // equals sign moves the key/value boundary.
    for (String hostile : new String[] {null, "", "a,service.name=impostor", "a=b", " "}) {
      assertThrows(
          BadRequestException.class,
          () -> DeploymentIdentifiers.requireAttributeValue(hostile, "x"),
          String.valueOf(hostile));
    }
  }
}
