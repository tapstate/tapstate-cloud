package io.tapstate.cloud.cluster;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Cluster read-only + retry endpoints.
 * <p>
 * Cluster creation and cancellation are NOT exposed here — they are managed
 * through the Subscription lifecycle (POST /subscription creates Cluster +
 * MDB allocation; POST /subscription/cancel cascades termination of all
 * org Clusters and MDB allocations).
 * <p>
 * This controller exposes list, get-by-id, and retry-provisioning_failed.
 * Access requires Cloud Console session (ADR-0122 D2).
 */
@RestController
@RequestMapping("/v1/api/orgs/{orgId}/clusters")
@RequiredArgsConstructor
@Tag(name = "Clusters", description = "Cluster read + retry — lifecycle managed via Subscription")
public class ClusterController extends BaseController {

    private final ClusterService clusterService;

    @GetMapping
    @Operation(
        summary = "List clusters in organization",
        description = "Returns all Clusters owned by this Organization that the current user has access to. " +
                      "Requires a valid Cloud Console session."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of Clusters"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization")
    })
    public ResponseEntity<ApiResponse<List<ClusterResponse>>> listClusters(
            @Parameter(description = "Organization UUID") @PathVariable String orgId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        List<Cluster> clusters = clusterService.listOrgClusters(orgId, userEmail);
        return ok(clusters.stream().map(ClusterResponse::from).toList());
    }

    @GetMapping("/{clusterId}")
    @Operation(
        summary = "Get Cluster by ID",
        description = "Returns detailed Cluster info including state, provisioning error code (if failed), " +
                      "runtime metadata (public DNS, Elastic IP, runtime ID), and heartbeat timestamps. " +
                      "Requires Cloud session + Organization membership + Cluster ownership."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cluster details"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member of this Organization or not cluster owner"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Cluster not found")
    })
    public ResponseEntity<ApiResponse<ClusterResponse>> getCluster(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Parameter(description = "Cluster UUID") @PathVariable String clusterId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        Cluster cluster = clusterService.getCluster(clusterId, orgId, userEmail);
        return ok(ClusterResponse.from(cluster));
    }

    @PostMapping("/{clusterId}/retry")
    @Operation(
        summary = "Retry provisioning_failed Cluster",
        description = "Retries a Cluster that previously failed provisioning (state = PROVISIONING_FAILED).\n\n" +
                      "StateMachine cleanup flow: clears residual resources from the failed attempt " +
                      "→ re-triggers the full provisioning pipeline from PROVISIONING_PENDING.\n\n" +
                      "Returns 409 if the Cluster is not in PROVISIONING_FAILED state."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Retry initiated, state reset to PROVISIONING_PENDING"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not cluster owner"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Cluster not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cluster not in PROVISIONING_FAILED state")
    })
    public ResponseEntity<ApiResponse<ClusterResponse>> retryCluster(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Parameter(description = "Cluster UUID") @PathVariable String clusterId) {
        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        Cluster cluster = clusterService.retryCluster(clusterId, orgId, userEmail);
        return ok(ClusterResponse.from(cluster));
    }

    // ========== Response DTO ==========

    public record ClusterResponse(
        @Schema(description = "Cluster UUID") String id,
        @Schema(description = "Organization UUID") String orgId,
        @Schema(description = "AWS Region code", example = "ap-southeast-1") String region,
        @Schema(description = "Cluster display name") String name,
        @Schema(description = "Provisioning spec_key (e.g. 'basic')") String specKey,
        @Schema(description = "Cluster state: PROVISIONING_PENDING / PROVISIONING / READY / PROVISIONING_FAILED / DEGRADED / DRAINING / TERMINATED") ClusterState state,
        @Schema(description = "Provisioning failure error code (null if not failed)") ProvisionErrorCode provisionErrorCode,
        @Schema(description = "Pipeline Runtime ID (EC2 instance identifier)") String runtimeId,
        @Schema(description = "Public DNS name (Route53 alias) for Cluster Console") String publicDns,
        @Schema(description = "Elastic IP — used for Atlas allowlist") String elasticIp,
        @Schema(description = "Last C2 heartbeat timestamp (ISO-8601 UTC)") String lastHeartbeatAt,
        @Schema(description = "Cluster creation timestamp (ISO-8601 UTC)") String createdAt,
        @Schema(description = "Ready timestamp (ISO-8601 UTC) — null until state = READY") String readyAt
    ) {
        public static ClusterResponse from(Cluster c) {
            return new ClusterResponse(
                c.getId() != null ? c.getId().toHexString() : null, c.getOrgId() != null ? c.getOrgId().toHexString() : null,
                c.getRegion(), c.getName(), c.getSpecKey(),
                c.getState(), c.getProvisionErrorCode(),
                c.getRuntimeId(), c.getPublicDns(), c.getElasticIp(),
                c.getLastHeartbeatAt() != null ? c.getLastHeartbeatAt().toString() : null,
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getReadyAt() != null ? c.getReadyAt().toString() : null);
        }
    }
}
