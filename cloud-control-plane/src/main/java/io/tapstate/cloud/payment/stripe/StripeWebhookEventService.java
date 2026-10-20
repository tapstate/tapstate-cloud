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
    private final io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService subscriptionStateMachineService;
    private final StripeWebhookConfig config;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public StripeWebhookEventService(StripeEventRepository eventRepository,
                                     SubscriptionRepository subscriptionRepository,
                                     @Lazy SubscriptionService subscriptionService,
                                     io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService subscriptionStateMachineService,
                                     StripeWebhookConfig config) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.subscriptionStateMachineService = subscriptionStateMachineService;
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

        // Idempotency — skip a replay only when a previous delivery actually succeeded.
        // An ERRORED record must stay replayable: Stripe retries failed deliveries, and a
        // blind "event id seen before" check would turn every retry into a silent no-op,
        // leaving the state the failure left behind forever.
        var previous = eventRepository.findByStripeEventId(stripeEvent.getId());
        if (previous.isPresent()) {
            if (!"ERRORED".equals(previous.get().getOutcome())) {
                log.info("StripeWebhookEventService: event {} already processed — skipping", stripeEvent.getId());
                return new PaymentGateway.WebhookResult(false, "already processed — replay skipped");
            }
            log.info("StripeWebhookEventService: event {} previously failed — retrying", stripeEvent.getId());
            // The audit row is keyed by the Stripe event id, so the save below
            // upserts over it — no delete is needed to make the retry durable.
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

        subscriptionService.cancelDueToChannelDeletion(
                subscription.getId().toHexString(),
                "stripe:customer.subscription.deleted");
        return "PROCESSED — subscription cancelled due to Stripe deletion";
    }

    private String handleInvoicePaymentFailed(Event event) {
        log.info("invoice.payment_failed — recording only (self-serve cancel handles user-facing flow)");
        return "SKIPPED (invoice.payment_failed not yet wired — will be payment reminder)";
    }

    /**
     * Renewal — Stripe collected the next invoice. The billing period the customer
     * just paid for lives on the invoice, so this is what advances our own
     * {@code currentPeriodEnd}; without it the locally stored period would freeze at
     * the value written at checkout time and every period-end decision (cancel
     * reaper, entitlement) would be based on a stale date.
     */
    private String handleInvoicePaid(Event event) {
        Map<String, Object> invoice = dataObject(event);
        String stripeSubscriptionId = invoiceSubscriptionId(invoice);
        if (stripeSubscriptionId == null) {
            return "SKIPPED (invoice is not tied to a subscription)";
        }

        Subscription subscription = subscriptionRepository
                .findByStripeSubscriptionId(stripeSubscriptionId)
                .orElse(null);
        if (subscription == null) {
            return "SKIPPED (subscription not found in our DB)";
        }

        Instant periodStart = epochSeconds(invoice.get("period_start"));
        Instant periodEnd = epochSeconds(invoice.get("period_end"));
        if (periodEnd == null) {
            return "SKIPPED (invoice carries no period_end)";
        }

        if (periodStart != null) {
            subscription.setCurrentPeriodStart(periodStart);
        }
        subscription.setCurrentPeriodEnd(periodEnd);
        subscriptionRepository.save(subscription);

        // A paid invoice means the paid period is running again: the first invoice
        // after a trial converts TRIAL, and one after dunning converts PAST_DUE.
        var statusNow = subscription.getStatus();
        if (statusNow == io.tapstate.cloud.subscription.SubscriptionStatus.TRIAL
                || statusNow == io.tapstate.cloud.subscription.SubscriptionStatus.PAST_DUE) {
            var conversionEvent = statusNow == io.tapstate.cloud.subscription.SubscriptionStatus.TRIAL
                    ? io.tapstate.cloud.subscription.fsm.SubscriptionEventType.PAYMENT_CONFIRMED
                    : io.tapstate.cloud.subscription.fsm.SubscriptionEventType.PAYMENT_RECEIVED;
            subscriptionStateMachineService.transition(subscription.getId(),
                    conversionEvent, "stripe:invoice.paid");
        }

        log.info("invoice.paid: subscription {} period advanced to {} (stripe subscription {})",
                subscription.getId(), periodEnd, stripeSubscriptionId);
        return "PROCESSED — billing period advanced to " + periodEnd;
    }

    /**
     * Stripe moved the subscription reference from {@code invoice.subscription} to
     * {@code invoice.parent.subscription_details.subscription}; accept either so the
     * handler keeps working across account API versions.
     */
    @SuppressWarnings("unchecked")
    private static String invoiceSubscriptionId(Map<String, Object> invoice) {
        String direct = str(invoice, "subscription");
        if (direct != null) {
            return direct;
        }
        Object parent = invoice.get("parent");
        if (parent instanceof Map<?, ?> parentMap) {
            Object details = ((Map<String, Object>) parentMap).get("subscription_details");
            if (details instanceof Map<?, ?> detailsMap) {
                return str((Map<String, Object>) detailsMap, "subscription");
            }
        }
        return null;
    }

    private static Instant epochSeconds(Object value) {
        if (value instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        return null;
    }
}
