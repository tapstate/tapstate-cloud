package io.tapstate.cloud.subscription;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Organization subscription endpoint — create subscription with initial Cluster + MDB allocation,
 * view current subscription status, items and billing period.
 * <p>
 * Creation flow (POST): creates Subscription (TRIAL state) → sync creates Cluster record →
 * sync creates MdbAllocation record → both state machines fire START_PROVISION events for
 * async CloudFormation simulation and Atlas DB/user creation respectively.
 * Both async flows run in parallel; all subscription items must reach READY before the
 * user can access the Cluster Console.
 * <p>
 * Payment callbacks and invoice list are Week 3+ features (not implemented here).
 */
@RestController
@RequestMapping("/v1/api/orgs/{orgId}")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "Organization subscription — create with initial Cluster, view status and items")
public class SubscriptionController extends BaseController {

    private final SubscriptionService subscriptionService;

    @PostMapping("/subscription")
    @Operation(
        summary = "Create subscription with initial Cluster + MDB allocation",
        description = "Creates a new subscription (TRIAL, 30-day period) with the two default billable items, " +
                      "then synchronously provisions an initial Cluster record and Atlas MDB allocation.\n\n" +
                      "Async provisioning (fired by state machines):\n" +
                      "  - Cluster state machine: START_PROVISION → CloudFormation simulation → READY/PROVISIONING_FAILED\n" +
                      "  - MDB state machine: START_PROVISION → Atlas createDatabase + createDatabaseUser → READY/PROVISIONING_FAILED\n\n" +
                      "Both flows run in parallel. Poll GET /subscription to track overall progress.\n\n" +
                      "Idempotency: if the org already has a subscription shell, reuses it and adds one Cluster."
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

        Subscription subscription = subscriptionService.createSubscription(
            orgId, request.region(), request.clusterName(), request.specKey(), userEmail);

        List<SubscriptionItem> items = subscriptionService.getItemsByOrgId(orgId);
        return okCreated(SubscriptionResponse.from(subscription, items));
    }

    @GetMapping("/subscription")
    @Operation(
        summary = "View organization subscription",
        description = "Returns the Organization's current subscription status, billing period, and item breakdown.\n\n" +
                      "Subscription states: TRIAL (free trial) / ACTIVE (paid) / PAST_DUE (grace period) / CANCELLED (terminated).\n\n" +
                      "Poll this endpoint to track async Cluster + MDB provisioning progress."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription details"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getSubscription(
            @Parameter(description = "Organization UUID") @PathVariable String orgId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        Subscription subscription = subscriptionService.getByOrgId(orgId);
        List<SubscriptionItem> items = subscriptionService.getItemsByOrgId(orgId);

        SubscriptionResponse resp = SubscriptionResponse.from(subscription, items);
        return ok(resp);
    }

    @PostMapping("/subscription/status")
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
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> transitionStatus(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @RequestBody Map<String, String> body) {
        String statusStr = body.get("status");
        if (statusStr == null) {
            return fail(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "status is required");
        }
        SubscriptionStatus target;
        try {
            target = SubscriptionStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AppErrorCode.BAD_REQUEST, 400, "Invalid subscription status: " + statusStr);
        }

        Subscription updated = subscriptionService.transitionStatus(orgId, target);
        List<SubscriptionItem> items = subscriptionService.getItemsByOrgId(orgId);
        return ok(SubscriptionResponse.from(updated, items));
    }

    @PostMapping("/subscription/cancel")
    @Operation(
        summary = "Cancel subscription — auto-terminate all org clusters + reclaim MDB",
        description = "Cancels this Organization's subscription (state → CANCELLED) and triggers " +
                      "cascade termination of every non-terminated Cluster + MDB allocation owned by this org.\n\n" +
                      "Cascade runs asynchronously via Cluster state-machine INITIATE_TERMINATION events and " +
                      "MDB state-machine INITIATE_RECLAIM events. This endpoint returns immediately.\n\n" +
                      "Safe to call on an already-cancelled subscription (idempotent)."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription cancelled, cascade termination scheduled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not Org owner"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> cancelSubscription(
            @Parameter(description = "Organization UUID") @PathVariable String orgId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        Subscription cancelled = subscriptionService.cancelSubscription(orgId, "gc:" + userEmail);
        List<SubscriptionItem> items = subscriptionService.getItemsByOrgId(orgId);
        return ok(SubscriptionResponse.from(cancelled, items));
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
        @Schema(description = "Current billing period start (ISO-8601 UTC)") Instant currentPeriodStart,
        @Schema(description = "Current billing period end (ISO-8601 UTC)") Instant currentPeriodEnd,
        @Schema(description = "Whether provisioning new Clusters is allowed (TRIAL / ACTIVE = true)") boolean provisioningAllowed,
        @Schema(description = "Subscription creation time (ISO-8601 UTC)") Instant createdAt,
        @Schema(description = "Last update time (ISO-8601 UTC)") Instant updatedAt,
        @Schema(description = "Subscription item breakdown") List<ItemResponse> items
    ) {
        public static SubscriptionResponse from(Subscription s, List<SubscriptionItem> items) {
            return new SubscriptionResponse(
                s.getId(),
                s.getOrgId(),
                s.getStatus().name(),
                s.getCurrentPeriodStart(),
                s.getCurrentPeriodEnd(),
                s.getStatus().allowsProvisioning(),
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
        @Schema(description = "Free-form metadata (e.g. unlimited flag, spec key)") Map<String, String> metadata
    ) {
        public static ItemResponse from(SubscriptionItem item) {
            SubscriptionItemType t = item.getItemType();
            return new ItemResponse(
                item.getId(),
                t.name(),
                t.getDisplayName(),
                t.getBillingUnit(),
                item.getIncludedQuantity(),
                item.getUnitPriceCents(),
                item.getMetadata());
        }
    }
}
