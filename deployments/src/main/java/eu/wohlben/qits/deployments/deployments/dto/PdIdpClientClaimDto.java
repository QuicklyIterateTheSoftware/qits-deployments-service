package eu.wohlben.qits.deployments.deployments.dto;

import java.time.Instant;

/**
 * One {@code idp-client} row this component provisioned or boot-registered, named well enough for
 * a garbage collector to tell "issued and claimed" apart from "issued and orphaned" — without the
 * credential the row also carries.
 *
 * <p><b>No secret.</b> {@code PdResource} carries a {@code password} for every row — generated here
 * for a postgres row, issued by qits-idp for an idp-client row — and this DTO omits it on purpose;
 * the one caller of this listing is a collector deciding which client ids are still claimed, which
 * never needs the credential to answer that.
 *
 * <p>{@code environmentName} is null for a platform-plane row, exactly as {@link PdResource} itself
 * leaves it — there is no tier to report for the deployer's own boot row or any other client
 * provisioned outside an environment.
 */
public record PdIdpClientClaimDto(
    String clientId, String applicationName, String environmentName, Instant createdAt) {}
