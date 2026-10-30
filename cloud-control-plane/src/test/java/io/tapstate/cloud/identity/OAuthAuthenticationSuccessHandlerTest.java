package io.tapstate.cloud.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuthAuthenticationSuccessHandlerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void googleSignupUsesTheInvitationSavedBeforeTheProviderRedirect() throws Exception {
        IdentityAuthenticationService identities = mock(IdentityAuthenticationService.class);
        User user = User.builder().email("guest@example.com").displayName("Guest").status("active").build();
        when(identities.authenticate(eq("google"), eq("google-subject"), eq("guest@example.com"),
            eq(true), eq("Guest"), eq("signup"), isNull(), isNull(), eq("invitation-digest"), isNull(), eq(false)))
            .thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("TAPSTATE_OAUTH_FLOW", "signup");
        session.setAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN", "invitation-digest");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(identities).onAuthenticationSuccess(request, response, googleAuthentication());

        assertEquals("https://console.example/auth?oauth=success", response.getRedirectedUrl());
        assertEquals("guest@example.com", session.getAttribute(AuthController.SESSION_USER_EMAIL));
        assertNull(session.getAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN"));
    }

    @Test
    void usesOnlyGitHubsVerifiedPrimaryEmailAndRotatesTheCloudSession() throws Exception {
        IdentityAuthenticationService identities = mock(IdentityAuthenticationService.class);
        OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer provider = MockRestServiceServer.bindTo(restClientBuilder).build();
        provider.expect(requestTo("https://api.github.com/user/emails"))
            .andExpect(header("Authorization", "Bearer provider-access-token"))
            .andRespond(withSuccess("""
                [
                  {"email":"unverified@example.com","primary":false,"verified":false},
                  {"email":"verified@example.com","primary":true,"verified":true}
                ]
                """, MediaType.APPLICATION_JSON));

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getName()).thenReturn("github-user");
        when(principal.getAttributes()).thenReturn(Map.of(
            "id", 42L, "login", "octocat", "name", "Octo Cat"));
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
            principal, List.of(new SimpleGrantedAuthority("ROLE_USER")), "github");
        Instant now = Instant.now();
        ClientRegistration registration = ClientRegistration.withRegistrationId("github")
            .clientId("client-id").clientSecret("client-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://cloud.example/login/oauth2/code/github")
            .authorizationUri("https://github.com/login/oauth/authorize")
            .tokenUri("https://github.com/login/oauth/access_token")
            .userInfoUri("https://api.github.com/user").userNameAttributeName("id")
            .clientName("GitHub").build();
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, authentication.getName(),
            new OAuth2AccessToken(TokenType.BEARER, "provider-access-token", now, now.plusSeconds(600)));
        when(authorizedClients.loadAuthorizedClient("github", "github-user")).thenReturn(client);

        User user = User.builder().email("verified@example.com").displayName("Octo Cat").status("active").build();
        when(identities.authenticate(eq("github"), eq("42"), eq("verified@example.com"), eq(true),
            eq("Octo Cat"), eq("signup"), isNull(), isNull(), eq("invitation-digest"), isNull(), eq(false)))
            .thenReturn(user);

        OAuthAuthenticationSuccessHandler handler = new OAuthAuthenticationSuccessHandler(
            identities, authorizedClients, restClientBuilder,
            mock(UserRepository.class), mock(io.tapstate.cloud.config.LegalConfig.class));
        ReflectionTestUtils.setField(handler, "consoleUrl", "https://console.example");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("TAPSTATE_OAUTH_FLOW", "signup");
        session.setAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN", "invitation-digest");
        request.setSession(session);
        String originalSessionId = session.getId();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        provider.verify();
        verify(identities).authenticate("github", "42", "verified@example.com", true,
            "Octo Cat", "signup", null, null, "invitation-digest", null, false);
        assertNotEquals(originalSessionId, request.getSession().getId());
        assertEquals("verified@example.com", session.getAttribute(AuthController.SESSION_USER_EMAIL));
        assertEquals("https://console.example/auth?oauth=success", response.getRedirectedUrl());
    }

    @Test
    void rejectedOAuthSignupClearsTheProviderAuthentication() throws Exception {
        IdentityAuthenticationService identities = mock(IdentityAuthenticationService.class);
        when(identities.authenticate(eq("google"), eq("google-subject"), eq("guest@example.com"), eq(true),
            eq("Guest"), eq("signup"), isNull(), isNull(), eq("invitation-digest"), isNull(), eq(false)))
            .thenThrow(new BusinessException(AppErrorCode.INVITATION_INVALID, 404));
        OAuthAuthenticationSuccessHandler handler = handler(identities);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("TAPSTATE_OAUTH_FLOW", "signup");
        session.setAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN", "invitation-digest");
        request.setSession(session);
        SecurityContext providerContext = SecurityContextHolder.createEmptyContext();
        providerContext.setAuthentication(googleAuthentication());
        SecurityContextHolder.setContext(providerContext);
        session.setAttribute("SPRING_SECURITY_CONTEXT", providerContext);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, googleAuthentication());

        assertEquals("https://console.example/auth?oauth=auth.invitation-invalid&mode=register",
            response.getRedirectedUrl());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertNull(session.getAttribute(AuthController.SESSION_USER_EMAIL));
        assertNull(session.getAttribute("TAPSTATE_OAUTH_FLOW"));
    }

    @Test
    void rejectedOAuthLinkRestoresTheExistingCloudIdentity() throws Exception {
        IdentityAuthenticationService identities = mock(IdentityAuthenticationService.class);
        when(identities.authenticate(eq("google"), eq("google-subject"), eq("guest@example.com"), eq(true),
            eq("Guest"), eq("link"), eq("owner@example.com"), any(Instant.class), isNull(), isNull(), eq(false)))
            .thenThrow(new BusinessException(AppErrorCode.IDENTITY_LINK_CONFLICT, 409));
        OAuthAuthenticationSuccessHandler handler = handler(identities);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("TAPSTATE_OAUTH_FLOW", "link");
        session.setAttribute("TAPSTATE_IDENTITY_REAUTH_AT", Instant.now());
        session.setAttribute(AuthController.SESSION_USER_EMAIL, "owner@example.com");
        session.setAttribute(OAuthSessionSupport.PREVIOUS_USER_EMAIL, "owner@example.com");
        request.setSession(session);
        SecurityContext providerContext = SecurityContextHolder.createEmptyContext();
        providerContext.setAuthentication(googleAuthentication());
        SecurityContextHolder.setContext(providerContext);
        session.setAttribute("SPRING_SECURITY_CONTEXT", providerContext);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, googleAuthentication());

        assertEquals("https://console.example/auth?oauth=auth.identity-link-conflict", response.getRedirectedUrl());
        assertEquals("owner@example.com", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertEquals("owner@example.com", session.getAttribute(AuthController.SESSION_USER_EMAIL));
        assertNotNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertNull(session.getAttribute(OAuthSessionSupport.PREVIOUS_USER_EMAIL));
    }

    @Test
    void deferredTrialSignupRoutesNewAccountToOrganizationOnboarding() throws Exception {
        IdentityAuthenticationService identities = mock(IdentityAuthenticationService.class);
        User user = User.builder().email("guest@example.com").displayName("Guest").status("active").build();
        when(identities.hasLinkedIdentity("google", "google-subject")).thenReturn(false);
        when(identities.authenticate(eq("google"), eq("google-subject"), eq("guest@example.com"), eq(true),
            eq("Guest"), eq("signup"), eq("previous@example.com"), isNull(), eq("invitation-digest"),
            isNull(), eq(true)))
            .thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("TAPSTATE_OAUTH_FLOW", "signup");
        session.setAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN", "invitation-digest");
        session.setAttribute("TAPSTATE_OAUTH_DEFER_ORGANIZATION_CREATION", true);
        session.setAttribute("TAPSTATE_OAUTH_TRIAL_INVITATION", true);
        session.setAttribute(AuthController.SESSION_USER_EMAIL, "previous@example.com");
        session.setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE, "old-invite");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(identities).onAuthenticationSuccess(request, response, googleAuthentication());

        assertEquals("https://console.example/auth?oauth=success&onboarding=organization",
            response.getRedirectedUrl());
        assertEquals("guest@example.com", session.getAttribute(AuthController.SESSION_USER_EMAIL));
        assertNull(session.getAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE));
        verify(identities).authenticate("google", "google-subject", "guest@example.com", true,
            "Guest", "signup", "previous@example.com", null, "invitation-digest", null, true);
    }

    @Test
    void deferredTrialInvitationForLinkedIdentityStaysInSessionUntilOrganizationCreation() throws Exception {
        IdentityAuthenticationService identities = mock(IdentityAuthenticationService.class);
        User user = User.builder().email("guest@example.com").displayName("Guest").status("active").build();
        when(identities.hasLinkedIdentity("google", "google-subject")).thenReturn(true);
        when(identities.authenticate(eq("google"), eq("google-subject"), eq("guest@example.com"), eq(true),
            eq("Guest"), eq("signup"), isNull(), isNull(), eq("invitation-digest"), isNull(), eq(true)))
            .thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("TAPSTATE_OAUTH_FLOW", "signup");
        session.setAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN", "invitation-digest");
        session.setAttribute("TAPSTATE_OAUTH_DEFER_ORGANIZATION_CREATION", true);
        session.setAttribute("TAPSTATE_OAUTH_TRIAL_INVITATION", true);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(identities).onAuthenticationSuccess(request, response, googleAuthentication());

        assertEquals("https://console.example/auth?oauth=success&onboarding=organization",
            response.getRedirectedUrl());
        assertEquals("invitation-digest",
            session.getAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE));
    }

    private OAuthAuthenticationSuccessHandler handler(IdentityAuthenticationService identities) {
        OAuthAuthenticationSuccessHandler handler = new OAuthAuthenticationSuccessHandler(
            identities, mock(OAuth2AuthorizedClientService.class), RestClient.builder(),
            mock(UserRepository.class), mock(io.tapstate.cloud.config.LegalConfig.class));
        ReflectionTestUtils.setField(handler, "consoleUrl", "https://console.example");
        return handler;
    }

    private OAuth2AuthenticationToken googleAuthentication() {
        OidcUser principal = mock(OidcUser.class);
        when(principal.getSubject()).thenReturn("google-subject");
        when(principal.getEmail()).thenReturn("guest@example.com");
        when(principal.getFullName()).thenReturn("Guest");
        when(principal.getEmailVerified()).thenReturn(true);
        return new OAuth2AuthenticationToken(principal, List.of(new SimpleGrantedAuthority("ROLE_USER")), "google");
    }
}
