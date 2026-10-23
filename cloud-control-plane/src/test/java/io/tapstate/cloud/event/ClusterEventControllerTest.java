package io.tapstate.cloud.event;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClusterEventControllerTest {

    @Mock
    private ClusterService clusterService;
    @Mock
    private EventCenterService eventCenterService;

    private ClusterEventController controller;

    private final ObjectId orgId = new ObjectId();
    private final ObjectId clusterId = new ObjectId();

    @BeforeEach
    void setUp() {
        controller = new ClusterEventController(clusterService, eventCenterService);
    }

    private Cluster cluster() {
        return Cluster.builder()
            .id(clusterId)
            .orgId(orgId)
            .name("acme-prod")
            .sdkStaticToken("sdk-token")
            .state(ClusterState.READY)
            .build();
    }

    @Test
    @DisplayName("an authenticated Cluster event is ingested under its own org and the cluster source")
    void ingestsWithDerivedOrgAndSource() {
        when(clusterService.getBySdkToken("sdk-token")).thenReturn(cluster());
        PlatformEvent stored = PlatformEvent.builder()
            .id(new ObjectId())
            .orgId(orgId)
            .source("cluster")
            .type("cluster.task.failed")
            .severity(EventSeverity.CRITICAL)
            .occurredAt(Instant.now())
            .build();
        when(eventCenterService.ingest(any(EventIngestRequest.class))).thenReturn(stored);

        var response = controller.ingest(clusterId.toHexString(), "Bearer sdk-token",
            new ClusterEventRequest("cluster.task.failed", EventSeverity.CRITICAL, "Task failed",
                Instant.now(), "task-1-failed",
                Map.of("taskId", "t-1", "reason", "source unreachable", "clusterId", "forged")));

        ArgumentCaptor<EventIngestRequest> captor = ArgumentCaptor.forClass(EventIngestRequest.class);
        verify(eventCenterService).ingest(captor.capture());
        EventIngestRequest request = captor.getValue();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(request.orgId()).isEqualTo(orgId.toHexString());
        assertThat(request.source()).isEqualTo("cluster");
        assertThat(request.type()).isEqualTo("cluster.task.failed");
        assertThat(request.severity()).isEqualTo(EventSeverity.CRITICAL);
        assertThat(request.dedupeKey()).isEqualTo("task-1-failed");
        assertThat(request.payload()).containsEntry("taskId", "t-1")
            .containsEntry("reason", "source unreachable")
            .containsEntry("clusterId", clusterId.toHexString())
            .containsEntry("clusterName", "acme-prod");
    }

    @Test
    @DisplayName("a token belonging to another Cluster cannot push events for the path Cluster")
    void rejectsTokenOfAnotherCluster() {
        when(clusterService.getBySdkToken("sdk-token")).thenReturn(cluster());

        assertThatThrownBy(() -> controller.ingest(new ObjectId().toHexString(), "Bearer sdk-token",
            new ClusterEventRequest("cluster.task.failed", EventSeverity.WARNING, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .satisfies(throwable -> {
                BusinessException error = (BusinessException) throwable;
                assertThat(error.getErrorCode()).isEqualTo(io.tapstate.cloud.common.api.AppErrorCode.SDK_TOKEN_INVALID);
                assertThat(error.getHttpStatus()).isEqualTo(401);
            });
        verify(eventCenterService, never()).ingest(any());
    }

    @Test
    @DisplayName("a missing Bearer header is rejected before any lookup")
    void rejectsMissingBearer() {
        assertThatThrownBy(() -> controller.ingest(clusterId.toHexString(), null,
            new ClusterEventRequest("cluster.task.failed", EventSeverity.WARNING, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .satisfies(throwable -> {
                BusinessException error = (BusinessException) throwable;
                assertThat(error.getErrorCode()).isEqualTo(io.tapstate.cloud.common.api.AppErrorCode.SDK_TOKEN_MISSING);
                assertThat(error.getHttpStatus()).isEqualTo(401);
            });
    }

    @Test
    @DisplayName("an event type outside the cluster namespace is rejected")
    void rejectsTypeOutsideClusterNamespace() {
        when(clusterService.getBySdkToken("sdk-token")).thenReturn(cluster());

        assertThatThrownBy(() -> controller.ingest(clusterId.toHexString(), "Bearer sdk-token",
            new ClusterEventRequest("billing.invoice.failed", EventSeverity.WARNING, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .satisfies(throwable -> assertThat(
                ((BusinessException) throwable).getErrorCode())
                .isEqualTo(io.tapstate.cloud.common.api.AppErrorCode.EVENT_INVALID));
        verify(eventCenterService, never()).ingest(any());
    }
}
