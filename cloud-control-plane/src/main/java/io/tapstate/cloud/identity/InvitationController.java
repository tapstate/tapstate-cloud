package io.tapstate.cloud.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/api/orgs/{orgId}/invitations")
@RequiredArgsConstructor
@Tag(name = "Organization invitations", description = "Owner-managed single-use organization invitations")
public class InvitationController extends BaseController {

    private final InvitationService invitationService;

    @PostMapping
    @Operation(operationId = "createInvitation", summary = "Create an organization invitation", description = "Only the organization owner may create a seven-day, single-use invitation. The returned token is shown once.")
    public ResponseEntity<ApiResponse<CreatedInvitation>> create(
            @PathVariable String orgId, @Valid @RequestBody CreateInvitationRequest request) {
        String ownerEmail = CurrentUser.getEmail();
        if (ownerEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        InvitationService.IssuedInvitation issued = invitationService.create(orgId, ownerEmail, request.email());
        return okCreated(new CreatedInvitation(issued.invitation(), issued.token()));
    }

    @GetMapping
    @Operation(operationId = "listInvitations", summary = "List organization invitations", description = "Returns invitation status without disclosing invitation tokens.")
    public ResponseEntity<ApiResponse<List<InvitationService.InvitationView>>> list(@PathVariable String orgId) {
        String ownerEmail = CurrentUser.getEmail();
        if (ownerEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        return ok(invitationService.list(orgId, ownerEmail));
    }

    @DeleteMapping("/{invitationId}")
    @Operation(operationId = "revokeInvitation", summary = "Revoke an organization invitation", description = "Revokes an unused invitation owned by the specified organization.")
    public ResponseEntity<ApiResponse<InvitationService.InvitationView>> revoke(
            @PathVariable String orgId, @PathVariable String invitationId) {
        String ownerEmail = CurrentUser.getEmail();
        if (ownerEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        return ok(invitationService.revoke(orgId, invitationId, ownerEmail));
    }

    public record CreateInvitationRequest(@Email @Size(max = 254) String email) {}

    public record CreatedInvitation(InvitationService.InvitationView invitation, String token) {}
}
