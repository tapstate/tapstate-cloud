package io.tapstate.cloud.cluster;

import io.tapstate.cloud.cluster.fsm.ClusterEventType;
import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import io.tapstate.cloud.subscription.SubscriptionCancelledEvent;
import io.tapstate.cloud.subscription.SubscriptionItemRepository;
import io.tapstate.cloud.subscription.SubscriptionItemType;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Cross-package cascade listener — when a Subscription is CANCELLED, terminate
 * that subscription's Cluster and reclaim that subscription's MdbAllocation.
 * <p>
 * An Organization owns many subscriptions; cancellation is strictly
 * subscription-scoped — sibling subscriptions of the same org and their
 * resources are never touched.
 * <p>
 * Why an event instead of direct injection: SubscriptionService CANNOT inject
 * ClusterService or MdbAllocationService to initiate cancel-side effects — that
 * would create circular FSM dependencies. Spring's {@code @EventListener} decouples
 * them cleanly.
 * <p>
 * Runs {@code @Async} — the subscription-cancel HTTP response returns immediately
 * to the user, while the GC state machines cascade events in the background. The
 * Cluster fires {@link ClusterEventType#INITIATE_TERMINATION} and the MdbAllocation
 * fires {@code INITIATE_RECLAIM} through its own state machine.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionCancelListener {

    private final ClusterRepository clusterRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final MongoTemplate mongoTemplate;
    private final ClusterStateMachineService clusterStateMachineService;
    private final MdbAllocationService mdbAllocationService;
    private final MdbAllocationRepository allocationRepo;

    /** States where a Cluster can still be terminated. */
    private static final Set<ClusterState> TERMINATABLE_STATES = Set.of(
        ClusterState.READY,
        ClusterState.DEGRADED,
        ClusterState.PROVISIONING_FAILED
    );

    /** MDB allocation states where reclaim can still run. */
    private static final Set<MdbAllocationStatus> RECLAIMABLE_STATES = Set.of(
        MdbAllocationStatus.PROVISIONING_PENDING,
        MdbAllocationStatus.PROVISIONING,
        MdbAllocationStatus.READY,
        MdbAllocationStatus.PROVISIONING_FAILED
    );

    @Async
    @EventListener
    public void onSubscriptionCancelled(SubscriptionCancelledEvent event) {
        ObjectId subscriptionId = new ObjectId(event.subscriptionId());
        ObjectId orgId = new ObjectId(event.orgId());
        log.info("[SubscriptionCancel] Cascade trigger: org={}, subscriptionId={}",
            event.orgId(), event.subscriptionId());

        // === Cluster termination (this subscription's Cluster only) ===
        List<Cluster> clusters = findSubscriptionClusters(subscriptionId, orgId);
        int terminated = 0;

        for (Cluster cluster : clusters) {
            if (!TERMINATABLE_STATES.contains(cluster.getState())) {
                log.info("[SubscriptionCancel] Skipping cluster={} state={} (not terminatable)",
                    cluster.getId(), cluster.getState());
                continue;
            }

            try {
                if (cluster.getSubscriptionId() == null) {
                    Query legacyCluster = Query.query(Criteria.where("_id").is(cluster.getId())
                        .and("orgId").is(orgId).and("subscriptionId").is(null));
                    mongoTemplate.updateFirst(legacyCluster,
                        new Update().set("subscriptionId", subscriptionId), Cluster.class);
                    Cluster linked = clusterRepository.findById(cluster.getId()).orElse(null);
                    if (linked == null || !subscriptionId.equals(linked.getSubscriptionId())) {
                        log.warn("[SubscriptionCancel] Cluster {} ownership changed before termination",
                            cluster.getId());
                        continue;
                    }
                }
                clusterStateMachineService.transition(cluster.getId(),
                    ClusterEventType.INITIATE_TERMINATION,
                    "subscription-cascade/" + event.operator());
                terminated++;
            } catch (Exception e) {
                log.error("[SubscriptionCancel] Failed to terminate cluster={}: {}",
                    cluster.getId(), e.getMessage());
            }
        }
        log.info("[SubscriptionCancel] Cluster cascade done: {}/{} terminated for subscription={}",
            terminated, clusters.size(), event.subscriptionId());

        // === MDB allocation reclaim (this subscription's allocations only) ===
        List<MdbAllocation> allocations = allocationRepo.findBySubscriptionId(subscriptionId);
        int reclaimed = 0;

        for (MdbAllocation allocation : allocations) {
            if (!RECLAIMABLE_STATES.contains(allocation.getStatus())) {
                log.info("[SubscriptionCancel] Skipping allocation={} state={} (already reclaiming/reclaimed)",
                    allocation.getId(), allocation.getStatus());
                continue;
            }

            try {
                mdbAllocationService.reclaimMdb(allocation.getClusterId());
                reclaimed++;
            } catch (Exception e) {
                log.error("[SubscriptionCancel] Failed to reclaim allocation={}: {}",
                    allocation.getId(), e.getMessage());
            }
        }
        log.info("[SubscriptionCancel] MDB cascade done: {}/{} reclaimed for subscription={}",
            reclaimed, allocations.size(), event.subscriptionId());
    }

    /** Recover cancelled subscriptions whose asynchronous Cluster termination was missed. */
    @Scheduled(initialDelay = 10_000, fixedDelay = 300_000)
    public void reconcileCancelledClusters() {
        for (var subscription : subscriptionRepository.findByStatus(SubscriptionStatus.CANCELLED)) {
            ObjectId subscriptionId = subscription.getId();
            ObjectId orgId = subscription.getOrgId();
            if (findSubscriptionClusters(subscriptionId, orgId).stream()
                .noneMatch(cluster -> TERMINATABLE_STATES.contains(cluster.getState()))) {
                continue;
            }
            onSubscriptionCancelled(new SubscriptionCancelledEvent(
                orgId.toHexString(), subscriptionId.toHexString(), "system:cancel-reconcile"));
        }
    }

    private List<Cluster> findSubscriptionClusters(ObjectId subscriptionId, ObjectId orgId) {
        var clusters = new LinkedHashMap<ObjectId, Cluster>();
        clusterRepository.findBySubscriptionId(subscriptionId).stream()
            .filter(cluster -> orgId.equals(cluster.getOrgId()))
            .forEach(cluster -> clusters.put(cluster.getId(), cluster));

        // Older Cluster records may lack subscriptionId, but the subscription's
        // billable item still carries the exact Cluster ID. Never follow a link
        // to another Organization or a Cluster owned by a different subscription.
        itemRepository.findBySubscriptionIdAndItemType(
                subscriptionId, SubscriptionItemType.CLUSTER_PIPELINE_RUNTIME)
            .map(item -> item.getClusterId())
            .filter(id -> id != null)
            .flatMap(clusterRepository::findById)
            .filter(cluster -> orgId.equals(cluster.getOrgId()))
            .filter(cluster -> cluster.getSubscriptionId() == null
                || subscriptionId.equals(cluster.getSubscriptionId()))
            .ifPresent(cluster -> clusters.put(cluster.getId(), cluster));

        return List.copyOf(clusters.values());
    }
}
