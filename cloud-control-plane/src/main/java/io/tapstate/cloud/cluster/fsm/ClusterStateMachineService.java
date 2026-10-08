package io.tapstate.cloud.cluster.fsm;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
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
 *   <li>Applies transition effects — async CloudFormation simulation, runtime field setup</li>
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

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${tapstate.cloud.cluster.provision.success-rate:85}")
    private int provisionSuccessRate;

    public ClusterStateMachineService(
        @Qualifier("clusterStateMachineFactory")
        StateMachineFactory<ClusterState, ClusterEventType> clusterStateMachineFactory,
        ClusterRepository clusterRepository) {
        this.clusterStateMachineFactory = clusterStateMachineFactory;
        this.clusterRepository = clusterRepository;
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
     *   - Fire simulated CloudFormation success/failure (85% success rate, virtual thread)
     * PROVISIONING → READY:
     *   - Set runtime fields (EC2 instance ID, elastic IP, public DNS, heartbeat)
     * PROVISIONING → PROVISIONING_FAILED:
     *   - Record provisionErrorCode + detail
     * TERMINATED:
     *   - Clear runtime credentials, record terminatedAt
     */
    private void applyTransitionEffects(Cluster cluster, ClusterState from, ClusterState to, ClusterEventType event) {

        // === PROVISIONING_PENDING → PROVISIONING: async simulated CloudFormation ===
        if (from == ClusterState.PROVISIONING_PENDING && to == ClusterState.PROVISIONING) {
            startAsyncCloudFormation(cluster);
        }

        // === PROVISIONING → READY: set runtime fields ===
        if (from == ClusterState.PROVISIONING && to == ClusterState.READY) {
            cluster.setReadyAt(Instant.now());
            cluster.setEc2InstanceId("i-" + cluster.getId().toHexString().substring(0, 12));
            cluster.setElasticIp("54." + secureRandom.nextInt(256) + "." +
                secureRandom.nextInt(256) + "." + secureRandom.nextInt(256));
            cluster.setPublicDns(cluster.getId().toHexString() + ".dev.cloud.tapstate.com");
            cluster.setLastHeartbeatAt(Instant.now());
        }

        // === PROVISIONING → PROVISIONING_FAILED ===
        if (from == ClusterState.PROVISIONING && to == ClusterState.PROVISIONING_FAILED) {
            cluster.setProvisionErrorCode(io.tapstate.cloud.cluster.ProvisionErrorCode.CLOUDFORMATION_FAILED);
            cluster.setProvisionErrorDetail("{\"error\": \"Simulated provisioning failure\"}");
        }

        if (from == ClusterState.READY && to == ClusterState.DEGRADED) {
            log.warn("[Cluster FSM] cluster {} degraded — heartbeat expired", cluster.getId());
        }

        if (from == ClusterState.DEGRADED && to == ClusterState.READY) {
            log.info("[Cluster FSM] cluster {} recovered from DEGRADED", cluster.getId());
        }

        // === TERMINATED: clear runtime credentials ===
        if (to == ClusterState.TERMINATED) {
            cluster.setTerminatedAt(Instant.now());
            cluster.setSdkStaticToken(null);
            cluster.setBootstrapToken(null);
            log.warn("[Cluster FSM] cluster {} TERMINATED — cleanup triggered", cluster.getId());
        }
    }

    // ========== Async simulated CloudFormation ==========

    private void startAsyncCloudFormation(Cluster cluster) {
        Thread.ofVirtual().start(() -> {
            try {
                // Simulate CloudFormation stack creation delay (5–15s)
                long delay = 5000 + secureRandom.nextInt(10000);
                TimeUnit.MILLISECONDS.sleep(delay);

                Cluster latest = clusterRepository.findById(cluster.getId()).orElse(null);
                if (latest == null || latest.getState() != ClusterState.PROVISIONING) {
                    return; // terminated mid-provision
                }

                // Configurable success rate (default 85%, 100% in IT profile)
                boolean success = secureRandom.nextInt(100) < provisionSuccessRate;
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
