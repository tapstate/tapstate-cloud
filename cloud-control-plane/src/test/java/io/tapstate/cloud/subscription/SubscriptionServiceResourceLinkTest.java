package io.tapstate.cloud.subscription;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the resource links written onto billable items by
 * {@link SubscriptionService#createSubscription}. The runtime billable item must
 * carry the Cluster ID and the storage item the MdbAllocation ID.
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

    private SubscriptionService subscriptionService;

    private final ObjectId orgId = new ObjectId();
    private final ObjectId subscriptionId = new ObjectId();
    private final ObjectId clusterId = new ObjectId();
    private final ObjectId allocationId = new ObjectId();

    private SubscriptionItem runtimeItem;
    private SubscriptionItem storageItem;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService(
            subscriptionRepository, itemRepository, subscriptionStateMachineService,
            eventPublisher, clusterService, mdbAllocationService);

        Subscription shell = Subscription.builder()
            .id(subscriptionId)
            .orgId(orgId)
            .status(SubscriptionStatus.TRIAL)
            .build();
        when(subscriptionRepository.findByOrgId(orgId)).thenReturn(Optional.of(shell));
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(shell));

        Cluster cluster = Cluster.builder().build();
        cluster.setId(clusterId);
        cluster.setOrgId(orgId);
        when(clusterService.createCluster(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(cluster);

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

    @Test
    void createSubscriptionStampsResourceIdsOnBillableItems() {
        subscriptionService.createSubscription(orgId.toHexString(), "ap-southeast-1",
            "qa-cluster", "basic", "leon.li@tapdata.io");

        assertThat(runtimeItem.getClusterId()).isEqualTo(clusterId);
        assertThat(runtimeItem.getMdbAllocationId()).isNull();
        assertThat(storageItem.getMdbAllocationId()).isEqualTo(allocationId);
        assertThat(storageItem.getClusterId()).isNull();
        verify(itemRepository).save(runtimeItem);
        verify(itemRepository).save(storageItem);
    }

    @Test
    void missingBillableItemDoesNotBlockProvisioning() {
        when(itemRepository.findBySubscriptionIdAndItemType(subscriptionId,
            SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)).thenReturn(Optional.empty());

        subscriptionService.createSubscription(orgId.toHexString(), "ap-southeast-1",
            "qa-cluster", "basic", "leon.li@tapdata.io");

        assertThat(storageItem.getMdbAllocationId()).isEqualTo(allocationId);
        verify(itemRepository).save(storageItem);
        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getResourceState()).isEqualTo(ResourceState.PROVISIONING);
    }
}
