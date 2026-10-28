package io.tapstate.cloud.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.identity.User;
import io.tapstate.cloud.identity.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class JiraTicketService {

    private final JiraTicketClient jira;
    private final JiraCustomerProvisioner customerProvisioner;
    private final CloudTicketRepository tickets;
    private final UserRepository users;

    public TicketPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw business(AppErrorCode.TICKET_REQUEST_REJECTED, 400);
        }
        jira.requireConfiguration();
        User user = currentUser();
        Page<CloudTicket> result = tickets.findByOwnerIdOrderByCreatedAtDesc(user.getId(),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Ticket> items = result.getContent().stream().map(mapping -> readTicket(mapping, false)).toList();
        return new TicketPage(items, result.getNumber(), result.getSize(), result.getTotalElements(),
            result.getTotalPages(), result.hasNext());
    }

    public Ticket create(String summary, String description) {
        if (summary == null || summary.isBlank() || summary.length() > 255
                || description == null || description.isBlank() || description.length() > 10000) {
            throw business(AppErrorCode.TICKET_REQUEST_REJECTED, 400);
        }
        jira.requireConfiguration();
        User user = currentUser();
        if (!user.isEmailVerified()) throw business(AppErrorCode.TICKET_EMAIL_NOT_VERIFIED, 403);
        String requesterAccountId = resolveCustomerAccountId(user);

        JsonNode created = jira.createRequest(requesterAccountId, summary.trim(), description.trim());
        String jiraKey = created.path("issueKey").asText(null);
        if (jiraKey == null || jiraKey.isBlank()) {
            jiraKey = created.path("issue").path("key").asText(null);
        }
        if (jiraKey == null || jiraKey.isBlank()) {
            throw business(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503);
        }

        CloudTicket mapping = CloudTicket.builder()
            .ownerId(user.getId())
            .jiraKey(jiraKey)
            .summary(summary.trim())
            .createdAt(Instant.now())
            .build();
        try {
            mapping = tickets.save(mapping);
        } catch (RuntimeException e) {
            log.error("JSM request was created but Cloud mapping persistence failed; opId={} jiraKey={} exception={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), jiraKey, e.getClass().getSimpleName());
            throw business(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503);
        }

        return toTicket(mapping, created, List.of(), description.trim());
    }

    private String resolveCustomerAccountId(User user) {
        List<JiraTicketClient.Customer> customers = jira.findExactCustomers(user.getEmail());
        if (customers.size() == 1) return customers.getFirst().accountId();
        if (customers.size() > 1 || !customerProvisioner.isEnabled()) {
            throw business(AppErrorCode.TICKET_CUSTOMER_NOT_READY, 409);
        }

        String provisionedAccountId = customerProvisioner.createOrAddCustomer(user.getEmail(), user.getDisplayName());
        if (provisionedAccountId == null || provisionedAccountId.isBlank()) {
            throw business(AppErrorCode.TICKET_CUSTOMER_NOT_READY, 409);
        }
        return provisionedAccountId;
    }

    public Ticket get(String cloudTicketId) {
        CloudTicket mapping = findOwned(cloudTicketId);
        return readTicket(mapping, true);
    }

    public Ticket close(String cloudTicketId) {
        CloudTicket mapping = findOwned(cloudTicketId);
        List<JiraTicketClient.Transition> transitions = jira.getTransitions(mapping.getJiraKey());
        String expected = jira.getCloseTransitionName();
        JiraTicketClient.Transition close = transitions.stream()
            .filter(t -> t.name().equalsIgnoreCase(expected))
            .findFirst()
            .orElseThrow(() -> business(AppErrorCode.TICKET_CLOSE_UNAVAILABLE, 409));
        jira.transition(mapping.getJiraKey(), close.id());
        JsonNode current = jira.getRequest(mapping.getJiraKey());
        List<JiraTicketClient.Transition> remaining = jira.getTransitions(mapping.getJiraKey());
        return toTicket(mapping, current, remaining, "");
    }

    private Ticket readTicket(CloudTicket mapping, boolean includeTransitions) {
        try {
            JsonNode current = jira.getRequest(mapping.getJiraKey());
            List<JiraTicketClient.Transition> transitions = includeTransitions
                ? jira.getTransitions(mapping.getJiraKey()) : List.of();
            return toTicket(mapping, current, transitions, "");
        } catch (RuntimeException e) {
            if (includeTransitions) {
                throw e;
            }
            log.warn("JSM read failed for mapping jiraKey={}; degrading to cached summary; opId={}",
                mapping.getJiraKey(), io.tapstate.cloud.common.api.OpIdHolder.get(), e);
            return new Ticket(mapping.getId().toHexString(), mapping.getJiraKey(), mapping.getSummary(),
                "", "Unavailable", "indeterminate", mapping.getCreatedAt(), false);
        }
    }

    private Ticket toTicket(CloudTicket mapping, JsonNode request, List<JiraTicketClient.Transition> transitions,
                            String fallbackDescription) {
        String summary = text(fieldValue(request, "summary"), mapping.getSummary());
        String description = text(fieldValue(request, "description"), fallbackDescription);
        JsonNode currentStatus = request.has("currentStatus")
            ? request.path("currentStatus") : request.path("requestStatus");
        String status = text(currentStatus.path("status"), "Open");
        JsonNode categoryNode = currentStatus.path("statusCategory");
        String category = categoryNode.isObject()
            ? text(categoryNode.path("key"), text(categoryNode.path("name"), ""))
            : categoryNode.asText("");
        Instant createdAt = parseCreatedAt(request.path("createdDate"), mapping.getCreatedAt());
        boolean canClose = transitions.stream().anyMatch(t ->
            t.name().equalsIgnoreCase(jira.getCloseTransitionName()));
        return new Ticket(mapping.getId().toHexString(), mapping.getJiraKey(), summary, description,
            status, category, createdAt, canClose);
    }

    private CloudTicket findOwned(String ticketId) {
        if (ticketId == null || !ObjectId.isValid(ticketId)) {
            throw business(AppErrorCode.TICKET_NOT_FOUND, 404);
        }
        return tickets.findByIdAndOwnerId(new ObjectId(ticketId), currentUser().getId())
            .orElseThrow(() -> business(AppErrorCode.TICKET_NOT_FOUND, 404));
    }

    private User currentUser() {
        String email = CurrentUser.getEmail();
        if (email == null || email.isBlank()) throw business(AppErrorCode.UNAUTHORIZED, 401);
        return users.findByEmail(email.toLowerCase(Locale.ROOT))
            .orElseThrow(() -> business(AppErrorCode.UNAUTHORIZED, 401));
    }

    private static String text(JsonNode value, String fallback) {
        return value == null || value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    private static JsonNode fieldValue(JsonNode request, String fieldId) {
        JsonNode fields = request.path("requestFieldValues");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                if (fieldId.equals(field.path("fieldId").asText())) return field.path("value");
            }
            return null;
        }
        return fields.isObject() ? fields.path(fieldId) : null;
    }

    private static Instant parseCreatedAt(JsonNode date, Instant fallback) {
        long epochMillis = date.path("epochMillis").asLong(0);
        if (epochMillis > 0) return Instant.ofEpochMilli(epochMillis);
        String iso8601 = date.path("iso8601").asText(null);
        if (iso8601 != null) {
            try { return OffsetDateTime.parse(iso8601).toInstant(); }
            catch (RuntimeException ignored) { return fallback; }
        }
        return fallback;
    }

    private static BusinessException business(AppErrorCode code, int status) {
        return new BusinessException(code, status);
    }

    public record TicketPage(List<Ticket> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {}
    public record Ticket(String id, String jiraKey, String summary, String description, String status,
                         String statusCategory, Instant createdAt, boolean canClose) {}
}
