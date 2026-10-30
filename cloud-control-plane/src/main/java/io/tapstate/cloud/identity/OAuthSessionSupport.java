package io.tapstate.cloud.identity;

import jakarta.servlet.http.HttpSession;
import io.tapstate.cloud.common.CurrentUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

final class OAuthSessionSupport {

    static final String PREVIOUS_USER_EMAIL = "TAPSTATE_OAUTH_PREVIOUS_USER_EMAIL";

    private OAuthSessionSupport() {}

    static void snapshotCurrentUser(HttpSession session) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            session.removeAttribute(PREVIOUS_USER_EMAIL);
        } else {
            session.setAttribute(PREVIOUS_USER_EMAIL, email);
        }
    }

    static void clearFlow(HttpSession session) {
        if (session == null) return;
        session.removeAttribute("TAPSTATE_OAUTH_FLOW");
        session.removeAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN");
        session.removeAttribute("TAPSTATE_OAUTH_ORGANIZATION_NAME");
        session.removeAttribute("TAPSTATE_OAUTH_DEFER_ORGANIZATION_CREATION");
        session.removeAttribute("TAPSTATE_OAUTH_TRIAL_INVITATION");
        session.removeAttribute("TAPSTATE_IDENTITY_REAUTH_AT");
        session.removeAttribute(PREVIOUS_USER_EMAIL);
    }

    static void reject(HttpSession session) {
        SecurityContextHolder.clearContext();
        if (session == null) return;

        Object previousEmail = session.getAttribute(PREVIOUS_USER_EMAIL);
        clearFlow(session);
        if (previousEmail instanceof String email && email.contains("@")) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            session.setAttribute("SPRING_SECURITY_CONTEXT", context);
            session.setAttribute(AuthController.SESSION_USER_EMAIL, email);
            return;
        }

        session.removeAttribute("SPRING_SECURITY_CONTEXT");
        session.removeAttribute(AuthController.SESSION_USER_EMAIL);
        session.removeAttribute("TAPSTATE_AUTHENTICATED_AT");
    }
}
