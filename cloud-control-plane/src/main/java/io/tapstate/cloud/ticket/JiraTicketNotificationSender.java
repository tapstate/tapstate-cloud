package io.tapstate.cloud.ticket;

/** Sends a notification for a Jira ticket that Cloud has already persisted. */
public interface JiraTicketNotificationSender {

    void send(JiraTicketNotification notification);
}
