package io.tapstate.cloud.subscription;

import com.mongodb.client.result.UpdateResult;
import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubscriptionResourceCoordinator} — the join point that
 * flips a Subscription's resources to READY only when BOTH the Cluster
 * deployment and the Atlas MdbAllocation are READY.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionResourceCoordinatorTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private ClusterRepository clusterRepository;
    @Mock
    private MdbAllocationRepository allocationRepository;
    @Mock
    private MongoTemplate mongoTemplate;

    private SubscriptionResourceCoordinator coordinator;

    private final ObjectId subscriptionId = new ObjectId();
    private final ObjectId orgId = new ObjectId();
    private final ObjectId clusterId = new ObjectId();
    private final ObjectId allocationId = new ObjectId();

    @BeforeEach
    void setUp() {
        coordinator = new SubscriptionResourceCoordinator(
            subscriptionRepository, clusterRepository, allocationRepository, mongoTemplate);
    }

    /** The coordinator only writes on a successful join; stub the write outcome there. */
    private void expectSuccessfulFlip() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Subscription.class)))
            .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
    }

    private Subscription subscription(SubscriptionStatus status, ResourceState resourceState) {
        return Subscription.builder()
            .id(subscriptionId)
            .orgId(orgId)
            .status(status)
            .resourceState(resourceState)
            .build();
    }

    private Cluster cluster(ClusterState state) {
        Cluster c = Cluster.builder().build();
        c.setId(clusterId);
        c.setOrgId(orgId);
        c.setState(state);
        return c;
    }

    private MdbAllocation allocation(MdbAllocationStatus status) {
        return MdbAllocation.builder()
            .id(allocationId)
            .subscriptionId(subscriptionId)
            .clusterId(clusterId)
            .orgId(orgId)
            .status(status)
            .build();
    }

    @Test
    void clusterReadyAloneDoesNotFlipSubscription() {
        when(allocationRepository.findByClusterId(clusterId))
            .thenReturn(Optional.of(allocation(MdbAllocationStatus.PROVISIONING)));

        coordinator.onClusterReady(new ClusterReadyEvent(clusterId));

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
            eq(Subscription.class));
    }

    @Test
    void allocationReadyAloneDoesNotFlipSubscription() {
        when(clusterRepository.findById(clusterId))
            .thenReturn(Optional.of(cluster(ClusterState.PROVISIONING)));

        coordinator.onMdbAllocationReady(
            new MdbAllocationReadyEvent(allocationId, clusterId, subscriptionId));

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
            eq(Subscription.class));
    }

    @Test
    void flipsReadyOnlyAfterBothClusterAndAllocationReady_viaClusterEvent() {
        expectSuccessfulFlip();
        when(subscriptionRepository.findById(subscriptionId))
            .thenReturn(Optional.of(subscription(SubscriptionStatus.TRIAL, ResourceState.PROVISIONING)));
        when(allocationRepository.findByClusterId(clusterId))
            .thenReturn(Optional.of(allocation(MdbAllocationStatus.READY)));
        when(clusterRepository.findByOrgId(orgId))
            .thenReturn(List.of(cluster(ClusterState.READY)));
        when(allocationRepository.findBySubscriptionId(subscriptionId))
            .thenReturn(List.of(allocation(MdbAllocationStatus.READY)));

        coordinator.onClusterReady(new ClusterReadyEvent(clusterId));

        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(Subscription.class));
    }

    @Test
    void flipsReadyViaMdbEventAsWell() {
        expectSuccessfulFlip();
        when(subscriptionRepository.findById(subscriptionId))
            .thenReturn(Optional.of(subscription(SubscriptionStatus.TRIAL, ResourceState.PROVISIONING)));
        when(clusterRepository.findById(clusterId))
            .thenReturn(Optional.of(cluster(ClusterState.READY)));
        when(clusterRepository.findByOrgId(orgId))
            .thenReturn(List.of(cluster(ClusterState.READY)));
        when(allocationRepository.findBySubscriptionId(subscriptionId))
            .thenReturn(List.of(allocation(MdbAllocationStatus.READY)));

        coordinator.onMdbAllocationReady(
            new MdbAllocationReadyEvent(allocationId, clusterId, subscriptionId));

        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(Subscription.class));
    }

    @Test
    void alreadyReadyIsIdempotent() {
        when(subscriptionRepository.findById(subscriptionId))
            .thenReturn(Optional.of(subscription(SubscriptionStatus.TRIAL, ResourceState.READY)));
        // The mdb event handler only evaluates on a READY cluster; provide one so
        // the coordinator proceeds far enough to exercise the READY short-circuit.
        when(clusterRepository.findById(clusterId))
            .thenReturn(Optional.of(cluster(ClusterState.READY)));

        coordinator.onMdbAllocationReady(
            new MdbAllocationReadyEvent(allocationId, clusterId, subscriptionId));

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
            eq(Subscription.class));
    }

    @Test
    void cancelledSubscriptionIsNeverMarkedReady() {
        when(subscriptionRepository.findById(subscriptionId))
            .thenReturn(Optional.of(subscription(SubscriptionStatus.CANCELLED, ResourceState.PROVISIONING)));
        when(clusterRepository.findById(clusterId))
            .thenReturn(Optional.of(cluster(ClusterState.READY)));

        coordinator.onMdbAllocationReady(
            new MdbAllocationReadyEvent(allocationId, clusterId, subscriptionId));

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
            eq(Subscription.class));
    }
}
