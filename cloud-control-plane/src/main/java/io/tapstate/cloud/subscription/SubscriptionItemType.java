package io.tapstate.cloud.subscription;

/**
 * Billable item types on a subscription — drives usage tracking and billing.
 * <p>
 * Week 2 scaffold ships with two default items:
 * <ul>
 *   <li>{@link #CLUSTER_PIPELINE_RUNTIME} — Tapstate Cluster Server runtime pods (pipeline execution)</li>
 *   <li>{@link #ATLAS_DATABASE_STORAGE} — Tapstate Atlas-managed database storage (per-GB-month)</li>
 * </ul>
 * <p>
 * Future items (not implemented in Week 2): SERVERLESS_EXECUTION, CONNECTOR_LICENSE, etc.
 */
public enum SubscriptionItemType {

    /** Tapstate Cluster Server runtime — pipeline execution engine. */
    CLUSTER_PIPELINE_RUNTIME("Cluster Pipeline Runtime", "runtime"),

    /** Tapstate Atlas Database managed storage — per-GB-month billing. */
    ATLAS_DATABASE_STORAGE("Atlas Database Storage", "storage_gb_month");

    private final String displayName;
    private final String billingUnit;

    SubscriptionItemType(String displayName, String billingUnit) {
        this.displayName = displayName;
        this.billingUnit = billingUnit;
    }

    public String getDisplayName() { return displayName; }
    public String getBillingUnit() { return billingUnit; }
}
