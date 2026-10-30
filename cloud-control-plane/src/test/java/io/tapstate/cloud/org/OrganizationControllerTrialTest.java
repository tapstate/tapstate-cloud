package io.tapstate.cloud.org;

import io.tapstate.cloud.identity.InvitationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrganizationControllerTrialTest {
    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void deferredTrialIsValidatedConsumedAndAllowsExistingMembership() {
        var service = mock(OrganizationService.class);
        var invitations = mock(InvitationService.class);
        var controller = new OrganizationController(service, mock(OrganizationRepository.class), invitations);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE, "trial-hash");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("owner@example.com", null, java.util.List.of()));
        when(service.createTrialOrganization("FLY", "owner@example.com")).thenReturn(Organization.builder().name("FLY").build());

        controller.create(new OrganizationController.CreateOrganizationRequest("FLY"), request);

        var order = inOrder(invitations, service);
        order.verify(invitations).requireUsableTrialHash("trial-hash", "owner@example.com");
        order.verify(service).createTrialOrganization("FLY", "owner@example.com");
        order.verify(invitations).consumeTrialHash("trial-hash", "owner@example.com");
        assertThat(request.getSession().getAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE)).isNull();
    }
    @Test
    void invalidDeferredTrialCannotCreateOrganizationOrConsumeGrant() {
        var service = mock(OrganizationService.class);
        var invitations = mock(InvitationService.class);
        var controller = new OrganizationController(service, mock(OrganizationRepository.class), invitations);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE, "expired");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("owner@example.com", null, java.util.List.of()));
        when(invitations.requireUsableTrialHash("expired", "owner@example.com"))
            .thenThrow(new IllegalArgumentException("expired"));

        assertThatThrownBy(() -> controller.create(new OrganizationController.CreateOrganizationRequest("FLY"), request))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
        verify(invitations, never()).consumeTrialHash(anyString(), anyString());
        assertThat(request.getSession().getAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE))
            .isEqualTo("expired");
    }

}
