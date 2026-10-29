package io.tapstate.cloud.ticket;

import io.tapstate.cloud.tawk.FeishuBotClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Builds and sends Jira ticket notifications through the configured Feishu bot. */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeishuJiraTicketNotificationSender implements JiraTicketNotificationSender {

    private final FeishuBotClient feishuBotClient;
    private final JiraTicketFeishuCardBuilder cardBuilder;

    @Override
    public void send(JiraTicketNotification notification) {
        try {
            FeishuBotClient.DeliveryResult result = feishuBotClient.sendInteractive(
                notification.jiraKey(), cardBuilder.build(notification));
            if (result == null || !result.delivered()) {
                String category = result == null ? "empty_delivery_result" : safeCategory(result.failureCategory());
                log.warn("Feishu ticket notification failed: jiraKey={} category={}",
                    notification.jiraKey(), category);
            }
        } catch (RuntimeException e) {
            log.warn("Feishu ticket notification failed: jiraKey={} category={}",
                notification.jiraKey(), e.getClass().getSimpleName());
        }
    }

    private static String safeCategory(String category) {
        return category != null && category.matches("[a-z0-9_]+") ? category : "unknown";
    }
}
