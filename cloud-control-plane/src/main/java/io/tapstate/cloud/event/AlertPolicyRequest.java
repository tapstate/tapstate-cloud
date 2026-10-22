package io.tapstate.cloud.event;

import java.util.List;

/** Create/update payload for an alert policy. */
public record AlertPolicyRequest(
    String name,
    Boolean enabled,
    List<String> eventTypes,
    EventSeverity minSeverity,
    List<String> recipients,
    String channel,
    Long cooldownSeconds,
    String quietHoursStart,
    String quietHoursEnd,
    String timezone
) {
}
