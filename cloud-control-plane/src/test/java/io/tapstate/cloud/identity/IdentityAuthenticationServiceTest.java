package io.tapstate.cloud.identity;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.org.OrganizationService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityAuthenticationServiceTest {

    @Mock private LinkedIdentityRepository identities;
    @Mock private UserRepository users;
    @Mock private MemberRepository members;
    @Mock private InvitationService invitations;

    @Test
    void linkedSubjectLogsInWithoutInvitationAndDoesNotRelinkByEmail() {
        User user = User.builder().email("account@example.com").displayName("Account").status("active").build();
        when(identities.findByProviderAndSubject("google", "provider-subject"))
            .thenReturn(Optional.of(LinkedIdentity.builder().provider("google").subject("provider-subject")
                .userEmail("account@example.com").build()));
        when(users.findByEmail("account@example.com")).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);

        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(true), new BCryptPasswordEncoder(), mock(OrganizationService.class));
        User result = service.authenticate("google", "provider-subject", "different@example.com", true,
            "Provider Name", "login", null, null, null);

        assertSame(user, result);
        assertNotNull(user.getLastLoginAt());
        verifyNoInteractions(invitations);
        verify(identities, never()).save(any());
    }

    @Test
    void sameEmailWithoutLinkedSubjectRequiresExplicitAccountLink() {
        when(identities.findByProviderAndSubject("github", "1234")).thenReturn(Optional.empty());
        when(users.existsByEmail("account@example.com")).thenReturn(true);
        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(true), new BCryptPasswordEncoder(), mock(OrganizationService.class));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.authenticate(
            "github", "1234", "Account@Example.com", true, "Account", "login", null, null, null));

        assertEquals(AppErrorCode.SSO_ACCOUNT_LINK_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(invitations);
        verify(identities, never()).save(any());
    }

    @Test
    void providerWithUnverifiedEmailCannotCreateAnAccount() {
        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(true), new BCryptPasswordEncoder(), mock(OrganizationService.class));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.authenticate(
            "google", "subject", "guest@example.com", false, "Guest", "signup", null, null, "invite-hash"));

        assertEquals(AppErrorCode.SSO_EMAIL_UNVERIFIED, exception.getErrorCode());
        verifyNoInteractions(invitations);
        verify(users, never()).save(any());
    }

    @Test
    void identityLinkRequiresRecentAuthenticationAndPersistsToTheCurrentAccount() {
        User currentUser = User.builder().email("current@example.com").status("active").build();
        when(identities.findByProviderAndSubject("google", "provider-subject")).thenReturn(Optional.empty());
        when(users.findByEmail("current@example.com")).thenReturn(Optional.of(currentUser));
        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(true), new BCryptPasswordEncoder(), mock(OrganizationService.class));

        User result = service.authenticate("google", "provider-subject", "verified@provider.example", true,
            "Provider User", "link", "current@example.com", Instant.now(), null);

        assertSame(currentUser, result);
        verify(identities).save(argThat(identity -> "google".equals(identity.getProvider())
            && "provider-subject".equals(identity.getSubject())
            && "current@example.com".equals(identity.getUserEmail())
            && "verified@provider.example".equals(identity.getProviderEmail())));
        verifyNoInteractions(invitations, members);
    }

    @Test
    void identityAlreadyLinkedToAnotherAccountCannotBeClaimed() {
        when(identities.findByProviderAndSubject("github", "provider-subject")).thenReturn(Optional.of(
            LinkedIdentity.builder().provider("github").subject("provider-subject")
                .userEmail("other@example.com").build()));
        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(true), new BCryptPasswordEncoder(), mock(OrganizationService.class));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.authenticate(
            "github", "provider-subject", "current@example.com", true, "Provider User", "link",
            "current@example.com", Instant.now(), null));

        assertEquals(AppErrorCode.IDENTITY_LINK_CONFLICT, exception.getErrorCode());
        verify(identities, never()).save(any());
        verifyNoInteractions(invitations, members, users);
    }

    @Test
    void identityLinkRejectsAStaleReauthentication() {
        when(identities.findByProviderAndSubject("google", "provider-subject")).thenReturn(Optional.empty());
        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(true), new BCryptPasswordEncoder(), mock(OrganizationService.class));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.authenticate(
            "google", "provider-subject", "current@example.com", true, "Provider User", "link",
            "current@example.com", Instant.now().minusSeconds(301), null));

        assertEquals(AppErrorCode.REAUTHENTICATION_REQUIRED, exception.getErrorCode());
        verify(users, never()).findByEmail(any());
        verify(identities, never()).save(any());
    }

    @Test
    void newProviderAccountCanCreateAnOrganizationWhenTrialCodeGateIsDisabled() {
        OrganizationService organizations = mock(OrganizationService.class);
        User user = User.builder().email("new@example.com").status("active").build();
        when(identities.findByProviderAndSubject("google", "new-subject")).thenReturn(Optional.empty());
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenReturn(user);

        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(false), new BCryptPasswordEncoder(), organizations);
        User result = service.authenticate("google", "new-subject", "new@example.com", true, "New User",
            "signup", null, null, null, "New Workspace");

        assertSame(user, result);
        verify(organizations).createTrialOrganization("New Workspace", "new@example.com");
        verify(invitations, never()).requireUsableHash(any(), any());
        verify(invitations, never()).consume(any(), any());
        verify(identities).save(argThat(identity -> "google".equals(identity.getProvider())
            && "new-subject".equals(identity.getSubject())
            && "new@example.com".equals(identity.getUserEmail())));
    }

    @Test
    void trialProviderSignupCanAuthenticateBeforeCreatingTheOrganization() {
        OrganizationService organizations = mock(OrganizationService.class);
        OrganizationInvitation invitation = OrganizationInvitation.builder()
            .id(new ObjectId()).invitedEmail("new@example.com").build();
        User user = User.builder().email("new@example.com").status("active").build();
        when(identities.findByProviderAndSubject("google", "new-subject")).thenReturn(Optional.empty());
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(invitations.requireUsableHash("invite-hash", "new@example.com")).thenReturn(invitation);
        when(invitations.isTrial(invitation)).thenReturn(true);
        when(users.save(any(User.class))).thenReturn(user);

        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(true), new BCryptPasswordEncoder(), organizations);
        User result = service.authenticate("google", "new-subject", "new@example.com", true, "New User",
            "signup", null, null, "invite-hash", null, true);

        assertSame(user, result);
        verify(invitations).consume(invitation, "new@example.com");
        verify(invitations, never()).requireOrganizationName(any());
        verify(organizations, never()).createTrialOrganization(any(), any());
        verify(members, never()).save(any());
    }

    @Test
    void loginCannotBecomeOpenRegistrationWhenTrialCodeGateIsDisabled() {
        when(identities.findByProviderAndSubject("google", "new-subject")).thenReturn(Optional.empty());
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        IdentityAuthenticationService service = new IdentityAuthenticationService(identities, users, members,
            invitations, new TrialCodePolicy(false), new BCryptPasswordEncoder(), mock(OrganizationService.class));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.authenticate(
            "google", "new-subject", "new@example.com", true, "New User", "login", null, null, null));

        assertEquals(AppErrorCode.INVITATION_REQUIRED, exception.getErrorCode());
        verify(users, never()).save(any());
        verify(identities, never()).save(any());
    }
}
