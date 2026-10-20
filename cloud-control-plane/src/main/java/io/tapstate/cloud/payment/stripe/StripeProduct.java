package io.tapstate.cloud.payment.stripe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stripe Product → nested Prices document.
 * <p>
 * Mirrors Stripe's real data model — one Product can carry multiple Price entries
 * (monthly + yearly, different currencies). Each Price entry's {@code specKey}
 * is the foreign key to {@code spec_config.key} on the platform side.
 * <p>
 * The {@code prices[]} array is replaced wholesale on each {@code refreshAll()} —
 * Stripe doesn't retain old Price versions, so there is no point keeping
 * {@code enabled=false} rows after a rotation. {@code refreshAll()} validates
 * that every {@code specKey} appears at most once across ALL Products (cross-doc
 * uniqueness cannot be enforced by a MongoDB unique index on an array sub-field,
 * so it is checked in application code).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stripe_products")
public class StripeProduct {

    /** Stripe Product ID (prod_xxx), unique across the entire collection. */
    private String productId;

    /** Human-readable product name from Stripe (e.g. "Tapstate Cloud 2C8G Starter"). */
    private String productName;

    /** Product-level active flag — {@code false} means no Price inside is usable. */
    private boolean enabled;

    /** Stripe API-level last update timestamp. */
    private Instant updatedAt;

    /** All recurring Prices on this Product (monthly / yearly, different currencies). */
    @Builder.Default
    private List<StripePriceEntry> prices = new ArrayList<>();
}
