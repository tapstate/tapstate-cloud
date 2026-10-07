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
 * MdbAllocation state machine orchestrator — mirrors the Cluster FSM pattern
 * with DB state reset + event send + persist cycle.
 * <p>
 * Every transition fires side-effects:
 * <ul>
 *   <li>PROVISIONING_PENDING → PROVISIONING: delegate async Atlas DB + user creation to {@link AtlasMdbService}</li>
 *   <li>PROVISIONING → READY: mark allocation ready (timestamp)</li>
 *   <li>PROVISIONING → PROVISIONING_FAILED: persist error detail</li>
 *   <li>INITIATE_RECLAIM → DELETING: fire async Atlas teardown (drop databases, then db user)</li>
 *   <li>DELETING → DELETED: record deletion timestamp</li>
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

            allocation.setStatus(newStatus);
            allocation.setUpdatedAt(Instant.now());
            allocationRepo.save(allocation);

            // Synchronous entity mutations land before the second save so the
            // new status is durable; asynchronous workers are scheduled only
            // after that, on a freshly loaded entity of their own. Otherwise a
            // worker's READY write races this thread's full-document save and
            // can be overwritten back to PROVISIONING (lost update).
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
                    atlasMdbService.createDatabase(allocation);
                    // Then create database user with readWrite on that DB
                    atlasMdbService.createDatabaseUser(allocation);
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
                } catch (Exception e) {
                    log.error("[MdbAllocation FSM] Atlas provisioning failed for allocation {} after {}ms: {}",
                        allocation.getId(), System.currentTimeMillis() - startedAt, e.getMessage());
                    // Record error detail before firing PROVISION_FAIL
                    MdbAllocation latest = allocationRepo.findById(allocation.getId()).orElse(allocation);
                    latest.setProvisionErrorCode("ATLAS_PROVISION_FAILED");
                    latest.setProvisionErrorDetail(e.getMessage());
                    allocationRepo.save(latest);
                    transition(allocation.getId(), MdbAllocationEventType.PROVISION_FAIL, "system(atlas-provision)");
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
                    atlasMdbService.dropDatabase(allocation);
                    atlasMdbService.dropDatabaseUser(allocation);
                    log.info("[MdbAllocation FSM] Atlas reclaim succeeded for allocation {} in {}ms",
                        allocation.getId(), System.currentTimeMillis() - startedAt);
                    transition(allocation.getId(), MdbAllocationEventType.RECLAIM_COMPLETE, "system(atlas-reclaim)");
                } catch (Exception e) {
                    log.warn("[MdbAllocation FSM] Atlas reclaim failed for allocation {} after {}ms: {}",
                        allocation.getId(), System.currentTimeMillis() - startedAt, e.getMessage());
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

    // ========== Result DTO ==========

    public record TransitionResult(boolean accepted, MdbAllocationStatus newStatus) {
        public static TransitionResult accepted(MdbAllocationStatus s) { return new TransitionResult(true, s); }
        public static TransitionResult rejected(MdbAllocationStatus s) { return new TransitionResult(false, s); }
    }
}
