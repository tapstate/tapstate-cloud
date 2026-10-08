package io.tapstate.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class SampleSourceConfigControllerTest {

    @Test
    void credentialsAreAvailableOnlyToTheReadyClusterAndAreNotCacheable() {
        ClusterService clusters = mock(ClusterService.class);
        ObjectId clusterId = new ObjectId();
        Cluster cluster = Cluster.builder().id(clusterId).state(ClusterState.READY).build();
        when(clusters.getBySdkToken("runtime-token")).thenReturn(cluster);
        SampleSourceConfigController controller =
                new SampleSourceConfigController(clusters, "sample.internal", "server-secret");

        var availability = controller.availability(clusterId.toHexString(), "Bearer runtime-token");
        var credentials = controller.credentials(clusterId.toHexString(), "Bearer runtime-token");

        assertTrue(availability.getBody().getData().available());
        assertEquals("server-secret", credentials.getBody().getData().password());
        assertTrue(credentials.getHeaders().getCacheControl().contains("no-store"));
        assertEquals("no-cache", credentials.getHeaders().getFirst(HttpHeaders.PRAGMA));
        assertFalse(credentials.getBody().getData().toString().contains("server-secret"));
    }

    @Test
    void refusesCredentialsWhenTheClusterIsNotReady() {
        ClusterService clusters = mock(ClusterService.class);
        ObjectId clusterId = new ObjectId();
        when(clusters.getBySdkToken("runtime-token"))
                .thenReturn(Cluster.builder().id(clusterId).state(ClusterState.PROVISIONING).build());
        SampleSourceConfigController controller =
                new SampleSourceConfigController(clusters, "sample.internal", "server-secret");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.credentials(clusterId.toHexString(), "Bearer runtime-token"));

        assertEquals(HttpStatus.CONFLICT.value(), failure.getHttpStatus());
    }

    @Test
    void refusesAValidTokenWhenItBelongsToAnotherCluster() {
        ClusterService clusters = mock(ClusterService.class);
        ObjectId ownerId = new ObjectId();
        ObjectId requestedId = new ObjectId();
        when(clusters.getBySdkToken("runtime-token"))
                .thenReturn(Cluster.builder().id(ownerId).state(ClusterState.READY).build());
        SampleSourceConfigController controller =
                new SampleSourceConfigController(clusters, "sample.internal", "server-secret");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.credentials(requestedId.toHexString(), "Bearer runtime-token"));

        assertEquals(HttpStatus.UNAUTHORIZED.value(), failure.getHttpStatus());
    }

    @Test
    void reportsUnavailableWithoutReturningCredentialsWhenPasswordIsNotConfigured() {
        ClusterService clusters = mock(ClusterService.class);
        ObjectId clusterId = new ObjectId();
        when(clusters.getBySdkToken("runtime-token"))
                .thenReturn(Cluster.builder().id(clusterId).state(ClusterState.READY).build());
        SampleSourceConfigController controller =
                new SampleSourceConfigController(clusters, "sample.internal", " ");

        var availability = controller.availability(clusterId.toHexString(), "Bearer runtime-token");

        assertFalse(availability.getBody().getData().available());
        BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.credentials(clusterId.toHexString(), "Bearer runtime-token"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), failure.getHttpStatus());
        assertFalse(failure.getMessage().contains("server-secret"));
    }
}
