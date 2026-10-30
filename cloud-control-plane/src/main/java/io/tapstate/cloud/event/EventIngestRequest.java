package io.tapstate.cloud.event;

import java.time.Instant;
import java.util.Map;

/**
 * Input contract for every event source: the HTTP ingestion endpoint, the
 * user-operation interceptor, scheduled jobs and external webhook adapters.
 */
public record EventIngestRequest(
    String orgId,
    String source,
    String type,
    EventSeverity severity,
    String subject,
    String actor,
    Instant occurredAt,
    String dedupeKey,
    Map<String, Object> payload
) {
}
