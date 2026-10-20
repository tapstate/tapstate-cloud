package io.tapstate.cloud.subscription.fsm;

/**
 * Events that drive the Subscription state machine.
 * <p>
 * Events fall into three buckets:
 * <ul>
 *   <li>Webhook-driven (CHECKOUT_COMPLETED) — Stripe confirms payment, shell activates</li>
 *   <li>User/admin API-driven (CANCEL_REQUESTED, CANCEL_AT_PERIOD_END_REQUESTED, IMMEDIATE_CANCEL, ADMIN_RESTORE)</li>
 *   <li>Scheduled (PERIOD_END_REAPED, PAYMENT_PENDING_EXPIRED) — reaper jobs</li>
 * </ul>
 */
public enum SubscriptionEventType {

    /** Initial subscription created with trial — auto-fires on org creation */
    CREATE_TRIAL,

    /** Stripe Checkout completed — payment confirmed, shell subscription activates */
    CHECKOUT_COMPLETED,

    /** Trial converted to paid (legacy WEEK 2 scaffold path) */
    PAYMENT_CONFIRMED,

    /** Invoice not paid within grace period */
    PAYMENT_FAILED,

    /** Payment received while PAST_DUE */
    PAYMENT_RECEIVED,

    /** User initiates self-serve cancel (immediate — legacy path) */
    CANCEL_REQUESTED,

    /** User cancels at period end — subscription enters PENDING_CANCEL, keeps service until cycle expires */
    CANCEL_AT_PERIOD_END_REQUESTED,

    /** Immediate cancellation (admin override or urgent self-serve) — no grace period */
    IMMEDIATE_CANCEL,

    /** Scheduled reaper: PENDING_CANCEL + period_end≤now → CANCELLED */
    PERIOD_END_REAPED,

    /** Scheduled reaper: PENDING_PAYMENT >24h without webhook → CANCELLED (expired shell) */
    PAYMENT_PENDING_EXPIRED,

    /** Admin restores subscription (from CANCELLED back to TRIAL / ACTIVE) */
    ADMIN_RESTORE,
}
