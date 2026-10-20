package io.tapstate.cloud.payment.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.subscription.Subscription;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches Stripe webhook events to the correct handler.
 * <p>
 * Handles the critical payment-event paths (<b>checkout.session.completed</b>
 * → activate subscription + provision resources) and cancellation paths
 * (customer.subscription.deleted, invoice.payment_failed). All other event
 * types are SKIPPED and recorded for audit — we deliberately only act on
 * the events we actually have a use case for.
 * <p>
 * Every successfully-parsed event is recorded in {@code stripe_events} with
 * outcome=PROCESSED/SKIPPED/ERRORED, so replays and retries are traceable.
 */
@Slf4j
@Service
public class StripeWebhookEventService {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed",
            "customer.subscription.deleted",
            "invoice.payment_failed",
            "invoice.paid"
    );

    private final StripeEventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final StripeWebhookConfig config;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public StripeWebhookEventService(StripeEventRepository eventRepository,
                                     SubscriptionRepository subscriptionRepository,
                                     @Lazy SubscriptionService subscriptionService,
                                     StripeWebhookConfig config) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.config = config;
    }

    /**
     * Parse a raw webhook body, audit it, and dispatch by event type.
     * Signature verification already happened upstream
     * (StripePaymentService.verifyWebhookSignature). We re-call
     * Webhook.constructEvent here to get a fully-typed Event object —
     * the signature check is redundant but cheap (computes HMAC again)
     * and ensures we never process a malformed or tampered payload even
     * if the upstream guard is somehow bypassed.
     */
    public PaymentGateway.WebhookResult dispatch(PaymentGateway.RawWebhookEvent rawEvent) {
        String rawBody = new String(rawEvent.rawBody());
        Event stripeEvent;
        try {
            stripeEvent = Webhook.constructEvent(
                    rawBody, rawEvent.signatureHeader(), config.webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("StripeWebhookEventService: signature verification failed (should have been caught upstream)");
            return new PaymentGateway.WebhookResult(false, "signature verification failed");
        } catch (Exception e) {
            log.warn("StripeWebhookEventService: failed to parse webhook body", e);
            return new PaymentGateway.WebhookResult(false, "failed to parse webhook body: " + e.getMessage());
        }

        // Idempotency — already processed?
        if (eventRepository.existsByStripeEventId(stripeEvent.getId())) {
            log.info("StripeWebhookEventService: event {} already processed — skipping", stripeEvent.getId());
            return new PaymentGateway.WebhookResult(false, "already processed — replay skipped");
        }

        StripeEvent auditRecord = StripeEvent.builder()
                .stripeEventId(stripeEvent.getId())
                .eventType(stripeEvent.getType())
                .paymentMethod("STRIPE")
                .rawBody(rawBody)
                .stripeCreatedAt(Instant.ofEpochSecond(stripeEvent.getCreated()))
                .build();

        String outcome;
        try {
            outcome = handle(stripeEvent);
            auditRecord.setOutcome("PROCESSED");
            auditRecord.setDetail(outcome);
            log.info("StripeWebhookEventService: event {} handled ({}) — {}",
                    stripeEvent.getId(), stripeEvent.getType(), outcome);
        } catch (Exception e) {
            auditRecord.setOutcome("ERRORED");
            auditRecord.setDetail(e.getMessage());
            log.error("StripeWebhookEventService: event {} ({}) processing failed",
                    stripeEvent.getId(), stripeEvent.getType(), e);
            // Don't re-throw — return PROCESSED=false so Stripe retries later
            return new PaymentGateway.WebhookResult(false, "event processing failed: " + e.getMessage());
        } finally {
            eventRepository.save(auditRecord);
        }

        return new PaymentGateway.WebhookResult(true, outcome);
    }

    // ---- per-type handlers -----------------------------------------------

    private String handle(Event event) {
        String type = event.getType();

        return switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                    handleCheckoutCompleted(event);
            case "checkout.session.async_payment_failed" ->
                    handleCheckoutFailed(event);
            case "customer.subscription.deleted" ->
                    handleSubscriptionDeleted(event);
            case "invoice.payment_failed" ->
                    handleInvoicePaymentFailed(event);
            case "invoice.paid" ->
                    handleInvoicePaid(event);
            default -> {
                log.info("StripeWebhookEventService: event type {} — recording only, no handler registered", type);
                yield "SKIPPED (no handler)";
            }
        };
    }

    /**
     * Read {@code data.object} as raw JSON instead of a typed Stripe model.
     * <p>
     * {@code EventDataObjectDeserializer#getObject()} returns empty whenever the
     * event's {@code api_version} differs from the SDK's pinned version — which
     * would silently skip every real webhook the moment Stripe's account version
     * moves. The handler only needs a few id fields, so reading them structurally
     * keeps the endpoint correct across API versions.
     */
    private Map<String, Object> dataObject(Event event) {
        String raw = event.getDataObjectDeserializer().getRawJson();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return jsonMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
    }

    private static String str(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Core activation path: Stripe confirmed payment.
     * Find the PENDING_PAYMENT Subscription by Checkout Session ID,
     * fill in the Stripe subscription/paymentMethod refs, and activate.
     */
    private String handleCheckoutCompleted(Event event) {
        Map<String, Object> session = dataObject(event);
        String checkoutSessionId = str(session, "id");
        if (checkoutSessionId == null) {
            return "SKIPPED (event carries no checkout session id)";
        }

        Subscription subscription = subscriptionRepository
                .findByStripeCheckoutSessionId(checkoutSessionId)
                .orElse(null);
        if (subscription == null) {
            log.warn("checkout.session.completed: no Subscription found for checkoutSessionId={}",
                    checkoutSessionId);
            return "SKIPPED (subscription not found)";
        }

        // Fill Stripe refs from the session
        subscription.setStripe(subscription.getStripe() == null
                ? new io.tapstate.cloud.subscription.StripeSubscriptionRef()
                : subscription.getStripe());
        subscription.getStripe().setSubscriptionId(str(session, "subscription"));
        subscription.getStripe().setCustomerId(str(session, "customer"));
        subscription.getStripe().setCheckoutSessionId(checkoutSessionId);
        subscription.getStripe().setAutoRenewAuthorized(true);
        subscription.getStripe().setAutoRenewAuthorizedChangedAt(Instant.now());
        subscriptionRepository.save(subscription);

        // Hand off to SubscriptionService for FSM + Cluster provisioning
        subscriptionService.activateAndProvision(subscription.getId().toHexString());

        return "PROCESSED — subscription activated";
    }

    private String handleCheckoutFailed(Event event) {
        Map<String, Object> session = dataObject(event);
        String checkoutSessionId = str(session, "id");
        if (checkoutSessionId == null) {
            return "SKIPPED (event carries no checkout session id)";
        }

        Subscription subscription = subscriptionRepository
                .findByStripeCheckoutSessionId(checkoutSessionId)
                .orElse(null);
        if (subscription == null) {
            return "SKIPPED (subscription not found)";
        }

        log.warn("checkout.session.async_payment_failed: subscription {} — payment did not complete",
                subscription.getId());
        return "PROCESSED — checkout failed, subscription left unactivated";
    }

    private String handleSubscriptionDeleted(Event event) {
        Map<String, Object> stripeSubscription = dataObject(event);
        String stripeSubscriptionId = str(stripeSubscription, "id");
        if (stripeSubscriptionId == null) {
            return "SKIPPED (event carries no subscription id)";
        }

        Subscription subscription = subscriptionRepository
                .findByStripeSubscriptionId(stripeSubscriptionId)
                .orElse(null);
        if (subscription == null) {
            return "SKIPPED (subscription not found in our DB)";
        }

        subscriptionService.cancelSubscription(
                subscription.getId().toHexString(),
                subscription.getOrgId().toHexString(),
                "stripe:customer.subscription.deleted");
        return "PROCESSED — subscription cancelled due to Stripe deletion";
    }

    private String handleInvoicePaymentFailed(Event event) {
        log.info("invoice.payment_failed — recording only (self-serve cancel handles user-facing flow)");
        return "SKIPPED (invoice.payment_failed not yet wired — will be payment reminder)";
    }

    private String handleInvoicePaid(Event event) {
        log.info("invoice.paid — recording only (renewal flow not yet wired)");
        return "SKIPPED (invoice.paid — renewal flow)";
    }
}
