package io.tapstate.cloud.event;

import java.time.Instant;
import java.util.Map;

/** Public event representation. Payload is already bounded and redacted. */
public record EventResponse(
    String id,
    String orgId,
    String source,
    String type,
    String severity,
    String subject,
    String actor,
    Instant occurredAt,
    String dedupeKey,
    int schemaVersion,
    Map<String, Object> payload,
    Instant createdAt
) {
    public static EventResponse from(PlatformEvent event) {
        return new EventResponse(
            event.getId() == null ? null : event.getId().toHexString(),
            event.getOrgId() == null ? null : event.getOrgId().toHexString(),
            event.getSource(),
            event.getType(),
            event.getSeverity() == null ? null : event.getSeverity().name(),
            event.getSubject(),
            event.getActor(),
            event.getOccurredAt(),
            event.getDedupeKey(),
            event.getSchemaVersion(),
            event.getPayload(),
            event.getCreatedAt());
    }
}
