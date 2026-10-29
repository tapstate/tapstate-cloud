package io.tapstate.cloud.tawk;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable, minimized notification state for one tawk.to event. */
@Document(collection = "tawk_webhook_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TawkWebhookEvent {

    @Id
    private String eventId;

    private String propertyId;
    private String propertyName;
    private String eventType;
    private String recordId;
    private String recordNumber;

    @Indexed
    private DeliveryStatus deliveryStatus;

    @Builder.Default
    private int attemptCount = 0;

    @Indexed
    private Instant nextAttemptAt;

    private Instant lastAttemptAt;
    private Instant deliveredAt;
    private String lastFailureCategory;
    private Instant receivedAt;

    @Indexed(name = "tawk_delivered_event_ttl", expireAfterSeconds = 7776000)
    private Instant deliveredTtlAt;

    public enum DeliveryStatus {
        PENDING,
        DELIVERED
    }
}
