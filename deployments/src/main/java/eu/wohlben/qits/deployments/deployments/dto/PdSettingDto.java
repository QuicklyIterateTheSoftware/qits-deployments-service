package eu.wohlben.qits.deployments.deployments.dto;

import java.time.Instant;

/**
 * One {@code pd_setting} row on the wire. {@code value} is ALWAYS null when {@code secret} is true —
 * on the listing and on the answer to the write that stored it alike.
 */
public record PdSettingDto(
    String application,
    String environment,
    String key,
    String value,
    boolean secret,
    Instant updatedAt,
    String updatedBy) {}
