package io.tapstate.cloud.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ProductionTrialInviteRelayClientTest {
    private static final String ENDPOINT =
        "https://cloud.tapstate.com/v1/api/webhooks/feishu/production-invite";
    private static final String SECRET = "shared-production-invite-hmac-secret";

    @Test
    void postsSignedRequestAndParsesProductionLink() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"chatId\":\"oc_source\",\"messageId\":\"om_one\"}"))
            .andExpect(request -> {
                String timestamp = request.getHeaders().getFirst("X-Tapstate-Invite-Timestamp");
                String signature = request.getHeaders().getFirst("X-Tapstate-Invite-Signature");
                assertTrue(timestamp != null && timestamp.matches("[0-9]{10}"));
                try {
                    assertEquals(expectedSignature(Long.parseLong(timestamp), "oc_source", "om_one"), signature);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            })
            .andRespond(withSuccess(
                "{\"url\":\"https://cloud.tapstate.com/auth#invite=A2B3C4D5E6F7\","
                    + "\"expiresAt\":\"2026-11-05T00:00:00Z\"}", MediaType.APPLICATION_JSON));

        ProductionTrialInviteRelayClient client =
            new ProductionTrialInviteRelayClient(builder.build(), ENDPOINT, SECRET);
        var issued = client.issue("oc_source", "om_one");

        assertEquals("https://cloud.tapstate.com/auth#invite=A2B3C4D5E6F7", issued.url());
        assertEquals(Instant.parse("2026-11-05T00:00:00Z"), issued.expiresAt());
        server.verify();
    }

    @Test
    void rejectsNonHttpsRelayEndpointsBeforeSendingTheSecret() {
        ProductionTrialInviteRelayClient client =
            new ProductionTrialInviteRelayClient(RestClient.builder().build(), "http://relay.example", SECRET);

        assertThrows(IllegalStateException.class, () -> client.issue("oc_source", "om_one"));
    }

    private static String expectedSignature(long timestamp, String chatId, String messageId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] canonical = ("production-invite\n" + timestamp + "\n" + chatId + "\n" + messageId)
            .getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(mac.doFinal(canonical));
    }
}
