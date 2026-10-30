package io.tapstate.cloud.org;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.identity.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Organization API without the legacy URL slug field. */
@RestController
@RequestMapping("/v2/api/orgs")
@RequiredArgsConstructor
@Tag(name = "Organizations V2", description = "Organization APIs using display names and stable IDs")
public class OrganizationV2Controller extends BaseController {

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;
    private final InvitationService invitationService;

    @GetMapping
    @Operation(summary = "List current user's organizations")
    public ResponseEntity<ApiResponse<List<OrganizationV2Response>>> listMyOrganizations() {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        return ok(organizationService.listUserOrganizations(userEmail).stream()
            .map(OrganizationV2Response::from).toList());
    }

    @PostMapping
    @Transactional
    @Operation(summary = "Create an organization from its display name")
    public ResponseEntity<ApiResponse<OrganizationV2Response>> create(
            @Valid @RequestBody CreateOrganizationRequest request, HttpServletRequest httpRequest) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);

        var session = httpRequest.getSession(false);
        String pendingTrialHash = session == null ? null : (String) session.getAttribute(
            InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE);
        if (pendingTrialHash != null && pendingTrialHash.isBlank()) pendingTrialHash = null;
        String trialCreationKey = session == null ? null : (String) session.getAttribute(
            InvitationService.PENDING_TRIAL_ORGANIZATION_CREATION_KEY_SESSION_ATTRIBUTE);
        if (trialCreationKey != null && trialCreationKey.isBlank()) trialCreationKey = null;

        if (pendingTrialHash != null && trialCreationKey == null) {
            trialCreationKey = java.util.UUID.randomUUID().toString();
            session.setAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_CREATION_KEY_SESSION_ATTRIBUTE,
                trialCreationKey);
        }
        String creationKey = trialCreationKey == null
            ? "owner:" + userEmail.trim().toLowerCase(java.util.Locale.ROOT)
            : "trial:" + trialCreationKey;
        var existing = organizationService.findCreatedOrganization(
            creationKey, userEmail, request.name());
        if (existing.isPresent()) return ok(OrganizationV2Response.from(existing.get()));
        if (trialCreationKey != null && pendingTrialHash == null) {
            throw new BusinessException(AppErrorCode.ORG_LIMIT_REACHED, HttpStatus.CONFLICT.value());
        }
        if (pendingTrialHash != null) {
            invitationService.requireUsableTrialHash(pendingTrialHash, userEmail);
        }

        Organization organization = organizationService.createOrganizationWithoutSlug(
            request.name(), userEmail, pendingTrialHash != null, creationKey);

        if (pendingTrialHash != null) {
            invitationService.consumeTrialHash(pendingTrialHash, userEmail);
            session.removeAttribute(InvitationService.PENDING_TRIAL_ORGANIZATION_SESSION_ATTRIBUTE);
        }
        return ok(OrganizationV2Response.from(organization));
    }

    @GetMapping("/{orgId}")
    @Operation(summary = "Get an organization by stable ID")
    public ResponseEntity<ApiResponse<OrganizationV2Response>> getOrganization(@PathVariable String orgId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        ObjectId objectId;
        try {
            objectId = new ObjectId(orgId);
        } catch (IllegalArgumentException exception) {
            return fail(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, orgId);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }
        Organization organization = organizationRepository.findById(objectId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId));
        return ok(OrganizationV2Response.from(organization));
    }

    public record CreateOrganizationRequest(
        @NotBlank @Size(max = 100) String name
    ) {}

    public record OrganizationV2Response(
        @Schema(description = "Organization UUID") String id,
        @Schema(description = "Organization display name") String name,
        @Schema(description = "Owner user email") String ownerEmail,
        @Schema(description = "Organization status") String status
    ) {
        public static OrganizationV2Response from(Organization organization) {
            return new OrganizationV2Response(
                organization.getId() != null ? organization.getId().toHexString() : null,
                organization.getName(), organization.getOwnerEmail(), organization.getStatus());
        }
    }
}
