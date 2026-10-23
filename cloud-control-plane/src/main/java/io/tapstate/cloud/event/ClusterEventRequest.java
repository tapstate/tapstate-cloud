package io.tapstate.cloud.event;

import java.time.Instant;
import java.util.Map;

/**
 * A runtime Cluster report pushed into the event center. Every field except
 * {@code type} is optional; the organization and the {@code cluster} source are
 * derived from the authenticated Cluster, never from the request body.
 * <p>
 * {@code dedupeKey} should be stable across retries of the same occurrence
 * (for example {@code task-123-failed-2026-01-01T00:00:00Z}), so a retried
 * report does not create a second event.
 */
public record ClusterEventRequest(
    String type,
    EventSeverity severity,
    String subject,
    Instant occurredAt,
    String dedupeKey,
    Map<String, Object> payload
) {
}
