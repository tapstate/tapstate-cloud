package io.tapstate.cloud.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/api/auth/identities")
@RequiredArgsConstructor
public class IdentityController extends BaseController {

    private final LinkedIdentityRepository identities;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Operation(operationId = "listIdentities")
    @GetMapping
    public ResponseEntity<ApiResponse<List<IdentityView>>> list(HttpServletRequest request) {
        String email = CurrentUser.getEmail();
        if (email == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        return ok(identities.findByUserEmailOrderByLinkedAtAsc(InvitationService.normalizeEmail(email)).stream()
            .map(linked -> new IdentityView(linked.getProvider(), linked.getProviderEmail(), linked.getLinkedAt()))
            .toList());
    }

    @PostMapping("/reauthenticate")
    public ResponseEntity<ApiResponse<Void>> reauthenticate(
            @Valid @RequestBody ReauthenticationRequest request, HttpServletRequest httpRequest) {
        String email = CurrentUser.getEmail();
        if (email == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        User user = users.findByEmail(InvitationService.normalizeEmail(email))
            .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, email));
        var session = httpRequest.getSession(false);
        if (session == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        String password = request.password();
        if (password != null && !password.isBlank()) {
            if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()
                    || !passwordEncoder.matches(password, user.getPasswordHash())) {
                return fail(AppErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
            }
        } else {
            Object authenticatedAtValue = session.getAttribute("TAPSTATE_AUTHENTICATED_AT");
            if (!(authenticatedAtValue instanceof Instant authenticatedAt)
                    || authenticatedAt.isBefore(Instant.now().minusSeconds(300))) {
                return fail(AppErrorCode.REAUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED);
            }
        }
        session.setAttribute("TAPSTATE_IDENTITY_REAUTH_AT", Instant.now());
        return ok();
    }

    public record ReauthenticationRequest(String password) {}

    public record IdentityView(String provider, String email, Instant linkedAt) {}
}
