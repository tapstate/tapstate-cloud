package io.tapstate.cloud.payment;

import io.tapstate.cloud.subscription.SpecConfig;
import io.tapstate.cloud.subscription.Subscription;

import java.util.List;

/**
 * Abstraction over payment channels.
 * <p>
 * SubscriptionService is the only caller. It creates a Subscription shell first
 * (PENDING_PAYMENT), calls {@link #createSession(CreateSessionRequest)} to get
 * a hosted-payment URL, then writes the channel-specific {@link PaymentChannelRef}
 * back onto the Subscription embed. Payment activation happens only when the
 * channel confirms via webhook — {@link #handleWebhookEvent(RawWebhookEvent)}
 * is the entry point that drives FSM transition and Cluster provisioning.
 * <p>
 * Each channel lives in {@code io.tapstate.cloud.payment.<channel>} and
 * declares its {@link PaymentMethod} on the shared enum. A Spring
 * {@code Map<PaymentMethod, PaymentGateway>} wires the call site through
 * {@link SubscriptionService}.
 */
public interface PaymentGateway {

    PaymentMethod getPaymentMethod();

    /**
     * Create a hosted payment session. The returned result contains the URL
     * the console redirects to, plus a channel-specific ref that the caller
     * will persist onto the Subscription embed.
     *
     * @param request channel-neutral inputs (org id, user email, spec, pre-resolved price id)
     */
    PaymentSessionResult createSession(CreateSessionRequest request);

    /**
     * Cancel auto-renew at period end. The current period stays usable.
     */
    void requestCancelAtPeriodEnd(Subscription subscription);

    /**
     * Immediate cancellation — no grace period, no residual billing cycle.
     */
    void cancelImmediately(Subscription subscription);

    /**
     * Verify the webhook signature. Throw if invalid (lets the controller
     * respond 400 and the channel retry).
     */
    void verifyWebhookSignature(RawWebhookEvent event);

    /**
     * Dispatch a webhook event. {@code processed=true} means the event
     * reached application code (even if it turned out to be a replay).
     */
    WebhookResult handleWebhookEvent(RawWebhookEvent event);

    // ---- shared request / result types ----------------------------------

    record CreateSessionRequest(
            String orgId,
            String userEmail,
            SpecConfig specConfig,
            String stripePriceId
    ) {}

    record PaymentSessionResult(
            String sessionUrl,
            PaymentChannelRef channelRef
    ) {}

    record RawWebhookEvent(
            byte[] rawBody,
            String signatureHeader,
            java.util.Map<String, String> headers
    ) {}

    record WebhookResult(
            boolean processed,
            String message
    ) {}
}
