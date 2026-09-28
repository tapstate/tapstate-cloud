package io.tapstate.cloud.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Authentication endpoints — Cloud Console user registration, login, session management.
 * <p>
 * Session model (ADR-0122 D1):
 * - Uses Spring Session (MongoDB-backed) — cookie TAPSTATE_CLOUD_SESSION handled by Spring Session automatically
 * - Idle expiry: 7 days (configured in application.yml spring.session.timeout)
 */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Auth", description = "Cloud Console authentication — register, login, session management")
public class AuthController extends BaseController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;

    // Session attribute key — also used by Security context recovery
    static final String SESSION_USER_EMAIL = "TAPSTATE_USER_EMAIL";

    // ========== Register ==========

    @PostMapping("/register")
    @Operation(
        summary = "Register new user",
        description = "Create a new Cloud Console account with email + password. " +
                      "In Week 2 preview, email is auto-verified (magic link deferred)."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User created",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input or email already registered")
    })
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        User user = authService.register(
            request.email(),
            request.password(),
            request.displayName()
        );

        log.info("Registration success: {}", user.getEmail());
        return okCreated(UserResponse.from(user));
    }

    // ========== Login ==========

    @PostMapping("/login")
    @Operation(
        summary = "Login and create session",
        description = "Authenticate with email + password. On success, session cookie is set " +
                      "by Spring Session automatically. Session expires after 7 days of inactivity."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful, session cookie set"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<ApiResponse<LoginResult>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );
            // Re-wrap with String principal so CurrentUser.getEmail() returns the email string
            UsernamePasswordAuthenticationToken finalAuth = new UsernamePasswordAuthenticationToken(
                request.email(), null, authentication.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(finalAuth);

            // Load full User record for displayName + update lastLoginAt
            User user = userRepository.findByEmail(request.email().toLowerCase())
                .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, request.email()));
            user.setLastLoginAt(Instant.now());
            userRepository.save(user);

            // Persist SecurityContext to session so subsequent requests restore it
            // (HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT")
            SecurityContext ctx = SecurityContextHolder.getContext();
            httpRequest.getSession(true).setAttribute("SPRING_SECURITY_CONTEXT", ctx);
            httpRequest.getSession().setAttribute(SESSION_USER_EMAIL, user.getEmail());

            log.info("Login success: {}", user.getEmail());
            return ok(new LoginResult(user.getEmail(), user.getDisplayName()));
        } catch (BadCredentialsException e) {
            return fail(AppErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
        } catch (BusinessException e) {
            // BusinessException carries its own error code + httpStatus
            return fail(e.getErrorCode(), HttpStatus.valueOf(e.getHttpStatus()), e.getParams());
        } catch (Exception e) {
            log.warn("Login failed for {}: {}", request.email(), e.getMessage());
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
    }

    // ========== Logout ==========

    @PostMapping("/logout")
    @Operation(
        summary = "Logout and invalidate session",
        description = "Invalidate the current user session. Spring Session cookie is cleared automatically."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Session invalidated")
    })
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ok();
    }

    // ========== Whoami ==========

    @GetMapping("/me")
    @Operation(
        summary = "Get current user info",
        description = "Return the authenticated user's profile. Session cookie required."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User profile",
            content = @Content(schema = @Schema(implementation = LoginResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<ApiResponse<LoginResult>> me(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        String email = (String) auth.getPrincipal();
        User user = userRepository.findByEmail(email.toLowerCase())
            .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, email));
        return ok(new LoginResult(user.getEmail(), user.getDisplayName()));
    }

    // ========== Request/Response DTOs ==========

    public record RegisterRequest(
        @NotBlank @Email(message = "Must be valid email")
        @Schema(description = "User email address", example = "alice@tapstate.io")
        String email,

        @NotBlank @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
        @Schema(description = "Password (min 8 chars)", example = "SecurePass123!")
        String password,

        @Size(max = 100)
        @Schema(description = "Display name (optional)", example = "Alice Chen")
        String displayName
    ) {}

    public record LoginRequest(
        @NotBlank @Email
        @Schema(description = "User email address", example = "alice@tapstate.io")
        String email,

        @NotBlank
        @Schema(description = "User password", example = "SecurePass123!")
        String password
    ) {}

    /** Simple login result — email + displayName only (no session management details). */
    public record LoginResult(
        @Schema(description = "Authenticated user email")
        String email,
        @Schema(description = "User display name")
        String displayName
    ) {}

    public record UserResponse(
        @Schema(description = "User UUID")
        String id,
        @Schema(description = "User email address")
        String email,
        @Schema(description = "User display name")
        String displayName,
        @Schema(description = "Account status: ACTIVE / PENDING_EMAIL / SUSPENDED")
        String status,
        @Schema(description = "Account creation time (ISO-8601)")
        Instant createdAt,
        @Schema(description = "Last login time (ISO-8601)")
        Instant lastLoginAt
    ) {
        public static UserResponse from(User user) {
            return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getLastLoginAt()
            );
        }
    }
}
