package io.tapstate.cloud.cluster;

import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.BaseController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Back-channel CONFIG initialization; the service verifies the Cluster secret before reading or changing its authority. */
@RestController
@RequestMapping("/v1/api/clusters/{clusterId}/configuration-initialization")
public class ClusterConfigurationInitializationController extends BaseController {
    private final ClusterConfigurationInitializationService service;
    public ClusterConfigurationInitializationController(ClusterConfigurationInitializationService service) { this.service = service; }
    public record Claim(String bootId, String configurationHash) {}
    public record Completion(String operationId, String configurationHash) {}

    @PostMapping("/claim")
    @io.swagger.v3.oas.annotations.Operation(operationId = "claimConfigurationInitialization", summary = "Claim the original CONFIG initialization")
    public ResponseEntity<ApiResponse<ClusterConfigurationInitializationService.Result>> claim(@PathVariable String clusterId,
            @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Claim request) {
        return ok(service.claim(clusterId, secret(authorization), request.bootId(), request.configurationHash()));
    }
    @PostMapping("/complete")
    @io.swagger.v3.oas.annotations.Operation(operationId = "completeConfigurationInitialization", summary = "Confirm the original CONFIG initialization")
    public ResponseEntity<ApiResponse<ClusterConfigurationInitializationService.Result>> complete(@PathVariable String clusterId,
            @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Completion request) {
        return ok(service.complete(clusterId, secret(authorization), request.operationId(), request.configurationHash()));
    }
    private static String secret(String value) { return value != null && value.startsWith("Bearer ") ? value.substring(7) : null; }
}
