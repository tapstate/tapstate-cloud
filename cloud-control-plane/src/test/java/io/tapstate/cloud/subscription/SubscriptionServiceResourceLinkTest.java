package io.tapstate.cloud.subscription;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import io.tapstate.cloud.version.ClusterVersion;
import io.tapstate.cloud.version.ClusterVersionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the resource links written onto billable items by
 * {@link SubscriptionService#finalizeProvisioning}. The runtime billable item
 * must carry the Cluster ID and the storage item the MdbAllocation ID, and each
 * subscription's links must stay scoped to its own items.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceResourceLinkTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private SubscriptionItemRepository itemRepository;
    @Mock
    private SubscriptionStateMachineService subscriptionStateMachineService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ClusterService clusterService;
    @Mock
    private MdbAllocationService mdbAllocationService;
    @Mock
    private DeploymentStepRecorder deploymentStepRecorder;
    @Mock
    private ClusterVersionService clusterVersionService;
    @Mock
    private SpecConfigService specConfigService;
    @Mock
    private io.tapstate.cloud.payment.stripe.StripeProductService stripeProductService;
    @Mock
    private io.tapstate.cloud.payment.PaymentGateway paymentGateway;

    private SubscriptionService subscriptionService;

    private final ObjectId orgId = new ObjectId();
    private final ObjectId subscriptionId = new ObjectId();
    private final ObjectId clusterId = new ObjectId();
    private final ObjectId allocationId = new ObjectId();

    /** Subscriptions by id, so a finalize on either one resolves its own document. */
    private final Map<ObjectId, Subscription> stored = new HashMap<>();

    private SubscriptionItem runtimeItem;
    private SubscriptionItem storageItem;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService(
            subscriptionRepository, itemRepository, subscriptionStateMachineService,
            eventPublisher, clusterService, mdbAllocationService, deploymentStepRecorder,
            clusterVersionService, specConfigService, stripeProductService, paymentGateway);

        stored.put(subscriptionId, subscription(subscriptionId));
        lenient().when(subscriptionRepository.findById(any(ObjectId.class))).thenAnswer(inv ->
            Optional.ofNullable(stored.get(inv.getArgument(0))));
        lenient().when(subscriptionRepository.save(any(Subscription.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        when(clusterVersionService.findClusterLastVersion())
            .thenReturn(ClusterVersion.builder().version("0.6.0").build());

        Cluster cluster = Cluster.builder().build();
        cluster.setId(clusterId);
        cluster.setOrgId(orgId);
        when(clusterService.createCluster(anyString(), any(ObjectId.class), anyString(),
            anyString(), anyString(), anyString(), anyString())).thenReturn(cluster);

        MdbAllocation allocation = MdbAllocation.builder().id(allocationId).build();
        when(mdbAllocationService.allocateMdb(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(allocation);

        runtimeItem = SubscriptionItem.builder()
            .id(new ObjectId())
            .subscriptionId(subscriptionId)
            .orgId(orgId)
            .itemType(SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)
            .build();
        storageItem = SubscriptionItem.builder()
            .id(new ObjectId())
            .subscriptionId(subscriptionId)
            .orgId(orgId)
            .itemType(SubscriptionItemType.ATLAS_DATABASE_STORAGE)
            .build();
        when(itemRepository.findBySubscriptionIdAndItemType(subscriptionId,
            SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)).thenReturn(Optional.of(runtimeItem));
        when(itemRepository.findBySubscriptionIdAndItemType(subscriptionId,
            SubscriptionItemType.ATLAS_DATABASE_STORAGE)).thenReturn(Optional.of(storageItem));
    }

    private Subscription subscription(ObjectId id) {
        return Subscription.builder()
            .id(id)
            .orgId(orgId)
            .status(SubscriptionStatus.TRIAL)
            .specConfig(SpecConfig.builder().key("2c8g").trialDays(7).enabled(true).build())
            .build();
    }

    @Test
    void finalizeStampsResourceIdsOnBillableItems() {
        Subscription finalized = subscriptionService.finalizeProvisioning(
            subscriptionId.toHexString(), "ap-southeast-1", "qa-cluster", "leon.li@tapdata.io");

        assertThat(finalized.getId()).isEqualTo(subscriptionId);
        assertThat(runtimeItem.getClusterId()).isEqualTo(clusterId);
        assertThat(runtimeItem.getMdbAllocationId()).isNull();
        assertThat(storageItem.getMdbAllocationId()).isEqualTo(allocationId);
        assertThat(storageItem.getClusterId()).isNull();
        verify(itemRepository).save(runtimeItem);
        verify(itemRepository).save(storageItem);
    }

    @Test
    void provisioningStartsOnlyAfterAllocationLinkAndItemsAreDurable() {
        subscriptionService.finalizeProvisioning(
            subscriptionId.toHexString(), "ap-southeast-1", "qa-cluster", "leon.li@tapdata.io");

        // START_PROVISION must fire only after the allocation link is saved, so
        // the async provisioning run can never observe the Cluster unlinked.
        var order = inOrder(clusterService, itemRepository);
        order.verify(clusterService).updateMdbAllocationId(clusterId, allocationId);
        order.verify(itemRepository).save(runtimeItem);
        order.verify(itemRepository).save(storageItem);
        order.verify(clusterService).startProvisioning(eq(clusterId),
            eq("subscription:leon.li@tapdata.io"));
    }

    @Test
    void missingBillableItemDoesNotBlockProvisioning() {
        when(itemRepository.findBySubscriptionIdAndItemType(subscriptionId,
            SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)).thenReturn(Optional.empty());

        subscriptionService.finalizeProvisioning(
            subscriptionId.toHexString(), "ap-southeast-1", "qa-cluster", "leon.li@tapdata.io");

        assertThat(storageItem.getMdbAllocationId()).isEqualTo(allocationId);
        verify(itemRepository).save(storageItem);
        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository, org.mockito.Mockito.atLeastOnce()).save(subCaptor.capture());
        assertThat(subCaptor.getAllValues())
            .anyMatch(s -> s.getResourceState() == ResourceState.PROVISIONING);
    }

    @Test
    void twoSubscriptionsLinkTheirOwnResourcesWithoutOverwritingEachOther() {
        ObjectId secondSubscriptionId = new ObjectId();
        ObjectId secondClusterId = new ObjectId();
        ObjectId secondAllocationId = new ObjectId();
        stored.put(secondSubscriptionId, subscription(secondSubscriptionId));

        SubscriptionItem secondRuntimeItem = SubscriptionItem.builder()
            .id(new ObjectId()).subscriptionId(secondSubscriptionId).orgId(orgId)
            .itemType(SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME).build();
        SubscriptionItem secondStorageItem = SubscriptionItem.builder()
            .id(new ObjectId()).subscriptionId(secondSubscriptionId).orgId(orgId)
            .itemType(SubscriptionItemType.ATLAS_DATABASE_STORAGE).build();
        when(itemRepository.findBySubscriptionIdAndItemType(secondSubscriptionId,
            SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)).thenReturn(Optional.of(secondRuntimeItem));
        when(itemRepository.findBySubscriptionIdAndItemType(secondSubscriptionId,
            SubscriptionItemType.ATLAS_DATABASE_STORAGE)).thenReturn(Optional.of(secondStorageItem));

        Cluster secondCluster = Cluster.builder().build();
        secondCluster.setId(secondClusterId);
        when(clusterService.createCluster(anyString(), eq(secondSubscriptionId), anyString(),
            anyString(), anyString(), anyString(), anyString())).thenReturn(secondCluster);
        when(mdbAllocationService.allocateMdb(anyString(), eq(secondClusterId.toHexString()),
            anyString(), eq(secondSubscriptionId.toHexString())))
            .thenReturn(MdbAllocation.builder().id(secondAllocationId).build());

        subscriptionService.finalizeProvisioning(
            subscriptionId.toHexString(), "ap-southeast-1", "qa-cluster-a", "leon.li@tapdata.io");
        subscriptionService.finalizeProvisioning(
            secondSubscriptionId.toHexString(), "ap-southeast-1", "qa-cluster-b", "leon.li@tapdata.io");

        // First subscription's items keep pointing at the first resource pair.
        assertThat(runtimeItem.getClusterId()).isEqualTo(clusterId);
        assertThat(storageItem.getMdbAllocationId()).isEqualTo(allocationId);
        // Second subscription's items point at the second pair, never at the first.
        assertThat(secondRuntimeItem.getClusterId()).isEqualTo(secondClusterId);
        assertThat(secondStorageItem.getMdbAllocationId()).isEqualTo(secondAllocationId);
        assertThat(secondRuntimeItem.getClusterId()).isNotEqualTo(clusterId);

        // The second cluster is created against the second subscription, and the
        // first subscription's items are never written again.
        verify(clusterService).createCluster(orgId.toHexString(), secondSubscriptionId,
            "ap-southeast-1", "qa-cluster-b", "2c8g", "leon.li@tapdata.io", "0.6.0");
        verify(itemRepository, org.mockito.Mockito.times(1)).save(runtimeItem);
        verify(itemRepository, org.mockito.Mockito.times(1)).save(storageItem);
        verify(itemRepository).save(secondRuntimeItem);
        verify(itemRepository).save(secondStorageItem);
        verify(itemRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
            i -> i != null && subscriptionId.equals(i.getSubscriptionId())
                && secondClusterId.equals(i.getClusterId())));
    }
}
