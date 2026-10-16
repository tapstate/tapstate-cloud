package io.tapstate.cloud.cluster.fsm;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterPartialWriter;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.cluster.ProvisionErrorCode;
import io.tapstate.cloud.iac.ClusterDomainName;
import io.tapstate.cloud.iac.ClusterProvisioningService;
import io.tapstate.cloud.iac.ClusterProvisioningService.ProvisionResult;
import io.tapstate.cloud.iac.IacProperties;
import io.tapstate.cloud.progress.DeploymentOpType;
import io.tapstate.cloud.progress.DeploymentOperation;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.progress.MockProvisionFailureService;
import io.tapstate.cloud.subscription.ClusterReadyEvent;
import io.tapstate.cloud.subscription.Subscription;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
    private final ClusterPartialWriter clusterPartialWriter;
    private final ClusterProvisioningService clusterProvisioningService;
    private final IacProperties iacProperties;
    private final ClusterDomainName clusterDomainName;
    private final ApplicationEventPublisher eventPublisher;
    private final SubscriptionRepository subscriptionRepository;
    private final DeploymentStepRecorder recorder;
    private final ObjectProvider<MockProvisionFailureService> mockFailureProvider;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * INSTALL operations executed by the mock Cluster worker. The Atlas-owned
     * operations (slot reservation, database + user creation) are recorded by
     * the MDB workers; the mock worker records WAIT_MDB_ALLOCATION itself
     * because no real resolution happens in mock mode.
     */
    private static final Set<DeploymentOperation> MOCK_CLUSTER_OPERATIONS = EnumSet.of(
        DeploymentOperation.GENERATE_SSH_KEY,
        DeploymentOperation.PROVISION_EC2,
        DeploymentOperation.WAIT_SSH_READY,
        DeploymentOperation.RESTRICT_SSH_INGRESS,
        DeploymentOperation.CREATE_DNS_RECORD,
        DeploymentOperation.WAIT_MDB_ALLOCATION,
        DeploymentOperation.START_RUNTIME_STACK,
        DeploymentOperation.CONFIGURE_NGINX,
        DeploymentOperation.WAIT_DNS_PROPAGATION,
        DeploymentOperation.ISSUE_TLS_CERTIFICATE,
        DeploymentOperation.HEALTH_CHECK);

    /** Per-step mock delay — keeps a full mock run around one second. */
    private static final int MOCK_STEP_DELAY_MS = 90;

    @Value("${tapstate.cloud.cluster.provision.success-rate:85}")
    private int provisionSuccessRate;

    public ClusterStateMachineService(
        @Qualifier("clusterStateMachineFactory")
        StateMachineFactory<ClusterState, ClusterEventType> clusterStateMachineFactory,
        ClusterRepository clusterRepository,
        ClusterPartialWriter clusterPartialWriter,
        ClusterProvisioningService clusterProvisioningService,
        IacProperties iacProperties,
        ClusterDomainName clusterDomainName,
        ApplicationEventPublisher eventPublisher,
        SubscriptionRepository subscriptionRepository,
        DeploymentStepRecorder recorder,
        ObjectProvider<MockProvisionFailureService> mockFailureProvider) {
        this.clusterStateMachineFactory = clusterStateMachineFactory;
        this.clusterRepository = clusterRepository;
        this.clusterPartialWriter = clusterPartialWriter;
        this.clusterProvisioningService = clusterProvisioningService;
        this.iacProperties = iacProperties;
        this.clusterDomainName = clusterDomainName;
        this.eventPublisher = eventPublisher;
        this.subscriptionRepository = subscriptionRepository;
        this.recorder = recorder;
        this.mockFailureProvider = mockFailureProvider;
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
            // Synchronous field mutations are applied before persisting so the
            // new state and its side-effect fields become visible atomically;
            // async cloud operations are scheduled only after the write.
            applySynchronousMutations(cluster, prevState, newState, event);
            clusterPartialWriter.apply(cluster);

            scheduleAsyncEffects(cluster, prevState, newState, event);

            // Notify the subscription resource-readiness join once the READY
            // state (and its synchronous fields) are durable.
            if (newState == ClusterState.READY) {
                eventPublisher.publishEvent(new ClusterReadyEvent(clusterId));
            }

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
            // Archive the failed attempts so the progress view exposes the
            // retry; must happen before the new worker starts the steps again.
            if (cluster.getSubscriptionId() != null) {
                recorder.markRetry(cluster.getSubscriptionId(), DeploymentOpType.INSTALL);
            }
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
            } else {
                recordMockTermination(cluster);
            }
            log.warn("[Cluster FSM] cluster {} TERMINATED", cluster.getId());
        }
    }

    // ========== Real-mode async effects ==========

    /** Provision infrastructure on a virtual thread and feed the result back. */
    private void startAsyncProvisioning(ObjectId clusterId) {
        log.info("[Cluster FSM] scheduling real provisioning for cluster {}", clusterId);
        Thread.ofVirtual().start(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null || latest.getState() != ClusterState.PROVISIONING) {
                    log.info("[Cluster FSM] real provisioning skipped for cluster {} (state={})",
                        clusterId, latest == null ? "missing" : latest.getState());
                    return;
                }

                ProvisionResult result = clusterProvisioningService.provision(latest);
                log.info("[Cluster FSM] real provisioning finished for cluster {} in {}ms success={}",
                    clusterId, System.currentTimeMillis() - startedAt, result.success());
                if (result.success()) {
                    transition(clusterId, ClusterEventType.PROVISION_SUCCESS, "system:iac");
                } else {
                    transition(clusterId, ClusterEventType.PROVISION_FAIL, "system:iac");
                }
                // Converge a cancellation that landed while provisioning was running.
                convergeCancelIfNeeded(clusterId);
            } catch (Exception e) {
                log.error("[Cluster FSM] provisioning error for cluster {} after {}ms: {}",
                    clusterId, System.currentTimeMillis() - startedAt, e.getMessage());
            }
        });
    }

    /** Sweep leftovers from the failed attempt, then re-enter provisioning. */
    private void scheduleRetry(ObjectId clusterId) {
        log.info("[Cluster FSM] scheduling real compensation+retry for cluster {}", clusterId);
        Thread.ofVirtual().start(() -> {
            try {
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null) {
                    log.info("[Cluster FSM] real retry skipped, cluster {} no longer exists", clusterId);
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
        log.info("[Cluster FSM] scheduling real resource termination for cluster {}", clusterId);
        Thread.ofVirtual().start(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null) {
                    log.info("[Cluster FSM] resource termination skipped, cluster {} no longer exists", clusterId);
                    return;
                }
                clusterProvisioningService.terminate(latest);
                log.info("[Cluster FSM] resource termination finished for cluster {} in {}ms",
                    clusterId, System.currentTimeMillis() - startedAt);
            } catch (Exception e) {
                log.error("[Cluster FSM] resource cleanup failed for cluster {} after {}ms: {}",
                    clusterId, System.currentTimeMillis() - startedAt, e.getMessage());
            }
        });
    }

    // ========== Mock-mode async effects ==========

    /**
     * Simulate provisioning operation by operation, recording progress for
     * each Cluster-owned step. The attempt outcome is decided first by the
     * success rate, then overridden deterministically when a one-shot failure
     * fixture exists for the organization (test injection).
     */
    private void startAsyncMockProvisioning(ObjectId clusterId) {
        boolean rateSucceed = secureRandom.nextInt(100) < provisionSuccessRate;
        log.info("[Cluster FSM] scheduling mock provisioning for cluster {} successRate={}%",
            clusterId, provisionSuccessRate);
        Thread.ofVirtual().start(() -> {
            try {
                Cluster latest = clusterRepository.findById(clusterId).orElse(null);
                if (latest == null || latest.getState() != ClusterState.PROVISIONING) {
                    log.info("[Cluster FSM] mock provisioning skipped for cluster {} (state={})",
                        clusterId, latest == null ? "missing" : latest.getState());
                    return;
                }

                // Test injection wins over the random rate. The fixture is
                // consumed here so the retry attempt succeeds.
                MockProvisionFailureService failureService = mockFailureProvider.getIfAvailable();
                String injectedFailOperation = failureService != null
                    ? failureService.consumeFailure(latest.getOrgId()) : null;
                boolean willSucceed = injectedFailOperation == null && rateSucceed;

                boolean failed = false;
                List<DeploymentOperation> ordered = DeploymentOperation.byOpType(DeploymentOpType.INSTALL);
                for (DeploymentOperation operation : ordered) {
                    if (!MOCK_CLUSTER_OPERATIONS.contains(operation)) {
                        continue; // recorded by the Atlas workers
                    }
                    sleepQuietly(MOCK_STEP_DELAY_MS);
                    if (!willSucceed && !failed && mustFailAt(operation, injectedFailOperation)) {
                        recorder.fail(latest, operation,
                            "Mock provisioning failure injected at " + operation.code());
                        failed = true;
                        break;
                    }
                    recorder.start(latest, operation);
                    sleepQuietly(20);
                    recorder.succeed(latest, operation, operation.displayName());
                }

                if (!failed && !willSucceed) {
                    // An injected code outside the mock cluster operations — fail the last step.
                    DeploymentOperation healthCheck = DeploymentOperation.HEALTH_CHECK;
                    recorder.fail(latest, healthCheck,
                        "Mock provisioning failure injected: " + injectedFailOperation);
                    failed = true;
                }

                // Reload before the terminal transition — the state may have
                // changed while the simulation was sleeping.
                Cluster current = clusterRepository.findById(clusterId).orElse(null);
                if (current == null || current.getState() != ClusterState.PROVISIONING) {
                    log.info("[Cluster FSM] mock provisioning not completing cluster {} (state={})",
                        clusterId, current == null ? "missing" : current.getState());
                    return;
                }
                log.info("[Cluster FSM] mock provisioning completing cluster {} outcome={}",
                    clusterId, failed ? "PROVISION_FAIL" : "PROVISION_SUCCESS");
                if (failed) {
                    transition(clusterId, ClusterEventType.PROVISION_FAIL, "mock:provision");
                } else {
                    transition(clusterId, ClusterEventType.PROVISION_SUCCESS, "mock:provision");
                }
                // Converge a cancellation that landed while provisioning was running.
                convergeCancelIfNeeded(clusterId);
            } catch (Exception e) {
                log.error("[Cluster FSM] mock provisioning error for cluster {}: {}",
                    clusterId, e.getMessage());
            }
        });
    }

    /** Whether the simulated failure must happen at this operation. */
    private static boolean mustFailAt(DeploymentOperation operation, String injectedFailOperation) {
        if (injectedFailOperation != null) {
            return injectedFailOperation.equals(operation.code());
        }
        // Rate-driven random failure — fail at the last operation for determinism.
        return operation == DeploymentOperation.HEALTH_CHECK;
    }

    /** Sleep without throwing — interruptions end the current simulation step. */
    private static void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Record the simulated UNINSTALL operations when a mock Cluster becomes
     * TERMINATED — no real EC2 or DNS exists in mock mode.
     */
    private void recordMockTermination(Cluster cluster) {
        if (cluster.getSubscriptionId() == null) {
            return;
        }
        recorder.markStart(cluster.getOrgId(), cluster.getSubscriptionId(),
            DeploymentOpType.UNINSTALL, DeploymentOperation.TERMINATE_EC2);
        recorder.succeed(cluster, DeploymentOperation.TERMINATE_EC2, "EC2 node terminated (mock)");
        recorder.markStart(cluster.getOrgId(), cluster.getSubscriptionId(),
            DeploymentOpType.UNINSTALL, DeploymentOperation.DELETE_DNS_RECORD);
        recorder.succeed(cluster, DeploymentOperation.DELETE_DNS_RECORD, "DNS record deleted (mock)");
    }

    // ========== Cancel convergence ==========

    /**
     * Converge a subscription that was cancelled while its Cluster was being
     * provisioned: after the provisioning attempt ended, a READY or
     * PROVISIONING_FAILED Cluster is immediately driven into termination. No
     * new Cluster state-machine states are introduced.
     */
    private void convergeCancelIfNeeded(ObjectId clusterId) {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null || cluster.getSubscriptionId() == null) {
            return;
        }
        Subscription subscription =
            subscriptionRepository.findById(cluster.getSubscriptionId()).orElse(null);
        if (subscription == null || subscription.getStatus() != SubscriptionStatus.CANCELLED) {
            return;
        }

        Cluster latest = clusterRepository.findById(clusterId).orElse(cluster);
        if (latest.getState() == ClusterState.READY
            || latest.getState() == ClusterState.PROVISIONING_FAILED) {
            transition(clusterId, ClusterEventType.INITIATE_TERMINATION,
                "system:cancel-converge");
        }
    }

    /** Re-enter provisioning after a short delay. */
    private void scheduleMockRetry(ObjectId clusterId) {
        log.info("[Cluster FSM] scheduling mock retry for cluster {} delayMs=1000", clusterId);
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
        cluster.setPublicDns(clusterDomainName.fqdn(cluster));
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
