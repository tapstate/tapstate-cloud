package io.tapstate.cloud.payment.stripe;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@code stripe_products} — Stripe Product + nested Prices.
 * <p>
 * The nested structure means a {@code findEnabledBySpecKey} is not a simple
 * repository method; aggregation with {@code $unwind} is used instead
 * (see {@link StripeProductService}). The repository here only covers the
 * obvious single-document lookups.
 */
public interface StripeProductRepository extends MongoRepository<StripeProduct, String> {

    Optional<StripeProduct> findByProductId(String productId);

    List<StripeProduct> findByEnabledTrue();
}
