package io.tapstate.cloud.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.resolve.CallbackSignService;
import io.tapstate.cloud.resolve.JtiRecord;
import io.tapstate.cloud.resolve.JtiRecordRepository;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

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
    private final JtiRecordRepository jtiRecordRepo;
    private final CallbackSignService callbackSignService;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(3)).build();
    private final SecureRandom secureRandom = new SecureRandom();

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
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User created"),
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
        description = "Invalidate the current Cloud session. " +
                      "Also fires signed revocation callbacks to ALL Clusters this user has active resolve JWTs for " +
                      "(Cloud→Cluster callback with RS256 signature — Cluster verifies via SDK verifyRequest). " +
                      "Each Cluster then revokes its local session for that jti."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Session invalidated")
    })
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        // 1. Capture current user email BEFORE invalidating session (Issue #14)
        String userEmail = CurrentUser.getEmail();

        // 2. Fire signed revocation callbacks to all active resolve JWTs for this user (Issue #5 items 3/4)
        if (userEmail != null) {
            revokeAllActiveJwts(userEmail);
        }

        // 3. Invalidate Cloud-side session AFTER callbacks are sent
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        return ok();
    }

    /**
     * Find all active (non-revoked, non-expired) JWT records for the user,
     * send signed revocation callback to each target Cluster, then mark all as revoked.
     * <p>
     * Cluster callback endpoint: {@code POST {baseUri}/auth/invalidate-session} with query params:
     * jti={jti}&ts={ts}&nonce={nonce}&sign={sign}
     * <p>
     * Canonical sign input (matches SDK verifyRequest): "POST|{ts}|{nonce}|{jti}".
     * <p>
     * Fail-safe: individual callback failures are logged but do NOT prevent other
     * callbacks or the local revocation — GC must not hang logout on Cluster reachability.
     */
    private void revokeAllActiveJwts(String userEmail) {
        // IMPORTANT: no expiry filter here — Issue #5 requires Cloud logout after JWT expiry
        // (t+20min while JWT expired at t+15min) still fires revocation, because the Cluster's
        // local HttpOnly session may still be active (30min sliding idle timeout).
        List<JtiRecord> active = jtiRecordRepo.findByUserEmailAndRevokedAtIsNull(userEmail);
        Instant now = Instant.now();

        for (JtiRecord rec : active) {
            try {
                sendRevocationCallback(rec);
                rec.setRevokedAt(now); jtiRecordRepo.save(rec);
                log.info("Revoked jti={}, cluster={}", rec.getJti(), rec.getClusterId());
            } catch (Exception e) {
                log.warn("Failed to revoke jti={}, cluster={} — marking revoked anyway: {}",
                    rec.getJti(), rec.getClusterId(), e.getMessage());
                // Still mark revoked locally — don't retry forever; Cluster will expire on its own
                rec.setRevokedAt(now); jtiRecordRepo.save(rec);
            }
        }
    }

    private void sendRevocationCallback(JtiRecord rec) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = randomNonce();
        // Canonical: POST|ts|nonce|jti  (no body — jti IS the revocation data)
        String canonicalBody = rec.getJti();
        String sign = callbackSignService.sign("POST", ts, nonce, canonicalBody);

        String url = rec.getClusterBaseUri() + "/auth/invalidate-session"
            + "?jti=" + java.net.URLEncoder.encode(rec.getJti(), java.nio.charset.StandardCharsets.UTF_8)
            + "&ts=" + ts
            + "&nonce=" + java.net.URLEncoder.encode(nonce, java.nio.charset.StandardCharsets.UTF_8)
            + "&sign=" + java.net.URLEncoder.encode(sign, java.nio.charset.StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(java.time.Duration.ofSeconds(3))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            log.warn("Cluster revocation callback returned {} for jti={}, cluster={}",
                resp.statusCode(), rec.getJti(), rec.getClusterId());
        }
    }

    private String randomNonce() {
        byte[] raw = new byte[16];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    // ========== Whoami ==========

    @GetMapping("/me")
    @Operation(
        summary = "Get current user info",
        description = "Return the authenticated user's profile. Session cookie required."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User profile"),
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
