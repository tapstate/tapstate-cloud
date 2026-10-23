package io.tapstate.cloud.event;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic runtime Cluster → event center ingestion.
 * <p>
 * A Cluster reports anything worth an alert through one contract — task failure
 * or interruption, data validation results, runtime degradation — and the event
 * center evaluates the organization's alert policies on the same path as every
 * other source. The organization is derived from the Cluster that owns the SDK
 * token; a caller can never address another organization.
 * <p>
 * Authentication mirrors the C2 status report: {@code Authorization: Bearer
 * <sdk-static-token>} plus a path {@code clusterId} that must match the token.
 */
@RestController
@RequestMapping("/v1/api/clusters/{clusterId}/events")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "C2 Cluster Events", description = "Runtime → event center event push (SDK Bearer auth)")
public class ClusterEventController extends BaseController {

    /** Namespace every Cluster event type must use. */
    static final String TYPE_PREFIX = "cluster.";
    static final String SOURCE = "cluster";

    private final ClusterService clusterService;
    private final EventCenterService eventCenterService;

    @PostMapping
    @Operation(
        summary = "Push a runtime Cluster event into the event center",
        description = "Accepts task failures/interruptions, data validation results and other runtime " +
                      "occurrences. The event is stored, then evaluated against the organization's " +
                      "enabled alert policies; matching policies create alerts and notification " +
                      "deliveries.\n\n" +
                      "Authentication: Bearer token in Authorization header — the Cluster's " +
                      "per-Cluster sdk-static-token. The organization and event source are derived " +
                      "from that token, never from the request body.\n\n" +
                      "Idempotency: reuse the same dedupeKey when retrying the same occurrence; a " +
                      "duplicate reports the stored event and does not re-evaluate policies.\n\n" +
                      "Supported types (any type under the cluster. namespace): " +
                      "cluster.task.failed, cluster.task.interrupted, cluster.validation.failed, " +
                      "cluster.validation.succeeded, cluster.runtime.degraded.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Event accepted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid event (event.invalid)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "SDK token missing or invalid (sdk.token-invalid)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cluster has no owning organization yet (cluster.invalid-state)")
    })
    public ResponseEntity<ApiResponse<EventResponse>> ingest(
            @Parameter(description = "Cluster ObjectId — must match the Bearer token's owning Cluster")
            @PathVariable String clusterId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ClusterEventRequest request) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(AppErrorCode.SDK_TOKEN_MISSING, HttpStatus.UNAUTHORIZED.value());
        }
        String token = authHeader.substring(7).trim();
        Cluster cluster = clusterService.getBySdkToken(token);
        if (!cluster.getId().toHexString().equals(clusterId)) {
            log.warn("[C2] Event token mismatch: path.clusterId={}, token.owns={}", clusterId, cluster.getId());
            throw new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, HttpStatus.UNAUTHORIZED.value());
        }
        if (cluster.getOrgId() == null) {
            throw new BusinessException(AppErrorCode.CLUSTER_INVALID_STATE, HttpStatus.CONFLICT.value(),
                cluster.getState());
        }
        String type = request.type() == null ? "" : request.type().trim();
        if (!type.startsWith(TYPE_PREFIX)) {
            throw new BusinessException(AppErrorCode.EVENT_INVALID, HttpStatus.BAD_REQUEST.value(),
                "type must start with '" + TYPE_PREFIX + "'");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.payload() != null) {
            payload.putAll(request.payload());
        }
        // The authenticated Cluster owns these values; body-supplied copies lose.
        payload.put("clusterId", cluster.getId().toHexString());
        if (cluster.getName() != null) {
            payload.put("clusterName", cluster.getName());
        }

        PlatformEvent event = eventCenterService.ingest(new EventIngestRequest(
            cluster.getOrgId().toHexString(),
            SOURCE,
            type,
            request.severity(),
            request.subject(),
            null,
            request.occurredAt(),
            request.dedupeKey(),
            payload));

        log.debug("[C2] Event {} type={} from cluster={}", event.getId(), event.getType(), clusterId);
        return ok(EventResponse.from(event));
    }
}
