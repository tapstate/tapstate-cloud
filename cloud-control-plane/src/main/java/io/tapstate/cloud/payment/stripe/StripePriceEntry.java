package io.tapstate.cloud.payment.stripe;

import java.time.Instant;

/**
 * A single Price inside {@link StripeProduct#prices}.
 * <p>
 * Stores the Stripe Price ID, monetary amount, and the {@code specKey} foreign
 * key that links this price to the platform's spec catalog in {@code spec_config}.
 * {@code enabled} at this level is the rotation mechanism for price changes —
 * when a Stripe Price is superseded by a new one for the same specKey, the old
 * entry is set {@code enabled=false} and the new one {@code enabled=true}.
 */
public record StripePriceEntry(
        String priceId,
        long unitPriceCents,
        String currency,
        String specKey,
        boolean enabled,
        String billingInterval,
        Instant updatedAt
) {}
