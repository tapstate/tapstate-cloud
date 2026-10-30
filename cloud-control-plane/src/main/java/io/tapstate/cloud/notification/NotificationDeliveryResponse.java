package io.tapstate.cloud.notification;

import java.time.Instant;

public record NotificationDeliveryResponse(
    String id,
    String orgId,
    String alertId,
    String channel,
    String recipient,
    String subject,
    String status,
    int attempts,
    String lastError,
    Instant nextAttemptAt,
    Instant createdAt,
    Instant sentAt
) {
    public static NotificationDeliveryResponse from(NotificationDelivery delivery) {
        return new NotificationDeliveryResponse(
            delivery.getId() == null ? null : delivery.getId().toHexString(),
            delivery.getOrgId() == null ? null : delivery.getOrgId().toHexString(),
            delivery.getAlertId() == null ? null : delivery.getAlertId().toHexString(),
            delivery.getChannel(),
            delivery.getRecipient(),
            delivery.getSubject(),
            delivery.getStatus() == null ? null : delivery.getStatus().name(),
            delivery.getAttempts(),
            delivery.getLastError(),
            delivery.getNextAttemptAt(),
            delivery.getCreatedAt(),
            delivery.getSentAt());
    }
}
