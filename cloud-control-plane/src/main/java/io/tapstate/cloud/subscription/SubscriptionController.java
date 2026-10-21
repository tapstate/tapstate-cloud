package io.tapstate.cloud.subscription;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
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
 * The only way to obtain a subscription is the paid flow: create a Stripe
 * Checkout session, let the webhook activate it, then finalize provisioning.
 * There is deliberately no endpoint that creates a subscription, or moves one
 * into a provisionable state, without the payment channel confirming it.
 * <p>
 * Read endpoints stay open to every member of the Organization; cancelling is
 * self-serve at period end. Poll GET /subscriptions/{subscriptionId} to track
 * async provisioning.
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

    // ========== Checkout + period-end cancel + finalize ==============

    @PostMapping("/subscriptions/checkout")
    @Operation(
        summary = "Create a Stripe Checkout Session + PENDING_PAYMENT subscription shell",
        description = "Validates the spec exists (platform spec_config.enabled=true) and has Stripe pricing configured, " +
                      "then creates a hosted Stripe Checkout Session. Returns the redirect URL and a PENDING_PAYMENT " +
                      "Subscription shell. Cluster provisioning does NOT start until the Checkout Session completes " +
                      "(webhook-driven PENDING_PAYMENT→ACTIVE transition + activateAndProvision)."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Checkout session created — shell subscription + redirect URL"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid specKey or spec deprecated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate subscription (clientRequestId already used)")
    })
    public ResponseEntity<ApiResponse<CheckoutResponse>> createCheckout(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Valid @RequestBody CreateCheckoutRequest request) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        if (!organizationService.isMember(orgId, userEmail)) {
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);
        }

        SubscriptionService.CheckoutResult result = subscriptionService.createCheckoutSession(
                orgId, request.specKey(), userEmail, request.clientRequestId());

        CheckoutResponse resp = new CheckoutResponse(
                result.subscription().getId().toHexString(),
                result.subscription().getStatus().name(),
                result.sessionUrl());
        return okCreated(resp);
    }

    @PostMapping("/subscriptions/{subscriptionId}/cancel-at-period-end")
    @Operation(
        summary = "Cancel subscription at the next billing period end",
        description = "Stripe subscription is marked cancel_at_period_end=true. The subscription stays ACTIVE " +
                      "(PENDING_CANCEL) until the billing cycle expires, then auto-terminates via the " +
                      "PERIOD_END_REAPED scheduled reaper. No immediate Cluster termination."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> cancelAtPeriodEnd(
            @PathVariable String orgId, @PathVariable String subscriptionId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        if (!organizationService.isMember(orgId, userEmail))
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);

        Subscription updated = subscriptionService.cancelAtPeriodEnd(subscriptionId, orgId, "gc:" + userEmail);
        return ok(toResponse(updated));
    }

    @PostMapping("/subscriptions/{subscriptionId}/cancel-now")
    @Operation(
        summary = "Immediate subscription cancellation — no grace period",
        description = "Cancels the Stripe subscription immediately and transitions to CANCELLED, triggering " +
                      "cascade termination of the Cluster + reclaim of MdbAllocation."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> cancelNow(
            @PathVariable String orgId, @PathVariable String subscriptionId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        if (!organizationService.isMember(orgId, userEmail))
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);

        Subscription updated = subscriptionService.cancelNow(subscriptionId, orgId, "gc:" + userEmail);
        return ok(toResponse(updated));
    }

    @PostMapping("/subscriptions/{subscriptionId}/finalize-provisioning")
    @Operation(
        summary = "Finalize Cluster + MDB provisioning after Checkout confirmation",
        description = "Called by the Console after the user confirms on the Checkout success page. " +
                      "Requires a paid state (ACTIVE, or TRIAL when the spec carries a trial). " +
                      "Creates Cluster + MdbAllocation and kicks off async provisioning. " +
                      "An optional version pins the Cluster version; omitted resolves the latest ACTIVE version."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> finalizeProvisioning(
            @PathVariable String orgId,
            @PathVariable String subscriptionId,
            @Valid @RequestBody FinalizeProvisioningRequest request) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        if (!organizationService.isMember(orgId, userEmail))
            return fail(AppErrorCode.ORG_NOT_MEMBER, HttpStatus.FORBIDDEN, orgId);

        Subscription updated = subscriptionService.finalizeProvisioning(
                subscriptionId, request.region(), request.clusterName(), userEmail, request.version());
        return okCreated(toResponse(updated));
    }

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

    // ========== Checkout DTOs ==========

    public record CreateCheckoutRequest(
            @NotBlank @Size(max = 50)
            @Parameter(description = "Platform spec key", example = "2c8g")
            String specKey,
            @Size(max = 100)
            @Parameter(description = "Idempotency key — prevents double-checkout on retry")
            String clientRequestId
    ) {}

    public record FinalizeProvisioningRequest(
            @NotBlank @Size(max = 100)
            @Parameter(description = "AWS Region code", example = "ap-southeast-1")
            String region,
            @NotBlank @Size(min = 1, max = 100)
            @Parameter(description = "Cluster display name", example = "Production Pipeline")
            String clusterName,
            @Size(max = 100)
            @Parameter(description = "Cluster version to deploy; omit/blank to use the latest ACTIVE version",
                       example = "1.4.0")
            String version
    ) {}

    public record CheckoutResponse(
            @Schema(description = "PENDING_PAYMENT Subscription UUID") String subscriptionId,
            @Schema(description = "Subscription status (PENDING_PAYMENT)") String status,
            @Schema(description = "Stripe-hosted Checkout redirect URL — Console redirects the user here") String checkoutUrl
    ) {}
}
