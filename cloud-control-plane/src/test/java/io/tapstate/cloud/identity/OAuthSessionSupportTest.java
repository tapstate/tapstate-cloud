package io.tapstate.cloud.identity;

import io.tapstate.cloud.common.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

class OAuthSessionSupportTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsOAuthWithoutRestoringAnAnonymousPrincipalAsAnAccount() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("anonymousUser", null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);

        assertNull(CurrentUser.getEmail());
        OAuthSessionSupport.snapshotCurrentUser(session);
        assertNull(session.getAttribute(OAuthSessionSupport.PREVIOUS_USER_EMAIL));

        // Also clear sessions poisoned by an earlier OAuth attempt.
        session.setAttribute(OAuthSessionSupport.PREVIOUS_USER_EMAIL, "anonymousUser");
        OAuthSessionSupport.reject(session);
        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertNull(session.getAttribute(AuthController.SESSION_USER_EMAIL));
        assertNull(CurrentUser.getEmail());
    }
}
