package io.tapstate.cloud.mdb.fsm;

import io.tapstate.cloud.mdb.AtlasMdbService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import io.tapstate.cloud.progress.DeploymentOperation;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.MdbAllocationReadyEvent;
import io.tapstate.cloud.subscription.Subscription;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * MdbAllocation state machine orchestrator — mirrors the Cluster FSM ordering:
 * synchronous state/field changes, a single persisted save, and only then the
 * scheduling of asynchronous side-effects.
 * <p>
 * Persistence ordering is load-bearing. The async provisioning/reclaim workers
 * persist their own terminal state on freshly loaded entities, so the caller
 * must never re-save the transition entity after scheduling: without optimistic
 * locking a blind whole-document save can serialize a stale PROVISIONING and
 * overwrite the durable READY (lost update). Synchronous effects therefore run
 * before the one and only save for this transition; every worker then reloads
 * its own allocation by id and never shares object identity with this document.
 *
 * <ul>
 *   <li>PROVISIONING_PENDING → PROVISIONING: async Atlas DB + user creation</li>
 *   <li>PROVISIONING → READY: ready timestamp</li>
 *   <li>PROVISIONING → PROVISIONING_FAILED: error detail persisted by the worker</li>
 *   <li>READY → DELETING: async Atlas teardown (databases, then user)</li>
 *   <li>DELETING → DELETED: deletion timestamp</li>
 * </ul>
 */
@Service
@Slf4j
public class MdbAllocationStateMachineService {

    private final StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> mdbAllocationStateMachineFactory;
    private final MdbAllocationRepository allocationRepo;
    private final AtlasMdbService atlasMdbService;
    private final ApplicationEventPublisher eventPublisher;
    private final SubscriptionRepository subscriptionRepo;
    private final DeploymentStepRecorder recorder;

    public MdbAllocationStateMachineService(
        @Qualifier("mdbAllocationStateMachineFactory")
        StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> mdbAllocationStateMachineFactory,
        MdbAllocationRepository allocationRepo,
        AtlasMdbService atlasMdbService,
        ApplicationEventPublisher eventPublisher,
        SubscriptionRepository subscriptionRepo,
        DeploymentStepRecorder recorder) {
        this.mdbAllocationStateMachineFactory = mdbAllocationStateMachineFactory;
        this.allocationRepo = allocationRepo;
        this.atlasMdbService = atlasMdbService;
        this.eventPublisher = eventPublisher;
        this.subscriptionRepo = subscriptionRepo;
        this.recorder = recorder;
    }

    /**
     * Send an event to the MdbAllocation state machine and persist the new state.
     *
     * @param allocationId target allocation UUID
     * @param event        event to fire
     * @param operator     human / system identifier (for audit)
     */
    public TransitionResult transition(ObjectId allocationId, MdbAllocationEventType event, String operator) {
        MdbAllocation allocation = allocationRepo.findById(allocationId)
            .orElseThrow(() -> new IllegalStateException("MdbAllocation not found: " + allocationId));

        StateMachine<MdbAllocationStatus, MdbAllocationEventType> sm =
            mdbAllocationStateMachineFactory.getStateMachine("mdb-" + allocationId.toHexString());

        sm.start();
        resetTo(sm, allocation.getStatus());

        boolean accepted = sm.sendEvent(event);

        TransitionResult result;
        if (accepted) {
            MdbAllocationStatus newStatus = sm.getState().getId();
            MdbAllocationStatus prevStatus = allocation.getStatus();

            // Synchronous field changes go on first, then the document is
            // persisted exactly once; asynchronous workers are scheduled only
            // after that save returns and reload their own entity by id. This
            // ordering is the fix for the lost update where a stale second save
            // of this document overwrote the worker's durable READY.
            allocation.setStatus(newStatus);
            allocation.setUpdatedAt(Instant.now());
            applySynchronousEffects(allocation, prevStatus, newStatus);
            allocationRepo.save(allocation);
            scheduleAsyncEffects(allocationId, prevStatus, newStatus);

            // Notify the subscription resource-readiness join once READY is durable.
            if (newStatus == MdbAllocationStatus.READY) {
                eventPublisher.publishEvent(new MdbAllocationReadyEvent(
                    allocationId, allocation.getClusterId(), allocation.getSubscriptionId()));
            }

            log.info("[MdbAllocation FSM] {} allocation {}: {} → {} on event={}",
                operator, allocationId, prevStatus, newStatus, event);
            result = TransitionResult.accepted(newStatus);
        } else {
            log.warn("[MdbAllocation FSM] {} allocation {}: event {} rejected from state {}",
                operator, allocationId, event, allocation.getStatus());
            result = TransitionResult.rejected(allocation.getStatus());
        }

        sm.stop();
        return result;
    }

    private void resetTo(StateMachine<MdbAllocationStatus, MdbAllocationEventType> sm,
                         MdbAllocationStatus persistedStatus) {
        sm.getStateMachineAccessor()
            .doWithAllRegions(accessor -> accessor.resetStateMachine(
                new DefaultStateMachineContext<>(persistedStatus, null, null, null)
            ));
    }

    /**
     * Apply synchronous side-effects that mutate the transition's entity —
     * these run before the entity is saved.
     */
    private void applySynchronousEffects(MdbAllocation allocation,
                                         MdbAllocationStatus from,
                                         MdbAllocationStatus to) {
        // === PROVISIONING → READY ===
        if (from == MdbAllocationStatus.PROVISIONING && to == MdbAllocationStatus.READY) {
            allocation.setReadyAt(Instant.now());
        }

        // === DELETING → DELETED ===
        if (to == MdbAllocationStatus.DELETED) {
            allocation.setDeletedAt(Instant.now());
        }
    }

    /**
     * Schedule asynchronous side-effects after the transition document has
     * been made durable. Every worker loads its own allocation instance so its
     * writes never share object identity with a caller thread's document.
     */
    private void scheduleAsyncEffects(ObjectId allocationId,
                                      MdbAllocationStatus from,
                                      MdbAllocationStatus to) {

        // === PROVISIONING_PENDING → PROVISIONING: async Atlas DB + user creation ===
        if (from == MdbAllocationStatus.PROVISIONING_PENDING && to == MdbAllocationStatus.PROVISIONING) {
            log.info("[MdbAllocation FSM] scheduling async Atlas provisioning for allocation {}", allocationId);
            Thread.ofVirtual().start(() -> {
                MdbAllocation allocation = allocationRepo.findById(allocationId)
                    .orElseThrow(() -> new IllegalStateException("MdbAllocation not found: " + allocationId));
                long startedAt = System.currentTimeMillis();
                try {
                    // First create database (connectivity check + seed)
                    recorder.start(allocation, DeploymentOperation.CREATE_DATABASE);
                    atlasMdbService.createDatabase(allocation);
                    recorder.succeed(allocation, DeploymentOperation.CREATE_DATABASE, "Database created");
                    // Then create database user with readWrite on that DB
                    recorder.start(allocation, DeploymentOperation.CREATE_DATABASE_USER);
                    atlasMdbService.createDatabaseUser(allocation);
                    recorder.succeed(allocation, DeploymentOperation.CREATE_DATABASE_USER, "Database user created");
                    log.info("[MdbAllocation FSM] Atlas provisioning succeeded for allocation {} in {}ms",
                        allocation.getId(), System.currentTimeMillis() - startedAt);
                    // READY is written solely by the PROVISION_SUCCESS
                    // transition below. The reload guard keeps a duplicate or
                    // already-advanced allocation from sending a spurious event.
                    MdbAllocation fresh = allocationRepo.findById(allocation.getId()).orElse(null);
                    if (fresh != null && fresh.getStatus() == MdbAllocationStatus.PROVISIONING) {
                        transition(allocation.getId(), MdbAllocationEventType.PROVISION_SUCCESS, "system(atlas-provision)");
                    } else {
                        log.info("[MdbAllocation FSM] allocation {} already in status {} after provisioning; skipping PROVISION_SUCCESS",
                            allocation.getId(), fresh == null ? "missing" : fresh.getStatus());
                    }
                    // Converge a cancellation that landed while provisioning was running.
                    convergeCancelIfNeeded(allocation.getId());
                } catch (Exception e) {
                    log.error("[MdbAllocation FSM] Atlas provisioning failed for allocation {} after {}ms: {}",
                        allocation.getId(), System.currentTimeMillis() - startedAt, e.getMessage());
                    // Record the failed operation explicitly for progress — the
                    // failure happened inside either createDatabase or createDatabaseUser.
                    MdbAllocation latestForFail = allocationRepo.findById(allocation.getId()).orElse(allocation);
                    markProvisionFailureStep(latestForFail, e.getMessage());
                    // Record error detail before firing PROVISION_FAIL
                    MdbAllocation latest = allocationRepo.findById(allocation.getId()).orElse(allocation);
                    latest.setProvisionErrorCode("ATLAS_PROVISION_FAILED");
                    latest.setProvisionErrorDetail(e.getMessage());
                    allocationRepo.save(latest);
                    transition(allocation.getId(), MdbAllocationEventType.PROVISION_FAIL, "system(atlas-provision)");
                    // A failed allocation of a CANCELLED subscription is reclaimed directly.
                    convergeCancelIfNeeded(allocation.getId());
                }
            });
        }

        // === READY → DELETING: async Atlas teardown ===
        if (from == MdbAllocationStatus.READY && to == MdbAllocationStatus.DELETING) {
            log.info("[MdbAllocation FSM] scheduling async Atlas reclaim for allocation {}", allocationId);
            Thread.ofVirtual().start(() -> {
                MdbAllocation allocation = allocationRepo.findById(allocationId)
                    .orElseThrow(() -> new IllegalStateException("MdbAllocation not found: " + allocationId));
                long startedAt = System.currentTimeMillis();
                try {
                    // Order matters: databases are dropped over a Mongo connection
                    // authenticated as the per-allocation dbUser, so the user must
                    // be deleted only AFTER its databases are gone.
                    recorder.start(allocation, DeploymentOperation.DROP_DATABASE);
                    atlasMdbService.dropDatabase(allocation);
                    recorder.succeed(allocation, DeploymentOperation.DROP_DATABASE, "Databases dropped");
                    recorder.start(allocation, DeploymentOperation.DROP_DATABASE_USER);
                    atlasMdbService.dropDatabaseUser(allocation);
                    recorder.succeed(allocation, DeploymentOperation.DROP_DATABASE_USER, "Database user dropped");
                    log.info("[MdbAllocation FSM] Atlas reclaim succeeded for allocation {} in {}ms",
                        allocation.getId(), System.currentTimeMillis() - startedAt);
                    transition(allocation.getId(), MdbAllocationEventType.RECLAIM_COMPLETE, "system(atlas-reclaim)");
                } catch (Exception e) {
                    log.warn("[MdbAllocation FSM] Atlas reclaim failed for allocation {} after {}ms: {}",
                        allocation.getId(), System.currentTimeMillis() - startedAt, e.getMessage());
                    // Record the in-flight operation as failed for progress.
                    MdbAllocation running = allocationRepo.findById(allocation.getId()).orElse(allocation);
                    recorder.markRunningFailed(running.getSubscriptionId(),
                        io.tapstate.cloud.progress.DeploymentOpType.UNINSTALL, e.getMessage());
                    // Keep the failure cause on the record before it becomes a
                    // DELETED tombstone — reclaim is terminal regardless.
                    MdbAllocation latest = allocationRepo.findById(allocation.getId()).orElse(allocation);
                    latest.setProvisionErrorCode("ATLAS_RECLAIM_FAILED");
                    latest.setProvisionErrorDetail(e.getMessage());
                    allocationRepo.save(latest);
                    transition(allocation.getId(), MdbAllocationEventType.RECLAIM_FAILED, "system(atlas-reclaim)");
                }
            });
        }
    }

    // ========== Cancel convergence ==========

    /**
     * Mark the in-flight INSTALL operation as FAILED after an Atlas provisioning
     * exception. A not-started CREATE_DATABASE_USER is left PENDING.
     */
    private void markProvisionFailureStep(MdbAllocation allocation, String message) {
        MdbAllocation fresh = allocationRepo.findById(allocation.getId()).orElse(allocation);
        recorder.markRunningFailed(fresh.getSubscriptionId(),
            io.tapstate.cloud.progress.DeploymentOpType.INSTALL, message);
    }

    /**
     * Converge a subscription that was cancelled while its Atlas provisioning
     * was in flight: a READY allocation starts the normal reclaim; a
     * PROVISIONING_FAILED allocation has nothing on Atlas and is finalized as a
     * DELETED tombstone. No new state-machine states are introduced.
     */
    private void convergeCancelIfNeeded(ObjectId allocationId) {
        MdbAllocation allocation = allocationRepo.findById(allocationId).orElse(null);
        if (allocation == null || allocation.getSubscriptionId() == null) {
            return;
        }
        Subscription subscription = subscriptionRepo.findById(allocation.getSubscriptionId()).orElse(null);
        if (subscription == null || subscription.getStatus() != SubscriptionStatus.CANCELLED) {
            return;
        }

        MdbAllocation latest = allocationRepo.findById(allocationId).orElse(allocation);
        switch (latest.getStatus()) {
            case READY -> {
                transition(allocationId, MdbAllocationEventType.INITIATE_RECLAIM,
                    "system:cancel-converge");
            }
            case PROVISIONING_FAILED -> {
                MdbAllocation tombstone = allocationRepo.findById(allocationId).orElse(latest);
                tombstone.setStatus(MdbAllocationStatus.DELETED);
                tombstone.setDeletedAt(Instant.now());
                tombstone.setUpdatedAt(Instant.now());
                allocationRepo.save(tombstone);
                recorder.succeed(tombstone, DeploymentOperation.DROP_DATABASE,
                    "No databases provisioned — nothing to drop");
                recorder.succeed(tombstone, DeploymentOperation.DROP_DATABASE_USER,
                    "No database user provisioned — nothing to drop");
            }
            default -> {
                // DELETING/DELETED/PROVISIONING — another path is already converging.
            }
        }
    }

    // ========== Result DTO ==========

    public record TransitionResult(boolean accepted, MdbAllocationStatus newStatus) {
        public static TransitionResult accepted(MdbAllocationStatus s) { return new TransitionResult(true, s); }
        public static TransitionResult rejected(MdbAllocationStatus s) { return new TransitionResult(false, s); }
    }
}
