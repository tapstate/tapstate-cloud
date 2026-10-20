package io.tapstate.cloud.payment.stripe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Stripe API key + webhook secret configuration.
 * <p>
 * Values come from {@code application.yml} / environment variables, typically
 * injected as Kubernetes Secrets:
 * <pre>
 *   STRIPE_SECRET_KEY      — sk_live_xxx (or sk_test_xxx for sandbox)
 *   STRIPE_WEBHOOK_SECRET  — whsec_xxx (from Stripe Dashboard webhook config)
 * </pre>
 * Both can be null/empty locally; the service layer checks before making any
 * outbound call and skips gracefully when misconfigured.
 * <p>
 * {@code tapstate.cloud.stripe.api-base} redirects the SDK at another endpoint
 * (a WireMock stub in the integration suite). Empty — the SDK default — is the
 * only value production ever uses.
 */
@Configuration
public class StripeWebhookConfig {

    private final String secretKey;
    private final String webhookSecret;
    private final String apiBase;

    public StripeWebhookConfig(
            @Value("${tapstate.cloud.stripe.secret-key:}") String secretKey,
            @Value("${tapstate.cloud.stripe.webhook-secret:}") String webhookSecret,
            @Value("${tapstate.cloud.stripe.api-base:}") String apiBase) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.apiBase = apiBase;
        if (apiBase != null && !apiBase.isBlank()) {
            com.stripe.Stripe.overrideApiBase(apiBase);
        }
    }

    public String secretKey() { return secretKey; }
    public String webhookSecret() { return webhookSecret; }
    public String apiBase() { return apiBase; }
}
