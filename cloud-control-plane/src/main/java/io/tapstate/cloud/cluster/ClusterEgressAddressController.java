package io.tapstate.cloud.cluster;

import io.swagger.v3.oas.annotations.Operation;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.common.api.OpIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Reads current address metadata using the runtime's existing per-Cluster identity. */
@RestController
@RequestMapping("/v1/api/clusters")
@RequiredArgsConstructor
public class ClusterEgressAddressController extends BaseController {
    private final ClusterService clusters;

    @GetMapping("/{clusterId}/egress-address")
    @Operation(summary = "Read the runtime Cluster's current outbound address")
    public ResponseEntity<ApiResponse<Address>> current(@PathVariable String clusterId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(AppErrorCode.SDK_TOKEN_MISSING, 401);
        }
        Cluster cluster = clusters.getBySdkToken(authorization.substring(7).trim());
        if (!cluster.getId().toHexString().equals(clusterId)) {
            throw new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, 401);
        }
        ClusterEgressAddressStatus status = cluster.getEgressAddressStatus() == null
            ? ClusterEgressAddressStatus.DYNAMIC : cluster.getEgressAddressStatus();
        boolean live = cluster.getState() == ClusterState.READY || cluster.getState() == ClusterState.DEGRADED;
        if (!live) {
            status = cluster.getState() == ClusterState.PROVISIONING
                    || cluster.getState() == ClusterState.PROVISIONING_PENDING
                ? ClusterEgressAddressStatus.ATTACHING : ClusterEgressAddressStatus.FAILED;
        }
        Address address = new Address(cluster.getOrgId().toHexString(), clusterId,
            live ? cluster.getEc2PublicIp() : null, status.name());
        ApiResponse<Address> response = ApiResponse.ok(address);
        response.setOpId(OpIdHolder.get());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    public record Address(String organizationId, String clusterId, String ec2PublicIp, String egressAddressStatus) { }
}
