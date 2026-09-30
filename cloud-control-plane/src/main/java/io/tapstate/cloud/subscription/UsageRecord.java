package io.tapstate.cloud.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;
import org.bson.types.ObjectId;

/**
 * Billing usage record — raw meter data for subscription items.
 * <p>
 * Stored by subscription item + period; aggregated at billing-cycle close
 * to produce invoice line items. This is a **Week 2 scaffold** — the schema
 * and repository are in place but no aggregation / invoicing logic exists yet.
 * <p>
 * Typical writers (Week 3+):
 * <ul>
 *   <li>Cluster heartbeat reporter — runtime hour tracking per pipeline worker</li>
 *   <li>Atlas DB storage reporter — per-GB-month measurement</li>
 * </ul>
 */
@Document(collection = "usage_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageRecord {

    @Id
    private String id;

    /** Parent subscription item ID (foreign key to subscription_items.id). */
    private ObjectId subscriptionItemId;

    /** Owning Organization ID (denormalized for fast queries). */
    private ObjectId orgId;

    /** Subscription item type (denormalized — enum name, e.g. CLUSTER_PIPELINE_RUNTIME). */
    private String itemType;

    /** Measured quantity — interpretation depends on itemType (runtime-hours, GB-month, ...). */
    private double quantity;

    /** Billing period start (UTC instant). */
    private Instant periodStart;

    /** Billing period end (UTC instant). */
    private Instant periodEnd;

    /** Optional source annotation — e.g. clusterId, runtimeId, atlasInstance. */
    @Builder.Default
    private String sourceRef = "";

    @CreatedDate
    private Instant createdAt;

    /** Helper to create a new random ID. */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
