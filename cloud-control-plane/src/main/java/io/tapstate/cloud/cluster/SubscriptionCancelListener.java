package io.tapstate.cloud.cluster;

import io.tapstate.cloud.cluster.fsm.ClusterEventType;
import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import io.tapstate.cloud.subscription.SubscriptionCancelledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import org.bson.types.ObjectId;

/**
 * Cross-package cascade listener — when a Subscription is CANCELLED, terminate
 * every non-terminated Cluster AND reclaim every MdbAllocation owned by that org.
 * <p>
 * Why an event instead of direct injection: SubscriptionService CANNOT inject
 * ClusterService or MdbAllocationService to initiate cancel-side effects — that
 * would create circular FSM dependencies. Spring's {@code @EventListener} decouples
 * them cleanly.
 * <p>
 * Runs {@code @Async} — the subscription-cancel HTTP response returns immediately
 * to the user, while the GC state machines cascade events in the background.
 * Each Cluster fires {@link ClusterEventType#INITIATE_TERMINATION} and each
 * MdbAllocation fires {@code INITIATE_RECLAIM} through its own state machine.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionCancelListener {

    private final ClusterService clusterService;
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
        log.info("[SubscriptionCancel] Cascade trigger: org={}, subscriptionId={}",
            event.orgId(), event.subscriptionId());

        // === Cluster termination ===
        List<Cluster> clusters = clusterService.findByOrgId(event.orgId());
        int terminated = 0;

        for (Cluster cluster : clusters) {
            if (!TERMINATABLE_STATES.contains(cluster.getState())) {
                log.info("[SubscriptionCancel] Skipping cluster={} state={} (not terminatable)",
                    cluster.getId(), cluster.getState());
                continue;
            }

            try {
                clusterStateMachineService.transition(cluster.getId(),
                    ClusterEventType.INITIATE_TERMINATION,
                    "subscription-cascade/" + event.operator());
                terminated++;
            } catch (Exception e) {
                log.error("[SubscriptionCancel] Failed to terminate cluster={}: {}",
                    cluster.getId(), e.getMessage());
            }
        }
        log.info("[SubscriptionCancel] Cluster cascade done: {}/{} terminated for org={}",
            terminated, clusters.size(), event.orgId());

        // === MDB allocation reclaim ===
        List<MdbAllocation> allocations = allocationRepo.findByOrgId(new ObjectId(event.orgId()));
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
        log.info("[SubscriptionCancel] MDB cascade done: {}/{} reclaimed for org={}",
            reclaimed, allocations.size(), event.orgId());
    }
}
