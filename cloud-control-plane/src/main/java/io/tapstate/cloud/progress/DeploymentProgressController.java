package io.tapstate.cloud.progress;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.org.OrganizationService;
import io.tapstate.cloud.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only deployment progress endpoints — aggregated percentage plus the
 * detail of every operation, scoped to one subscription.
 */
@RestController
@RequestMapping("/v1/api/orgs/{orgId}")
@RequiredArgsConstructor
@Tag(name = "Deployment Progress", description = "Subscription deployment/reclamation progress")
public class DeploymentProgressController extends BaseController {

    private final SubscriptionService subscriptionService;
    private final OrganizationService organizationService;
    private final DeploymentProgressService progressService;

    @GetMapping("/subscriptions/{subscriptionId}/progress")
    @Operation(
        summary = "Get the deployment progress of one subscription",
        description = "Returns the aggregate completion percentage and overall status plus the detail " +
                      "of every operation. Use opType=INSTALL for the initial deployment (default) and " +
                      "opType=UNINSTALL for the release/reclamation after cancellation. " +
                      "Operations not started yet are returned as PENDING."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Aggregated deployment progress"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid opType"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription not found in this Organization")
    })
    public ResponseEntity<ApiResponse<DeploymentProgressResponse>> getProgress(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Parameter(description = "Subscription UUID") @PathVariable String subscriptionId,
            @Parameter(description = "Operation category: INSTALL (default) / UNINSTALL")
            @RequestParam(name = "opType", defaultValue = "INSTALL") String opType) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }

        // Subscription ownership — 404 when missing or owned by another org.
        subscriptionService.getByIdAndOrgId(subscriptionId, orgId);

        DeploymentOpType parsed = parseOpType(opType);
        DeploymentProgressResponse progress =
            progressService.getProgress(new ObjectId(subscriptionId), parsed);
        return ok(progress);
    }

    private static DeploymentOpType parseOpType(String value) {
        try {
            return DeploymentOpType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AppErrorCode.DEPLOYMENT_OP_TYPE_INVALID, 400, value);
        }
    }
}
