package io.tapstate.cloud.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OAuthAuthenticationFailureHandlerTest {

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportsCancellationOnlyForAValidatedProviderDenial() throws Exception {
        OAuthAuthenticationFailureHandler handler = new OAuthAuthenticationFailureHandler();
        ReflectionTestUtils.setField(handler, "consoleUrl", "https://console.example");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("error", "access_denied");
        MockHttpServletResponse cancelled = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, cancelled,
            new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        MockHttpServletResponse rejectedState = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, rejectedState,
            new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found")));

        assertEquals("https://console.example/auth?oauth=cancelled", cancelled.getRedirectedUrl());
        assertEquals("https://console.example/auth?oauth=provider-error", rejectedState.getRedirectedUrl());
    }

    @Test
    void providerFailureRestoresTheCloudIdentityCapturedBeforeOAuth() throws Exception {
        OAuthAuthenticationFailureHandler handler = new OAuthAuthenticationFailureHandler();
        ReflectionTestUtils.setField(handler, "consoleUrl", "https://console.example");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(OAuthSessionSupport.PREVIOUS_USER_EMAIL, "owner@example.com");
        session.setAttribute(AuthController.SESSION_USER_EMAIL, "owner@example.com");
        session.setAttribute("TAPSTATE_OAUTH_FLOW", "link");
        request.setSession(session);
        SecurityContext providerContext = SecurityContextHolder.createEmptyContext();
        providerContext.setAuthentication(new UsernamePasswordAuthenticationToken("github-subject", null));
        SecurityContextHolder.setContext(providerContext);
        session.setAttribute("SPRING_SECURITY_CONTEXT", providerContext);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response,
            new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found")));

        assertEquals("https://console.example/auth?oauth=provider-error", response.getRedirectedUrl());
        assertEquals("owner@example.com", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertEquals("owner@example.com", session.getAttribute(AuthController.SESSION_USER_EMAIL));
        assertNotNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertNull(session.getAttribute(OAuthSessionSupport.PREVIOUS_USER_EMAIL));
        assertNull(session.getAttribute("TAPSTATE_OAUTH_FLOW"));
    }
}
