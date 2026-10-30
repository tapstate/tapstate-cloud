package io.tapstate.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatusReportController} authentication status codes.
 * <p>
 * Guards against Java enum-to-int overloading ambiguity (HttpStatus.UNAUTHORIZED
 * would match the Object-param BusinessException constructor and silently default
 * to HTTP 400 instead of 401/403). Regression test for tapstate-cloud#78.
 */
@DisplayName("StatusReportControllerAuthTest — authentication HTTP statuses")
class StatusReportControllerAuthTest {

    private static final String PATH_CLUSTER_ID = new ObjectId().toHexString();
    private static final String DIFFERENT_CLUSTER_ID = new ObjectId().toHexString();
    private static final String VALID_TOKEN = "valid-static-token";
    private static final String UNKNOWN_TOKEN = "unknown-token";

    private final ClusterService clusterService = mock(ClusterService.class);
    private final StatusReportController controller = new StatusReportController(clusterService);

    private final StatusReportRequest request = StatusReportRequest.builder()
        .nonce("nonce-1")
        .runtimeVersion("2.1.0")
        .uptimeMs(12345L)
        .activePipelines(1)
        .build();

    private Cluster clusterOwnedBy(String hexId, String token) {
        Cluster c = new Cluster();
        c.setId(new ObjectId(hexId));
        c.setSdkStaticToken(token);
        return c;
    }

    @Test
    @DisplayName("missing Bearer → HTTP 401 sdk.token-missing")
    void missingBearer_returns401() {
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class,
            () -> controller.report(PATH_CLUSTER_ID, null, request));

        assertEquals(401, ex.getHttpStatus(), "Missing Bearer must be 401, not 400");
        assertSame(AppErrorCode.SDK_TOKEN_MISSING, ex.getErrorCode());
        verify(clusterService, never()).recordStatusReport(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("non-Bearer header → HTTP 401 sdk.token-missing")
    void nonBearerHeader_returns401() {
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class,
            () -> controller.report(PATH_CLUSTER_ID, "Basic abc", request));

        assertEquals(401, ex.getHttpStatus());
        assertSame(AppErrorCode.SDK_TOKEN_MISSING, ex.getErrorCode());
        verify(clusterService, never()).recordStatusReport(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("unknown token → HTTP 401 sdk.token-invalid (from ClusterService)")
    void unknownToken_returns401() {
        when(clusterService.getBySdkToken(UNKNOWN_TOKEN)).thenThrow(
            new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, 401));

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class,
            () -> controller.report(PATH_CLUSTER_ID, "Bearer " + UNKNOWN_TOKEN, request));

        assertEquals(401, ex.getHttpStatus(), "Unknown token must be 401");
        assertSame(AppErrorCode.SDK_TOKEN_INVALID, ex.getErrorCode());
        verify(clusterService, never()).recordStatusReport(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("token owned by different cluster than path variable → HTTP 403 sdk.token-invalid")
    void tokenPathMismatch_returns403() {
        Cluster tokenOwner = clusterOwnedBy(DIFFERENT_CLUSTER_ID, VALID_TOKEN);
        when(clusterService.getBySdkToken(VALID_TOKEN)).thenReturn(tokenOwner);

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class,
            () -> controller.report(PATH_CLUSTER_ID, "Bearer " + VALID_TOKEN, request));

        assertEquals(403, ex.getHttpStatus(),
            "Token belongs to different cluster → 403 (authenticated but unauthorized resource), not 400");
        assertSame(AppErrorCode.SDK_TOKEN_INVALID, ex.getErrorCode());
        verify(clusterService, never()).recordStatusReport(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("valid token matching path clusterId → 200 accepted")
    void validToken_matchesPath_returns200() {
        Cluster matching = clusterOwnedBy(PATH_CLUSTER_ID, VALID_TOKEN);
        when(clusterService.getBySdkToken(VALID_TOKEN)).thenReturn(matching);

        var response = controller.report(PATH_CLUSTER_ID, "Bearer " + VALID_TOKEN, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("accepted", response.getBody().getData().status());
        verify(clusterService).recordStatusReport(PATH_CLUSTER_ID, request);
    }
}
