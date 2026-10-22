package io.tapstate.cloud.payment.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.net.Webhook;
import io.tapstate.cloud.payment.PaymentChannelRef;
import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.payment.PaymentMethod;
import io.tapstate.cloud.subscription.Subscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stripe Checkout implementation of {@link PaymentGateway}.
 * <p>
 * Creates hosted Checkout Sessions with a single recurring line item (the
 * pre-resolved Stripe Price ID), and handles webhook events that the Stripe
 * Dashboard posts back to {@code POST /v1/api/internal/payment/stripe/webhook}.
 * <p>
 * All outbound calls use the native Stripe SDK directly (no Spring Boot Stripe
 * starter) so that we fully control endpoint paths, redirect URLs, and idempotency.
 * The {@link StripeWebhookConfig} provides both the secret key (for outbound
 * API calls) and webhook signing secret (for inbound signature verification).
 */
@Slf4j
@Service
public class StripePaymentService implements PaymentGateway {

    private final StripeWebhookConfig config;
    private final StripeCustomerService customerService;

    // Injected at construction; resolved lazily to avoid circular bean dep with StripeWebhookEventService
    private final StripeWebhookEventService webhookEventService;

    public StripePaymentService(StripeWebhookConfig config,
                                StripeCustomerService customerService,
                                StripeWebhookEventService webhookEventService) {
        this.config = config;
        this.customerService = customerService;
        this.webhookEventService = webhookEventService;
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.STRIPE;
    }

    // ---- Checkout session creation -------------------------------------

    @Override
    public PaymentSessionResult createSession(CreateSessionRequest request) {
        if (config.secretKey() == null || config.secretKey().isBlank()) {
            throw new IllegalStateException("Stripe secret key is not configured");
        }
        Stripe.apiKey = config.secretKey();

        try {
            String stripeCustomerId = customerService.getOrCreate(
                    request.orgId(), request.userEmail());

            SessionCreateParams params = buildSessionParams(request, stripeCustomerId);
            Session session = Session.create(checkoutRequest(params, request.specConfig().hasTrial()));
            log.info("StripePaymentService: created Checkout session {} for org {} price {} trial={}",
                    session.getId(), request.orgId(), request.stripePriceId(), request.specConfig().hasTrial());

            // Build the channel ref — session and price IDs are known here;
            // stripeSubscriptionId will be filled in by the webhook handler.
            PaymentChannelRef channelRef = new StripePaymentChannelRecord(
                    stripeCustomerId,
                    null,                              // subscriptionId → webhook fills this
                    session.getId(),                   // checkoutSessionId
                    request.stripePriceId(),           // priceId
                    null                               // productId → resolved from Price in webhook
            );

            return new PaymentSessionResult(session.getUrl(), channelRef);
        } catch (StripeException e) {
            log.error("StripePaymentService: Checkout session creation failed", e);
            throw new StripeIntegrationException("Checkout session creation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Checkout Session parameters for one spec.
     * <p>
     * A spec that carries a trial gets {@code subscription_data.trial_period_days}:
     * no invoice is issued until the trial ends. Whether a card is collected for
     * that trial is decided in {@link #checkoutRequest} — a trial can start
     * card-less. A spec without a trial starts billing on the first invoice.
     */
    public SessionCreateParams buildSessionParams(CreateSessionRequest request, String stripeCustomerId) {
        SessionCreateParams.LineItem lineItem = SessionCreateParams.LineItem.builder()
                .setPrice(request.stripePriceId())
                .setQuantity(1L)
                .build();

        // Stripe Price already has billing configured (monthly USD recurring).
        // We don't override it here — just pass through.
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .addLineItem(lineItem)
                .setCustomer(stripeCustomerId)
                .putMetadata("org_id", request.orgId())
                .putMetadata("spec_key", request.specConfig().getKey())
                .setClientReferenceId(request.orgId() + ":" + request.specConfig().getKey())
                .setSuccessUrl(buildSuccessUrl())
                .setCancelUrl(buildCancelUrl())
                .setAutomaticTax(SessionCreateParams.AutomaticTax.builder()
                        .setEnabled(true).build())
                // Automatic tax requires a customer address; "auto" makes Checkout
                // save the billing address the customer enters back onto the Customer.
                .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                        .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                        .build());

        if (request.specConfig().hasTrial()) {
            builder.setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                    .setTrialPeriodDays((long) request.specConfig().getTrialDays())
                    .build());
        }

        return builder.build();
    }

    /**
     * The request actually sent to Stripe.
     * <p>
     * The pinned SDK predates {@code payment_method_collection} and
     * {@code subscription_data.trial_settings}, so a trial request is sent as the
     * same nested map with those two added. They are what make a trial card-less
     * ({@code if_required} — nothing is due, so no card is asked for) and what
     * keeps it from turning into an unpaid invoice: at trial end Stripe cancels a
     * subscription that never bound a payment method, instead of letting it go
     * past due.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> checkoutRequest(SessionCreateParams params, boolean trial) {
        Map<String, Object> request = new LinkedHashMap<>(params.toMap());
        if (!trial) {
            return request;
        }
        request.put("payment_method_collection", "if_required");
        Map<String, Object> subscriptionData = new LinkedHashMap<>(
                (Map<String, Object>) request.getOrDefault("subscription_data", Map.of()));
        subscriptionData.put("trial_settings",
                Map.of("end_behavior", Map.of("missing_payment_method", "cancel")));
        request.put("subscription_data", subscriptionData);
        return request;
    }

    /**
     * Whether Stripe has a payment method to charge when the trial ends — the
     * subscription's own default, or the one set on the customer for invoices.
     */
    boolean hasPaymentMethod(String stripeSubscriptionId) {
        Stripe.apiKey = config.secretKey();
        try {
            com.stripe.model.Subscription stripeSub =
                    com.stripe.model.Subscription.retrieve(stripeSubscriptionId);
            if (stripeSub.getDefaultPaymentMethod() != null) {
                return true;
            }
            String customerId = stripeSub.getCustomer();
            if (customerId == null) {
                return false;
            }
            Customer customer = Customer.retrieve(customerId);
            return customer.getInvoiceSettings() != null
                    && customer.getInvoiceSettings().getDefaultPaymentMethod() != null;
        } catch (StripeException e) {
            throw new StripeIntegrationException(
                    "Cannot read the payment method of Stripe subscription " + stripeSubscriptionId
                            + ": " + e.getMessage(), e);
        }
    }

    // ---- Cancellation flows --------------------------------------------

    @Override
    public void requestCancelAtPeriodEnd(Subscription subscription) {
        if (config.secretKey() == null || config.secretKey().isBlank()) {
            log.warn("Stripe secret key missing — cannot send cancel_at_period_end for subscription {}",
                    subscription.getId());
            return;
        }
        if (subscription.getStripe() == null || subscription.getStripe().getSubscriptionId() == null) {
            log.warn("No Stripe Subscription ID on subscription {} — cancel_at_period_end skipped",
                    subscription.getId());
            return;
        }

        Stripe.apiKey = config.secretKey();
        try {
            com.stripe.model.Subscription stripeSub = com.stripe.model.Subscription.retrieve(subscription.getStripe().getSubscriptionId());
            com.stripe.model.Subscription updated = stripeSub.update(Map.of("cancel_at_period_end", true));
            log.info("StripePaymentService: cancel_at_period_end=true for Stripe subscription {}",
                    updated.getId());
        } catch (StripeException e) {
            log.error("StripePaymentService: cancel_at_period_end failed for Stripe subscription {}",
                    subscription.getStripe().getSubscriptionId(), e);
            throw new StripeIntegrationException(
                    "cancel_at_period_end failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelImmediately(Subscription subscription) {
        if (config.secretKey() == null || config.secretKey().isBlank()) {
            log.warn("Stripe secret key missing — cannot perform immediate cancel for subscription {}",
                    subscription.getId());
            return;
        }
        if (subscription.getStripe() == null || subscription.getStripe().getSubscriptionId() == null) {
            log.info("No Stripe Subscription ID on subscription {} — skipping Stripe cancel (local-only)",
                    subscription.getId());
            return;
        }

        Stripe.apiKey = config.secretKey();
        try {
            com.stripe.model.Subscription stripeSub = com.stripe.model.Subscription.retrieve(subscription.getStripe().getSubscriptionId());
            com.stripe.model.Subscription cancelled = stripeSub.cancel(Map.of("invoice_now", false));
            log.info("StripePaymentService: Stripe subscription {} cancelled immediately (status={})",
                    cancelled.getId(), cancelled.getStatus());
        } catch (com.stripe.exception.InvalidRequestException e) {
            // Already gone on Stripe's side (period-end deletion, manual Dashboard cancel).
            // Cancelling is idempotent from our point of view, so this is not an error.
            if ("resource_missing".equals(e.getCode())) {
                log.info("StripePaymentService: Stripe subscription {} already absent — treated as cancelled",
                        subscription.getStripe().getSubscriptionId());
                return;
            }
            log.error("StripePaymentService: immediate cancel failed for Stripe subscription {}",
                    subscription.getStripe().getSubscriptionId(), e);
            throw new StripeIntegrationException(
                    "Immediate cancel failed: " + e.getMessage(), e);
        } catch (StripeException e) {
            log.error("StripePaymentService: immediate cancel failed for Stripe subscription {}",
                    subscription.getStripe().getSubscriptionId(), e);
            throw new StripeIntegrationException(
                    "Immediate cancel failed: " + e.getMessage(), e);
        }
    }

    // ---- Webhook handling ----------------------------------------------

    @Override
    public void verifyWebhookSignature(RawWebhookEvent event) {
        String webhookSecret = config.webhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("Stripe webhook secret is not configured");
        }
        try {
            // constructEvent parses + verifies signature in one call. Throws
            // SignatureVerificationException on mismatch — caller responds 400.
            Webhook.constructEvent(new String(event.rawBody()), event.signatureHeader(), webhookSecret);
        } catch (Exception e) {
            log.warn("StripePaymentService: webhook signature verification failed");
            throw new StripeSignatureInvalidException("Webhook signature verification failed", e);
        }
    }

    @Override
    public WebhookResult handleWebhookEvent(RawWebhookEvent event) {
        return webhookEventService.dispatch(event);
    }

    // ---- helpers --------------------------------------------------------

    private String buildSuccessUrl() {
        return System.getenv().getOrDefault(
                "TAPSTATE_CLOUD_PUBLIC_BASE_URL",
                "https://cloud.tapstate.com") + "/checkout/success";
    }

    private String buildCancelUrl() {
        return System.getenv().getOrDefault(
                "TAPSTATE_CLOUD_PUBLIC_BASE_URL",
                "https://cloud.tapstate.com") + "/checkout/cancel";
    }
}
