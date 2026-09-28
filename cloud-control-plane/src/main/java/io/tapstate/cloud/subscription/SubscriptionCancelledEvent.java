package io.tapstate.cloud.subscription;

/**
 * Spring event — a Subscription has been transitioned to {@link SubscriptionStatus#CANCELLED}.
 * <p>
 * Published by {@link SubscriptionService} after a successful {@code CANCEL_REQUESTED}
 * state-machine transition. Listened to by the Cluster package to cascade
 * termination of all Clusters owned by this Organization (cross-package decoupling —
 * ClusterService must NOT inject SubscriptionService to avoid circular FSM deps).
 *
 * @see SubscriptionService#cancelSubscription(String, String)
 */
public record SubscriptionCancelledEvent(String orgId, String subscriptionId, String operator) {}
