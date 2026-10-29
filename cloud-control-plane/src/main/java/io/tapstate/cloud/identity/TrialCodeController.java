package io.tapstate.cloud.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.AppErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api/trial-codes")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Cloud Console authentication, session management and trial access")
public class TrialCodeController extends BaseController {
    private final InvitationService invitations;

    @Value("${tapstate.cloud.auth.console-url:http://localhost:5173}")
    private String consoleUrl;

    @PostMapping
    @Operation(operationId = "createTrialCode", summary = "Issue a single-use trial code")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Trial code created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the configured trial issuer")
    })
    public ResponseEntity<ApiResponse<TrialCode>> create() {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        InvitationService.IssuedInvitation issued = invitations.createTrialCode(email);
        String url = consoleUrl.replaceAll("/$", "") + "/auth#invite=" + issued.token();
        return okCreated(new TrialCode(issued.token(), url, issued.invitation().expiresAt()));
    }

    public record TrialCode(String code, String url, java.time.Instant expiresAt) {}
}
