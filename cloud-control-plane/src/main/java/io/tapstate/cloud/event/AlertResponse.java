package io.tapstate.cloud.event;

import java.time.Instant;

public record AlertResponse(
    String id,
    String orgId,
    String policyId,
    String policyName,
    String eventId,
    String eventType,
    String severity,
    String subject,
    String actor,
    String status,
    Instant createdAt
) {
    public static AlertResponse from(AlertRecord alert) {
        return new AlertResponse(
            alert.getId() == null ? null : alert.getId().toHexString(),
            alert.getOrgId() == null ? null : alert.getOrgId().toHexString(),
            alert.getPolicyId() == null ? null : alert.getPolicyId().toHexString(),
            alert.getPolicyName(),
            alert.getEventId() == null ? null : alert.getEventId().toHexString(),
            alert.getEventType(),
            alert.getSeverity() == null ? null : alert.getSeverity().name(),
            alert.getSubject(),
            alert.getActor(),
            alert.getStatus() == null ? null : alert.getStatus().name(),
            alert.getCreatedAt());
    }
}
