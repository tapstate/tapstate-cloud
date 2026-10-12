package io.tapstate.cloud.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClusterVersionController} token handling: missing or
 * bad Bearer credentials are 401, an unset configured token fails closed with
 * 503, a correct token reaches the service, and the console GET list needs no
 * release token.
 */
class ClusterVersionControllerAuthTest {

    private static final String TOKEN = "correct-release-token";

    private final ClusterVersionService service = mock(ClusterVersionService.class);
    private final ClusterVersionController controller =
        new ClusterVersionController(service, TOKEN);

    private ReleaseClusterRequest request() {
        return new ReleaseClusterRequest("1.4.0", ClusterReleaseCode.STABLE, "notes",
            GitInfo.builder().branch("main").commitId("abc123")
                .extra(Map.of("tag", "v1.4.0")).build());
    }

    private ClusterVersion version140() {
        return ClusterVersion.builder()
            .id(new ObjectId())
            .version("1.4.0")
            .versionNumber(3L)
            .releaseCode(ClusterReleaseCode.STABLE)
            .state(ClusterVersionState.ACTIVE)
            .git(GitInfo.builder().branch("main").commitId("abc123").build())
            .build();
    }

    @Test
    void postWithoutAuthorizationHeaderIs401() {
        BusinessException failure = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class, () -> controller.release(null, request()));
        assertEquals(401, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_RELEASE_TOKEN_MISSING, failure.getErrorCode());
        verify(service, never()).releaseCluster(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void postWithNonBearerHeaderIs401() {
        BusinessException failure = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class, () -> controller.release("Basic " + TOKEN, request()));
        assertEquals(401, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_RELEASE_TOKEN_MISSING, failure.getErrorCode());
    }

    @Test
    void postWithWrongTokenIs401() {
        BusinessException failure = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class, () -> controller.release("Bearer wrong-token", request()));
        assertEquals(401, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_RELEASE_TOKEN_INVALID, failure.getErrorCode());
    }

    @Test
    void postWithBlankConfiguredTokenFailsClosed503() {
        ClusterVersionController disabled = new ClusterVersionController(service, "  ");

        BusinessException failure = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class,
            () -> disabled.release("Bearer " + TOKEN, request()));
        assertEquals(503, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_RELEASE_DISABLED, failure.getErrorCode());
    }

    @Test
    void postWithCorrectTokenPublishes201() {
        ClusterVersion published = version140();
        when(service.releaseCluster(org.mockito.ArgumentMatchers.any())).thenReturn(published);

        var response = controller.release("Bearer " + TOKEN, request());

        assertEquals(201, response.getStatusCode().value());
        assertEquals("1.4.0", response.getBody().getData().version());
        assertEquals(3L, response.getBody().getData().versionNumber());
    }

    @Test
    void patchWithCorrectTokenUpdatesState200() {
        ClusterVersion offlined = version140();
        offlined.setState(ClusterVersionState.OFFLINE);
        when(service.updateState("1.4.0", ClusterVersionState.OFFLINE)).thenReturn(offlined);

        var response = controller.updateState("1.4.0", "Bearer " + TOKEN,
            new UpdateVersionStateRequest(ClusterVersionState.OFFLINE));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(ClusterVersionState.OFFLINE, response.getBody().getData().state());
    }

    @Test
    void patchWithWrongTokenIs401() {
        BusinessException failure = org.junit.jupiter.api.Assertions.assertThrows(
            BusinessException.class,
            () -> controller.updateState("1.4.0", "Bearer nope",
                new UpdateVersionStateRequest(ClusterVersionState.OFFLINE)));
        assertEquals(401, failure.getHttpStatus());
    }

    @Test
    void getListNeedsNoReleaseTokenAndReturnsActiveVersions() {
        when(service.listActiveVersions()).thenReturn(List.of(version140()));

        var response = controller.list();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("1.4.0", response.getBody().getData().get(0).version());
    }
}
