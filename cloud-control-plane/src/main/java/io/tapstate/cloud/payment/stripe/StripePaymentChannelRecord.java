package io.tapstate.cloud.payment.stripe;

import io.tapstate.cloud.payment.PaymentChannelRef;
import io.tapstate.cloud.payment.PaymentMethod;

/**
 * Stripe-specific payment-channel reference.
 * <p>
 * Returned by {@link StripePaymentService#createSession} and persisted onto
 * the Subscription's {@code stripe} embed (see {@link StripeSubscriptionRef}).
 * Field values are populated incrementally: the Checkout session creation
 * sets {@code stripeCustomerId} / {@code checkoutSessionId} / {@code priceId};
 * the {@code checkout.session.completed} webhook later sets
 * {@code stripeSubscriptionId} and {@code paymentMethodId}.
 */
public record StripePaymentChannelRecord(
        String stripeCustomerId,
        String stripeSubscriptionId,
        String checkoutSessionId,
        String priceId,
        String productId
) implements PaymentChannelRef {

    @Override
    public PaymentMethod paymentMethod() {
        return PaymentMethod.STRIPE;
    }
}
