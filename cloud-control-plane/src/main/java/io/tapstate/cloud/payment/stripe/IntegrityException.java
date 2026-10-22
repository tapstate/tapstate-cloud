package io.tapstate.cloud.payment.stripe;

/**
 * Raised when application-level invariants protecting {@code stripe_products}
 * are violated.
 * <p>
 * Mongo cannot enforce cross-document uniqueness across array sub-fields, so
 * {@link StripeProductService#refreshAll} checks these invariants in Java and
 * throws this exception on violation. The exception message includes enough
 * context for the operator to locate the duplicate and fix the Stripe-side
 * metadata before re-running.
 */
public class IntegrityException extends RuntimeException {

    public IntegrityException(String message, Object... args) {
        super(String.format(message, args));
    }

    public IntegrityException(String message) {
        super(message);
    }
}
