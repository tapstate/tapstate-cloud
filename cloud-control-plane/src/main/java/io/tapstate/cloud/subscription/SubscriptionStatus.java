package io.tapstate.cloud.subscription;

/**
 * Subscription lifecycle status.
 * <p>
 * State transitions (Stripe + self-serve cancel flow):
 * <pre>
 *   CHECKOUT_CREATED ──→ PENDING_PAYMENT ──CHECKOUT_COMPLETED──→ ACTIVE
 *                          │  (24h timeout)                       │
 *                          └── EXPIRED → CANCELLED               CANCEL_AT_PERIOD_END
 *                                                              └─→ PENDING_CANCEL
 *                                                             PERIOD_END_REAPED
 *                                                              └─→ CANCELLED
 *
 *   TRIAL ──PAYMENT_CONFIRMED──→ ACTIVE (original scaffold path, still valid)
 *   ACTIVE ──PAYMENT_FAILED──→ PAST_DUE ──PAYMENT_RECEIVED──→ ACTIVE
 *   any-state ──IMMEDIATE_CANCEL──→ CANCELLED (admin or urgent)
 * </pre>
 * PENDING_PAYMENT is the post-Checkout shell — no Cluster/MDB provisioned yet.
 * The ACTIVE/PENDING_CANCEL split lets users cancel and keep the service until
 * the next billing cycle instead of losing everything instantly.
 */
public enum SubscriptionStatus {

    /** Checkout session created, waiting for Stripe to confirm payment — no resources yet. */
    PENDING_PAYMENT,

    /** Fresh trial, all resources available — 30-day default (Week 2 scaffold path). */
    TRIAL,

    /** Paid subscription, auto-renewing — fully active. */
    ACTIVE,

    /** Invoice not paid after grace period — read-only, no new provisioning. */
    PAST_DUE,

    /** User cancelled at period-end — service stays usable until billing cycle expires. */
    PENDING_CANCEL,

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

    /** True when this subscription has NOT yet activated its Cloud resources. */
    public boolean isPreActivation() {
        return this == PENDING_PAYMENT;
    }
}
