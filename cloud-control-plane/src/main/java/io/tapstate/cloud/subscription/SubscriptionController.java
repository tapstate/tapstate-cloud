package io.tapstate.cloud.subscription;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.org.OrganizationService;
import io.tapstate.cloud.progress.DeploymentOpType;
import io.tapstate.cloud.progress.DeploymentProgressService;
import io.tapstate.cloud.progress.DeploymentProgressSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Subscription endpoints — an Organization owns many subscriptions; each
 * subscription owns one Cluster and one MdbAllocation.
 * <p>
 * Creation flow (POST /subscriptions): creates Subscription (TRIAL state) →
 * sync creates its Cluster record → sync creates its MdbAllocation record →
 * both state machines run async provisioning in parallel. Poll
 * GET /subscriptions/{subscriptionId} to track progress.
 * <p>
 * Payment callbacks and invoice list are Week 3+ features (not implemented here).
 */
@RestController
@RequestMapping("/v1/api/orgs/{orgId}")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "Organization subscriptions — each subscription provisions one Cluster + one MDB allocation")
public class SubscriptionController extends BaseController {

    private final SubscriptionService subscriptionService;
    private final OrganizationService organizationService;
    private final DeploymentProgressService progressService;

    /**
     * Build the API view of one subscription: items plus the INSTALL and
     * UNINSTALL progress summaries.
     */
    private SubscriptionResponse toResponse(Subscription subscription) {
        List<SubscriptionItem> items = subscriptionService.getItems(subscription.getId());
        DeploymentProgressSummary install =
            progressService.summary(subscription.getId(), DeploymentOpType.INSTALL);
        DeploymentProgressSummary uninstall =
            progressService.summary(subscription.getId(), DeploymentOpType.UNINSTALL);
        return SubscriptionResponse.from(subscription, items, install, uninstall);
    }

    @GetMapping("/subscriptions")
    @Operation(
        summary = "List organization subscriptions",
        description = "Returns every Subscription owned by the Organization, newest first. " +
                      "The list may be empty — creating an Organization no longer creates a subscription."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of subscriptions (may be empty)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization")
    })
    public ResponseEntity<ApiResponse<List<SubscriptionResponse>>> listSubscriptions(
            @Parameter(description = "Organization UUID") @PathVariable String orgId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }
        List<SubscriptionResponse> responses = subscriptionService.listByOrgId(orgId).stream()
            .map(this::toResponse)
            .toList();
        return ok(responses);
    }

    @PostMapping("/subscriptions")
    @Operation(
        summary = "Create a subscription with one Cluster + one MDB allocation",
        description = "Creates a new subscription (TRIAL, 30-day period) with the two default billable items, " +
                      "then synchronously provisions one Cluster record and one Atlas MDB allocation.\n\n" +
                      "Async provisioning (fired by state machines):\n" +
                      "  - Cluster state machine: START_PROVISION → CloudFormation simulation → READY/PROVISIONING_FAILED\n" +
                      "  - MDB state machine: START_PROVISION → Atlas createDatabase + createDatabaseUser → READY/PROVISIONING_FAILED\n\n" +
                      "Both flows run in parallel. Poll GET /subscriptions/{subscriptionId} to track progress.\n\n" +
                      "An Organization may own many subscriptions; cluster names stay unique within org + region."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Subscription created, Cluster + MDB allocation provisioning pending"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input (missing region/name/spec)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate cluster name in org + region"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "No Atlas capacity for the requested region")
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> createSubscription(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }

        Subscription subscription = subscriptionService.createSubscription(
            orgId, request.region(), request.clusterName(), request.specKey(), userEmail);

        return okCreated(toResponse(subscription));
    }

    @GetMapping("/subscriptions/{subscriptionId}")
    @Operation(
        summary = "Get one subscription",
        description = "Returns the subscription's status, billing period, provisioned resources and item breakdown.\n\n" +
                      "Subscription states: TRIAL (free trial) / ACTIVE (paid) / PAST_DUE (grace period) / CANCELLED (terminated).\n\n" +
                      "Poll this endpoint to track async Cluster + MDB provisioning progress."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription details"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription not found in this Organization")
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getSubscription(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Parameter(description = "Subscription UUID") @PathVariable String subscriptionId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }

        Subscription subscription = subscriptionService.getByIdAndOrgId(subscriptionId, orgId);
        return ok(toResponse(subscription));
    }

    @PostMapping("/subscriptions/{subscriptionId}/status")
    @Operation(
        summary = "Update subscription status (admin/internal)",
        description = "Internal admin endpoint for subscription state transition.\n\n" +
                      "Used in Week 2 to simulate PAST_DUE / CANCELLED states for testing. " +
                      "Production version (Week 12) will be driven by Marketplace billing callbacks.\n\n" +
                      "Accepted status values: TRIAL / ACTIVE / PAST_DUE / CANCELLED."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid status value or missing field"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> transitionStatus(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Parameter(description = "Subscription UUID") @PathVariable String subscriptionId,
            @RequestBody Map<String, String> body) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }

        String statusStr = body.get("status");
        if (statusStr == null) {
            return fail(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "status is required");
        }
        SubscriptionStatus target;
        try {
            target = SubscriptionStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                AppErrorCode.BAD_REQUEST, 400, "Invalid subscription status: " + statusStr);
        }

        Subscription updated = subscriptionService.transitionStatus(subscriptionId, orgId, target);
        return ok(toResponse(updated));
    }

    @PostMapping("/subscriptions/{subscriptionId}/cancel")
    @Operation(
        summary = "Cancel one subscription — terminate its Cluster + reclaim its MDB",
        description = "Cancels this subscription (state → CANCELLED) and triggers cascade termination of " +
                      "its Cluster and reclaim of its MdbAllocation. Other subscriptions of the same " +
                      "Organization are unaffected.\n\n" +
                      "Cascade runs asynchronously via the Cluster state-machine INITIATE_TERMINATION event and " +
                      "MDB state-machine INITIATE_RECLAIM event. This endpoint returns immediately.\n\n" +
                      "Safe to call on an already-cancelled subscription (idempotent)."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription cancelled, cascade termination scheduled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> cancelSubscription(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Parameter(description = "Subscription UUID") @PathVariable String subscriptionId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }

        Subscription cancelled = subscriptionService.cancelSubscription(subscriptionId, orgId, "gc:" + userEmail);
        return ok(toResponse(cancelled));
    }

    // ========== Request DTO ==========

    public record CreateSubscriptionRequest(
        @NotBlank @Parameter(description = "AWS Region code", example = "ap-southeast-1")
        String region,
        @NotBlank @Size(min = 1, max = 100)
        @Parameter(description = "Cluster display name (unique within org + region)", example = "Production Pipeline")
        String clusterName,
        @Size(max = 50) @Parameter(description = "Provisioning spec_key (Week 2: 'basic' only)", example = "basic")
        String specKey
    ) {}

    // ========== Response DTOs ==========

    public record SubscriptionResponse(
        @Schema(description = "Subscription UUID") String id,
        @Schema(description = "Organization UUID") String orgId,
        @Schema(description = "Subscription state: TRIAL / ACTIVE / PAST_DUE / CANCELLED") String status,
        @Schema(description = "Cloud resource state: PROVISIONING / READY (null until resources are provisioned)") String resourceState,
        @Schema(description = "Initial deployment progress summary") DeploymentProgressSummary installProgress,
        @Schema(description = "Release/reclamation progress summary") DeploymentProgressSummary uninstallProgress,
        @Schema(description = "When the Cluster and MdbAllocation first became READY (ISO-8601 UTC)") Instant resourcesReadyAt,
        @Schema(description = "Current billing period start (ISO-8601 UTC)") Instant currentPeriodStart,
        @Schema(description = "Current billing period end (ISO-8601 UTC)") Instant currentPeriodEnd,
        @Schema(description = "Whether provisioning new resources is allowed (TRIAL / ACTIVE = true)") boolean provisioningAllowed,
        @Schema(description = "The subscription's Cluster UUID") String clusterId,
        @Schema(description = "The subscription's Atlas MdbAllocation UUID") String mdbAllocationId,
        @Schema(description = "Subscription creation time (ISO-8601 UTC)") Instant createdAt,
        @Schema(description = "Last update time (ISO-8601 UTC)") Instant updatedAt,
        @Schema(description = "Subscription item breakdown") List<ItemResponse> items
    ) {
        public static SubscriptionResponse from(Subscription s, List<SubscriptionItem> items,
                                                DeploymentProgressSummary installProgress,
                                                DeploymentProgressSummary uninstallProgress) {
            String clusterId = items.stream()
                .filter(i -> i.getItemType() == SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)
                .map(SubscriptionItem::getClusterId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(ObjectId::toHexString)
                .orElse(null);
            String mdbAllocationId = items.stream()
                .filter(i -> i.getItemType() == SubscriptionItemType.ATLAS_DATABASE_STORAGE)
                .map(SubscriptionItem::getMdbAllocationId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(ObjectId::toHexString)
                .orElse(null);
            return new SubscriptionResponse(
                s.getId() != null ? s.getId().toHexString() : null,
                s.getOrgId() != null ? s.getOrgId().toHexString() : null,
                s.getStatus().name(),
                s.getResourceState() != null ? s.getResourceState().name() : null,
                installProgress,
                uninstallProgress,
                s.getResourcesReadyAt(),
                s.getCurrentPeriodStart(),
                s.getCurrentPeriodEnd(),
                s.getStatus().allowsProvisioning(),
                clusterId,
                mdbAllocationId,
                s.getCreatedAt(),
                s.getUpdatedAt(),
                items.stream().map(ItemResponse::from).toList());
        }
    }

    public record ItemResponse(
        @Schema(description = "Subscription item UUID") String id,
        @Schema(description = "Item type enum: TAPSTATE_CLUSTER / ATLAS_DATABASE") String itemType,
        @Schema(description = "Human-readable item name") String itemTypeDisplayName,
        @Schema(description = "Billing unit (e.g. 'per-cluster', 'per-GB')") String billingUnit,
        @Schema(description = "Included quantity (0 = unlimited with metadata.unlimited=true)") int includedQuantity,
        @Schema(description = "Unit price in cents (100 = $1.00)") long unitPriceCents,
        @Schema(description = "Provisioned Cluster ID billed by this item (CLUSTER_PIPELINE_RUNTIME)") String clusterId,
        @Schema(description = "Provisioned MdbAllocation ID billed by this item (ATLAS_DATABASE_STORAGE)") String mdbAllocationId,
        @Schema(description = "Free-form metadata (e.g. unlimited flag, spec key)") Map<String, String> metadata
    ) {
        public static ItemResponse from(SubscriptionItem item) {
            SubscriptionItemType t = item.getItemType();
            return new ItemResponse(
                item.getId() != null ? item.getId().toHexString() : null,
                t.name(),
                t.getDisplayName(),
                t.getBillingUnit(),
                item.getIncludedQuantity(),
                item.getUnitPriceCents(),
                item.getClusterId() != null ? item.getClusterId().toHexString() : null,
                item.getMdbAllocationId() != null ? item.getMdbAllocationId().toHexString() : null,
                item.getMetadata());
        }
    }
}
