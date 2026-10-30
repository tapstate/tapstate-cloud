package io.tapstate.cloud.org;

import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.identity.InvitationService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OrganizationV2ControllerTest {

    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final InvitationService invitationService = mock(InvitationService.class);
    private final OrganizationV2Controller controller = new OrganizationV2Controller(
        organizationService, organizationRepository, invitationService);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void retryAfterTrialInvitationConsumptionReusesTheGrantCreationKey() {
        authenticateAsOwner();
        Organization existing = Organization.builder().id(new ObjectId()).name("Second Workspace")
            .ownerEmail("owner@example.com").creationKey("trial:grant-key").build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_CREATION_KEY_SESSION_ATTRIBUTE,
            "grant-key");
        request.setSession(session);
        when(organizationService.findCreatedOrganization("trial:grant-key", "owner@example.com", "Second Workspace"))
            .thenReturn(Optional.of(existing));

        var response = controller.create(
            new OrganizationV2Controller.CreateOrganizationRequest("Second Workspace"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(organizationService, never()).createOrganizationWithoutSlug(anyString(), anyString(), anyBoolean(), anyString());
        verifyNoInteractions(invitationService);
    }

    @Test
    void consumesPendingTrialInvitationAndKeepsItsCreationKeyForRetries() {
        authenticateAsOwner();
        Organization created = Organization.builder().id(new ObjectId()).name("Second Workspace")
            .ownerEmail("owner@example.com").creationKey("trial:grant-key").build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE, "invite-hash");
        session.setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_CREATION_KEY_SESSION_ATTRIBUTE,
            "grant-key");
        request.setSession(session);
        when(organizationService.findCreatedOrganization("trial:grant-key", "owner@example.com", "Second Workspace"))
            .thenReturn(Optional.empty());
        when(organizationService.createOrganizationWithoutSlug(
            "Second Workspace", "owner@example.com", true, "trial:grant-key")).thenReturn(created);

        var response = controller.create(
            new OrganizationV2Controller.CreateOrganizationRequest("Second Workspace"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(invitationService).requireUsableTrialHash("invite-hash", "owner@example.com");
        verify(invitationService).consumeTrialHash("invite-hash", "owner@example.com");
        assertThat(session.getAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE)).isNull();
        assertThat(session.getAttribute(
            InvitationService.PENDING_TRIAL_ORGANIZATION_CREATION_KEY_SESSION_ATTRIBUTE)).isEqualTo("grant-key");
    }

    @Test
    void retainedTrialCreationKeyCannotCreateAnOrganizationWithoutItsPendingInvitation() {
        authenticateAsOwner();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_CREATION_KEY_SESSION_ATTRIBUTE,
            "grant-key");
        request.setSession(session);
        when(organizationService.findCreatedOrganization("trial:grant-key", "owner@example.com", "Second Workspace"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.create(
            new OrganizationV2Controller.CreateOrganizationRequest("Second Workspace"), request))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(AppErrorCode.ORG_LIMIT_REACHED));
        verify(organizationService, never()).createOrganizationWithoutSlug(anyString(), anyString(), anyBoolean(), anyString());
        verifyNoInteractions(invitationService);
    }

    private void authenticateAsOwner() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()));
    }
}
