package io.tapstate.cloud.cluster;

import io.tapstate.cloud.cluster.fsm.ClusterEventType;
import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.subscription.SubscriptionCancelledEvent;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Cross-package cascade listener — when a Subscription is CANCELLED, terminate
 * every non-terminated Cluster owned by that Organization.
 * <p>
 * Why an event instead of direct injection: SubscriptionService CANNOT inject
 * ClusterService (that would create a circular FSM dependency — Subscription
 * FSM triggers Cluster FSM, ClusterService already depends on SubscriptionService
 * for the provisioning gate). Spring's {@code @EventListener} decouples them cleanly.
 * <p>
 * Runs {@code @Async} — the subscription-cancel HTTP response returns immediately
 * to the user, while the GC state machine cascades TERMINATION events in the
 * background. Each Cluster fires {@link ClusterEventType#INITIATE_TERMINATION}
 * through its own independent state-machine instance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionCancelListener {

    private final ClusterService clusterService;
    private final ClusterStateMachineService clusterStateMachineService;

    /** States where a Cluster can still be terminated. */
    private static final Set<ClusterState> TERMINATABLE_STATES = Set.of(
        ClusterState.READY,
        ClusterState.DEGRADED,
        ClusterState.PROVISIONING_FAILED
    );

    @Async
    @EventListener
    public void onSubscriptionCancelled(SubscriptionCancelledEvent event) {
        log.info("[SubscriptionCancel] Cascade trigger: cancelling all non-terminated clusters for org={}", event.orgId());

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
                // Log + continue — one cluster failure must not block the rest
                log.error("[SubscriptionCancel] Failed to terminate cluster={}: {}",
                    cluster.getId(), e.getMessage());
            }
        }

        log.info("[SubscriptionCancel] Cascade done: {}/{} clusters terminated for org={}",
            terminated, clusters.size(), event.orgId());
    }
}
