package io.tapstate.cloud.subscription.fsm;

/**
 * Events that drive the Subscription state machine.
 * <p>
 * Unlike Cluster, Subscription transitions are mostly triggered by human/admin
 * or payment callback — no runtime SDK-side events.
 */
public enum SubscriptionEventType {

    /** Initial subscription created with trial — auto-fires on org creation */
    CREATE_TRIAL,

    /** Payment confirmed — trial converted to paid */
    PAYMENT_CONFIRMED,

    /** Invoice not paid within grace period */
    PAYMENT_FAILED,

    /** Payment received while PAST_DUE */
    PAYMENT_RECEIVED,

    /** User initiates self-serve cancel */
    CANCEL_REQUESTED,

    /** Admin restores subscription (from CANCELLED back to TRIAL / ACTIVE) */
    ADMIN_RESTORE,
}
