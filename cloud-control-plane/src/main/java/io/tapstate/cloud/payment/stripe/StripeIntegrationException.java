package io.tapstate.cloud.payment.stripe;

/**
 * Raised when a Stripe API call fails — wraps the original StripeException
 * with enough context for the caller to decide whether to retry or abort.
 */
public class StripeIntegrationException extends RuntimeException {
    public StripeIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
    public StripeIntegrationException(String message) {
        super(message);
    }
}
