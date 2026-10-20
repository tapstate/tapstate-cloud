package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.CheckoutResponse;
import io.tapstate.cloud.sdk.model.CreateCheckoutRequest;
import io.tapstate.cloud.sdk.model.FinalizeProvisioningRequest;
import io.tapstate.cloud.sdk.model.SpecWithPricingResponse;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-environment integration test for the Stripe Checkout subscription flow.
 * <p>
 * Runs against an already-deployed Control Plane (see {@link RealEnvIT}) — no
 * local application is forked and no Stripe SDK is stubbed, so this exercises
 * the real network path: real Stripe Checkout Session creation, real signature
 * verification, real MongoDB persistence, real state machine.
 *
 * <h3>Environment</h3>
 * Required (inherited from {@link RealEnvIT}): {@code CP_BASE_URL},
 * {@code CP_EMAIL}, {@code CP_PASSWORD}.
 * <p>
 * Optional: {@code CP_STRIPE_WEBHOOK_SECRET} — the {@code whsec_...} value the
 * target environment validates against. Without it the webhook-dependent tests
 * are skipped; catalog and checkout-creation assertions still run.
 * <p>
 * The target environment must have a working payment-channel credential and
 * active pricing, otherwise {@code /v1/api/specs} is empty and checkout is
 * rejected as "pricing not configured" — which is itself a useful assertion.
 *
 * <h3>What the signed webhook deliberately omits</h3>
 * The synthetic {@code checkout.session.completed} payload carries no
 * {@code subscription} field. The activation path only needs the Checkout
 * Session id; leaving the Stripe subscription id unset makes the later cancel
 * tests take the documented "no Stripe subscription — cancel locally" branch
 * instead of calling Stripe with an id Stripe has never issued. Stripe-side
 * cancellation is covered separately by the WireMock unit tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionCheckoutIT extends RealEnvIT {

    /** Stripe Checkout URLs carry the session id in the path: /c/pay/cs_test_xxx */
    private static final Pattern CHECKOUT_SESSION_ID =
        Pattern.compile("/c/pay/(cs_[A-Za-z0-9_]+)");

    private static final String WEBHOOK_PATH = "/v1/api/internal/payment/stripe/webhook";

    /** Resolved once per class — real environments cap one Organization per account. */
    private static String cachedOrgId;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // ========== T0: public spec catalog is JOINed with channel pricing ==========

    @Test
    @Order(0)
    void t0_specCatalog_returnsPricedSpecsWithoutAuthentication() throws Exception {
        // Anonymous client — the Console renders pricing before sign-in.
        ApiClient anonymous = new ApiClient();
        anonymous.setBasePath(BASE_URL);
        SubscriptionApi publicApi = new SubscriptionApi(anonymous);

        List<SpecWithPricingResponse> specs = publicApi.listSpecs().getData();

        assertNotNull(specs, "spec catalog payload must not be null");
        assertFalse(specs.isEmpty(),
            "/v1/api/specs is empty — the control plane has no Stripe catalog for this environment "
                + "(check the Stripe credential and that the catalog sync ran)");

        for (SpecWithPricingResponse spec : specs) {
            assertNotNull(spec.getKey(), "spec key must not be null");
            assertTrue(spec.getUnitPriceCents() > 0,
                "spec " + spec.getKey() + " must carry a positive price, got " + spec.getUnitPriceCents());
            assertNotNull(spec.getStripePriceId(), "spec " + spec.getKey() + " must carry a price id");
            assertTrue(spec.getStripePriceId().startsWith("price_"),
                "price id must be a Stripe Price id, got " + spec.getStripePriceId());
            assertEquals("STRIPE", spec.getPaymentMethod(),
                "this catalog is served from the Stripe channel");
            assertNotNull(spec.getCurrency(), "currency must be set");
        }

        // The three specs seeded from classpath:spec-config.json must all be
        // priced, otherwise the Console shows a partial catalog.
        List<String> keys = specs.stream().map(SpecWithPricingResponse::getKey).toList();
        assertTrue(keys.containsAll(List.of("2c8g", "4c16g", "8c32g")),
            "expected all three platform specs to be priced, got " + keys);
    }

    // ========== T1: checkout rejects an unknown spec ==========

    @Test
    @Order(1)
    void t1_checkout_rejectsUnknownSpec() throws Exception {
        loginAsConfiguredUser();
        String orgId = orgId();

        ApiException bad = assertThrows(ApiException.class, () ->
            subscriptionApi.createCheckoutSession(orgId,
                new CreateCheckoutRequest().specKey("no-such-spec")));
        assertEquals(400, bad.getCode(),
            "an unknown spec key must be rejected with 400, body=" + bad.getResponseBody());
        assertTrue(bad.getResponseBody().contains("subscription.spec-not-found")
                || bad.getResponseBody().contains("not found"),
            "400 body should name the missing spec, got: " + bad.getResponseBody());
    }

    // ========== T2: checkout creates a PENDING_PAYMENT shell, no resources ==========

    @Test
    @Order(2)
    void t2_checkout_createsPendingPaymentShellWithStripeUrl() throws Exception {
        loginAsConfiguredUser();
        String orgId = orgId();

        String specKey = pricedSpecKey();

        CheckoutResponse checkout = subscriptionApi.createCheckoutSession(orgId,
            new CreateCheckoutRequest()
                .specKey(specKey)
                .clientRequestId("it-" + System.nanoTime()))
            .getData();

        assertNotNull(checkout.getSubscriptionId(), "checkout must return the shell subscription id");
        assertEquals("PENDING_PAYMENT", checkout.getStatus(),
            "a checkout shell must start in PENDING_PAYMENT");

        assertNotNull(checkout.getCheckoutUrl(), "checkout URL must be present");
        assertTrue(checkout.getCheckoutUrl().startsWith("https://checkout.stripe.com/"),
            "checkout must be a Stripe-hosted URL, got " + checkout.getCheckoutUrl());

        // Persisted shell — visible through the ordinary read path.
        SubscriptionResponse shell = subscriptionApi
            .getSubscription(orgId, checkout.getSubscriptionId()).getData();
        assertEquals("PENDING_PAYMENT", shell.getStatus());
        assertNull(shell.getClusterId(),
            "no Cluster may exist before payment is confirmed");
        assertNull(shell.getMdbAllocationId(),
            "no MdbAllocation may exist before payment is confirmed");
        assertNull(shell.getResourceState(),
            "resourceState stays null until provisioning is finalised");
    }

    // ========== T3: a correctly signed webhook activates the subscription ==========

    @Test
    @Order(3)
    void t3_signedWebhookCheckoutCompleted_activatesSubscription() throws Exception {
        String webhookSecret = webhookSecret();
        loginAsConfiguredUser();
        String orgId = orgId();

        CheckoutResponse checkout = subscriptionApi.createCheckoutSession(orgId,
            new CreateCheckoutRequest()
                .specKey(pricedSpecKey())
                .clientRequestId("it-" + System.nanoTime()))
            .getData();

        String checkoutSessionId = extractCheckoutSessionId(checkout.getCheckoutUrl());

        HttpResponse<String> resp = postSignedWebhook(webhookSecret,
            checkoutCompletedEvent(checkoutSessionId));

        assertEquals(200, resp.statusCode(),
            "a correctly signed webhook must be accepted, body=" + resp.body());

        SubscriptionResponse activated = subscriptionApi
            .getSubscription(orgId, checkout.getSubscriptionId()).getData();
        assertEquals("ACTIVE", activated.getStatus(),
            "checkout.session.completed must drive PENDING_PAYMENT → ACTIVE");

        // Idempotency: replaying the same event must not error and must not
        // change the outcome.
        HttpResponse<String> replay = postSignedWebhook(webhookSecret,
            checkoutCompletedEvent(checkoutSessionId));
        assertEquals(200, replay.statusCode(), "a replayed webhook must not fail, body=" + replay.body());
        assertEquals("ACTIVE",
            subscriptionApi.getSubscription(orgId, checkout.getSubscriptionId()).getData().getStatus());
    }

    // ========== T4: a forged signature is rejected before any state change ==========

    @Test
    @Order(4)
    void t4_webhook_rejectsForgedSignature() throws Exception {
        // No secret needed: sign with a value the server cannot accept.
        HttpResponse<String> resp = postWebhook(
            checkoutCompletedEvent("cs_test_forged_" + System.nanoTime()),
            "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64));

        assertEquals(400, resp.statusCode(),
            "a forged signature must be rejected with 400, body=" + resp.body());
    }

    // ========== T5: cancel at period end keeps the subscription ACTIVE → PENDING_CANCEL ==========

    @Test
    @Order(5)
    void t5_cancelAtPeriodEnd_movesToPendingCancel() throws Exception {
        String webhookSecret = webhookSecret();
        loginAsConfiguredUser();
        String orgId = orgId();

        CheckoutResponse checkout = subscriptionApi.createCheckoutSession(orgId,
            new CreateCheckoutRequest()
                .specKey(pricedSpecKey())
                .clientRequestId("it-" + System.nanoTime()))
            .getData();
        String checkoutSessionId = extractCheckoutSessionId(checkout.getCheckoutUrl());
        assertEquals(200, postSignedWebhook(webhookSecret,
            checkoutCompletedEvent(checkoutSessionId)).statusCode());

        SubscriptionResponse cancelled = subscriptionApi
            .cancelAtPeriodEnd(orgId, checkout.getSubscriptionId()).getData();

        assertEquals("PENDING_CANCEL", cancelled.getStatus(),
            "cancel-at-period-end must keep the subscription usable until the period ends");

        // Idempotent — repeating is a no-op, not an error.
        assertEquals("PENDING_CANCEL",
            subscriptionApi.cancelAtPeriodEnd(orgId, checkout.getSubscriptionId()).getData().getStatus());
    }

    // ========== T6: cancel-now terminates immediately ==========

    @Test
    @Order(6)
    void t6_cancelNow_cancelsImmediately() throws Exception {
        String webhookSecret = webhookSecret();
        loginAsConfiguredUser();
        String orgId = orgId();

        CheckoutResponse checkout = subscriptionApi.createCheckoutSession(orgId,
            new CreateCheckoutRequest()
                .specKey(pricedSpecKey())
                .clientRequestId("it-" + System.nanoTime()))
            .getData();
        String checkoutSessionId = extractCheckoutSessionId(checkout.getCheckoutUrl());
        assertEquals(200, postSignedWebhook(webhookSecret,
            checkoutCompletedEvent(checkoutSessionId)).statusCode());

        SubscriptionResponse cancelled = subscriptionApi
            .cancelNow(orgId, checkout.getSubscriptionId()).getData();

        assertEquals("CANCELLED", cancelled.getStatus(),
            "cancel-now must terminate without a grace period");
        assertFalse(Boolean.TRUE.equals(cancelled.isProvisioningAllowed()),
            "a cancelled subscription must block provisioning");

        // Idempotent.
        assertEquals("CANCELLED",
            subscriptionApi.cancelNow(orgId, checkout.getSubscriptionId()).getData().getStatus());
    }

    // ========== T7: provisioning cannot be finalised before activation ==========

    @Test
    @Order(7)
    void t7_finalizeProvisioning_rejectedWhilePendingPayment() throws Exception {
        loginAsConfiguredUser();
        String orgId = orgId();

        CheckoutResponse checkout = subscriptionApi.createCheckoutSession(orgId,
            new CreateCheckoutRequest()
                .specKey(pricedSpecKey())
                .clientRequestId("it-" + System.nanoTime()))
            .getData();

        ApiException rejected = assertThrows(ApiException.class, () ->
            subscriptionApi.finalizeProvisioning(orgId, checkout.getSubscriptionId(),
                new FinalizeProvisioningRequest()
                    .region("ap-southeast-1")
                    .clusterName("it-finalize-" + System.nanoTime())));

        assertTrue(rejected.getCode() >= 400 && rejected.getCode() < 500,
            "finalising an unactivated subscription must be a client error, got " + rejected.getCode()
                + " body=" + rejected.getResponseBody());

        // Still a shell — nothing was provisioned.
        assertNull(subscriptionApi.getSubscription(orgId, checkout.getSubscriptionId()).getData().getClusterId());
    }

    // ========== Helpers ==========

    /**
     * Resolve the Organization used by every scenario, once per class.
     * <p>
     * Real environments commonly cap one Organization per account, so a
     * per-test create would 409 on the second test. Prefer the account's
     * existing Organization, and only create one when the account has none.
     */
    private String orgId() throws Exception {
        if (cachedOrgId != null) {
            return cachedOrgId;
        }
        if (ORG_ID != null && !ORG_ID.isBlank()) {
            cachedOrgId = ORG_ID;
            return cachedOrgId;
        }
        var existing = orgsApi.listMyOrganizations().getData();
        if (existing != null && !existing.isEmpty()) {
            cachedOrgId = existing.get(0).getId();
            System.out.println("[real-env] reusing Organization " + cachedOrgId);
            return cachedOrgId;
        }
        cachedOrgId = resolveOrgId();
        System.out.println("[real-env] created Organization " + cachedOrgId);
        return cachedOrgId;
    }

    /** First spec the environment actually prices — keeps the IT independent of seed order. */
    private String pricedSpecKey() throws Exception {
        ApiClient anonymous = new ApiClient();
        anonymous.setBasePath(BASE_URL);
        List<SpecWithPricingResponse> specs = new SubscriptionApi(anonymous).listSpecs().getData();
        assumeTrue(specs != null && !specs.isEmpty(),
            "/v1/api/specs is empty — no priced spec to check out");
        return specs.get(0).getKey();
    }

    private String webhookSecret() {
        String secret = System.getenv("CP_STRIPE_WEBHOOK_SECRET");
        assumeTrue(secret != null && !secret.isBlank(),
            "CP_STRIPE_WEBHOOK_SECRET is not set — skipping webhook-dependent scenario");
        return secret.trim();
    }

    private String extractCheckoutSessionId(String checkoutUrl) {
        Matcher m = CHECKOUT_SESSION_ID.matcher(checkoutUrl);
        assertTrue(m.find(),
            "could not extract a Checkout Session id from the Stripe URL: " + checkoutUrl);
        return m.group(1);
    }

    /**
     * Minimal {@code checkout.session.completed} event. {@code subscription} is
     * intentionally absent — see the class javadoc.
     */
    private String checkoutCompletedEvent(String checkoutSessionId) {
        long now = Instant.now().getEpochSecond();
        return """
            {
              "id": "evt_it_%d",
              "object": "event",
              "api_version": "2024-10-28",
              "created": %d,
              "livemode": false,
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "checkout.session",
                  "mode": "subscription",
                  "status": "complete",
                  "payment_status": "paid"
                }
              }
            }
            """.formatted(now, now, checkoutSessionId);
    }

    private HttpResponse<String> postSignedWebhook(String secret, String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signature = hmacSha256Hex(secret, timestamp + "." + payload);
        return postWebhook(payload, "t=" + timestamp + ",v1=" + signature);
    }

    private HttpResponse<String> postWebhook(String payload, String signatureHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + WEBHOOK_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Stripe-Signature", signatureHeader)
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
