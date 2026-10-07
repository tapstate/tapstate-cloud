package io.tapstate.cloud.mdb.fsm;

import io.tapstate.cloud.mdb.AtlasMdbService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import io.tapstate.cloud.subscription.MdbAllocationReadyEvent;
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
 * Persistence ordering is load-bearing. The async provisioning/reclaim threads
 * share the in-memory entity and persist their own terminal state, so the
 * caller must never re-save that entity after scheduling: without optimistic
 * locking a blind whole-document save can serialize a stale PROVISIONING and
 * overwrite the durable READY (lost update). All side-effects therefore run
 * after the one and only save for this transition.
 * <ul>
 *   <li>PROVISIONING_PENDING → PROVISIONING: async Atlas DB + user creation</li>
 *   <li>PROVISIONING → READY: ready timestamp</li>
 *   <li>PROVISIONING → PROVISIONING_FAILED: async error detail</li>
 *   <li>INITIATE_RECLAIM → DELETING: async Atlas teardown (databases, then user)</li>
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

    public MdbAllocationStateMachineService(
        @Qualifier("mdbAllocationStateMachineFactory")
        StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> mdbAllocationStateMachineFactory,
        MdbAllocationRepository allocationRepo,
        AtlasMdbService atlasMdbService,
        ApplicationEventPublisher eventPublisher) {
        this.mdbAllocationStateMachineFactory = mdbAllocationStateMachineFactory;
        this.allocationRepo = allocationRepo;
        this.atlasMdbService = atlasMdbService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Send an event to the MdbAllocation state machine and persist the new state.
     *
     * @param allocationId target allocation id
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
            MdbAllocationStatus prevStatus = allocation.getStatus();
            MdbAllocationStatus newStatus = sm.getState().getId();

            // 1) Apply synchronous field changes on the loaded entity.
            allocation.setStatus(newStatus);
            allocation.setUpdatedAt(Instant.now());
            applySynchronousFields(allocation, newStatus);

            // 2) Persist exactly once, before any async work touches the entity.
            allocationRepo.save(allocation);

            // 3) Only now schedule asynchronous side-effects. The entity is
            // never saved again on this calling path, so no stale overwrite.
            scheduleAsyncEffects(allocation, prevStatus, newStatus);

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
     * Synchronous timestamp fields derived purely from the target state.
     */
    private void applySynchronousFields(MdbAllocation allocation, MdbAllocationStatus to) {
        if (to == MdbAllocationStatus.READY) {
            allocation.setReadyAt(Instant.now());
        }
        if (to == MdbAllocationStatus.DELETED) {
            allocation.setDeletedAt(Instant.now());
        }
    }

    /**
     * Schedule asynchronous side-effects after the transition has been persisted.
     */
    private void scheduleAsyncEffects(MdbAllocation allocation,
                                      MdbAllocationStatus from,
                                      MdbAllocationStatus to) {
        if (from == MdbAllocationStatus.PROVISIONING_PENDING
            && to == MdbAllocationStatus.PROVISIONING) {
            scheduleProvisioning(allocation);
        }

        if (from == MdbAllocationStatus.READY
            && to == MdbAllocationStatus.DELETING) {
            scheduleReclaim(allocation);
        }
    }

    private void scheduleProvisioning(MdbAllocation allocation) {
        log.info("[MdbAllocation FSM] scheduling async Atlas provisioning for allocation {} clusterId={}",
            allocation.getId(), allocation.getClusterId());
        Thread.ofVirtual().start(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                // First create database (connectivity check + seed), then the
                // database user with readWrite on that database.
                atlasMdbService.createDatabase(allocation);
                atlasMdbService.createDatabaseUser(allocation);
                log.info("[MdbAllocation FSM] Atlas provisioning succeeded for allocation {} in {}ms",
                    allocation.getId(), System.currentTimeMillis() - startedAt);

                // AtlasMdbService.markReady persists READY directly after user
                // creation. Fire the FSM event only when the document is still
                // PROVISIONING; otherwise the send would be a spurious transition.
                MdbAllocation fresh = allocationRepo.findById(allocation.getId()).orElse(null);
                if (fresh != null && fresh.getStatus() == MdbAllocationStatus.PROVISIONING) {
                    transition(allocation.getId(), MdbAllocationEventType.PROVISION_SUCCESS,
                        "system(atlas-provision)");
                } else {
                    log.info("[MdbAllocation FSM] allocation {} already in status {} after provisioning; skipping PROVISION_SUCCESS",
                        allocation.getId(), fresh == null ? "missing" : fresh.getStatus());
                }
            } catch (Exception e) {
                log.error("[MdbAllocation FSM] Atlas provisioning failed for allocation {} after {}ms: {}",
                    allocation.getId(), System.currentTimeMillis() - startedAt, e.getMessage());
                failProvisioning(allocation, e);
            }
        });
    }

    private void failProvisioning(MdbAllocation allocation, Exception cause) {
        MdbAllocation latest = allocationRepo.findById(allocation.getId()).orElse(allocation);
        latest.setStatus(MdbAllocationStatus.PROVISIONING_FAILED);
        latest.setUpdatedAt(Instant.now());
        latest.setProvisionErrorCode("ATLAS_PROVISION_FAILED");
        latest.setProvisionErrorDetail(cause.getMessage());
        allocationRepo.save(latest);
    }

    private void scheduleReclaim(MdbAllocation allocation) {
        log.info("[MdbAllocation FSM] scheduling async Atlas reclaim for allocation {} clusterId={}",
            allocation.getId(), allocation.getClusterId());
        Thread.ofVirtual().start(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                // Order matters: databases are dropped over a connection
                // authenticated as the per-allocation dbUser, so the user must
                // be deleted only AFTER its databases are gone.
                atlasMdbService.dropDatabase(allocation);
                atlasMdbService.dropDatabaseUser(allocation);
                log.info("[MdbAllocation FSM] Atlas reclaim succeeded for allocation {} in {}ms",
                    allocation.getId(), System.currentTimeMillis() - startedAt);
                transition(allocation.getId(), MdbAllocationEventType.RECLAIM_COMPLETE,
                    "system(atlas-reclaim)");
            } catch (Exception e) {
                log.warn("[MdbAllocation FSM] Atlas reclaim failed for allocation {} after {}ms: {}",
                    allocation.getId(), System.currentTimeMillis() - startedAt, e.getMessage());
                transition(allocation.getId(), MdbAllocationEventType.RECLAIM_FAILED,
                    "system(atlas-reclaim)");
            }
        });
    }

    // ========== Result DTO ==========

    public record TransitionResult(boolean accepted, MdbAllocationStatus newStatus) {
        public static TransitionResult accepted(MdbAllocationStatus s) { return new TransitionResult(true, s); }
        public static TransitionResult rejected(MdbAllocationStatus s) { return new TransitionResult(false, s); }
    }
}
