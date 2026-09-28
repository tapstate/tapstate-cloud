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
import java.util.Map;

/**
 * A single billable item on an Organization's subscription.
 * <p>
 * Stored in its own collection (not embedded in {@link Subscription}) so that
 * usage aggregation can query items directly by type / orgId / period without
 * loading the whole subscription document.
 * <p>
 * Week 2 scaffold: each subscription starts with two default items —
 * {@link SubscriptionItemType#CLUSTER_PIPELINE_RUNTIME} and
 * {@link SubscriptionItemType#ATLAS_DATABASE_STORAGE}. {@code quantity} /
 * {@code unitPrice} / {@code metadata} are populated during plan migration
 * or payment activation (Week 3+); for trial subscriptions these carry the
 * default "unlimited during trial" marker.
 */
@Document(collection = "subscription_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Billable item on a subscription")
public class SubscriptionItem {

    @Id
    private String id;

    @Schema(description = "Parent subscription UUID")
    private String subscriptionId;

    @Schema(description = "Owning Organization UUID (denormalized for fast usage aggregation)")
    private String orgId;

    @Schema(description = "Billable item type")
    private SubscriptionItemType itemType;

    /**
     * Included quantity — interpretation depends on itemType.
     * For CLUSTER_PIPELINE_RUNTIME: number of concurrent pipeline workers included.
     * For ATLAS_DATABASE_STORAGE: included GB-month ceiling (0 = unlimited during trial).
     */
    @Builder.Default
    @Schema(description = "Included quantity", example = "0")
    private int includedQuantity = 0;

    /** Per-unit price in cents (USD). 0 = free during trial. */
    @Builder.Default
    @Schema(description = "Per-unit price in cents (USD)", example = "0")
    private long unitPriceCents = 0L;

    /** Free-form metadata — e.g. plan tier, SKU, product feature flags. */
    @Builder.Default
    @Schema(description = "Item metadata (plan tier, feature flags, etc.)")
    private Map<String, String> metadata = Map.of();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
