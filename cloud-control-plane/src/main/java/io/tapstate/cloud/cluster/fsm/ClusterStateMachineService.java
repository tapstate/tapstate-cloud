package io.tapstate.cloud.cluster.fsm;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.cluster.ProvisionErrorCode;
import io.tapstate.cloud.iac.ClusterProvisioningService;
import io.tapstate.cloud.iac.ClusterProvisioningService.ProvisionResult;
import io.tapstate.cloud.iac.IacProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.bson.types.ObjectId;

/**
 * Cluster state machine orchestrator — wraps Spring Statemachine factory with
 * per-Cluster state resolution from DB.
 * <p>
 * The pattern: every call to {@link StateMachineFactory#getStateMachine(String)}
 * creates a fresh instance starting from {@code initial}. This service is the
 * single point that:
 * <ol>
 *   <li>Loads the Cluster from MongoDB</li>
 *   <li>Rebuilds the fresh state machine with {@link #resetTo(StateMachine, ClusterState)}
 *       so it starts from the Cluster's persisted state, not {@code initial}</li>
 *   <li>Sends the requested event</li>
 *   <li>If the transition was accepted, persists the new state back to MongoDB</li>
 *   <li>Applies transition effects — real IaC provisioning or a mock simulation</li>
 * </ol>
 * <p>
 * <b>MDB allocation is independent</b> — managed by
 * {@link io.tapstate.cloud.mdb.fsm.MdbAllocationStateMachineService}. The two
 * FSMs run in parallel; subscription-level orchestration (SubscriptionService)
 * coordinates them.
 */
@Service
@Slf4j
public class ClusterStateMachineService {

    private final StateMachineFactory<ClusterState, ClusterEventType> clusterStateMachineFactory;
    private final ClusterRepository clusterRepository;
    private final ClusterProvisioningService clusterProvisioningService;
    private final IacProperties iacProperties;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${tapstate.cloud.cluster.provision.success-rate:85}")
    private int provisionSuccessRate;

    public ClusterStateMachineService(
        @Qualifier("clusterStateMachineFactory")
        StateMachineFactory<ClusterState, ClusterEventType> clusterStateMachineFactory,
        ClusterRepository clusterRepository,
        ClusterProvisioningService clusterProvisioningService,
        IacProperties iacProperties) {
        this.clusterStateMachineFactory = clusterStateMachineFactory;
        this.clusterRepository = clusterRepository;
        this.clusterProvisioningService = clusterProvisioningService;
        this.iacProperties = iacProperties;
    }

    /**
     * Send an event to the Cluster state machine and persist the new state.
     *
     * @param clusterId target Cluster UUID
     * @param event     event to fire
     * @param operator  human / system identifier (for audit)
     * @return transition result — check {@link TransitionResult#accepted} / newState
     */
    public TransitionResult transition(ObjectId clusterId, ClusterEventType event, String operator) {
        Cluster cluster = clusterRepository.findById(clusterId)
            .orElseThrow(() -> new IllegalStateException("Cluster not found: " + clusterId));

        StateMachine<ClusterState, ClusterEventType> sm =
            clusterStateMachineFactory.getStateMachine("cluster-" + clusterId.toHexString());

        sm.start();
        resetTo(sm, cluster.getState());

        boolean accepted = sm.sendEvent(event);

        TransitionResult result;
        if (accepted) {
            ClusterState newState = sm.getState().getId();
            ClusterState prevState = cluster.getState();

            cluster.setState(newState);
            cluster.setUpdatedAt(Instant.now());
            // Synchronous field mutations are applied before persisting so the
            // new state and its side-effect fields become visible atomically;
            // async cloud operations are scheduled only after the save.
            applySynchronousMutations(cluster, prevState, newState, event);
            clusterRepository.save(cluster);

            scheduleAsyncEffects(cluster, prevState, newState, event);

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
     * Apply in-memory field mutations for a transition. Persisted by the caller
     * before any async effect runs.
     */
    private void applySynchronousMutations(Cluster cluster, ClusterState from, ClusterState to,
                                           ClusterEventType event) {
        boolean real = "real".equalsIgnoreCase(iacProperties.getMode());

        // === PROVISIONING → READY ===
        if (from == ClusterState.PROVISIONING && to == ClusterState.READY) {
            cluster.setReadyAt(Instant.now());
            if (!real) {
                simulateRuntimeFields(cluster);
            }
        }

        // === PROVISIONING → PROVISIONING_FAILED ===
        if (from == ClusterState.PROVISIONING && to == ClusterState.PROVISIONING_FAILED) {
            if (cluster.getProvisionErrorCode() == null) {
                cluster.setProvisionErrorCode(ProvisionErrorCode.CLOUDFORMATION_FAILED);
                cluster.setProvisionErrorDetail("{\"error\": \"Provisioning failed without a coded cause\"}");
            }
        }

        if (from == ClusterState.READY && to == ClusterState.DEGRADED) {
            log.warn("[Cluster FSM] cluster {} degraded — heartbeat expired", cluster.getId());
        }

        if (from == ClusterState.DEGRADED && to == ClusterState.READY) {
            log.info("[Cluster FSM] cluster {} recovered from DEGRADED", cluster.getId());
        }

        // === TERMINATED: clear credentials ===
        if (to == ClusterState.TERMINATED) {
            cluster.setTerminatedAt(Instant.now());
            cluster.setSdkStaticToken(null);
            cluster.setBootstrapToken(null);
        }
    }

    /**
     * Schedule side-effects that must run after the transition is persisted:
     * real-mode provisioning, retry cleanup and resource termination.
     */
    private void scheduleAsyncEffects(Cluster cluster, ClusterState from, ClusterState to,
                                      ClusterEventType event) {
        boolean real = "real".equalsIgnoreCase(iacProperties.getMode());

        // === PROVISIONING_PENDING → PROVISIONING ===
        if (from == ClusterState.PROVISIONING_PENDING && to == ClusterState.PROVISIONING) {
            if (real) {
                startAsyncProvisioning(cluster.getId());
            } else {
                startAsyncMockProvisioning(cluster.getId());
            }
        }

        // === PROVISIONING_FAILED → PROVISIONING_PENDING: restart ===
        if (from == ClusterState.PROVISIONING_FAILED && to == ClusterState.PROVISIONING_PENDING) {
            if (real) {
                scheduleRetry(cluster.getId());
            } else {
                scheduleMockRetry(cluster.getId());
            }
        }

        // === TERMINATED: reclaim resources in real mode ===
        if (to == ClusterState.TERMINATED) {
            if (real) {
                startAsyncTermination(cluster.getId());
            }
            log.warn("[Cluster FSM] cluster {} TERMINATED", cluster.getId());
        }
    }

    // ========== Real-mode async effects ==========

    /** Provision infrastructure on a virtual thread and feed the result back. */
    private void startAsyncProvisioning(ObjectId clusterId) {
        Thread.ofVirtual().start(() -> {
            try {
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null || latest.getState() != ClusterState.PROVISIONING) {
                    return;
                }

                ProvisionResult result = clusterProvisioningService.provision(latest);
                if (result.success()) {
                    transition(clusterId, ClusterEventType.PROVISION_SUCCESS, "system:iac");
                } else {
                    transition(clusterId, ClusterEventType.PROVISION_FAIL, "system:iac");
                }
            } catch (Exception e) {
                log.error("[Cluster FSM] provisioning error for cluster {}: {}",
                    clusterId, e.getMessage());
            }
        });
    }

    /** Sweep leftovers from the failed attempt, then re-enter provisioning. */
    private void scheduleRetry(ObjectId clusterId) {
        Thread.ofVirtual().start(() -> {
            try {
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null) {
                    return;
                }
                // Idempotent: a fully compensated attempt has no stack and no DNS.
                clusterProvisioningService.terminate(latest);
                transition(clusterId, ClusterEventType.START_PROVISION, "system:retry");
            } catch (Exception e) {
                log.error("[Cluster FSM] retry restart failed for cluster {}: {}",
                    clusterId, e.getMessage());
            }
        });
    }

    /** Reclaim cloud resources on a virtual thread. */
    private void startAsyncTermination(ObjectId clusterId) {
        Thread.ofVirtual().start(() -> {
            try {
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null) {
                    return;
                }
                clusterProvisioningService.terminate(latest);
            } catch (Exception e) {
                log.error("[Cluster FSM] resource cleanup failed for cluster {}: {}",
                    clusterId, e.getMessage());
            }
        });
    }

    // ========== Mock-mode async effects ==========

    /** Simulate the provisioning delay, then succeed or fail at the configured rate. */
    private void startAsyncMockProvisioning(ObjectId clusterId) {
        Thread.ofVirtual().start(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(2_000 + secureRandom.nextInt(3_000));
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null || latest.getState() != ClusterState.PROVISIONING) {
                    return;
                }
                if (secureRandom.nextInt(100) < provisionSuccessRate) {
                    transition(clusterId, ClusterEventType.PROVISION_SUCCESS, "mock:provision");
                } else {
                    transition(clusterId, ClusterEventType.PROVISION_FAIL, "mock:provision");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[Cluster FSM] mock provisioning error for cluster {}: {}",
                    clusterId, e.getMessage());
            }
        });
    }

    /** Re-enter provisioning after a short delay. */
    private void scheduleMockRetry(ObjectId clusterId) {
        Thread.ofVirtual().start(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(1_000);
                transition(clusterId, ClusterEventType.START_PROVISION, "mock:retry");
            } catch (Exception e) {
                log.error("[Cluster FSM] mock retry failed for cluster {}: {}",
                    clusterId, e.getMessage());
            }
        });
    }

    /** Fill placeholder runtime fields for a simulated READY Cluster. */
    private void simulateRuntimeFields(Cluster cluster) {
        cluster.setEc2InstanceId("i-" + cluster.getId().toHexString().substring(0, 12));
        cluster.setEc2PublicIp("54." + secureRandom.nextInt(256) + "."
            + secureRandom.nextInt(256) + "." + secureRandom.nextInt(256));
        cluster.setPublicDns(cluster.getId().toHexString() + ".dev.cloud.tapstate.com");
        cluster.setLastHeartbeatAt(Instant.now());
    }

    // ========== Result DTO ==========

    /** Transition outcome — returned by {@link #transition(ObjectId, ClusterEventType, String)}. */
    public record TransitionResult(boolean accepted, ClusterState newState) {
        public static TransitionResult accepted(ClusterState newState) {
            return new TransitionResult(true, newState);
        }

        public static TransitionResult rejected(ClusterState currentState) {
            return new TransitionResult(false, currentState);
        }
    }
}
