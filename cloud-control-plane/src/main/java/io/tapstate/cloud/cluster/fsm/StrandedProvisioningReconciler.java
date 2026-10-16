package io.tapstate.cloud.cluster.fsm;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterPartialWriter;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.cluster.ProvisionErrorCode;
import io.tapstate.cloud.progress.DeploymentOpType;
import io.tapstate.cloud.progress.DeploymentStep;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.progress.DeploymentStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Converges a Cluster whose provisioning driver is gone.
 * <p>
 * Provisioning runs on an in-process virtual thread owned by one control-plane
 * replica ({@link ClusterStateMachineService#scheduleAsyncEffects}). That worker
 * is the only thing that can move a Cluster out of {@code PROVISIONING_PENDING}
 * or {@code PROVISIONING}: it fires {@code PROVISION_SUCCESS} / {@code PROVISION_FAIL}
 * when it returns. When its process disappears mid-run — a rolling update, a
 * crash, an evicted pod — the work and the state transition are lost together:
 * the Cluster keeps no failure code, the in-flight deployment step stays
 * {@code RUNNING}, and retry is refused because it is accepted only from
 * {@code PROVISIONING_FAILED}. The Cluster then shows {@code provisioning}
 * forever with no recovery action available in the product.
 * <p>
 * This reconciler removes that dead end by requiring every transient provisioning
 * state to be backed by recent activity:
 * <ul>
 *   <li>{@code PROVISIONING} with no step activity inside the budget → the run is
 *       failed visibly with a coded reason, so the Cluster reaches
 *       {@code PROVISIONING_FAILED} and the existing retry path (which compensates
 *       leftovers and provisions again) becomes reachable.</li>
 *   <li>{@code PROVISIONING_PENDING} with no step activity inside the budget → the
 *       subscription flow died between creating the Cluster and calling
 *       {@code startProvisioning}. Nothing has been created at that point, so the
 *       event the flow never sent is fired here.</li>
 * </ul>
 * <p>
 * The liveness signal is the deployment-step records, which is why they — and not
 * {@code Cluster.updatedAt} — are read: every accepted C2 heartbeat moves
 * {@code Cluster.updatedAt}, so it cannot tell a live run from an abandoned one,
 * and it is exactly the case of a heartbeat-writing Cluster stuck in
 * {@code PROVISIONING} that must be caught. A step record is written only by the
 * provisioning workers, and every step carries an explicit timeout shorter than
 * the budget, so a live run always refreshes it in time.
 * <p>
 * A heartbeat deliberately does <b>not</b> promote {@code PROVISIONING → READY}:
 * the runtime starts before the node's nginx/TLS installation and the end-to-end
 * health check, so a reporting runtime proves the container is up and nothing more.
 * <p>
 * Reconciling is idempotent: the work is derived from stored state on each tick, so
 * replicas ticking together (or a tick that failed halfway) repeat the same
 * convergence instead of compounding it.
 */
@Component
@Slf4j
public class StrandedProvisioningReconciler {

    /**
     * How long a provisioning run may pass without step activity before its driver
     * is presumed gone. The longest single step timeout is 15 minutes
     * (CloudFormation stack create), so a live run always writes within it.
     */
    private static final Duration STALL_BUDGET = Duration.ofMinutes(30);

    private final ClusterRepository clusterRepository;
    private final DeploymentStepRepository stepRepository;
    private final DeploymentStepRecorder recorder;
    private final ClusterPartialWriter clusterPartialWriter;
    private final ClusterStateMachineService clusterStateMachineService;

    public StrandedProvisioningReconciler(ClusterRepository clusterRepository,
                                         DeploymentStepRepository stepRepository,
                                         DeploymentStepRecorder recorder,
                                         ClusterPartialWriter clusterPartialWriter,
                                         ClusterStateMachineService clusterStateMachineService) {
        this.clusterRepository = clusterRepository;
        this.stepRepository = stepRepository;
        this.recorder = recorder;
        this.clusterPartialWriter = clusterPartialWriter;
        this.clusterStateMachineService = clusterStateMachineService;
    }

    /**
     * Sweep both transient provisioning states. Runs immediately at startup — the
     * run this replica took over from is already stranded, and that is the moment
     * the operator is watching it — and on a fixed delay afterwards.
     */
    @Scheduled(fixedDelayString = "${tapstate.cloud.cluster.provision.reconcile-interval-ms:300000}")
    public void reconcile() {
        Instant deadline = Instant.now().minus(STALL_BUDGET);
        int failed = 0;
        int restarted = 0;

        for (Cluster cluster : clusterRepository.findByState(ClusterState.PROVISIONING)) {
            Optional<Instant> lastActivity = lastStepActivity(cluster);
            if (lastActivity.isEmpty() || !lastActivity.get().isBefore(deadline)) {
                continue;
            }
            if (failStranded(cluster, lastActivity.get())) {
                failed++;
            }
        }

        for (Cluster cluster : clusterRepository.findByState(ClusterState.PROVISIONING_PENDING)) {
            Optional<Instant> lastActivity = lastStepActivity(cluster);
            if (lastActivity.isEmpty() || !lastActivity.get().isBefore(deadline)) {
                continue;
            }
            if (restartPending(cluster, lastActivity.get())) {
                restarted++;
            }
        }

        if (failed > 0 || restarted > 0) {
            log.warn("[Provision Reconcile] stranded runs converged: failed={} restarted={} budget={}",
                failed, restarted, STALL_BUDGET);
        }
    }

    /**
     * Fail a stranded run so it stops being invisible: record the coded reason,
     * fail the step the run was executing, then drive the state transition. The
     * Cluster reaches {@code PROVISIONING_FAILED} with a reason and an operator
     * recovery path, instead of sitting in {@code PROVISIONING}.
     */
    private boolean failStranded(Cluster cluster, Instant lastActivity) {
        String clusterId = cluster.getId().toHexString();
        String reason = "Provisioning made no progress for " + STALL_BUDGET.toMinutes()
            + " minutes (last step activity " + lastActivity
            + "); the control-plane process driving it is gone. Retry to provision again.";
        try {
            // The transition reloads the Cluster from the store, so the reason has
            // to be durable before it runs; the state machine keeps a non-null code.
            cluster.setProvisionErrorCode(ProvisionErrorCode.PROVISIONING_TIMEOUT);
            cluster.setProvisionErrorDetail(ProvisionErrorCode.PROVISIONING_TIMEOUT + ": " + reason);
            clusterPartialWriter.apply(cluster);

            if (cluster.getSubscriptionId() != null) {
                recorder.markRunningFailed(cluster.getSubscriptionId(), DeploymentOpType.INSTALL, reason);
            }

            ClusterStateMachineService.TransitionResult result = clusterStateMachineService.transition(
                cluster.getId(), ClusterEventType.PROVISION_FAIL, "system:provisioning-reconciler");
            log.warn("[Provision Reconcile] cluster={} stranded run failed ({}, lastActivity={})",
                clusterId, result.accepted() ? "PROVISIONING_FAILED" : "already moved: " + result.newState(),
                lastActivity);
            return result.accepted();
        } catch (Exception e) {
            log.error("[Provision Reconcile] failing stranded cluster {} failed: {}",
                clusterId, e.getMessage());
            return false;
        }
    }

    /**
     * Re-drive a Cluster whose subscription flow died before starting provisioning.
     * Safe by construction: the process that schedules the run persists
     * {@code PROVISIONING} before any cloud resource is created, so a Cluster still
     * in {@code PROVISIONING_PENDING} has nothing to compensate.
     */
    private boolean restartPending(Cluster cluster, Instant lastActivity) {
        String clusterId = cluster.getId().toHexString();
        try {
            ClusterStateMachineService.TransitionResult result = clusterStateMachineService.transition(
                cluster.getId(), ClusterEventType.START_PROVISION, "system:provisioning-reconciler");
            log.warn("[Provision Reconcile] cluster={} pending run restarted ({}, lastActivity={})",
                clusterId, result.accepted() ? "PROVISIONING" : "already moved: " + result.newState(),
                lastActivity);
            return result.accepted();
        } catch (Exception e) {
            log.error("[Provision Reconcile] restarting pending cluster {} failed: {}",
                clusterId, e.getMessage());
            return false;
        }
    }

    /**
     * Most recent activity of this Cluster's INSTALL steps, or empty when the
     * Cluster has no step records at all — a Cluster from before the progress
     * records existed, whose provisioning state cannot be judged from here.
     */
    private Optional<Instant> lastStepActivity(Cluster cluster) {
        if (cluster.getSubscriptionId() == null) {
            return Optional.empty();
        }
        List<DeploymentStep> steps = stepRepository.findBySubscriptionIdAndOpTypeOrderBySequenceAsc(
            cluster.getSubscriptionId(), DeploymentOpType.INSTALL);
        Instant latest = null;
        for (DeploymentStep step : steps) {
            Instant stamp = step.getUpdatedAt() != null ? step.getUpdatedAt() : step.getCreatedAt();
            if (stamp != null && (latest == null || stamp.isAfter(latest))) {
                latest = stamp;
            }
        }
        return Optional.ofNullable(latest);
    }
}
