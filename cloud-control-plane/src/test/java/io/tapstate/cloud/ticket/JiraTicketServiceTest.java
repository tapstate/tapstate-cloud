package io.tapstate.cloud.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.identity.User;
import io.tapstate.cloud.identity.UserRepository;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class JiraTicketServiceTest {

    private static final String EMAIL = "unverified@example.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JiraTicketClient jira;
    private JiraCustomerProvisioner customerProvisioner;
    private CloudTicketRepository tickets;
    private UserRepository users;
    private JiraTicketService service;
    private User user;

    @BeforeEach
    void setUp() {
        jira = mock(JiraTicketClient.class);
        customerProvisioner = mock(JiraCustomerProvisioner.class);
        tickets = mock(CloudTicketRepository.class);
        users = mock(UserRepository.class);
        service = new JiraTicketService(jira, customerProvisioner, tickets, users);
        user = User.builder()
            .id(new ObjectId())
            .email(EMAIL)
            .displayName("Support User")
            .emailVerified(true)
            .status("active")
            .build();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(customerProvisioner.isEnabled()).thenReturn(true);
        when(tickets.save(any(CloudTicket.class))).thenAnswer(invocation -> {
            CloudTicket ticket = invocation.getArgument(0);
            ticket.setId(new ObjectId());
            return ticket;
        });
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(EMAIL, "not-used", List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsUnverifiedEmailBeforeLookingUpOrCreatingAJiraCustomer() {
        user.setEmailVerified(false);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.create("Help", "I need help with my Cloud account."));

        assertEquals(403, failure.getHttpStatus());
        assertEquals("ticket.email-not-verified", failure.getErrorCode().getCode());
        verify(jira, never()).findExactCustomers(anyString());
        verify(jira, never()).createRequest(anyString(), anyString(), anyString());
        verify(customerProvisioner, never()).isEnabled();
        verify(tickets, never()).save(any(CloudTicket.class));
    }

    @Test
    void reusesTheUniqueServiceDeskCustomerWithoutUsingAdminProvisioning() throws Exception {
        when(jira.findExactCustomers(EMAIL))
            .thenReturn(List.of(new JiraTicketClient.Customer(EMAIL, "existing-account")));
        when(jira.createRequest("existing-account", "Help", "I need help."))
            .thenReturn(createdRequest("TSSD-12"));

        JiraTicketService.Ticket ticket = service.create(" Help ", " I need help. ");

        assertEquals("TSSD-12", ticket.jiraKey());
        verify(customerProvisioner, never()).isEnabled();
        verify(customerProvisioner, never()).createOrAddCustomer(anyString(), anyString());
    }

    @Test
    void provisionsOneCustomerBeforeCreatingTheJiraRequest() throws Exception {
        when(jira.findExactCustomers(EMAIL)).thenReturn(List.of(),
            List.of(new JiraTicketClient.Customer(EMAIL, "new-account")));
        when(jira.createRequest("new-account", "Help", "I need help."))
            .thenReturn(createdRequest("TSSD-13"));

        JiraTicketService.Ticket ticket = service.create("Help", "I need help.");

        assertEquals("TSSD-13", ticket.jiraKey());
        InOrder order = inOrder(jira, customerProvisioner);
        order.verify(jira).findExactCustomers(EMAIL);
        order.verify(customerProvisioner).isEnabled();
        order.verify(customerProvisioner).createOrAddCustomer(EMAIL, "Support User");
        order.verify(jira).findExactCustomers(EMAIL);
        order.verify(jira).createRequest("new-account", "Help", "I need help.");
    }

    @Test
    void failsClosedIfCustomerProvisioningDoesNotProduceOneExactServiceDeskCustomer() {
        when(jira.findExactCustomers(EMAIL)).thenReturn(List.of(), List.of());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.create("Help", "I need help."));

        assertEquals(409, failure.getHttpStatus());
        verify(jira, never()).createRequest(anyString(), anyString(), anyString());
        verify(tickets, never()).save(any(CloudTicket.class));
    }

    @Test
    void doesNotCreateAJiraCustomerWhenCustomerProvisioningIsDisabled() {
        when(jira.findExactCustomers(EMAIL)).thenReturn(List.of());
        when(customerProvisioner.isEnabled()).thenReturn(false);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.create("Help", "I need help."));

        assertEquals(409, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_CUSTOMER_NOT_READY, failure.getErrorCode());
        verify(customerProvisioner, never()).createOrAddCustomer(anyString(), anyString());
        verify(jira, never()).createRequest(anyString(), anyString(), anyString());
    }

    @Test
    void refusesToReadAnotherUsersTicketBeforeCallingJira() {
        String ticketId = new ObjectId().toHexString();
        when(tickets.findByIdAndOwnerId(new ObjectId(ticketId), user.getId())).thenReturn(Optional.empty());

        BusinessException failure = assertThrows(BusinessException.class, () -> service.get(ticketId));

        assertEquals(404, failure.getHttpStatus());
        verify(jira, never()).getRequest(anyString());
    }

    @Test
    void listsOnlyCurrentOwnersMappingsAndReadsLiveJiraStatus() throws Exception {
        CloudTicket mapping = ownedTicket();
        when(tickets.findByOwnerIdOrderByCreatedAtDesc(eq(user.getId()), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(mapping)));
        when(jira.getRequest("TSSD-14")).thenReturn(MAPPER.readTree("""
            {
              "requestFieldValues": [
                {"fieldId": "summary", "value": "Live Jira summary"},
                {"fieldId": "description", "value": "Live Jira detail"}
              ],
              "currentStatus": {"status": "Waiting for support", "statusCategory": {"key": "indeterminate"}}
            }
            """));

        JiraTicketService.TicketPage page = service.list(0, 20);

        assertEquals(1, page.totalItems());
        assertEquals(1, page.items().size());
        assertEquals("Live Jira summary", page.items().getFirst().summary());
        assertEquals("Live Jira detail", page.items().getFirst().description());
        assertEquals("Waiting for support", page.items().getFirst().status());
        verify(tickets).findByOwnerIdOrderByCreatedAtDesc(eq(user.getId()), any(Pageable.class));
        verify(jira).getRequest("TSSD-14");
    }

    @Test
    void returnsCurrentOwnersTicketDetailAndOnlyAdvertisesAvailableCloseTransition() throws Exception {
        CloudTicket mapping = ownedTicket();
        when(tickets.findByIdAndOwnerId(mapping.getId(), user.getId())).thenReturn(Optional.of(mapping));
        when(jira.getRequest("TSSD-14")).thenReturn(MAPPER.readTree("""
            {
              "requestFieldValues": [
                {"fieldId": "summary", "value": "Live Jira summary"},
                {"fieldId": "description", "value": "Live Jira detail"}
              ],
              "currentStatus": {"status": "Open", "statusCategory": {"key": "new"}}
            }
            """));
        when(jira.getCloseTransitionName()).thenReturn("Close Request");
        when(jira.getTransitions("TSSD-14"))
            .thenReturn(List.of(new JiraTicketClient.Transition("41", "Close Request")));

        JiraTicketService.Ticket ticket = service.get(mapping.getId().toHexString());

        assertEquals("Live Jira summary", ticket.summary());
        assertEquals("Live Jira detail", ticket.description());
        assertEquals("Open", ticket.status());
        assertEquals("TSSD-14", ticket.jiraKey());
        assertTrue(ticket.canClose());
    }

    @Test
    void refusesToCloseAnotherUsersTicketBeforeReadingJiraTransitions() {
        String ticketId = new ObjectId().toHexString();
        when(tickets.findByIdAndOwnerId(new ObjectId(ticketId), user.getId())).thenReturn(Optional.empty());

        BusinessException failure = assertThrows(BusinessException.class, () -> service.close(ticketId));

        assertEquals(404, failure.getHttpStatus());
        verify(jira, never()).getTransitions(anyString());
        verify(jira, never()).transition(anyString(), anyString());
    }

    @Test
    void closesOnlyWithTheNamedJiraTransitionAndReturnsTheRereadStatus() throws Exception {
        CloudTicket mapping = ownedTicket();
        when(tickets.findByIdAndOwnerId(mapping.getId(), user.getId())).thenReturn(Optional.of(mapping));
        when(jira.getCloseTransitionName()).thenReturn("Close Request");
        when(jira.getTransitions("TSSD-14"))
            .thenReturn(List.of(new JiraTicketClient.Transition("41", "Close Request")), List.of());
        when(jira.getRequest("TSSD-14")).thenReturn(MAPPER.readTree(
            "{\"currentStatus\":{\"status\":\"Closed\",\"statusCategory\":{\"key\":\"done\"}}}"));

        JiraTicketService.Ticket closed = service.close(mapping.getId().toHexString());

        assertEquals("Closed", closed.status());
        assertEquals("done", closed.statusCategory());
        assertFalse(closed.canClose());
        verify(jira).transition("TSSD-14", "41");
        verify(jira).getRequest("TSSD-14");
    }

    @Test
    void doesNotWriteWhenTheConfiguredCloseTransitionIsUnavailable() {
        CloudTicket mapping = ownedTicket();
        when(tickets.findByIdAndOwnerId(mapping.getId(), user.getId())).thenReturn(Optional.of(mapping));
        when(jira.getCloseTransitionName()).thenReturn("Close Request");
        when(jira.getTransitions("TSSD-14")).thenReturn(List.of());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.close(mapping.getId().toHexString()));

        assertEquals(409, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_CLOSE_UNAVAILABLE, failure.getErrorCode());
        verify(jira, never()).transition(anyString(), anyString());
    }

    @Test
    void keepsTicketCreationUncertainAndDoesNotSaveAnOwnerMappingWhenJiraTimesOut() {
        when(jira.findExactCustomers(EMAIL))
            .thenReturn(List.of(new JiraTicketClient.Customer(EMAIL, "existing-account")));
        when(jira.createRequest("existing-account", "Help", "I need help."))
            .thenThrow(new BusinessException(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.create("Help", "I need help."));

        assertEquals(503, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, failure.getErrorCode());
        verify(tickets, never()).save(any(CloudTicket.class));
        verify(jira).createRequest("existing-account", "Help", "I need help.");
    }

    @Test
    void reportsUncertainResultWhenCloudCannotPersistTheJiraKey() throws Exception {
        when(jira.findExactCustomers(EMAIL))
            .thenReturn(List.of(new JiraTicketClient.Customer(EMAIL, "existing-account")));
        when(jira.createRequest("existing-account", "Help", "I need help."))
            .thenReturn(createdRequest("TSSD-15"));
        when(tickets.save(any(CloudTicket.class))).thenThrow(new IllegalStateException("mongo write failed"));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.create("Help", "I need help."));

        assertEquals(503, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, failure.getErrorCode());
        verify(jira).createRequest("existing-account", "Help", "I need help.");
    }

    private CloudTicket ownedTicket() {
        return CloudTicket.builder()
            .id(new ObjectId())
            .ownerId(user.getId())
            .jiraKey("TSSD-14")
            .summary("Existing ticket")
            .createdAt(java.time.Instant.parse("2026-10-26T00:00:00Z"))
            .build();
    }

    private static JsonNode createdRequest(String issueKey) throws Exception {
        return MAPPER.readTree("{\"issueKey\":\"" + issueKey + "\"}");
    }
}
