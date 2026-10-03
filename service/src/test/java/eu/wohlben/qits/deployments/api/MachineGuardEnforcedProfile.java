package eu.wohlben.qits.deployments.api;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The deployment posture after the gate flips: {@code qits.auth.machine.required=true}. Everything
 * else here exists to make that posture testable without a qits-idp.
 *
 * <p><b>The verification key is inlined instead of fetched.</b> {@code quarkus.oidc.public-key} puts
 * the extension into local verification, and {@code auth-server-url} is cleared beside it — the key
 * alone is not enough, because a tenant that still has a server URL tries to reach it on the first
 * bearer and answers 500 when it cannot. Clearing the URL also drops the issuer that came with it,
 * so {@code token.issuer} is stated explicitly and {@code iss} stays checked. Everything else — the
 * signature, and {@code aud=qits-platform} from application.properties — is the shipped configuration,
 * checked by the real extension exactly as it will be against the real idp.
 *
 * <p><b>The dev user is switched off</b>, and that is load-bearing rather than tidy. Under
 * {@code %test} qits-auth-core ships {@code qits.auth.forward.dev-user=dev}, so forward-auth
 * authenticates every request and a bearer would never reach the token mechanism. Empty reads back
 * as absent, which is the mechanism's "no synthetic identity" state — the same state a production
 * build is always in.
 */
public class MachineGuardEnforcedProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "qits.auth.machine.required", "true",
        "qits.auth.forward.dev-user", "",
        "quarkus.oidc.auth-server-url", "",
        "quarkus.oidc.token.issuer", MachineTokens.ISSUER,
        "quarkus.oidc.public-key", base64Key());
  }

  // The PEM body without its armour: quarkus.oidc.public-key takes the key, not a location.
  private static String base64Key() {
    return MachineTokens.pem(MachineTokens.VERIFICATION_KEY)
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replaceAll("\\s", "");
  }
}
