package io.tapstate.cloud.payment.stripe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Stripe webhook event audit trail — every payload we successfully process
 * is recorded here so replays, retries, and event ordering are traceable.
 * {@code stripeEventId} is unique; the same event never records twice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stripe_events")
public class StripeEvent {

    /** Stripe Event ID (evt_xxx) — unique across the entire collection. */
    private String stripeEventId;

    /** Stripe webhook payload type (e.g. "checkout.session.completed"). */
    private String paymentMethod;

    /** Stripe webhook payload type (e.g. "checkout.session.completed"). */
    private String eventType;

    /** Raw webhook payload body (for replay / forensic analysis). */
    private String rawBody;

    /** Processing outcome: "PROCESSED" / "SKIPPED" / "ERRORED". */
    @Builder.Default
    private String outcome = "PROCESSED";

    /** Outcome detail — e.g. "subscription not found, skipping" or error message. */
    private String detail;

    /** When the Stripe Event was created (from Stripe's data.created field). */
    private Instant stripeCreatedAt;

    @CreatedDate
    private Instant createdAt;
}
