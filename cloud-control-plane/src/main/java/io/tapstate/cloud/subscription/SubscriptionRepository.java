package io.tapstate.cloud.subscription;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/**
 * Spring Data MongoDB repository for {@link Subscription}.
 * <p>
 * An Organization owns many Subscriptions; each Subscription owns exactly one
 * Cluster + one MdbAllocation. Lookups are therefore list-by-org or
 * id-scoped-by-org — there is deliberately no unique org lookup.
 */
public interface SubscriptionRepository extends MongoRepository<Subscription, ObjectId> {

    List<Subscription> findByOrgIdOrderByCreatedAtDesc(ObjectId orgId);

    List<Subscription> findByStatus(SubscriptionStatus status);

    List<Subscription> findByStatusAndCurrentPeriodEndBefore(SubscriptionStatus status, java.time.Instant cutoff);

    List<Subscription> findByStatusAndCreatedAtBefore(SubscriptionStatus status, java.time.Instant cutoff);

    Optional<Subscription> findByIdAndOrgId(ObjectId id, ObjectId orgId);

    /** Resolve a Subscription from a Stripe Checkout Session ID — used by checkout.session.completed webhook. */
    Optional<Subscription> findByStripeCheckoutSessionId(String checkoutSessionId);

    /** Resolve a Subscription from a Stripe Subscription ID — used by subscription.* and invoice.* webhooks. */
    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    /** Resolve by client-supplied idempotency key (dedup Checkout creation races). */
    Optional<Subscription> findByClientRequestIdAndOrgId(String clientRequestId, ObjectId orgId);
}
