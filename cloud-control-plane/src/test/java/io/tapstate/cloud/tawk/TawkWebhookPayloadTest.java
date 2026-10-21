package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TawkWebhookPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatPayloadRetainsRoutingMetadataButDropsVisitorAndMessageContent() throws Exception {
        byte[] body = """
            {
              "event":"chat:start",
              "chatId":"chat-123",
              "property":{"id":"property-1","name":"Cloud Dev"},
              "visitor":{"name":"private-name","email":"private@example.com"},
              "message":{"text":"private-feedback"}
            }
            """.getBytes();

        TawkWebhookPayload payload = TawkWebhookPayload.parse(body, objectMapper);

        assertEquals("chat:start", payload.eventType());
        assertEquals("property-1", payload.propertyId());
        assertEquals("Cloud Dev", payload.propertyName());
        assertEquals("chat-123", payload.recordId());
        assertFalse(payload.toString().contains("private-name"));
        assertFalse(payload.toString().contains("private@example.com"));
        assertFalse(payload.toString().contains("private-feedback"));
    }

    @Test
    void ticketPayloadKeepsOnlyTicketRoutingFields() throws Exception {
        byte[] body = """
            {
              "event":"ticket:create",
              "property":{"id":"property-1","name":"Cloud Prod"},
              "requester":{"name":"private-name","email":"private@example.com"},
              "ticket":{"id":"ticket-123","humanId":42,"message":"private-feedback"}
            }
            """.getBytes();

        TawkWebhookPayload payload = TawkWebhookPayload.parse(body, objectMapper);

        assertEquals("ticket:create", payload.eventType());
        assertEquals("ticket-123", payload.recordId());
        assertEquals("42", payload.recordNumber());
        assertFalse(payload.toString().contains("private-name"));
        assertFalse(payload.toString().contains("private@example.com"));
        assertFalse(payload.toString().contains("private-feedback"));
    }
}
