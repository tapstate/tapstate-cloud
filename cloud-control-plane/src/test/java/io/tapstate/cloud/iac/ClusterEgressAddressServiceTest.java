package io.tapstate.cloud.iac;

import com.mongodb.client.result.UpdateResult;
import io.tapstate.cloud.cluster.*;
import io.tapstate.cloud.iac.model.NodeInstance;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClusterEgressAddressServiceTest {
    private final ClusterRepository clusters = mock(ClusterRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DnsInfrastructure dns = mock(DnsInfrastructure.class);
    private final LockProvider lockProvider = mock(LockProvider.class);
    private final ComputeInfrastructure compute = mock(ComputeInfrastructure.class);
    private final Cluster cluster = new Cluster();
    private ClusterEgressAddressService service;

    @BeforeEach
    void setUp() {
        cluster.setId(new ObjectId());
        cluster.setRegion("us-east-1");
        cluster.setState(ClusterState.READY);
        cluster.setEgressAddressStatus(ClusterEgressAddressStatus.ATTACHING);
        cluster.setEgressAddressAllocationId("eipalloc-owned");
        cluster.setEgressAddressOperationStartedAt(Instant.parse("2026-10-15T00:00:00Z"));
        cluster.setPublicDns("cluster.example.test");
        when(clusters.findById(cluster.getId())).thenReturn(Optional.of(cluster));
        when(compute.provider()).thenReturn(CloudProvider.AWS);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Cluster.class)))
            .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        ClusterDnsRecordCoordinator dnsRecords =
            new ClusterDnsRecordCoordinator(lockProvider, clusters, dns,
                new ClusterDomainName(new IacProperties()));
        service = new ClusterEgressAddressService(
            clusters, mongo, events, dnsRecords, Map.of("aws", compute), lockProvider, Runnable::run);
    }

    @Test
    void duplicateMigrationRequestsDoNotPublishOrAllocateAgain() {
        assertThat(service.requestStabilization(cluster)).isSameAs(cluster);
        verifyNoInteractions(events, mongo, dns);
        verify(compute, never()).stabilizeEgressAddress(anyString(), anyString(), any());
    }

    @Test
    void aLostLeaseOrDuplicateEventDoesNotModifyInfrastructure() {
        service.onStabilizationRequested(request());
        verify(compute, never()).stabilizeEgressAddress(anyString(), anyString(), any());
        verifyNoInteractions(dns);
    }

    @Test
    void migrationPublishesStableOnlyAfterTheOriginalAllocationAndDnsAreAttached() {
        claim();
        when(compute.stabilizeEgressAddress(anyString(), anyString(), eq("eipalloc-owned")))
            .thenReturn(new NodeInstance(CloudProvider.AWS, cluster.getId().toHexString(), "i-new",
                "203.0.113.42", "10.0.0.1", "eipalloc-owned"));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Cluster.class)))
            .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        service.onStabilizationRequested(request());
        var order = inOrder(compute, dns, mongo);
        order.verify(compute).stabilizeEgressAddress("us-east-1", cluster.getId().toHexString(), "eipalloc-owned");
        var observed = ArgumentCaptor.forClass(Update.class);
        order.verify(mongo).updateFirst(any(Query.class), observed.capture(), eq(Cluster.class));
        assertThat(observed.getValue().getUpdateObject().get("$set", org.bson.Document.class))
            .containsEntry("ec2PublicIp", "203.0.113.42")
            .containsEntry("egressAddressAllocationId", "eipalloc-owned")
            .doesNotContainEntry("egressAddressStatus", ClusterEgressAddressStatus.STABLE);
        order.verify(dns).upsertARecord("cluster.example.test", "203.0.113.42");
        var update = ArgumentCaptor.forClass(Update.class);
        var query = ArgumentCaptor.forClass(Query.class);
        order.verify(mongo).updateFirst(query.capture(), update.capture(), eq(Cluster.class));
        assertThat(update.getValue().getUpdateObject().get("$set", org.bson.Document.class))
            .containsEntry("egressAddressStatus", ClusterEgressAddressStatus.STABLE);
        assertThat(query.getValue().getQueryObject().toString()).contains("egressAddressOperationStartedAt", "ATTACHING");
    }

    @Test
    void dnsFailureLeavesARetryableFailureRatherThanPublishingAStableAddress() {
        claim();
        when(compute.stabilizeEgressAddress(anyString(), anyString(), any()))
            .thenReturn(new NodeInstance(CloudProvider.AWS, cluster.getId().toHexString(), "i-new",
                "203.0.113.42", "10.0.0.1", "eipalloc-owned"));
        doThrow(new IllegalStateException("controlled DNS failure")).when(dns).upsertARecord(anyString(), anyString());
        service.onStabilizationRequested(request());
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(2)).updateFirst(any(Query.class), update.capture(), eq(Cluster.class));
        assertThat(update.getAllValues().get(0).getUpdateObject().get("$set", org.bson.Document.class))
            .containsEntry("ec2PublicIp", "203.0.113.42")
            .containsEntry("egressAddressAllocationId", "eipalloc-owned");
        assertThat(update.getAllValues().get(1).getUpdateObject().get("$set", org.bson.Document.class))
            .containsEntry("egressAddressStatus", ClusterEgressAddressStatus.FAILED);
        verify(compute, never()).terminate(anyString(), anyString());
    }

    @Test
    void terminatedClusterCannotContinueAfterObtainingALease() {
        claim();
        cluster.setState(ClusterState.TERMINATED);
        service.onStabilizationRequested(request());
        verify(compute, never()).stabilizeEgressAddress(anyString(), anyString(), any());
        verifyNoInteractions(dns);
    }

    @Test
    void destructionDuringAssociationReclaimsLateResourcesWithoutRecreatingDns() {
        claim();
        when(compute.stabilizeEgressAddress(anyString(), anyString(), any())).thenAnswer(invocation -> {
            cluster.setState(ClusterState.TERMINATED);
            return new NodeInstance(CloudProvider.AWS, cluster.getId().toHexString(), "i-new",
                "203.0.113.42", "10.0.0.1", "eipalloc-owned");
        });
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Cluster.class)))
            .thenReturn(UpdateResult.acknowledged(0, 0L, null), UpdateResult.acknowledged(1, 1L, null));
        service.onStabilizationRequested(request());
        verify(compute).terminate("us-east-1", cluster.getId().toHexString());
        verify(dns, never()).upsertARecord(anyString(), anyString());
        verify(dns).deleteRecord("cluster.example.test");
        assertThat(cluster.getTerminatedResourceCleanupLastAttemptAt()).isNotNull();
        var updates = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(2)).updateFirst(any(Query.class), updates.capture(), eq(Cluster.class));
        assertThat(updates.getAllValues().get(1).getUpdateObject().get("$set", org.bson.Document.class))
            .containsKey("terminatedResourceCleanupLastAttemptAt");
    }

    @Test
    void destructionDuringDnsUpdateRemovesTheLateRecordAndAddress() {
        claim();
        when(compute.stabilizeEgressAddress(anyString(), anyString(), any()))
            .thenReturn(new NodeInstance(CloudProvider.AWS, cluster.getId().toHexString(), "i-new",
                "203.0.113.42", "10.0.0.1", "eipalloc-owned"));
        doAnswer(invocation -> {
            cluster.setState(ClusterState.TERMINATED);
            return null;
        }).when(dns).upsertARecord(anyString(), anyString());
        service.onStabilizationRequested(request());
        var order = inOrder(dns, compute);
        order.verify(dns).upsertARecord("cluster.example.test", "203.0.113.42");
        order.verify(compute).terminate("us-east-1", cluster.getId().toHexString());
        order.verify(dns).deleteRecord("cluster.example.test");
    }

    @Test
    void terminatedCleanupRetriesFailuresWithBackoff() {
        cluster.setState(ClusterState.TERMINATED);
        when(clusters.findByState(
            eq(ClusterState.TERMINATED), any(Pageable.class))).thenReturn(java.util.List.of(cluster));
        doThrow(new IllegalStateException("controlled release failure")).doNothing()
            .when(compute).terminate("us-east-1", cluster.getId().toHexString());
        service.reconcileTerminatedClusters();
        service.reconcileTerminatedClusters();
        verify(compute, times(1)).terminate("us-east-1", cluster.getId().toHexString());

        cluster.setTerminatedResourceCleanupLastAttemptAt(Instant.now().minusSeconds(6 * 60));
        service.reconcileTerminatedClusters();
        verify(compute, times(2)).terminate("us-east-1", cluster.getId().toHexString());
        verify(dns).deleteRecord("cluster.example.test");
        assertThat(cluster.getTerminatedResourceCleanupLastAttemptAt()).isNotNull();
    }

    @Test
    void terminatedClusterDoesNotDeleteHostnameReusedByAnotherOrganization() {
        cluster.setState(ClusterState.TERMINATED);
        cluster.setDnsLabel("shared-name");
        when(clusters.findByState(
            eq(ClusterState.TERMINATED), any(Pageable.class))).thenReturn(java.util.List.of(cluster));
        when(clusters.existsByPublicDnsAndStateNotAndIdNot(
            "cluster.example.test", ClusterState.TERMINATED, cluster.getId())).thenReturn(true);

        service.reconcileTerminatedClusters();

        verify(compute).terminate("us-east-1", cluster.getId().toHexString());
        verify(clusters).existsByPublicDnsAndStateNotAndIdNot(
            "cluster.example.test", ClusterState.TERMINATED, cluster.getId());
        verify(dns, never()).deleteRecord(anyString());
    }

    @Test
    void terminatedCleanupRunsOffTheScheduledCallbackAndUsesABoundedSubmissionPoint() {
        cluster.setState(ClusterState.TERMINATED);
        when(clusters.findByState(
            eq(ClusterState.TERMINATED), any(Pageable.class))).thenReturn(java.util.List.of(cluster));
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ClusterDnsRecordCoordinator dnsRecords =
            new ClusterDnsRecordCoordinator(lockProvider, clusters, dns,
                new ClusterDomainName(new IacProperties()));
        ClusterEgressAddressService asyncService = new ClusterEgressAddressService(
            clusters, mongo, events, dnsRecords, Map.of("aws", compute), lockProvider, queued::add);
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));

        asyncService.reconcileTerminatedClusters();

        verifyNoInteractions(compute);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(compute).terminate("us-east-1", cluster.getId().toHexString());
    }

    @Test
    void boundedCleanupQueueRotatesPastEarlierTerminatedClusters() {
        cluster.setState(ClusterState.TERMINATED);
        Cluster later = new Cluster();
        later.setId(new ObjectId());
        later.setRegion("us-east-1");
        later.setState(ClusterState.TERMINATED);
        later.setPublicDns("later.example.test");
        when(clusters.findByState(
            eq(ClusterState.TERMINATED), any(Pageable.class)))
            .thenReturn(java.util.List.of(cluster, later), java.util.List.of(later));

        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ClusterDnsRecordCoordinator dnsRecords =
            new ClusterDnsRecordCoordinator(lockProvider, clusters, dns,
                new ClusterDomainName(new IacProperties()));
        java.util.concurrent.Executor boundedExecutor = task -> {
            if (!queued.isEmpty()) throw new java.util.concurrent.RejectedExecutionException();
            queued.add(task);
        };
        ClusterEgressAddressService boundedService = new ClusterEgressAddressService(
            clusters, mongo, events, dnsRecords, Map.of("aws", compute), lockProvider, boundedExecutor);

        boundedService.reconcileTerminatedClusters();
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();

        boundedService.reconcileTerminatedClusters();
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();

        verify(compute).terminate("us-east-1", cluster.getId().toHexString());
        verify(compute).terminate("us-east-1", later.getId().toHexString());
        var pages = ArgumentCaptor.forClass(Pageable.class);
        verify(clusters, times(2)).findByState(eq(ClusterState.TERMINATED), pages.capture());
        assertThat(pages.getAllValues()).allSatisfy(page -> {
            assertThat(page.getPageSize()).isEqualTo(100);
            assertThat(page.getSort().getOrderFor("terminatedResourceCleanupLastAttemptAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
            assertThat(page.getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
            assertThat(page.getSort().getOrderFor("_id").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        });
    }

    @Test
    void successfulCleanupRemainsInPeriodicSweepForLateAwsSideEffects() {
        cluster.setState(ClusterState.TERMINATED);
        when(clusters.findByState(
            eq(ClusterState.TERMINATED), any(Pageable.class))).thenReturn(
                java.util.List.of(cluster), java.util.List.of(cluster));

        service.reconcileTerminatedClusters();
        cluster.setTerminatedResourceCleanupLastAttemptAt(Instant.now().minusSeconds(6 * 60));
        service.reconcileTerminatedClusters();

        verify(compute, times(2)).terminate("us-east-1", cluster.getId().toHexString());
        verify(dns, times(2)).deleteRecord("cluster.example.test");
    }

    private void claim() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Cluster.class)))
            .thenReturn(cluster);
    }

    private ClusterEgressAddressService.StabilizationRequest request() {
        return new ClusterEgressAddressService.StabilizationRequest(cluster.getId(), cluster.getEgressAddressOperationStartedAt(), true);
    }
}
