package io.tapstate.cloud.payment.stripe;

import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.subscription.SpecConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire shape of a Checkout request, for the trial and the paid path.
 * <p>
 * A trial starts without a card: {@code payment_method_collection=if_required}
 * skips card collection when nothing is due, and
 * {@code trial_settings.end_behavior.missing_payment_method=cancel} stops Stripe
 * from letting an unbound trial fall into an unpaid invoice. A spec without a
 * trial must send neither — it collects the card because the first invoice is
 * due immediately.
 */
class StripeCheckoutRequestTest {

    private static final String ORG_ID = "6ad700000000000000000001";

    @Test
    @DisplayName("trial: no card is collected, and a trial ending without one is cancelled by Stripe")
    void trialRequestAsksForACardlessTrialThatCancelsWhenNoCardIsBound() {
        Map<String, Object> request = checkoutRequest(spec("2c8g", 7), true);

        assertThat(request).containsEntry("payment_method_collection", "if_required");

        Map<String, Object> subscriptionData = subscriptionData(request);
        assertThat(subscriptionData.get("trial_period_days")).isEqualTo(7L);
        assertThat(subscriptionData).containsEntry("trial_settings", Map.of(
                "end_behavior", Map.of("missing_payment_method", "cancel")));
    }

    @Test
    @DisplayName("paid spec: the card is collected up front and nothing is said about trials")
    void paidRequestKeepsCollectingTheCard() {
        Map<String, Object> request = checkoutRequest(spec("4c16g", 0), false);

        assertThat(request).doesNotContainKey("payment_method_collection");
        assertThat(request).doesNotContainKey("subscription_data");
    }

    // ---- helpers ---------------------------------------------------------

    private static Map<String, Object> checkoutRequest(SpecConfig spec, boolean trial) {
        StripePaymentService service = new StripePaymentService(
                new StripeWebhookConfig("sk_test_unit", "whsec_unit", ""), null, null);
        PaymentGateway.CreateSessionRequest request = new PaymentGateway.CreateSessionRequest(
                ORG_ID, "buyer@example.com", spec, "price_" + spec.getKey());
        return service.checkoutRequest(service.buildSessionParams(request, "cus_test"), trial);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subscriptionData(Map<String, Object> request) {
        return (Map<String, Object>) request.get("subscription_data");
    }

    private static SpecConfig spec(String key, int trialDays) {
        return SpecConfig.builder()
                .key(key)
                .displayName(key)
                .trialDays(trialDays)
                .enabled(true)
                .build();
    }
}
