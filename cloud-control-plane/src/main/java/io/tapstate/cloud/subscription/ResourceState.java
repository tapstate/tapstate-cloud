package io.tapstate.cloud.subscription;

/**
 * Provisioning state of the cloud resources a Subscription owns (Cluster nodes
 * and Atlas database allocations), orthogonal to the billing
 * {@link SubscriptionStatus}.
 * <p>
 * Billing state (TRIAL / ACTIVE / PAST_DUE / CANCELLED) answers "may this
 * organization pay / use the service"; resource state answers "have the
 * provisioned resources finished deploying". A Subscription becomes
 * {@link #READY} only once every Cluster is deployed AND every MdbAllocation is
 * ready. Subscription shells created before an explicit subscribe carry no
 * resource state ({@code null}).
 */
public enum ResourceState {

    /** Cluster + Atlas provisioning is in progress (at least one resource not ready). */
    PROVISIONING,

    /** Every Cluster and every MdbAllocation of the Subscription is ready. */
    READY
}
