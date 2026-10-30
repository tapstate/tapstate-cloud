package io.tapstate.cloud.org;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Organization endpoints — create, list, membership.
 * <p>
 * Access requires Cloud Console session (ADR-0122 D2).
 */
@RestController
@RequestMapping("/v1/api/orgs")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Organization management — isolation boundary for Clusters")
public class OrganizationController extends BaseController {

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;

    @GetMapping
    @Operation(
        summary = "List current user's organizations",
        description = "Returns all Organizations the authenticated user is a member of. " +
                      "Organizations are the isolation boundary for Clusters and subscriptions."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of Organizations (may be empty)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<ApiResponse<List<OrganizationResponse>>> listMyOrganizations() {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        List<Organization> orgs = organizationService.listUserOrganizations(userEmail);
        return ok(orgs.stream().map(OrganizationResponse::from).toList());
    }

    @PostMapping
    @Operation(
        summary = "Create new organization (user becomes owner automatically)",
        description = "Creates a new Organization from its display name and adds the caller as its owner. " +
                      "The Organization starts with no subscriptions — create them explicitly via " +
                      "POST /v1/api/orgs/{orgId}/subscriptions."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Organization name is invalid"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Account already belongs to an organization")
    })
    public ResponseEntity<ApiResponse<OrganizationResponse>> create(
            @Valid @RequestBody CreateOrganizationRequest request) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = organizationService.createOrganization(request.name(), userEmail);
        return ok(OrganizationResponse.from(org));
    }

    @GetMapping("/{orgId}")
    @Operation(
        summary = "Get organization by ID",
        description = "Returns Organization details including owner and status. " +
                      "Caller must be a member of this Organization."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization details"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Organization not found")
    })
    public ResponseEntity<ApiResponse<OrganizationResponse>> getOrg(
            @Parameter(description = "Organization UUID") @PathVariable String orgId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        ObjectId orgObjectId;
        try {
            orgObjectId = new ObjectId(orgId);
        } catch (IllegalArgumentException e) {
            return fail(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, orgId);
        }
        Organization org = organizationRepository.findById(orgObjectId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId));
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }
        return ok(OrganizationResponse.from(org));
    }

    public record OrganizationResponse(
        @Schema(description = "Organization UUID") String id,
        @Schema(description = "Organization display name") String name,
        @Schema(description = "Owner user email") String ownerEmail,
        @Schema(description = "Organization status: ACTIVE / SUSPENDED") String status
    ) {
        public static OrganizationResponse from(Organization org) {
            return new OrganizationResponse(
                org.getId() != null ? org.getId().toHexString() : null,
                org.getName(), org.getOwnerEmail(), org.getStatus());
        }
    }

    public record CreateOrganizationRequest(
        @NotBlank @Size(max = 100) String name
    ) {}
}
