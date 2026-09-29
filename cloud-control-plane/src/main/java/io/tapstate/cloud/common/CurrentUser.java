package io.tapstate.cloud.common;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

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
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof String s) return s;
        if (principal instanceof UserDetails ud) return ud.getUsername();
        return null;
    }
}
