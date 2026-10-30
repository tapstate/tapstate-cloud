package io.tapstate.cloud.org;

import io.tapstate.cloud.identity.InvitationService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    void createsAnOrganizationWithoutASlugForAnAuthenticatedOwner() {
        authenticateAsOwner();
        Organization created = Organization.builder()
            .id(new ObjectId()).name("FLY").ownerEmail("owner@example.com").build();
        when(organizationService.createOrganizationWithoutSlug("FLY", "owner@example.com", false))
            .thenReturn(created);

        var response = controller.create(
            new OrganizationV2Controller.CreateOrganizationRequest("FLY"), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(organizationService).createOrganizationWithoutSlug("FLY", "owner@example.com", false);
        verifyNoInteractions(invitationService);
    }

    @Test
    void validatesAndConsumesThePendingTrialInvitationWhenCreatingAnOrganization() {
        authenticateAsOwner();
        Organization created = Organization.builder()
            .id(new ObjectId()).name("Second Workspace").ownerEmail("owner@example.com").build();
        when(organizationService.createOrganizationWithoutSlug(
            "Second Workspace", "owner@example.com", true)).thenReturn(created);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE, "invite-hash");
        request.setSession(session);

        var response = controller.create(
            new OrganizationV2Controller.CreateOrganizationRequest("Second Workspace"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(invitationService).requireUsableTrialHash("invite-hash", "owner@example.com");
        verify(organizationService).createOrganizationWithoutSlug(
            "Second Workspace", "owner@example.com", true);
        verify(invitationService).consumeTrialHash("invite-hash", "owner@example.com");
        assertThat(session.getAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE)).isNull();
    }

    private void authenticateAsOwner() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()));
    }
}
