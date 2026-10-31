package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * This repository deploys ITSELF, so its own {@code .config/qits/deployments.yml} is the one spec on
 * the estate whose reader is in the same reactor — and the only one a build here can hold to account.
 *
 * <p><b>This file IS parsed here, with the real parser.</b> {@link DeploymentSpecParser} is this
 * component's own, a spec is fetched at the released tag, and an
 * unknown key fails a deployment. So parsing it here is not a second opinion, it is the only opinion.
 *
 * <p><b>What it pins is the self-deploy's health gate.</b> {@link DeployService#resolveHealthPath}
 * asks the spec, then the STORED {@code pd_service.health_path} row, then {@link
 * DeployService#routeHealthPath} off this file's own {@code routes: /deployments}, then {@link
 * DeployService#conventionHealthPath} — and all of them agree now that the route segment and the
 * application name do. So this file states NO {@code health_path:}, and the assertion is that it
 * states none and that the derived default is the path the service actually serves. A key here
 * would be a second spelling of that string, free to drift from the route the image bakes in.
 *
 * <p>The key was present for exactly one release and was load-bearing then: the stored row held
 * {@code /platform-deployments/q/health/ready}, written when the route really was there, and a
 * stored row beats the convention. The route moved on 2026-09-26, the row was re-registered with the
 * served path, and the override came out. <b>Re-adding it is the regression this test names</b>, not
 * removing it.
 */
class OwnSpecTest {

  /** The application this repository deploys as — short, and not the repository's name. */
  private static final String APPLICATION = "qits-deployments";

  /** The spec, found by walking up from whatever directory surefire started this module in. */
  private static Path spec() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(SpecSource.SPEC_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no " + SpecSource.SPEC_PATH + " above " + Path.of("").toAbsolutePath());
  }

  private static DeploymentSpec parsed() throws IOException {
    return DeploymentSpecParser.parse(Files.readString(spec()), SpecSource.SPEC_PATH);
  }

  @Test
  void thisRepositoryDeploysAsTheShortApplicationName() throws IOException {
    assertEquals(
        APPLICATION,
        parsed().application(),
        "the repository is named for its role and the application is not — see the file's own header");
  }

  @Test
  void noHealthPathIsSTATEDAndTheConventionDerivesTheServedOne() throws IOException {
    // ABSENT: every source resolveHealthPath asks agrees now, so an override would only restate
    // one of them — and a restatement is a second place for the path to drift from the route.
    assertNull(
        parsed().healthPath(),
        "health_path: must stay OUT of .config/qits/deployments.yml — the convention derives the"
            + " served path and the stored pd_service row names it too, so a key here is a second"
            + " spelling free to drift from the route this image bakes in");

    // …and what the route and the name-convention each derive is the path this service actually
    // serves, which is the half the absence relies on: with no override and no stored row, either
    // default IS the gate, and this file's `routes: /deployments` makes them the same string.
    assertEquals(
        "/deployments/q/health/ready",
        DeployService.conventionHealthPath(APPLICATION),
        "the health-path convention and the served non-application root are the same string; a"
            + " difference means one of the two moved without the other");
    assertEquals(
        Optional.of("/deployments/q/health/ready"),
        DeployService.routeHealthPath(parsed().routes()),
        "the first declared route and the served non-application root are the same string too");
  }

  @Test
  void theRoutesDeclareTheServedPrefixAlone() throws IOException {
    // The edge projects its route table from `routes:`, so the live prefix has to be there or the
    // API is reachable on no vhost at all — and nothing else may be, because a declared prefix this
    // service does not serve is a route the edge advertises into a 404. The retired
    // /platform-deployments was the second entry for exactly one release, while
    // api/LegacyPrefixReroute rewrote it; both went on 2026-09-26.
    assertEquals(
        java.util.List.of("/deployments"),
        parsed().routes(),
        "the served prefix, and only the served prefix");
  }

  @Test
  void theApiDocsLinkSitsUnderTheServedPrefix() throws IOException {
    String apiDocs = parsed().apiDocs();

    assertNotNull(apiDocs, "the shell's Api Docs viewer reads this off the navigation document");
    assertTrue(
        apiDocs.startsWith("/deployments/"),
        "a documented API at a prefix this service does not serve is a dead link in the shell: "
            + apiDocs);
  }
}
