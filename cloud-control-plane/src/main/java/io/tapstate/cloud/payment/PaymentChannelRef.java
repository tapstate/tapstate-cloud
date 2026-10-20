package io.tapstate.cloud.payment;

/**
 * Marker for a payment-gateway-specific reference returned by
 * {@link PaymentGateway#createSession(CreateSessionRequest)}.
 * <p>
 * PaymentGateway implementations return their own subtype. The caller
 * (SubscriptionService) pattern-matches on the subtype to write the
 * channel-specific embed back onto the Subscription document. Keeping this
 * as a plain interface allows clean extension without module sealing headaches.
 */
public interface PaymentChannelRef {
    PaymentMethod paymentMethod();
}
