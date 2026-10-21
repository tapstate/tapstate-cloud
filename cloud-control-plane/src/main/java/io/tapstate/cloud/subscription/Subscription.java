package io.tapstate.cloud.subscription;

import io.swagger.v3.oas.annotations.media.Schema;
import io.tapstate.cloud.payment.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Subscription — the billing/usage anchor for one Cluster and one MdbAllocation.
 * <p>
 * An Organization owns many Subscriptions; every Subscription owns exactly one
 * Cluster and one MdbAllocation. Its {@link SubscriptionStatus} drives what
 * operations are allowed at enforcement gates (provisioning, cancellation).
 * {@link SubscriptionItem} entries carry the billable items (cluster runtime,
 * Atlas storage) and are stored as a separate collection for efficient usage
 * aggregation queries.
 * <p>
 * Subscriptions start in {@code PENDING_PAYMENT} (Stripe Checkout flow) or
 * {@code TRIAL} (week 2 scaffold path with 30-day default trial). The payment
 * method and Stripe-specific references are carried on dedicated embeds; spec
 * configuration is snapshotted so existing subscriptions survive catalog edits.
 */
@Document(collection = "subscriptions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Organization subscription — billing anchor for all Cloud resources")
public class Subscription {

    @Id
    @Schema(description = "Subscription ObjectId")
    private ObjectId id;

    @Schema(description = "Owning Organization ID", example = "6475a2b3c1d4e5f6a7b8c9d0")
    private ObjectId orgId;

    @Builder.Default
    @Schema(description = "Subscription lifecycle status", example = "PENDING_PAYMENT")
    private SubscriptionStatus status = SubscriptionStatus.TRIAL;

    /**
     * Payment channel that created this Subscription. {@code null} only for
     * legacy TRIAL subscriptions created through the Week 2 scaffold path.
     */
    @Schema(description = "Payment channel (STRIPE, null for legacy TRIAL)")
    private PaymentMethod paymentMethod;

    /** Stripe-specific references — populated incrementally at session-create and webhook time. */
    @Schema(description = "Stripe payment channel references (checkout session, subscription id, etc.)")
    private StripeSubscriptionRef stripe;

    /**
     * Snapshot of the SpecConfig this Subscription was created against.
     * Self-contained so existing subscriptions are unaffected by future catalog edits.
     */
    @Schema(description = "Snapshot of the platform SpecConfig this subscription was created with — immutable after creation")
    private SpecConfig specConfig;

    /**
     * Idempotency flag — set true when Cluster/MDB provisioning has started.
     * A webhook replay cannot double-provision because any subsequent attempt
     * sees this already true and skips.
     */
    @Builder.Default
    @Schema(description = "Whether Cluster + MDB provisioning has been initiated (prevents double-provision on webhook replay)")
    private boolean provisionInitiated = false;

    /**
     * Provisioning state of the Subscription's cloud resources —
     * {@link ResourceState#PROVISIONING} while the Cluster + Atlas deployment is
     * in flight, {@link ResourceState#READY} once the Cluster and its
     * MdbAllocation are both ready.
     * <p>
     * {@code null} during PENDING_PAYMENT (no resources yet created).
     */
    @Schema(description = "Cloud resource provisioning state: PROVISIONING / READY (null until resources are provisioned)")
    private ResourceState resourceState;

    @Schema(description = "When the Cluster and MdbAllocation first became READY")
    private Instant resourcesReadyAt;

    @Schema(description = "Trial / billing period start")
    private Instant currentPeriodStart;

    @Schema(description = "Trial / billing period end — used by PENDING_CANCEL reaper to know when to terminate")
    private Instant currentPeriodEnd;

    /**
     * Client-supplied idempotency key from the API caller. Allows the webhook
     * handler and create path to safely retry without creating duplicate Subscriptions.
     */
    @Schema(description = "Client-supplied idempotency key from the caller's request")
    private String clientRequestId;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
