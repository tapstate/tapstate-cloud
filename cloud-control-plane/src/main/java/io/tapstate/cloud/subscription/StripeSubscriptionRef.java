package io.tapstate.cloud.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Stripe-specific embed persisted on {@link Subscription}.
 * <p>
 * Unlike {@link io.tapstate.cloud.payment.stripe.StripePaymentChannelRecord}
 * (returned from the gateway call itself), this embed is a <b>MongoDocument-ready
 * bean</b> annotated with Lombok and a default constructor so Spring Data
 * MongoDB can serialize/deserialize it inside the Subscription document.
 * <p>
 * Field population timeline:
 * <ol>
 *   <li>{@code createSession} — writes {@code customerId}, {@code checkoutSessionId}, {@code priceId}, {@code productId}</li>
 *   <li>{@code checkout.session.completed} webhook — writes {@code subscriptionId}, {@code paymentMethodId}, {@code autoRenewAuthorized=true}</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StripeSubscriptionRef {

    /** Stripe Customer ID (cus_xxx), resolved/created at Checkout-session time. */
    private String customerId;

    /** Stripe Subscription ID (sub_xxx) — populated after Checkout completes. */
    private String subscriptionId;

    /** Checkout Session ID (cs_xxx), kept for audit and admin lookup. */
    private String checkoutSessionId;

    /** Stripe Price ID (price_xxx) — the exact price the user committed to. */
    private String priceId;

    /** Stripe Product ID (prod_xxx) — for audit and troubleshooting. */
    private String productId;

    /** Whether auto-renew is on — set true at checkout completion, flipped by user-cancel endpoints. */
    @Builder.Default
    private boolean autoRenewAuthorized = false;

    /** When autoRenewAuthorized last changed — audit trail. */
    private Instant autoRenewAuthorizedChangedAt;

    /** The Stripe payment method ID (pm_xxx) attached to this subscription. */
    private String paymentMethodId;
}
