package io.tapstate.cloud.ticket;

import java.util.List;

/** The user and request details needed to notify the support group after ticket creation. */
public record JiraTicketNotification(
        List<String> organizationNames,
        String email,
        String jiraKey,
        String summary,
        String description,
        String jiraUrl) {

    public JiraTicketNotification {
        organizationNames = List.copyOf(organizationNames);
    }
}
