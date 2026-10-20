package io.tapstate.cloud.billing;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * Stripe API stubs and webhook signing shared by the subscription integration
 * tests.
 * <p>
 * The tests point the Stripe SDK at this server ({@code Stripe.overrideApiBase}),
 * so every outbound call the control plane makes is a real HTTP request with the
 * real SDK's encoding — only the far end is fake.
 */
final class StripeStub {

    static final String CUSTOMER_ID = "cus_it_stub";
    static final String CHECKOUT_SESSION_ID = "cs_test_it_stub";
    static final String CHECKOUT_URL = "https://checkout.stripe.test/c/pay/" + CHECKOUT_SESSION_ID;
    static final String SUBSCRIPTION_ID = "sub_it_stub";
    static final String WEBHOOK_SECRET = "whsec_it_stub";

    private StripeStub() {
    }

    /** Customer lookup/create plus Checkout Session creation. */
    static void checkout(WireMockExtension server) {
        server.stubFor(get(urlPathEqualTo("/v1/customers/search"))
                .willReturn(okJson("""
                        {"object":"search_result","url":"/v1/customers/search","has_more":false,"data":[]}
                        """)));
        server.stubFor(post(urlPathEqualTo("/v1/customers"))
                .willReturn(okJson("""
                        {"id":"%s","object":"customer"}
                        """.formatted(CUSTOMER_ID))));
        server.stubFor(post(urlPathEqualTo("/v1/checkout/sessions"))
                .willReturn(okJson("""
                        {"id":"%s","object":"checkout.session","mode":"subscription","status":"open",
                         "url":"%s"}
                        """.formatted(CHECKOUT_SESSION_ID, CHECKOUT_URL))));
    }

    /** Subscription retrieve + update — the cancel-at-period-end path. */
    static void cancelAtPeriodEnd(WireMockExtension server) {
        server.stubFor(get(urlPathEqualTo("/v1/subscriptions/" + SUBSCRIPTION_ID))
                .willReturn(okJson(subscription(false))));
        server.stubFor(post(urlPathEqualTo("/v1/subscriptions/" + SUBSCRIPTION_ID))
                .willReturn(okJson(subscription(true))));
    }

    private static String subscription(boolean cancelAtPeriodEnd) {
        return """
                {"id":"%s","object":"subscription","status":"active","cancel_at_period_end":%s,
                 "current_period_start":1792486269,"current_period_end":1795078269,
                 "items":{"object":"list","url":"/v1/subscription_items?subscription=%s",
                          "has_more":false,"data":[]}}
                """.formatted(SUBSCRIPTION_ID, cancelAtPeriodEnd, SUBSCRIPTION_ID);
    }

    /** Envelope for one webhook event; {@code dataObject} is the raw {@code data.object}. */
    static String event(String type, String dataObject) {
        long now = Instant.now().getEpochSecond();
        return event("evt_it_" + now, type, dataObject);
    }

    /**
     * Envelope with an explicit event id. Distinct events delivered in the same
     * second need distinct ids, otherwise the audit row reads as a replay.
     */
    static String event(String eventId, String type, String dataObject) {
        long now = Instant.now().getEpochSecond();
        return """
                {"id":"%s","object":"event","api_version":"2024-10-28","created":%d,
                 "livemode":false,"type":"%s","data":{"object":%s}}
                """.formatted(eventId, now, type, dataObject);
    }

    static String checkoutCompleted(String checkoutSessionId) {
        return event("checkout.session.completed", """
                {"id":"%s","object":"checkout.session","mode":"subscription","status":"complete",
                 "payment_status":"paid","customer":"%s","subscription":"%s"}
                """.formatted(checkoutSessionId, CUSTOMER_ID, SUBSCRIPTION_ID));
    }

    /**
     * One {@code invoice.paid} delivery. The invoice-level period and the line
     * period are separate on purpose: a trial-start {@code $0} invoice carries a
     * zero-length invoice period and the real trial window on its line item.
     */
    static String invoicePaid(String eventId, long invoicePeriodStart, long invoicePeriodEnd,
                              long linePeriodStart, long linePeriodEnd,
                              String billingReason, long amountPaid) {
        return event(eventId, "invoice.paid", """
                {"id":"in_it_%s","object":"invoice","subscription":"%s",
                 "billing_reason":"%s","amount_paid":%d,"amount_due":%d,
                 "period_start":%d,"period_end":%d,
                 "lines":{"object":"list","has_more":false,"data":[
                   {"id":"il_it","object":"line_item","amount":%d,
                    "period":{"start":%d,"end":%d}}]}}
                """.formatted(eventId, SUBSCRIPTION_ID, billingReason, amountPaid, amountPaid,
                invoicePeriodStart, invoicePeriodEnd, amountPaid, linePeriodStart, linePeriodEnd));
    }

    static String stripeSignatureHeader(String payload) {
        long timestamp = Instant.now().getEpochSecond();
        return "t=" + timestamp + ",v1=" + hmacSha256Hex(WEBHOOK_SECRET, timestamp + "." + payload);
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign webhook payload", e);
        }
    }
}
