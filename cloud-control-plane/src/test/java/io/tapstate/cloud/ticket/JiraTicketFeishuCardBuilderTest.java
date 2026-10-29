package io.tapstate.cloud.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class JiraTicketFeishuCardBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JiraTicketFeishuCardBuilder builder = new JiraTicketFeishuCardBuilder();

    @Test
    void buildsCardWithTicketDetailsAsPlainTextAndAnAgentLink() throws Exception {
        String description = "Literal **bold** <at id=all> [link](https://example.com)";
        String jiraUrl = "https://tapdata.atlassian.net/browse/TSSD-42";

        JsonNode card = MAPPER.readTree(builder.build(notification(description, jiraUrl)));

        assertEquals("2.0", card.path("schema").asText());
        assertEquals("New Cloud support ticket", card.path("header").path("title").path("content").asText());
        JsonNode fields = card.path("body").path("elements").get(0).path("fields");
        assertEquals("Organization: Alpha Workspace, Zulu Workspace", fields.get(0).path("text").path("content").asText());
        assertEquals("Email: support@example.com", fields.get(1).path("text").path("content").asText());
        assertEquals("Jira: TSSD-42", fields.get(2).path("text").path("content").asText());
        JsonNode content = card.path("body").path("elements").get(1)
            .path("elements").get(1);
        assertEquals("plain_text", content.path("text").path("tag").asText());
        assertEquals("Summary: Cannot connect\n\nDescription:\n" + description,
            content.path("text").path("content").asText());
        assertEquals(jiraUrl, card.path("body").path("elements").get(2)
            .path("behaviors").get(0).path("default_url").asText());
    }

    @Test
    void truncatesLongMultibyteDescriptionWithoutDroppingTheJiraLink() throws Exception {
        String jiraUrl = "https://tapdata.atlassian.net/browse/TSSD-43";
        String longDescription = "工🙂".repeat(6_000);

        String serialized = builder.build(notification(longDescription, jiraUrl));
        JsonNode card = MAPPER.readTree(serialized);
        JsonNode elements = card.path("body").path("elements");
        assertEquals(3, elements.size());
        JsonNode content = elements.get(1)
            .path("elements").get(1);

        assertTrue(serialized.getBytes(StandardCharsets.UTF_8).length <= 24 * 1024);
        assertTrue(content.path("text").path("content").asText().contains("内容已截断"));
        assertEquals(jiraUrl, card.path("body").path("elements").get(2)
            .path("behaviors").get(0).path("default_url").asText());
    }

    private static JiraTicketNotification notification(String description, String jiraUrl) {
        return new JiraTicketNotification(
            List.of("Alpha Workspace", "Zulu Workspace"),
            "support@example.com",
            "TSSD-42",
            "Cannot connect",
            description,
            jiraUrl);
    }
}
