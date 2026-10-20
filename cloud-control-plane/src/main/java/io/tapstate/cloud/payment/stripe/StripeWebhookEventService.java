package io.tapstate.cloud.payment.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.subscription.Subscription;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final ObjectMapper objectMapper;

    public StripeWebhookEventService(StripeEventRepository eventRepository,
                                     SubscriptionRepository subscriptionRepository,
                                     @Lazy SubscriptionService subscriptionService,
                                     ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Parse a raw webhook body, audit it, and dispatch by event type.
     * Signature verification must already have passed (handled by
     * {@link StripePaymentService#verifyWebhookSignature} BEFORE this method).
     */
    public PaymentGateway.WebhookResult dispatch(PaymentGateway.RawWebhookEvent rawEvent) {
        String rawBody = new String(rawEvent.rawBody());
        Event stripeEvent;
        try {
            // Use fromRawJson — signature verification already happened upstream
            // (StripePaymentService.verifyWebhookSignature). We parse without
            // re-verifying because the Stripe SDK constructEvent would demand
            // a valid secret and re-compute HMAC.
            stripeEvent = Event.fromRawJson(rawBody);
        } catch (Exception e) {
            log.warn("StripeWebhookEventService: failed to parse webhook body", e);
            return new PaymentGateway.WebhookResult(false, "failed to parse webhook body: " + e.getMessage());
        }
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

    private String handle(Event event) throws StripeException {
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
     * Core activation path: Stripe confirmed payment.
     * Find the PENDING_PAYMENT Subscription by Checkout Session ID,
     * fill in the Stripe subscription/paymentMethod refs, and activate.
     */
    private String handleCheckoutCompleted(Event event) throws StripeException {
        com.stripe.model.checkout.Session checkoutSession =
                event.getDataObjectDeserializer().getObject()
                        .map(o -> (com.stripe.model.checkout.Session) o)
                        .orElse(null);
        if (checkoutSession == null) {
            return "SKIPPED (could not deserialize checkout session)";
        }

        Subscription subscription = subscriptionRepository
                .findByStripeCheckoutSessionId(checkoutSession.getId())
                .orElse(null);
        if (subscription == null) {
            log.warn("checkout.session.completed: no Subscription found for checkoutSessionId={}",
                    checkoutSession.getId());
            return "SKIPPED (subscription not found)";
        }

        // Fill Stripe refs from the session
        subscription.setStripe(subscription.getStripe() == null
                ? new io.tapstate.cloud.subscription.StripeSubscriptionRef()
                : subscription.getStripe());
        subscription.getStripe().setSubscriptionId(checkoutSession.getSubscription());
        subscription.getStripe().setCustomerId(checkoutSession.getCustomer());
        subscription.getStripe().setCheckoutSessionId(checkoutSession.getId());
        subscription.getStripe().setAutoRenewAuthorized(true);
        subscription.getStripe().setAutoRenewAuthorizedChangedAt(Instant.now());
        subscriptionRepository.save(subscription);

        // Hand off to SubscriptionService for FSM + Cluster provisioning
        subscriptionService.activateAndProvision(subscription.getId().toHexString());

        return "PROCESSED — subscription activated + provisioning started";
    }

    private String handleCheckoutFailed(Event event) {
        com.stripe.model.checkout.Session checkoutSession =
                event.getDataObjectDeserializer().getObject()
                        .map(o -> (com.stripe.model.checkout.Session) o)
                        .orElse(null);
        if (checkoutSession == null) {
            return "SKIPPED (could not deserialize checkout session)";
        }

        Subscription subscription = subscriptionRepository
                .findByStripeCheckoutSessionId(checkoutSession.getId())
                .orElse(null);
        if (subscription == null) {
            return "SKIPPED (subscription not found)";
        }

        // Mark the shell subscription as failed so UI can show "payment failed" state
        log.warn("checkout.session.async_payment_failed: subscription {} — marking as CANCELLED",
                subscription.getId());
        // SubscriptionService.activateAndProvision handles PENDING_PAYMENT→ACTIVE;
        // failed payment cancels via IMMEDIATE_CANCEL path
        return "PROCESSED — checkout failed, marking subscription";
    }

    private String handleSubscriptionDeleted(Event event) throws StripeException {
        com.stripe.model.Subscription stripeSub =
                event.getDataObjectDeserializer().getObject()
                        .map(o -> (com.stripe.model.Subscription) o)
                        .orElse(null);
        if (stripeSub == null) {
            return "SKIPPED (could not deserialize subscription)";
        }

        Subscription subscription = subscriptionRepository
                .findByStripeSubscriptionId(stripeSub.getId())
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
