package io.tapstate.cloud.cluster;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * C2 SDK status-report endpoint — runtime Cluster → Global Control Plane
 * heartbeat + metrics.
 * <p>
 * Auth model (ADR-0124 D3 — C2 contract):
 * <ul>
 *   <li>HTTP method: POST</li>
 *   <li>Header: {@code Authorization: Bearer <sdk-static-token>} — per-Cluster unique
 *       string stored on {@link Cluster#sdkStaticToken} at provisioning time</li>
 *   <li>Path: {@code /v1/api/clusters/{clusterId}/status-report}</li>
 * </ul>
 * This endpoint is <b>publicly reachable</b> in Spring Security (no session cookie
 * required). The Bearer token inside is the sole authentication mechanism — each
 * runtime carries its Cluster-specific static token and GC validates it on every call.
 * <p>
 * Nonce replay protection: every POST must include a unique {@code nonce} string.
 * The GC records it on the Cluster document and rejects duplicates with 409
 * {@code auth.nonce-replay}.
 * <p>
 * Data minimization: this endpoint only accepts infrastructure-level health
 * metrics — runtime ID, uptime, active pipeline count, last heartbeat time.
 * It explicitly does NOT carry user data, Source/Pipeline configs, Atlas credentials
 * or any other business state.
 */
@RestController
@RequestMapping("/v1/api/clusters")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "C2 Status Report", description = "Runtime → Global Control Plane heartbeat + health metrics (SDK Bearer auth)")
public class StatusReportController extends BaseController {

    private final ClusterService clusterService;

    /**
     * C2 status report — runtime SDK calls this periodically.
     *
     * @param clusterId Path variable (must match the token's owning Cluster — double validation)
     * @param authHeader {@code Authorization: Bearer <sdk-static-token>}
     * @param request Request body with runtime metrics (nonce required, all others optional)
     */
    @PostMapping("/{clusterId}/status-report")
    @Operation(
        summary = "Runtime C2 status report (heartbeat + metrics)",
        description = "Runtime SDK posts heartbeat + infrastructure-level health metrics.\n\n" +
                      "Authentication: Bearer token in Authorization header — the Cluster's " +
                      "per-Cluster sdk-static-token (issued at provisioning time).\n\n" +
                      "Nonce replay protection: Every request must carry a unique nonce string. " +
                      "Duplicate nonce → 409 auth.nonce-replay.\n\n" +
                      "Data minimization: Only infrastructure metrics accepted " +
                      "(runtime version, uptime, active pipeline count, last error time). " +
                      "No user data, Source/Pipeline configs, or Atlas credentials flow through this endpoint.\n\n" +
                      "Effects: Updates Cluster.lastHeartbeatAt; auto-transitions state " +
                      "DEGRADED → READY on heartbeat recovery (5min window)."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status report accepted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "SDK token missing or invalid (auth.sdk-token-invalid)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Token does not match path clusterId (auth.cluster-mismatch)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Nonce replay (auth.nonce-replay)")
    })
    public ResponseEntity<ApiResponse<StatusReportResponse>> report(
            @Parameter(description = "Cluster UUID — must match the Bearer token's owning Cluster")
            @PathVariable String clusterId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody StatusReportRequest request) {

        // --- 1. Validate Bearer token (runtime → GC identity) ---
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(AppErrorCode.SDK_TOKEN_MISSING, HttpStatus.UNAUTHORIZED);
        }
        String token = authHeader.substring(7).trim();
        Cluster cluster = clusterService.getBySdkToken(token);
        if (!cluster.getId().equals(clusterId)) {
            // Token belongs to a DIFFERENT cluster than the path variable — reject
            log.warn("[C2] Token mismatch: path.clusterId={}, token.owns={}", clusterId, cluster.getId());
            throw new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, HttpStatus.UNAUTHORIZED);
        }

        // --- 2. Record heartbeat + nonce replay check + state transition ---
        clusterService.recordStatusReport(clusterId, request.nonce());

        log.debug("[C2] Status report from cluster={} uptimeMs={} pipelines={} nonce={}",
            clusterId, request.uptimeMs(), request.activePipelines(), request.nonce());

        return ok(new StatusReportResponse("accepted"));
    }

    /**
     * Minimal C2 status-report payload — every field is intentionally infrastructure-level
     * only. User-facing data (Source/Pipeline config, Atlas credentials, business state)
     * must NEVER flow through this endpoint.
     *
     * @param nonce          Client-generated unique string — MUST change on every request.
     *                       Same value twice → 409 auth.nonce-replay.
     * @param runtimeVersion Tapstate runtime version string (e.g. "2.1.0")
     * @param uptimeMs       Milliseconds since runtime process started
     * @param activePipelines Currently-running pipeline count (runtime knows its own)
     * @param lastErrorAt    ISO-8601 UTC instant of last runtime error, or null if clean
     */
    public record StatusReportRequest(
            @Parameter(description = "Unique nonce per request — MUST change every call. Same value twice → 409 auth.nonce-replay", required = true)
            String nonce,
            @Parameter(description = "Tapstate runtime version (e.g. '2.1.0')")
            String runtimeVersion,
            @Parameter(description = "Milliseconds since runtime process started")
            Long uptimeMs,
            @Parameter(description = "Currently running pipeline count")
            Integer activePipelines,
            @Parameter(description = "ISO-8601 UTC instant of last runtime error, null if none")
            String lastErrorAt) {}

    @Schema(description = "Status report acknowledgment")
    public record StatusReportResponse(@Schema(description = "Always 'accepted' on success") String status) {}
}
