package io.tapstate.cloud.payment;

/**
 * Sealed marker for a payment-gateway-specific reference returned by
 * {@link PaymentGateway#createSession(CreateSessionRequest)}.
 * <p>
 * PaymentGateway implementations return their own subtype. The caller
 * (SubscriptionService) pattern-matches on the subtype to write the
 * channel-specific embed back onto the Subscription document. Keeping this
 * as a sealed interface gives the compiler a finite set to exhaustively
 * cover in pattern switches, which is why each channel's package also
 * owns its subtype record.
 */
public sealed interface PaymentChannelRef permits
        io.tapstate.cloud.payment.stripe.StripePaymentChannelRecord {

    PaymentMethod paymentMethod();
}
