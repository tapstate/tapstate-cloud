package io.tapstate.cloud.payment.stripe;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Repository for the Stripe webhook event audit trail. */
public interface StripeEventRepository extends MongoRepository<StripeEvent, String> {

    Optional<StripeEvent> findByStripeEventId(String stripeEventId);

    boolean existsByStripeEventId(String stripeEventId);
}
