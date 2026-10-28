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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    // Bounded retry policy for the async reclaim worker.
    // 1 original attempt + up to 3 retries (4 total), exponential backoff
    // 30s → 60s → 120s. After exhaustion the allocation stays in RECLAIM_FAILED
    // for operator intervention (instead of silently becoming a DELETED tombstone).
    private static final int MAX_RECLAIM_RETRIES = 3;
    private static final long[] RECLAIM_BACKOFF_MS = {30_000L, 60_000L, 120_000L};

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
     * <p>
     * When called from within an active {@code @Transactional} boundary —
     * specifically from {@code SubscriptionService.finalizeProvisioning}, which
     * persists both the MdbAllocation and the Cluster before scheduling both
     * async workers — the virtual thread cannot observe the uncommitted
     * transition on its first read. We defer to {@code afterCommit} so the
     * durable state is guaranteed to be visible before the worker starts.
     * Outside a transaction we run directly.
     */
    private void scheduleAsyncEffects(ObjectId allocationId,
                                      MdbAllocationStatus from,
                                      MdbAllocationStatus to) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doScheduleAsyncEffects(allocationId, from, to);
                }
            });
            return;
        }
        doScheduleAsyncEffects(allocationId, from, to);
    }

    private void doScheduleAsyncEffects(ObjectId allocationId,
                                        MdbAllocationStatus from,
                                        MdbAllocationStatus to) {

        // === PROVISIONING_PENDING → PROVISIONING: async Atlas DB + user creation ===
        if (from == MdbAllocationStatus.PROVISIONING_PENDING && to == MdbAllocationStatus.PROVISIONING) {
            log.info("[MdbAllocation FSM] scheduling async Atlas provisioning for allocation {}", allocationId);
            Thread.ofVirtual().start(() -> {
                MdbAllocation allocation = loadWithRetry(allocationId);
                if (allocation == null || allocation.getStatus() != MdbAllocationStatus.PROVISIONING) {
                    log.info("[MdbAllocation FSM] Atlas provisioning skipped for allocation {} (state={})",
                        allocationId, allocation == null ? "missing" : allocation.getStatus());
                    return;
                }
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
            Thread.ofVirtual().start(() -> runReclaimWithRetry(allocationId, 0));
        }

        // === RECLAIM_FAILED → DELETING: retry (auto or manual) ===
        if (from == MdbAllocationStatus.RECLAIM_FAILED && to == MdbAllocationStatus.DELETING) {
            log.info("[MdbAllocation FSM] scheduling async Atlas reclaim RETRY for allocation {}", allocationId);
            Thread.ofVirtual().start(() -> {
                // Increment retry counter — the RECLAIM_FAILED record carried
                // the previous count; this transition is a new attempt.
                MdbAllocation latest = allocationRepo.findById(allocationId).orElse(null);
                int retryCount = latest != null ? latest.getReclaimRetryCount() + 1 : 1;
                runReclaimWithRetry(allocationId, retryCount);
            });
        }
    }

    /**
     * Run the Atlas reclaim (dropDatabase + dropDatabaseUser) with bounded
     * exponential-backoff retries. On failure:
     * - If retries remain → transition back to RECLAIM_FAILED + schedule
     *   a delayed retry via the reclaim scheduler (or just the next manual
     *   reclaimMdb call hitting the RECLAIM_FAILED case).
     * - If retries exhausted → stay in RECLAIM_FAILED with nextRetryAt=null,
     *   leaving the allocation visible for operator inspection.
     * <p>
     * Never transitions to DELETED on failure — that was the root cause of
     * orphaned Atlas databases: transient Atlas API errors turned into
     * permanent DELETED tombstones with no remediation path.
     *
     * @param allocationId target allocation id
     * @param retryCount   number of retries consumed so far (0 on first attempt)
     */
    private void runReclaimWithRetry(ObjectId allocationId, int retryCount) {
        MdbAllocation allocation = allocationRepo.findById(allocationId)
            .orElseThrow(() -> new IllegalStateException("MdbAllocation not found: " + allocationId));

        long startedAt = System.currentTimeMillis();
        try {
            // Same order as before: databases first, then user (user has
            // dbAdmin on those DBs, so it must still hold them to drop them).
            recorder.start(allocation, DeploymentOperation.DROP_DATABASE);
            atlasMdbService.dropDatabase(allocation);
            recorder.succeed(allocation, DeploymentOperation.DROP_DATABASE, "Databases dropped");
            recorder.start(allocation, DeploymentOperation.DROP_DATABASE_USER);
            atlasMdbService.dropDatabaseUser(allocation);
            recorder.succeed(allocation, DeploymentOperation.DROP_DATABASE_USER, "Database user dropped");
            log.info("[MdbAllocation FSM] Atlas reclaim succeeded for allocation {} (retry={}) in {}ms",
                allocation.getId(), retryCount, System.currentTimeMillis() - startedAt);
            // Reset retry counters on success — the persistent allocation is
            // about to become DELETED anyway, but being clean helps if a
            // future version needs to distinguish "was retried" tombstones.
            MdbAllocation clean = allocationRepo.findById(allocationId).orElse(allocation);
            clean.setReclaimRetryCount(0);
            clean.setReclaimNextRetryAt(null);
            allocationRepo.save(clean);
            transition(allocationId, MdbAllocationEventType.RECLAIM_COMPLETE, "system(atlas-reclaim)");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            log.warn("[MdbAllocation FSM] Atlas reclaim failed for allocation {} (retry={}) after {}ms: {}",
                allocation.getId(), retryCount, elapsed, e.getMessage());

            // Record the in-flight operation as failed for progress.
            MdbAllocation running = allocationRepo.findById(allocationId).orElse(allocation);
            recorder.markRunningFailed(running.getSubscriptionId(),
                io.tapstate.cloud.progress.DeploymentOpType.UNINSTALL, e.getMessage());

            if (retryCount < MAX_RECLAIM_RETRIES) {
                // Schedule a bounded retry. Backoff grows with attempt:
                // 30s → 60s → 120s (MAX_RECLAIM_RETRIES = 3).
                long backoffMs = RECLAIM_BACKOFF_MS[Math.min(retryCount, RECLAIM_BACKOFF_MS.length - 1)];
                MdbAllocation next = allocationRepo.findById(allocationId).orElse(allocation);
                next.setReclaimRetryCount(retryCount);
                next.setReclaimNextRetryAt(Instant.now().plusMillis(backoffMs));
                next.setProvisionErrorCode("ATLAS_RECLAIM_FAILED");
                next.setProvisionErrorDetail(e.getMessage());
                allocationRepo.save(next);
                // Transition to RECLAIM_FAILED so the main flow stops here;
                // the scheduled delayed retry below fires INITIATE_RECLAIM again.
                transition(allocationId, MdbAllocationEventType.RECLAIM_FAILED, "system(atlas-reclaim)");

                log.info("[MdbAllocation FSM] reclaim retry scheduled for allocation {} in {}ms (attempt {}/{})",
                    allocationId, backoffMs, retryCount + 1, MAX_RECLAIM_RETRIES);
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(backoffMs);
                        MdbAllocation scheduled = allocationRepo.findById(allocationId).orElse(null);
                        if (scheduled != null && scheduled.getStatus() == MdbAllocationStatus.RECLAIM_FAILED
                            && scheduled.getReclaimRetryCount() == retryCount) {
                            log.info("[MdbAllocation FSM] running scheduled reclaim retry for allocation {} (attempt {}/{})",
                                allocationId, retryCount + 1, MAX_RECLAIM_RETRIES);
                            transition(allocationId, MdbAllocationEventType.INITIATE_RECLAIM, "system(atlas-reclaim-schedule)");
                        } else {
                            log.info("[MdbAllocation FSM] skipping scheduled reclaim retry for allocation {} (state={}, retryCount={})",
                                allocationId,
                                scheduled == null ? "missing" : scheduled.getStatus(),
                                scheduled == null ? "n/a" : scheduled.getReclaimRetryCount());
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                // All retries exhausted — leave the allocation in RECLAIM_FAILED
                // for operator intervention. The provisionErrorDetail carries
                // the last failure reason; ops can re-fire reclaimMdb manually
                // after investigating (e.g. transient Atlas access-list issue).
                MdbAllocation exhausted = allocationRepo.findById(allocationId).orElse(allocation);
                exhausted.setReclaimRetryCount(retryCount);
                exhausted.setReclaimNextRetryAt(null);
                exhausted.setProvisionErrorCode("ATLAS_RECLAIM_FAILED");
                exhausted.setProvisionErrorDetail("Retries exhausted (" + MAX_RECLAIM_RETRIES + "/" + MAX_RECLAIM_RETRIES
                    + "). Last error: " + e.getMessage());
                allocationRepo.save(exhausted);
                transition(allocationId, MdbAllocationEventType.RECLAIM_FAILED, "system(atlas-reclaim-retries-exhausted)");
                log.error("[MdbAllocation FSM] reclaim retries EXHAUSTED for allocation {} — operator intervention needed. "
                    + "Databases may still exist in Atlas.", allocationId);
            }
        }
    }

    // ========== Cancel convergence ==========

    /**
     * Load the allocation with a short retry loop. After commit the durable
     * state is usually visible on the very first read, but a brief replication
     * lag or GC pause can make it come back empty; polling a few times keeps
     * the worker resilient without adding latency to the happy path.
     */
    private MdbAllocation loadWithRetry(ObjectId allocationId) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            MdbAllocation a = allocationRepo.findById(allocationId).orElse(null);
            if (a != null) {
                return a;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

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
            case READY, RECLAIM_FAILED -> {
                // READY → normal reclaim. RECLAIM_FAILED is a previous reclaim
                // attempt that failed — cancel also retries reclaim to clean
                // up whatever was created before the cancel landed.
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
