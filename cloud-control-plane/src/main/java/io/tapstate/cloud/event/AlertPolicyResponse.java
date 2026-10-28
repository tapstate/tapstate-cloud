package io.tapstate.cloud.event;

import java.time.Instant;
import java.util.List;

public record AlertPolicyResponse(
    String id,
    String name,
    boolean enabled,
    List<String> eventTypes,
    String minSeverity,
    List<String> recipients,
    String channel,
    long cooldownSeconds,
    String quietHoursStart,
    String quietHoursEnd,
    String timezone,
    Instant createdAt,
    Instant updatedAt
) {
    public static AlertPolicyResponse from(AlertPolicy policy) {
        return new AlertPolicyResponse(
            policy.getId() == null ? null : policy.getId().toHexString(),
            policy.getName(),
            policy.isEnabled(),
            policy.getEventTypes(),
            policy.getMinSeverity() == null ? null : policy.getMinSeverity().name(),
            policy.getRecipients(),
            policy.getChannel(),
            policy.getCooldownSeconds(),
            policy.getQuietHoursStart(),
            policy.getQuietHoursEnd(),
            policy.getTimezone(),
            policy.getCreatedAt(),
            policy.getUpdatedAt());
    }
}
