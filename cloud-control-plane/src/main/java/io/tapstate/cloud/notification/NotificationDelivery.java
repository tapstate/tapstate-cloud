package io.tapstate.cloud.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Durable outbox row for one alert/channel/recipient.
 * <p>
 * The unique index makes alert fan-out idempotent; the lease and status
 * condition on {@code findAndModify} make concurrent workers send a row once.
 */
@Document(collection = "notification_deliveries")
@CompoundIndex(name = "delivery_alert_channel_recipient_unique",
    def = "{'alertId': 1, 'channel': 1, 'recipient': 1}", unique = true)
@CompoundIndex(name = "delivery_due", def = "{'status': 1, 'nextAttemptAt': 1}")
@CompoundIndex(name = "delivery_org_created", def = "{'orgId': 1, 'createdAt': -1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDelivery {

    @Id
    private ObjectId id;

    private ObjectId orgId;

    private ObjectId alertId;

    private String channel;

    private String recipient;

    private String subject;

    private String body;

    private String htmlBody;

    private boolean inlineLogo;

    /**
     * Idempotency key for every notification, including non-alert email such as
     * verification codes. Sparse so pre-existing rows without the field stay
     * valid during rollout.
     */
    @Indexed(name = "delivery_notification_key_unique", unique = true, sparse = true)
    private String notificationKey;

    private DeliveryStatus status;

    @Builder.Default
    private int attempts = 0;

    private Instant nextAttemptAt;

    private String lastError;

    private String leaseOwner;

    private Instant leaseUntil;

    @CreatedDate
    private Instant createdAt;

    private Instant updatedAt;

    private Instant sentAt;
}
