package io.tapstate.cloud.common;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

/**
 * Extract the authenticated user's email from SecurityContext.
 * <p>
 * Works with both String principal (set by our custom /v1/api/auth/login endpoint)
 * and UserDetails principal (if Spring Security form-login is used in future).
 * <p>
 * Returns {@code null} if no authenticated user is present (caller handles 401).
 */
public final class CurrentUser {

    private CurrentUser() {}

    /**
     * @return the authenticated user's email, or null if not authenticated
     */
    public static String getEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof String s) return emailOrNull(s);
        if (principal instanceof UserDetails ud) return emailOrNull(ud.getUsername());
        return null;
    }

    private static String emailOrNull(String value) {
        return value != null && value.contains("@") ? value : null;
    }
}
