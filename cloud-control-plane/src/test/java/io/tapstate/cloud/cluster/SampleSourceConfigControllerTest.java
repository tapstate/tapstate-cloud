package io.tapstate.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Optional;

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
                controller(clusters, "server-secret");

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
                controller(clusters, "server-secret");

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
                controller(clusters, "server-secret");

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
                controller(clusters, " ");

        var availability = controller.availability(clusterId.toHexString(), "Bearer runtime-token");

        assertFalse(availability.getBody().getData().available());
        BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.credentials(clusterId.toHexString(), "Bearer runtime-token"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), failure.getHttpStatus());
        assertFalse(failure.getMessage().contains("server-secret"));
    }

    private SampleSourceConfigController controller(ClusterService clusters, String password) {
        SampleSourceProfileRepository profiles = mock(SampleSourceProfileRepository.class);
        SampleSourceProfile profile = new SampleSourceProfile("sample_core_banking", "MySQL order sample",
                "Orders", "mysql", "sample.internal", 33306, "tpcc_331", null, "root",
                "shared-sample-password", "bmsql_oorder", "bmsql_order_line", "bmsql_customer",
                true, true, 10);
        when(profiles.findAllByOrderBySortOrderAsc()).thenReturn(List.of(profile));
        when(profiles.findById("sample_core_banking")).thenReturn(Optional.of(profile));
        return new SampleSourceConfigController(clusters, profiles, password);
    }
}
