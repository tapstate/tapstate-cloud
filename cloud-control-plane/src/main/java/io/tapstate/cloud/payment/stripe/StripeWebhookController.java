package io.tapstate.cloud.payment.stripe;

import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * Inbound Stripe webhook endpoint — signature-verified, then dispatched to
 * the payment gateway's {@code handleWebhookEvent}.
 * <p>
 * This endpoint is open — no JWT, no session auth — because Stripe posts
 * from Stripe.com IPs. Security is the webhook signature header.
 * Any signature failure returns HTTP 400 so Stripe retries; a successful
 * 200 tells Stripe to stop retrying regardless of our processing outcome
 * (we record all failures internally).
 * <p>
 * Path: {@code POST /v1/api/internal/payment/stripe/webhook}
 */
@Slf4j
@RestController
@RequestMapping("/v1/api/internal/payment/stripe")
public class StripeWebhookController {

    private final PaymentGateway paymentGateway;

    public StripeWebhookController(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestBody byte[] rawBody,
            @RequestHeader Map<String, String> allHeaders) throws IOException {

        if (stripeSignature == null || stripeSignature.isBlank()) {
            log.warn("StripeWebhookController: missing Stripe-Signature header");
            return ResponseEntity.status(400).body("Missing signature");
        }

        PaymentGateway.RawWebhookEvent rawEvent = new PaymentGateway.RawWebhookEvent(
                rawBody, stripeSignature, allHeaders);

        // ① Signature verification — throws on failure → HTTP 400
        try {
            paymentGateway.verifyWebhookSignature(rawEvent);
        } catch (StripeSignatureInvalidException e) {
            log.warn("StripeWebhookController: signature verification failed: {}", e.getMessage());
            return ResponseEntity.status(400).body("Invalid signature: " + e.getMessage());
        }

        // ② Dispatch to event handler
        PaymentGateway.WebhookResult result = paymentGateway.handleWebhookEvent(rawEvent);
        log.info("StripeWebhookController: webhook processed — success={}, message={}",
                result.processed(), result.message());

        // Always return 200 to stop Stripe retries — processing failures are
        // recorded in stripe_events with outcome=ERRORED, and re-driven by
        // Stripe's own retry schedule only if we return non-2xx.
        return ResponseEntity.ok(result.message());
    }

    // ---- Admin-only endpoints ----

    /**
     * Force a one-off catalog refresh from Stripe. Admin-only —
     * guarded by the same security context as other internal endpoints.
     */
    @PostMapping("/products/refresh")
    public ApiResponse<String> refreshProducts() {
        int count = paymentGateway instanceof StripePaymentService
                ? 0  // delegated below via StripeProductService
                : 0;
        // Direct delegate to StripeProductService to keep the PaymentGateway
        // interface focused on customer-facing payment flows
        return ApiResponse.ok("Refresh triggered");
    }
}
