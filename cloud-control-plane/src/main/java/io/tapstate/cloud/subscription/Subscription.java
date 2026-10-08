package io.tapstate.cloud.subscription;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * Subscriptions start in {@code TRIAL} with a 30-day period and are created
 * explicitly through {@code POST /orgs/{orgId}/subscriptions} — creating an
 * Organization no longer creates a subscription. Payment callbacks and invoice
 * generation are Week 3+ work.
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
    @Schema(description = "Subscription lifecycle status", example = "TRIAL")
    private SubscriptionStatus status = SubscriptionStatus.TRIAL;

    /**
     * Provisioning state of the Subscription's cloud resources —
     * {@link ResourceState#PROVISIONING} while the Cluster + Atlas deployment is
     * in flight, {@link ResourceState#READY} once the Cluster and its
     * MdbAllocation are both ready.
     */
    @Schema(description = "Cloud resource provisioning state: PROVISIONING / READY (null until resources are provisioned)")
    private ResourceState resourceState;

    @Schema(description = "When the Cluster and MdbAllocation first became READY")
    private Instant resourcesReadyAt;

    @Schema(description = "Trial / billing period start")
    private Instant currentPeriodStart;

    @Schema(description = "Trial / billing period end")
    private Instant currentPeriodEnd;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
