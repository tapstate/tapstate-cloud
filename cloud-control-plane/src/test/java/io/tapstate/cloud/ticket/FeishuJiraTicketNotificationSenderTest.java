package io.tapstate.cloud.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.tawk.FeishuBotClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@ExtendWith(OutputCaptureExtension.class)
class FeishuJiraTicketNotificationSenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registersASenderThatBuildsAndSendsTheCardAsAnInteractiveMessage() throws Exception {
        FeishuBotClient client = mock(FeishuBotClient.class);
        when(client.sendInteractive(eq("TSSD-42"), anyString()))
            .thenReturn(new FeishuBotClient.DeliveryResult(true, null));
        JiraTicketNotificationSender sender = sender(client);

        sender.send(notification("Literal **bold** <at id=all>"));

        org.mockito.ArgumentCaptor<String> cardJson = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).sendInteractive(eq("TSSD-42"), cardJson.capture());
        JsonNode card = MAPPER.readTree(cardJson.getValue());
        assertEquals("2.0", card.path("schema").asText());
        assertEquals("Organization: Alpha Workspace, Zulu Workspace",
            card.path("body").path("elements").get(0).path("fields").get(0)
                .path("text").path("content").asText());
        assertEquals("Email: support@example.com",
            card.path("body").path("elements").get(0).path("fields").get(1)
                .path("text").path("content").asText());
        JsonNode contentBlock = card.path("body").path("elements").get(1);
        assertEquals("column_set", contentBlock.path("tag").asText());
        assertEquals("plain_text", contentBlock.path("columns").get(0)
            .path("elements").get(1).path("text").path("tag").asText());
        assertEquals("https://tapdata.atlassian.net/browse/TSSD-42",
            card.path("body").path("elements").get(2).path("behaviors").get(0)
                .path("default_url").asText());
    }

    @Test
    void logsDeliveryFailureWithoutEmailOrTicketBody(CapturedOutput output) {
        FeishuBotClient client = mock(FeishuBotClient.class);
        when(client.sendInteractive(eq("TSSD-42"), anyString()))
            .thenReturn(new FeishuBotClient.DeliveryResult(false, "network_error"));

        sender(client).send(notification("private description"));

        assertTrue(output.getOut().contains("TSSD-42"));
        assertTrue(output.getOut().contains("network_error"));
        assertFalse(output.getOut().contains("support@example.com"));
        assertFalse(output.getOut().contains("private description"));
    }

    @Test
    void doesNotSendCardWhenRequiredFieldsExceedTheSizeLimit(CapturedOutput output) {
        FeishuBotClient client = mock(FeishuBotClient.class);
        JiraTicketNotification notification = new JiraTicketNotification(
            java.util.Collections.nCopies(90, "工".repeat(100)),
            "support@example.com",
            "TSSD-44",
            "Cannot connect",
            "private description",
            "https://tapdata.atlassian.net/browse/TSSD-44");

        sender(client).send(notification);

        verify(client, never()).sendInteractive(anyString(), anyString());
        assertTrue(output.getOut().contains("TSSD-44"));
        assertFalse(output.getOut().contains("support@example.com"));
        assertFalse(output.getOut().contains("private description"));
    }

    private static JiraTicketNotificationSender sender(FeishuBotClient client) {
        AtomicReference<JiraTicketNotificationSender> result = new AtomicReference<>();
        new ApplicationContextRunner()
            .withUserConfiguration(SenderConfiguration.class)
            .withBean(FeishuBotClient.class, () -> client)
            .withBean(JiraTicketFeishuCardBuilder.class, JiraTicketFeishuCardBuilder::new)
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1, context.getBeanNamesForType(JiraTicketNotificationSender.class).length);
                result.set(context.getBean(JiraTicketNotificationSender.class));
            });
        assertNotNull(result.get());
        return result.get();
    }

    private static JiraTicketNotification notification(String description) {
        return new JiraTicketNotification(
            List.of("Alpha Workspace", "Zulu Workspace"),
            "support@example.com",
            "TSSD-42",
            "Cannot connect",
            description,
            "https://tapdata.atlassian.net/browse/TSSD-42");
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
        basePackageClasses = JiraTicketNotificationSender.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = JiraTicketNotificationSender.class))
    static class SenderConfiguration {}
}
