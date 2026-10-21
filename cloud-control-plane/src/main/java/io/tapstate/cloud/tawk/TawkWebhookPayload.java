package io.tapstate.cloud.tawk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The small, non-sensitive part of a tawk.to event needed for a group alert. */
public record TawkWebhookPayload(
        String eventType,
        String propertyId,
        String propertyName,
        String recordId,
        String recordNumber) {

    public static TawkWebhookPayload parse(byte[] rawBody, ObjectMapper objectMapper) throws Exception {
        JsonNode root = objectMapper.readTree(rawBody);
        if (root == null || !root.isObject()) {
            return null;
        }

        String eventType = text(root, "event");
        JsonNode property = root.path("property");
        String propertyId = text(property, "id");
        String propertyName = text(property, "name");
        if (eventType == null || propertyId == null) {
            return null;
        }

        if (eventType.startsWith("chat:")) {
            return new TawkWebhookPayload(eventType, propertyId, propertyName,
                text(root, "chatId"), null);
        }
        if ("ticket:create".equals(eventType)) {
            JsonNode ticket = root.path("ticket");
            return new TawkWebhookPayload(eventType, propertyId, propertyName,
                text(ticket, "id"), text(ticket, "humanId"));
        }
        return new TawkWebhookPayload(eventType, propertyId, propertyName, null, null);
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
