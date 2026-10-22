package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TawkWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    private TawkWebhookService webhookService;
    private TawkWebhookController controller;

    @BeforeEach
    void setUp() {
        webhookService = mock(TawkWebhookService.class);
        controller = new TawkWebhookController(webhookService, SECRET, "property-1");
    }

    @Test
    void recordsOnlyAValidSignedEventForTheConfiguredProperty() throws Exception {
        byte[] body = chatPayload("property-1");
        when(webhookService.accepts("chat:start")).thenReturn(true);

        ResponseEntity<Void> response = controller.receive(signature(body), "event-1", body);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(webhookService).record("event-1", new TawkWebhookPayload(
            "chat:start", "property-1", "Cloud Dev", "chat-123", null));
    }

    @Test
    void rejectsAnInvalidSignatureBeforeRecordingTheEvent() throws Exception {
        byte[] body = chatPayload("property-1");

        ResponseEntity<Void> response = controller.receive("bad-signature", "event-1", body);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(webhookService, never()).record(anyString(), any(TawkWebhookPayload.class));
    }

    @Test
    void rejectsAnEventFromAnotherProperty() throws Exception {
        byte[] body = chatPayload("property-2");

        ResponseEntity<Void> response = controller.receive(signature(body), "event-1", body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(webhookService, never()).record(anyString(), any(TawkWebhookPayload.class));
    }

    private static byte[] chatPayload(String propertyId) {
        return ("{\"event\":\"chat:start\",\"chatId\":\"chat-123\","
            + "\"property\":{\"id\":\"" + propertyId + "\",\"name\":\"Cloud Dev\"},"
            + "\"visitor\":{\"email\":\"private@example.com\"},"
            + "\"message\":{\"text\":\"private-feedback\"}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static String signature(byte[] body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
