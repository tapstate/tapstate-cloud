package io.tapstate.cloud.payment;

/**
 * Payment channel / payment gateway selector.
 * <p>
 * Each channel is implemented by a {@link PaymentGateway} in a dedicated package
 * ({@code io.tapstate.cloud.payment.stripe} for Stripe,
 * {@code io.tapstate.cloud.payment.aws} for AWS Marketplace, etc.).
 * <p>
 * A Subscription records which channel it was created through on its
 * {@code paymentMethod} field, so subscription and cancellation flows can
 * dispatch to the right gateway without conditional branches on channel name.
 */
public enum PaymentMethod {

    /** Self-managed Stripe Checkout + Webhook (current implementation). */
    STRIPE,

    /** AWS Marketplace channel (reserved, not yet implemented). */
    AWS_MARKETPLACE,

    /** GCP Marketplace channel (reserved, not yet implemented). */
    GCP_MARKETPLACE
}
