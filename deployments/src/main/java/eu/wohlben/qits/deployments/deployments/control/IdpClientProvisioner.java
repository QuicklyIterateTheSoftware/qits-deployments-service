package eu.wohlben.qits.deployments.deployments.control;

/**
 * The seam between this component's orchestration and qits-idp's service-client API — the {@link
 * DeploymentDriver} arrangement again: this module owns the interface and the arm logic that calls
 * it ({@link ResourceProvisioning}), {@code service/idphost} owns the sole production
 * implementation ({@code HttpIdpClientProvisioner}), and the suite installs a scripted fake
 * (`FakeIdpClientProvisioner`) so a clone's {@code mvn verify} reaches no idp.
 *
 * <p><b>Four verbs, not one.</b> {@link ResourceProvisioner} is a single idempotent {@code ensure}
 * because postgres answers every drift case from one round trip of DDL; qits-idp's own API is
 * shaped as a read and two writes ({@code GET}, {@code POST}, {@code POST .../secret}), and there is
 * no single call that means "converge, whatever the state". So the arm logic — which of the three to
 * call, and in what order — is domain code in {@code ResourceProvisioning}, testable against the
 * fake, and this interface stays a plain courier of the requests. The fourth, {@link #delete}, is
 * not part of that convergence at all: it retires a client nothing should hold any more ({@code
 * ResourceProvisioning.retireIdpClient}), and it is idempotent on its own — a client already gone
 * is a delete that succeeded.
 *
 * <p><b>It never sees a password it did not just receive.</b> Unlike postgres, qits-idp never takes
 * a caller-supplied secret: {@link #create} and {@link #rotate} always come back with a
 * server-generated one, or fail. There is no "recreate the client with the stored secret" arm — a
 * database row whose secret idp has lost is fixed by creating a NEW client and overwriting the row,
 * not by handing idp back what this component remembers.
 */
public interface IdpClientProvisioner {

  /**
   * Whether qits-idp already holds a DATABASE row for this client id — {@code GET
   * /idp/api/service-clients/{id}} answering 200 with {@code source} {@code database} or {@code
   * both}, as opposed to 404 or {@code source: environment} alone. An environment-only client is a
   * legacy static entry this component knows nothing about and must not touch.
   */
  boolean databaseClientPresent(String clientId);

  /**
   * Create a new database service client. {@code conflict} is true on a 409 — a database row
   * already exists at idp despite this component's own registry disagreeing, the one case the
   * caller falls back to {@link #rotate} for.
   */
  Result create(String clientId);

  /** Rotate an existing database service client's secret. */
  Result rotate(String clientId);

  /**
   * Delete a database service client — {@code DELETE /idp/api/service-clients/{id}}. {@code ok} on
   * a 2xx AND on a 404: the client being absent is exactly the state a delete asks for, so a retry
   * after a lost answer converges rather than failing. Never carries a secret.
   */
  Result delete(String clientId);

  /**
   * What qits-idp answered. {@code secret} is set exactly when {@code ok} is true on {@link #create}
   * and {@link #rotate}, and never on {@link #delete}, which has none to hand back; {@code detail} is
   * the operator-facing sentence on failure and null on success — and, like {@link
   * ResourceProvisioner.Result}, neither field may ever carry a secret into a log.
   */
  record Result(boolean ok, boolean conflict, String secret, String detail) {}
}
