package io.tapstate.cloud.cluster.fsm;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.mdb.AtlasMdbService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Cluster state machine orchestrator — wraps Spring Statemachine factory with
 * per-Cluster state resolution from DB.
 * <p>
 * The pattern (from hare {@code JobStateMachineConfig}): every call to
 * {@link StateMachineFactory#getStateMachine(String)} creates a fresh instance
 * starting from {@code initial}. This service is the single point that:
 * <ol>
 *   <li>Loads the Cluster from MongoDB</li>
 *   <li>Rebuilds the fresh state machine with {@link #resetTo(StateMachine, ClusterState)}
 *       so it starts from the Cluster's persisted state, not {@code initial}</li>
 *   <li>Sends the requested event</li>
 *   <li>If the transition was accepted, persists the new state back to MongoDB</li>
 *   <li>Applies transition effects — including async Atlas Mdb allocation via {@link AtlasMdbService}</li>
 * </ol>
 */
@Service
@Slf4j
public class ClusterStateMachineService {

    private final StateMachineFactory<ClusterState, ClusterEventType> clusterStateMachineFactory;
    private final ClusterRepository clusterRepository;
    private final AtlasMdbService atlasMdbService;
    private final MdbAllocationRepository allocationRepo;

    private final SecureRandom secureRandom = new SecureRandom();

    public ClusterStateMachineService(
        @Qualifier("clusterStateMachineFactory")
        StateMachineFactory<ClusterState, ClusterEventType> clusterStateMachineFactory,
        ClusterRepository clusterRepository,
        AtlasMdbService atlasMdbService,
        MdbAllocationRepository allocationRepo) {
        this.clusterStateMachineFactory = clusterStateMachineFactory;
        this.clusterRepository = clusterRepository;
        this.atlasMdbService = atlasMdbService;
        this.allocationRepo = allocationRepo;
    }

    /**
     * Send an event to the Cluster state machine and persist the new state.
     * <p>
     * If the state machine rejects the event (transition not defined from the
     * current state), returns {@link TransitionResult#REJECTED} without mutating
     * the Cluster record.
     *
     * @param clusterId target Cluster UUID
     * @param event     event to fire
     * @param operator  human / system identifier (for audit)
     * @return transition result — check {@link TransitionResult#accepted} / newState
     */
    public TransitionResult transition(String clusterId, ClusterEventType event, String operator) {
        Cluster cluster = clusterRepository.findById(clusterId)
            .orElseThrow(() -> new IllegalStateException("Cluster not found: " + clusterId));

        StateMachine<ClusterState, ClusterEventType> sm =
            clusterStateMachineFactory.getStateMachine("cluster-" + clusterId);

        sm.start();
        resetTo(sm, cluster.getState());

        boolean accepted = sm.sendEvent(event);

        TransitionResult result;
        if (accepted) {
            ClusterState newState = sm.getState().getId();
            ClusterState prevState = cluster.getState();

            cluster.setState(newState);
            cluster.setUpdatedAt(Instant.now());
            clusterRepository.save(cluster);

            applyTransitionEffects(cluster, prevState, newState, event);

            clusterRepository.save(cluster);

            log.info("[Cluster FSM] {} cluster {}: {} → {} on event={}",
                operator, clusterId, prevState, newState, event);
            result = TransitionResult.accepted(newState);
        } else {
            log.warn("[Cluster FSM] {} cluster {}: event {} rejected from state {}",
                operator, clusterId, event, cluster.getState());
            result = TransitionResult.rejected(cluster.getState());
        }

        sm.stop();
        return result;
    }

    private void resetTo(StateMachine<ClusterState, ClusterEventType> sm, ClusterState persistedState) {
        sm.getStateMachineAccessor()
            .doWithAllRegions(accessor -> accessor.resetStateMachine(
                new DefaultStateMachineContext<>(persistedState, null, null, null)
            ));
    }

    /**
     * Apply side-effects after a successful state transition.
     * <p>
     * PROVISIONING_PENDING → PROVISIONING:
     *   - Fire async Atlas Mdb allocation (createDatabase + createDatabaseUser) via AtlasMdbService
     *   - Fire simulated CloudFormation success/failure (85% success rate)
     * PROVISIONING → PROVISIONING_SUCCESS:
     *   - Mark allocation READY, set cluster runtime fields (EC2, DNS, elasticIp)
     * TERMINATED:
     *   - Decrement Atlas instance capacity, drop DB user + database (async)
     */
    private void applyTransitionEffects(Cluster cluster, ClusterState from, ClusterState to, ClusterEventType event) {

        // === PROVISIONING_PENDING → PROVISIONING: start async Atlas MDB + CloudFormation ===
        if (from == ClusterState.PROVISIONING_PENDING && to == ClusterState.PROVISIONING) {
            startAsyncProvisioning(cluster);
        }

        // === PROVISIONING → READY: confirm allocation READY, set runtime fields ===
        if (from == ClusterState.PROVISIONING && to == ClusterState.READY) {
            markAllocationReady(cluster);

            cluster.setReadyAt(Instant.now());
            cluster.setEc2InstanceId("i-" + cluster.getId().substring(0, 12));
            cluster.setElasticIp("54." + secureRandom.nextInt(256) + "." +
                secureRandom.nextInt(256) + "." + secureRandom.nextInt(256));
            cluster.setPublicDns(cluster.getId() + ".api.tapstate.io");
            cluster.setLastHeartbeatAt(Instant.now());
        }

        // === PROVISIONING → PROVISIONING_FAILED: allocation also marked failed ===
        if (from == ClusterState.PROVISIONING && to == ClusterState.PROVISIONING_FAILED) {
            cluster.setProvisionErrorCode(io.tapstate.cloud.cluster.ProvisionErrorCode.CLOUDFORMATION_FAILED);
            cluster.setProvisionErrorDetail("{\"error\": \"Simulated provisioning failure\"}");
            markAllocationFailed(cluster);
        }

        if (from == ClusterState.READY && to == ClusterState.DEGRADED) {
            log.warn("[Cluster FSM] cluster {} degraded — heartbeat expired", cluster.getId());
        }

        if (from == ClusterState.DEGRADED && to == ClusterState.READY) {
            log.info("[Cluster FSM] cluster {} recovered from DEGRADED", cluster.getId());
        }

        // === TERMINATED: release Atlas slot + async drop DB/user ===
        if (to == ClusterState.TERMINATED) {
            cluster.setTerminatedAt(Instant.now());
            cluster.setSdkStaticToken(null);
            cluster.setBootstrapToken(null);
            log.warn("[Cluster FSM] cluster {} TERMINATED — cleanup triggered", cluster.getId());

            startAsyncDeallocation(cluster);
        }
    }

    // ========== Async provisioning (Atlas MDB + simulated CloudFormation) ==========

    private void startAsyncProvisioning(Cluster cluster) {
        Thread.ofVirtual().start(() -> {
            try {
                long delay = 5000 + secureRandom.nextInt(10000);
                TimeUnit.MILLISECONDS.sleep(delay);

                Cluster latest = clusterRepository.findById(cluster.getId()).orElse(null);
                if (latest == null || latest.getState() != ClusterState.PROVISIONING) {
                    return; // terminated mid-provision
                }

                // --- Atlas MDB provisioning ---
                MdbAllocation allocation = findAllocation(latest);
                if (allocation != null) {
                    try {
                        atlasMdbService.createDatabase(allocation);
                        atlasMdbService.createDatabaseUser(allocation);
                        log.info("[Cluster FSM] MdbAllocation {} provisioned (db={}, user={})",
                            allocation.getId(), allocation.getDatabaseName(), allocation.getDbUsername());
                    } catch (Exception e) {
                        log.error("[Cluster FSM] Atlas MDB provisioning failed for cluster {}: {}",
                            latest.getId(), e.getMessage());
                        // Even if Atlas fails, let simulated CloudFormation decide final outcome
                    }
                }

                // --- Simulated CloudFormation ---
                boolean success = secureRandom.nextInt(100) < 85;
                if (success) {
                    transition(latest.getId(), ClusterEventType.PROVISION_SUCCESS, "system(auto-provision)");
                } else {
                    transition(latest.getId(), ClusterEventType.PROVISION_FAIL, "system(auto-provision)");
                }
            } catch (Exception e) {
                log.error("[Cluster FSM] auto-provisioning error for cluster {}: {}", cluster.getId(), e.getMessage());
            }
        });
    }

    // ========== Async deallocation ==========

    private void startAsyncDeallocation(Cluster cluster) {
        Thread.ofVirtual().start(() -> {
            try {
                MdbAllocation allocation = findAllocation(cluster);
                if (allocation != null) {
                    try {
                        allocation.setStatus(MdbAllocationStatus.DELETING);
                        allocationRepo.save(allocation);

                        atlasMdbService.dropDatabaseUser(allocation);
                        atlasMdbService.dropDatabase(allocation);

                        allocation.setStatus(MdbAllocationStatus.DELETED);
                        allocation.setDeletedAt(Instant.now());
                        allocationRepo.save(allocation);

                        log.info("[Cluster FSM] MdbAllocation {} released", allocation.getId());
                    } catch (Exception e) {
                        log.warn("[Cluster FSM] Deallocation failed for {}: {}", allocation.getId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("[Cluster FSM] Deallocation error for cluster {}: {}", cluster.getId(), e.getMessage());
            }
        });
    }

    // ========== Helpers ==========

    private MdbAllocation findAllocation(Cluster cluster) {
        if (cluster.getMdbAllocationId() == null) {
            log.warn("[Cluster FSM] Cluster {} has no mdbAllocationId", cluster.getId());
            return null;
        }
        return allocationRepo.findById(cluster.getMdbAllocationId()).orElse(null);
    }

    private void markAllocationReady(Cluster cluster) {
        MdbAllocation allocation = findAllocation(cluster);
        if (allocation != null && allocation.getStatus() != MdbAllocationStatus.READY) {
            allocation.setStatus(MdbAllocationStatus.READY);
            allocation.setReadyAt(Instant.now());
            allocationRepo.save(allocation);
        }
    }

    private void markAllocationFailed(Cluster cluster) {
        MdbAllocation allocation = findAllocation(cluster);
        if (allocation != null) {
            allocation.setStatus(MdbAllocationStatus.PROVISIONING_FAILED);
            allocation.setProvisionErrorCode("CLUSTER_PROVISION_FAILED");
            allocation.setProvisionErrorDetail(cluster.getProvisionErrorDetail());
            allocationRepo.save(allocation);
        }
    }

    // ========== Result DTO ==========

    /** Transition outcome — returned by {@link #transition(String, ClusterEventType, String)}. */
    public record TransitionResult(boolean accepted, ClusterState newState) {
        public static TransitionResult accepted(ClusterState newState) {
            return new TransitionResult(true, newState);
        }

        public static TransitionResult rejected(ClusterState currentState) {
            return new TransitionResult(false, currentState);
        }
    }
}
