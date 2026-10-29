package io.tapstate.cloud.notification;

import org.bson.types.ObjectId;

/**
 * A request to put one notification on the durable outbox.
 * <p>
 * {@code sourceId} is the alert id for event alerts; non-alert senders (such as
 * email verification) pass a generated correlation id because the outbox model
 * still uses that field for per-notification identity.
 * <p>
 * {@code notificationKey} is the idempotency key. Re-enqueuing the same key
 * returns the existing row instead of sending twice.
 */
public record NotificationRequest(
    ObjectId orgId,
    ObjectId sourceId,
    String channel,
    String recipient,
    String subject,
    String textBody,
    String htmlBody,
    boolean inlineLogo,
    String notificationKey
) {
}
