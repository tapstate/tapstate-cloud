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
import io.tapstate.cloud.config.LegalConfig;
import io.tapstate.cloud.resolve.CallbackSignService;
import io.tapstate.cloud.resolve.JtiRecord;
import io.tapstate.cloud.resolve.JtiRecordRepository;
import io.tapstate.cloud.resolve.LocalClusterEndpoints;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.io.IOException;

/**
 * Authentication endpoints — Cloud Console user registration, login, session management.
 * <p>
 * Session model (ADR-0122 D1):
 * - Uses Spring Session (MongoDB-backed) — cookie TAPSTATE_CLOUD_SESSION handled by Spring Session automatically
 * - Idle expiry: 7 days (configured in application.yml spring.session.timeout)
 */
@RestController
@RequestMapping("/v1/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Auth", description = "Cloud Console authentication, session management and trial access")
public class AuthController extends BaseController {

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final InvitationService invitationService;
    private final TrialCodePolicy trialCodePolicy;
    private final AuthenticationManager authenticationManager;
    private final JtiRecordRepository jtiRecordRepo;
    private final CallbackSignService callbackSignService;
    private final LocalClusterEndpoints endpoints;
    private final ClientRegistrationRepository clientRegistrations;
    private final LegalConfig legalConfig;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(3)).build();
    private final SecureRandom secureRandom = new SecureRandom();

    // Session attribute key — also used by Security context recovery
    static final String SESSION_USER_EMAIL = "TAPSTATE_USER_EMAIL";

    @GetMapping("/registration-policy")
    @Operation(summary = "Get public registration policy", security = {})
    public ResponseEntity<ApiResponse<RegistrationPolicy>> registrationPolicy() {
        return ok(new RegistrationPolicy(trialCodePolicy.isRequired()));
    }

    public record RegistrationPolicy(boolean trialCodeRequired) {}

    // ========== Register ==========

    @PostMapping("/register")
    @Operation(
        summary = "Reject open registration",
        description = "Open registration is disabled. New accounts require an organization invitation and email verification."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Open registration is disabled")
    })
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        return fail(AppErrorCode.INVITATION_REQUIRED, HttpStatus.FORBIDDEN);
    }

    @PostMapping("/email/code")
    @Operation(summary = "Send an email verification code", description = "Requests an email code for a valid invitation, or for open self-registration when trial codes are not required.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Request accepted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or invitation token"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Invitation email does not match"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email is already registered; sign in instead"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410", description = "Invitation expired or revoked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Cooldown or hourly rate limit reached"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Email delivery is unavailable")
    })
    public ResponseEntity<ApiResponse<Void>> requestEmailCode(
            @Valid @RequestBody EmailCodeRequest request, HttpServletRequest httpRequest) {
        emailVerificationService.requestCode(request.invitationToken(), request.email(), httpRequest.getRemoteAddr());
        return okStatus(HttpStatus.ACCEPTED, null);
    }

    @PostMapping("/access")
    @Operation(operationId = "accessType", summary = "Inspect a trial code or organization invitation")
    public ResponseEntity<ApiResponse<AccessType>> accessType(@Valid @RequestBody AccessCode request) {
        return ok(new AccessType(invitationService.accessType(request.code())));
    }

    public record AccessCode(@NotBlank String code) {}
    public record AccessType(String type) {}

    @PostMapping("/email/register")
    @Operation(summary = "Complete email registration", description = "Verifies the one-time code and creates the account. Organization invitations add the account to their target Organization. A valid invitation is required while trial-code registration is enabled. New Cloud Console clients may defer trial Organization creation until the authenticated onboarding step.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created and signed in"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or unusable verification code"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Invitation email does not match"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Invitation was already consumed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410", description = "Invitation or verification code expired"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Verification attempts exceeded"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "OTP verification is not configured")
    })
    public ResponseEntity<ApiResponse<LoginResult>> registerWithEmailCode(
            @Valid @RequestBody EmailRegistrationRequest request, HttpServletRequest httpRequest) {
        // Require the caller to agree to the current legal document version.
        String currentLegal = legalConfig.getCurrentVersion();
        if (request.agreedLegalVersion() == null || request.agreedLegalVersion().isBlank()
                || !request.agreedLegalVersion().equals(currentLegal)) {
            return fail(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "legal.agreed-version-mismatch");
        }
        User user = emailVerificationService.verifyAndRegister(request.invitationToken(), request.email(),
            request.code(), request.password(), request.displayName(), request.organizationName(),
            request.deferOrganizationCreation());
        authenticateSession(user, httpRequest);
        return okCreated(new LoginResult(user.getEmail(), user.getDisplayName(), user.getAgreedLegalVersion()));
    }

    @PostMapping("/invitations/accept")
    @Operation(summary = "Accept an organization invitation", description = "Adds the authenticated existing account to the invitation's organization. For a trial code, clients may instead defer Organization creation to onboarding.")
    public ResponseEntity<ApiResponse<LoginResult>> acceptInvitation(
            @Valid @RequestBody AcceptInvitationRequest request, HttpServletRequest httpRequest) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        User user = request.deferOrganizationCreation()
            ? invitationService.deferTrialForExistingUser(request.invitationToken(), email,
                httpRequest.getSession(true))
            : invitationService.acceptForExistingUser(request.invitationToken(), email, request.organizationName());
        return ok(new LoginResult(user.getEmail(), user.getDisplayName(), user.getAgreedLegalVersion()));
    }

    @PostMapping("/oauth/{provider}/start")
    @Operation(summary = "Start Google or GitHub sign-in", description = "Stores a hashed invitation or identity-link intent in the server session and returns the provider authorization path. New Cloud Console clients may defer trial Organization creation until the authenticated onboarding step.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Provider authorization path returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Unsupported OAuth flow"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Recent authentication required for identity linking"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Provider is not configured")
    })
    public ResponseEntity<ApiResponse<OAuthStartResult>> startOAuth(
            @PathVariable String provider, @Valid @RequestBody OAuthStartRequest oauthRequest,
            HttpServletRequest request) {
        if (!("google".equals(provider) || "github".equals(provider))) {
            return fail(AppErrorCode.SSO_PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (clientRegistrations.findByRegistrationId(provider) == null) {
            return fail(AppErrorCode.SSO_PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        }
        String flow = oauthRequest.flow();
        String invitationToken = oauthRequest.invitationToken();
        if (!("login".equals(flow) || "signup".equals(flow) || "link".equals(flow))) {
            return fail(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST);
        }
        if (oauthRequest.deferOrganizationCreation() && "link".equals(flow)) {
            return fail(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST);
        }
        String invitationTokenHash = null;
        boolean trialInvitation = false;
        if (invitationToken != null && !invitationToken.isBlank()) {
            trialInvitation = invitationService.isTrial(invitationService.requireUsable(invitationToken, null));
            invitationTokenHash = InvitationService.hash(invitationToken);
        }
        var session = request.getSession(true);
        if ("link".equals(flow)) {
            String email = CurrentUser.getEmail();
            Object reauthValue = session.getAttribute("TAPSTATE_IDENTITY_REAUTH_AT");
            if (!(reauthValue instanceof Instant)) {
                Object authenticatedAtValue = session.getAttribute("TAPSTATE_AUTHENTICATED_AT");
                if (authenticatedAtValue instanceof Instant authenticatedAt
                        && authenticatedAt.isAfter(Instant.now().minusSeconds(300))) {
                    reauthValue = authenticatedAt;
                    session.setAttribute("TAPSTATE_IDENTITY_REAUTH_AT", authenticatedAt);
                }
            }
            if (email == null || !(reauthValue instanceof Instant reauthAt)
                    || reauthAt.isBefore(Instant.now().minusSeconds(300))) {
                return fail(AppErrorCode.REAUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED);
            }
        }
        OAuthSessionSupport.snapshotCurrentUser(session);
        session.setAttribute("TAPSTATE_OAUTH_FLOW", flow);
        if (invitationTokenHash == null) {
            session.removeAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN");
        } else {
            session.setAttribute("TAPSTATE_OAUTH_INVITATION_TOKEN", invitationTokenHash);
        }
        if (oauthRequest.organizationName() == null || oauthRequest.organizationName().isBlank()) {
            session.removeAttribute("TAPSTATE_OAUTH_ORGANIZATION_NAME");
        } else {
            session.setAttribute("TAPSTATE_OAUTH_ORGANIZATION_NAME", oauthRequest.organizationName().trim());
        }
        session.setAttribute("TAPSTATE_OAUTH_DEFER_ORGANIZATION_CREATION",
            oauthRequest.deferOrganizationCreation());
        session.setAttribute("TAPSTATE_OAUTH_TRIAL_INVITATION",
            oauthRequest.deferOrganizationCreation() && trialInvitation);
        return ok(new OAuthStartResult(request.getContextPath() + "/oauth2/authorization/" + provider));
    }

    // ========== Login ==========

    @PostMapping("/email/login/code")
    @Operation(summary = "Request an email sign-in code")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Request accepted")})
    public ResponseEntity<ApiResponse<Void>> requestEmailLoginCode(
            @Valid @RequestBody EmailLoginCodeRequest request, HttpServletRequest httpRequest) {
        emailVerificationService.requestLoginCode(request.email(), httpRequest.getRemoteAddr());
        return okStatus(HttpStatus.ACCEPTED, null);
    }

    @PostMapping("/email/login")
    @Operation(summary = "Sign in using an email verification code")
    public ResponseEntity<ApiResponse<LoginResult>> loginWithEmailCode(
            @Valid @RequestBody EmailLoginRequest request, HttpServletRequest httpRequest) {
        User user = emailVerificationService.verifyLoginCode(request.email(), request.code());
        Instant now = Instant.now();
        user.setLastLoginAt(now);
        String currentLegal = legalConfig.getCurrentVersion();
        if (user.getAgreedLegalVersion() == null || !user.getAgreedLegalVersion().equals(currentLegal)) {
            user.setAgreedLegalVersion(currentLegal);
            user.setAgreedLegalAt(now);
        }
        userRepository.save(user);
        authenticateSession(user, httpRequest);
        return ok(new LoginResult(user.getEmail(), user.getDisplayName(), user.getAgreedLegalVersion()));
    }

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
            String email = InvitationService.normalizeEmail(request.email());
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password())
            );
            // Re-wrap with String principal so CurrentUser.getEmail() returns the email string
            UsernamePasswordAuthenticationToken finalAuth = new UsernamePasswordAuthenticationToken(
                email, null, authentication.getAuthorities());

            // Load full User record for displayName + update lastLoginAt
            User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, request.email()));
            Instant now = Instant.now();
            user.setLastLoginAt(now);

            // Auto-upgrade: if the user has no recorded legal version or it is stale,
            // silently bump it to the current version on login. This covers existing
            // users created before the legal-agreement feature landed.
            String currentLegal = legalConfig.getCurrentVersion();
            if (user.getAgreedLegalVersion() == null || !user.getAgreedLegalVersion().equals(currentLegal)) {
                user.setAgreedLegalVersion(currentLegal);
                user.setAgreedLegalAt(now);
            }
            userRepository.save(user);

            authenticateSession(user, httpRequest, finalAuth);

            log.info("Login success: {}", user.getEmail());
            return ok(new LoginResult(user.getEmail(), user.getDisplayName(), user.getAgreedLegalVersion()));
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

        String url = endpoints.callbackOrigin(rec) + "/auth/invalidate-session"
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

    private void authenticateSession(User user, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            user.getEmail(), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        authenticateSession(user, request, authentication);
    }

    private void authenticateSession(User user, HttpServletRequest request, Authentication authentication) {
        var session = request.getSession(true);
        String previousEmail = CurrentUser.getEmail();
        if (previousEmail == null && session.getAttribute(SESSION_USER_EMAIL) instanceof String storedEmail) {
            previousEmail = storedEmail;
        }
        if (previousEmail == null || !InvitationService.normalizeEmail(previousEmail)
                .equals(InvitationService.normalizeEmail(user.getEmail()))) {
            InvitationService.clearPendingTrialOrganization(session);
        }
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        session.setAttribute(SESSION_USER_EMAIL, user.getEmail());
        session.setAttribute("TAPSTATE_AUTHENTICATED_AT", Instant.now());
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
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || CurrentUser.getEmail() == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        String email = CurrentUser.getEmail();
        User user = userRepository.findByEmail(email.toLowerCase())
            .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, email));
        return ok(new LoginResult(user.getEmail(), user.getDisplayName(), user.getAgreedLegalVersion()));
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

    public record EmailCodeRequest(
        @NotBlank @Email String email,
        String invitationToken
    ) {}

    public record EmailLoginCodeRequest(@NotBlank @Email String email) {}
    public record EmailLoginRequest(@NotBlank @Email String email, @NotBlank @Size(min = 6, max = 6) String code) {}

    public record EmailRegistrationRequest(
        @NotBlank @Email String email,
        String invitationToken,
        @NotBlank @Size(min = 6, max = 6) String code,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 100) String displayName,
        @Size(max = 100) String organizationName,
        @Schema(description = "Legal document version the user agreed to (must match current version)")
        String agreedLegalVersion,
        @Schema(description = "Create the Organization after the user completes Cloud onboarding")
        boolean deferOrganizationCreation
    ) {}

    public record AcceptInvitationRequest(@NotBlank String invitationToken, String organizationName,
                                          boolean deferOrganizationCreation) {}

    public record OAuthStartRequest(@NotBlank String flow, String invitationToken, String organizationName,
                                    boolean deferOrganizationCreation) {}

    public record OAuthStartResult(String authorizationUrl) {}

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
        String displayName,
        @Schema(description = "Legal document version the user has agreed to")
        String agreedLegalVersion
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
        Instant lastLoginAt,
        @Schema(description = "Legal document version the user has agreed to")
        String agreedLegalVersion
    ) {
        public static UserResponse from(User user) {
            return new UserResponse(
                user.getId() != null ? user.getId().toHexString() : null,
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getAgreedLegalVersion()
            );
        }
    }
}
