package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class FeishuBotClientTest {

    @Test
    void refusesToSendWithoutTheConfiguredSigningSecret() {
        FeishuBotClient client = new FeishuBotClient(
            RestClient.builder(), "https://example.invalid/webhook", " ");

        FeishuBotClient.DeliveryResult result = client.send("notification");

        assertFalse(result.delivered());
        assertEquals("signing_secret_not_configured", result.failureCategory());
    }
}
