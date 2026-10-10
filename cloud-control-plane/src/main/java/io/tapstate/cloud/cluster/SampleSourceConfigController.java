package io.tapstate.cloud.cluster;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Server-to-server access to the shared sample database configuration. */
@RestController
@Hidden
@RequestMapping("/v1/api/clusters/{clusterId}/sample-sources")
@Tag(name = "Sample source runtime configuration",
     description = "Cluster Server-only access to shared sample-source configuration")
public class SampleSourceConfigController extends BaseController {

    private final ClusterService clusterService;
    private final String host;
    private final String password;

    public SampleSourceConfigController(ClusterService clusterService,
            @Value("${tapstate.cloud.sample-sources.host:47.238.126.103}") String host,
            @Value("${tapstate.cloud.sample-sources.password:}") String password) {
        this.clusterService = clusterService;
        this.host = host;
        this.password = password;
    }

    @GetMapping("/availability")
    @Operation(summary = "Check whether shared sample sources are configured",
               description = "Called by the authenticated Cluster Server. Never returns sample credentials.")
    public ResponseEntity<ApiResponse<Availability>> availability(
            @PathVariable String clusterId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireReadyCluster(clusterId, authorization);
        return noStore(new Availability(configured()));
    }

    @PostMapping("/credentials")
    @Operation(summary = "Fetch shared sample source credentials",
               description = "Server-to-server only. The response is private, not cached, and must never be forwarded to a browser.")
    public ResponseEntity<ApiResponse<Credentials>> credentials(
            @PathVariable String clusterId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireReadyCluster(clusterId, authorization);
        if (!configured()) {
            throw new BusinessException(AppErrorCode.SAMPLE_SOURCE_CREDENTIALS_NOT_CONFIGURED,
                    HttpStatus.SERVICE_UNAVAILABLE.value());
        }
        return noStore(new Credentials(host, password));
    }

    private Cluster requireReadyCluster(String clusterId, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(AppErrorCode.SDK_TOKEN_MISSING, HttpStatus.UNAUTHORIZED.value());
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Cluster cluster = clusterService.getBySdkToken(token);
        if (cluster.getId() == null || !cluster.getId().toHexString().equals(clusterId)) {
            throw new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, HttpStatus.UNAUTHORIZED.value());
        }
        if (cluster.getState() != ClusterState.READY) {
            throw new BusinessException(AppErrorCode.CLUSTER_INVALID_STATE, HttpStatus.CONFLICT.value(), cluster.getState());
        }
        return cluster;
    }

    private boolean configured() {
        return host != null && !host.isBlank() && password != null && !password.isBlank();
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(T data) {
        ResponseEntity<ApiResponse<T>> response = ok(data);
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers -> {
                    headers.putAll(response.getHeaders());
                    headers.setCacheControl(CacheControl.noStore());
                    headers.setPragma("no-cache");
                })
                .body(response.getBody());
    }

    public record Availability(boolean available) { }

    public record Credentials(String host, String password) {
        @Override
        public String toString() {
            return "Credentials[host=" + host + ", password=<redacted>]";
        }
    }
}
