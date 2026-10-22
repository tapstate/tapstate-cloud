package io.tapstate.cloud.payment.stripe;

/**
 * Raised when a Stripe webhook signature does not match. The controller
 * should respond HTTP 400 (bad signature) so Stripe retries later with a
 * valid payload — the service never processes a payload with an invalid sig.
 */
public class StripeSignatureInvalidException extends RuntimeException {
    public StripeSignatureInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
