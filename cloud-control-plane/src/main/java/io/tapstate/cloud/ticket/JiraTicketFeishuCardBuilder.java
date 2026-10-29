package io.tapstate.cloud.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JiraTicketFeishuCardBuilder {

    private static final int MAX_CARD_BYTES = 24 * 1024;
    private static final String HEADER_ICON = "file-form_colorful";
    private static final String TRUNCATION_SUFFIX = "\n\n（内容已截断；请打开 Jira 工单查看完整内容。）";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String build(JiraTicketNotification notification) {
        String card = serialize(notification, notification.description());
        if (serializedSize(card) <= MAX_CARD_BYTES) return card;

        String description = notification.description();
        int low = 0;
        int high = description.codePointCount(0, description.length());
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            String candidate = prefix(description, middle) + TRUNCATION_SUFFIX;
            if (serializedSize(serialize(notification, candidate)) <= MAX_CARD_BYTES) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return serialize(notification, prefix(description, low) + TRUNCATION_SUFFIX);
    }

    private String serialize(JiraTicketNotification notification, String description) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("update_multi", true);
        config.put("width_mode", "default");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", plainText("New Cloud support ticket"));
        header.put("subtitle", plainText("Jira: " + notification.jiraKey()));
        header.put("template", "blue");
        header.put("icon", Map.of("tag", "standard_icon", "token", HEADER_ICON));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("direction", "vertical");
        body.put("padding", "12px 12px 20px 12px");
        body.put("vertical_spacing", "8px");
        body.put("elements", elements(notification, description));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", config);
        card.put("header", header);
        card.put("body", body);
        try {
            return MAPPER.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Feishu ticket card serialization failed", e);
        }
    }

    private static List<Map<String, Object>> elements(JiraTicketNotification notification, String description) {
        Map<String, Object> contentBlock = new LinkedHashMap<>();
        contentBlock.put("tag", "interactive_container");
        contentBlock.put("width", "fill");
        contentBlock.put("has_border", true);
        contentBlock.put("border_color", "blue-100");
        contentBlock.put("corner_radius", "8px");
        contentBlock.put("background_style", "blue-50");
        contentBlock.put("padding", "12px");
        contentBlock.put("vertical_spacing", "4px");
        contentBlock.put("elements", List.of(
            plainTextDiv("Ticket content", "heading-4"),
            plainTextDiv("Summary: " + notification.summary() + "\n\nDescription:\n" + description, "normal")));

        Map<String, Object> openJiraButton = new LinkedHashMap<>();
        openJiraButton.put("tag", "button");
        openJiraButton.put("text", plainText("Open Jira ticket"));
        openJiraButton.put("type", "primary_filled");
        openJiraButton.put("width", "fill");
        openJiraButton.put("behaviors", List.of(Map.of(
            "type", "open_url",
            "default_url", notification.jiraUrl())));

        return List.of(
            Map.of("tag", "div", "fields", List.of(
                field("Organization", String.join(", ", notification.organizationNames())),
                field("Email", notification.email()),
                field("Jira", notification.jiraKey()))),
            contentBlock,
            openJiraButton);
    }

    private static Map<String, Object> field(String label, String value) {
        return Map.of("is_short", true, "text", plainText(label + ": " + value));
    }

    private static Map<String, String> plainText(String content) {
        return Map.of("tag", "plain_text", "content", content);
    }

    private static Map<String, Object> plainTextDiv(String content, String textSize) {
        return Map.of("tag", "div", "text", Map.of(
            "tag", "plain_text",
            "content", content,
            "text_size", textSize));
    }

    private static String prefix(String value, int codePoints) {
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    private static int serializedSize(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
