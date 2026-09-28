package io.tapstate.cloud.subscription;

/**
 * Subscription lifecycle status.
 * <p>
 * State transitions (simplified — payment integration is Week 3+):
 * <pre>
 *   TRIAL ──→ ACTIVE ──→ PAST_DUE ──→ CANCELLED
 *                 │
 *                 └──→ CANCELLED (user self-serve cancel)
 * </pre>
 * Week 2 scaffold: all subscriptions start as {@link #TRIAL} with a 30-day
 * trial period. {@link #PAST_DUE} / {@link #CANCELLED} enforcement gates are
 * wired in; the actual payment callback that moves ACTIVE→PAST_DUE is a stub.
 */
public enum SubscriptionStatus {

    /** Fresh trial, all resources available — 30-day default. */
    TRIAL,

    /** Paid subscription, auto-renewing — fully active. */
    ACTIVE,

    /** Invoice not paid after grace period — read-only, no new provisioning. */
    PAST_DUE,

    /** User cancelled or hard-debt — all provisioning blocked, existing clusters read-only. */
    CANCELLED;

    /** Returns true if new resource provisioning is allowed. */
    public boolean allowsProvisioning() {
        return this == TRIAL || this == ACTIVE;
    }

    /** Returns true if the organization can view/manage existing resources. */
    public boolean isManaged() {
        return this != CANCELLED;
    }
}
