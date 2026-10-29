package io.tapstate.cloud.version;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster version catalog endpoints.
 * <p>
 * The list endpoint is a normal authenticated console API used when choosing a
 * version for a new subscription. Publishing and listing-state changes are
 * operator automation endpoints: their paths are permitAll in Spring Security
 * and the controller authenticates a static Bearer token itself, the same model
 * as the C2 status-report endpoint. When no token is configured the endpoints
 * fail closed with 503.
 */
@RestController
@RequestMapping("/v1/api/cluster-versions")
@Slf4j
@Tag(name = "Cluster Versions", description = "Published Cluster versions — listing for console, publish/delist for operators")
public class ClusterVersionController extends BaseController {

    private final ClusterVersionService clusterVersionService;
    private final String releaseToken;

    public ClusterVersionController(
            ClusterVersionService clusterVersionService,
            @Value("${tapstate.cloud.cluster-version.release-token:}") String releaseToken) {
        this.clusterVersionService = clusterVersionService;
        this.releaseToken = releaseToken;
    }

    @GetMapping
    @Operation(operationId = "listClusterVersions", summary = "List active Cluster versions, newest first",
        description = "Returns every ACTIVE Cluster version ordered by descending versionNumber; "
            + "the first row is the latest version and the default for new subscriptions.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active versions, newest first"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<ApiResponse<List<ClusterVersionResponse>>> list() {
        List<ClusterVersionResponse> versions = clusterVersionService.listActiveVersions().stream()
            .map(ClusterVersionResponse::from)
            .toList();
        return ok(versions);
    }

    @PostMapping
    @Hidden
    @Operation(summary = "Publish a new Cluster version (operator static token)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Version published"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request body"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Release token missing or invalid"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Version already exists"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Release endpoint not configured")
    })
    public ResponseEntity<ApiResponse<ClusterVersionResponse>> release(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ReleaseClusterRequest request) {
        requireReleaseToken(authorization);
        ClusterVersion published = clusterVersionService.releaseCluster(request);
        return okCreated(ClusterVersionResponse.from(published));
    }

    @PatchMapping("/{version}/state")
    @Hidden
    @Operation(summary = "List or delist a Cluster version (operator static token)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "State updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request body"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Release token missing or invalid"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Version not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Release endpoint not configured")
    })
    public ResponseEntity<ApiResponse<ClusterVersionResponse>> updateState(
            @Parameter(description = "Version string") @PathVariable String version,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody UpdateVersionStateRequest request) {
        requireReleaseToken(authorization);
        ClusterVersion updated = clusterVersionService.updateState(version, request.state());
        return ok(ClusterVersionResponse.from(updated));
    }

    /**
     * Authenticate the operator static Bearer token. Fail closed: an unset
     * configured token disables the endpoints (503); missing/malformed header
     * is 401, wrong token is 401. Comparison is constant-time.
     */
    private void requireReleaseToken(String authorization) {
        if (releaseToken == null || releaseToken.isBlank()) {
            throw new BusinessException(AppErrorCode.CLUSTER_VERSION_RELEASE_DISABLED,
                HttpStatus.SERVICE_UNAVAILABLE.value());
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(AppErrorCode.CLUSTER_VERSION_RELEASE_TOKEN_MISSING,
                HttpStatus.UNAUTHORIZED.value());
        }
        String presented = authorization.substring("Bearer ".length()).trim();
        byte[] expected = releaseToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException(AppErrorCode.CLUSTER_VERSION_RELEASE_TOKEN_INVALID,
                HttpStatus.UNAUTHORIZED.value());
        }
    }
}
