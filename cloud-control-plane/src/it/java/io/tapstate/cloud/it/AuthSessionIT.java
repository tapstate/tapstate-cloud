package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.AuthApi;
import io.tapstate.cloud.sdk.model.LoginRequest;
import io.tapstate.cloud.sdk.model.LoginResult;
import io.tapstate.cloud.sdk.model.RegisterRequest;
import io.tapstate.cloud.sdk.model.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cloud Console auth flow — email/password session-based authentication.
 * <p>
 * Covers (Week 2 ADR-0122 D1 / cloud-week2-entry-and-onboarding T3):
 * <ul>
 *   <li>Register → 201 (ApiResponseUserResponse envelope, SDK unwraps via .getData())</li>
 *   <li>Duplicate email register → 409</li>
 *   <li>Login → session cookie set → /me returns user (ApiResponseLoginResult)</li>
 *   <li>Logout → session invalidated → /me returns 401</li>
 *   <li>Bad credential login → 401</li>
 *   <li>Missing session → /me returns 401</li>
 *   <li>Password too short → 400 validation error</li>
 * </ul>
 */
@DisplayName("AuthSessionIT — Register / Login / Session / Logout")
class AuthSessionIT extends BaseIT {

    private static final String EMAIL = "it-user@tapstate.io";
    private static final String PASSWORD = "StrongPass123!";

    @Test
    @DisplayName("T1 — Register new user → 201, ApiResponse envelope unwrapped via .getData()")
    void register_returns201() throws ApiException {
        UserResponse resp = authApi.register(
            new RegisterRequest().email(EMAIL).password(PASSWORD).displayName("IT User")).getData();
        captureSessionCookie();

        assertEquals("ACTIVE", resp.getStatus(), "User status should be ACTIVE");
        assertNotNull(resp.getId(), "User id should be present");
        assertEquals(EMAIL, resp.getEmail(), "User email should match");
    }

    @Test
    @DisplayName("T2 — Duplicate email → 409")
    void register_duplicateEmail_returns409() throws ApiException {
        register(EMAIL, PASSWORD);

        ApiException ex = assertThrows(ApiException.class,
            () -> authApi.register(new RegisterRequest().email(EMAIL).password(PASSWORD).displayName("Dup")));
        assertEquals(409, ex.getCode(), "Duplicate email should return 409");
    }

    @Test
    @DisplayName("T3 — Login + session cookie → /me returns authenticated user via ApiResponseLoginResult")
    void login_thenMe_authenticated() throws ApiException {
        signupAndLogin(EMAIL, PASSWORD);
        assertNotNull(sessionCookieValue, "Session cookie should be captured after login");

        LoginResult meResp = authApi.me().getData();
        assertNotNull(meResp, "me() should return data");
        assertEquals(EMAIL, meResp.getEmail(), "Authenticated user should match");
    }

    @Test
    @DisplayName("T4 — Logout → session invalidated → /me returns 401")
    void logout_invalidatesSession() throws ApiException {
        signupAndLogin(EMAIL, PASSWORD);
        assertNotNull(authApi.me().getData(), "me() should return data before logout");
        authApi.logout();

        ApiException ex = assertThrows(ApiException.class, () -> authApi.me());
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("T5 — Bad password login → 401")
    void login_badPassword_returns401() throws ApiException {
        register(EMAIL, PASSWORD);

        ApiException ex = assertThrows(ApiException.class,
            () -> authApi.login(new LoginRequest().email(EMAIL).password("wrong-password")));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("T6 — No session → /me returns 401")
    void me_withoutSession_returns401() {
        AuthApi anonymousApi = createAnonymousAuthApi();

        ApiException ex = assertThrows(ApiException.class, anonymousApi::me);
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("T7 — Password too short → 400 validation error")
    void register_shortPassword_returns400() {
        ApiException ex = assertThrows(ApiException.class,
            () -> authApi.register(new RegisterRequest().email("short@tapstate.io").password("123").displayName("Short")));
        assertEquals(400, ex.getCode());
    }
}
