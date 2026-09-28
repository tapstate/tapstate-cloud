package io.tapstate.cloud.mdb.fsm;

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

import java.time.Instant;

/**
 * MdbAllocation state machine orchestrator — mirrors the Cluster FSM pattern
 * with DB state reset + event send + persist cycle.
 * <p>
 * Every transition fires side-effects:
 * <ul>
 *   <li>PROVISIONING_PENDING → PROVISIONING: delegate async Atlas DB + user creation to {@link AtlasMdbService}</li>
 *   <li>PROVISIONING → READY: mark allocation ready (timestamp)</li>
 *   <li>PROVISIONING → PROVISIONING_FAILED: persist error detail</li>
 *   <li>INITIATE_RECLAIM → DELETING: fire async Atlas DB + user teardown</li>
 *   <li>DELETING → DELETED: record deletion timestamp</li>
 * </ul>
 */
@Service
@Slf4j
public class MdbAllocationStateMachineService {

    private final StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> mdbAllocationStateMachineFactory;
    private final MdbAllocationRepository allocationRepo;
    private final AtlasMdbService atlasMdbService;

    public MdbAllocationStateMachineService(
        @Qualifier("mdbAllocationStateMachineFactory")
        StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> mdbAllocationStateMachineFactory,
        MdbAllocationRepository allocationRepo,
        AtlasMdbService atlasMdbService) {
        this.mdbAllocationStateMachineFactory = mdbAllocationStateMachineFactory;
        this.allocationRepo = allocationRepo;
        this.atlasMdbService = atlasMdbService;
    }

    /**
     * Send an event to the MdbAllocation state machine and persist the new state.
     *
     * @param allocationId target allocation UUID
     * @param event        event to fire
     * @param operator     human / system identifier (for audit)
     */
    public TransitionResult transition(String allocationId, MdbAllocationEventType event, String operator) {
        MdbAllocation allocation = allocationRepo.findById(allocationId)
            .orElseThrow(() -> new IllegalStateException("MdbAllocation not found: " + allocationId));

        StateMachine<MdbAllocationStatus, MdbAllocationEventType> sm =
            mdbAllocationStateMachineFactory.getStateMachine("mdb-" + allocationId);

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

            applyTransitionEffects(allocation, prevStatus, newStatus, event);

            allocationRepo.save(allocation);

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
     * Apply side-effects after a successful state transition.
     */
    private void applyTransitionEffects(MdbAllocation allocation,
                                        MdbAllocationStatus from,
                                        MdbAllocationStatus to,
                                        MdbAllocationEventType event) {

        // === PROVISIONING_PENDING → PROVISIONING: async Atlas DB + user creation ===
        if (from == MdbAllocationStatus.PROVISIONING_PENDING && to == MdbAllocationStatus.PROVISIONING) {
            Thread.ofVirtual().start(() -> {
                try {
                    // First create database (connectivity check + seed)
                    atlasMdbService.createDatabase(allocation);
                    // Then create database user with readWrite on that DB
                    atlasMdbService.createDatabaseUser(allocation);
                    // Success — fire PROVISION_SUCCESS
                    transition(allocation.getId(), MdbAllocationEventType.PROVISION_SUCCESS, "system(atlas-provision)");
                } catch (Exception e) {
                    log.error("[MdbAllocation FSM] Atlas provisioning failed for allocation {}: {}",
                        allocation.getId(), e.getMessage());
                    // Record error detail before firing PROVISION_FAIL
                    MdbAllocation latest = allocationRepo.findById(allocation.getId()).orElse(allocation);
                    latest.setProvisionErrorCode("ATLAS_PROVISION_FAILED");
                    latest.setProvisionErrorDetail(e.getMessage());
                    allocationRepo.save(latest);
                    transition(allocation.getId(), MdbAllocationEventType.PROVISION_FAIL, "system(atlas-provision)");
                }
            });
        }

        // === PROVISIONING → READY ===
        if (from == MdbAllocationStatus.PROVISIONING && to == MdbAllocationStatus.READY) {
            allocation.setReadyAt(Instant.now());
        }

        // === INITIATE_RECLAIM → DELETING: async Atlas teardown ===
        if (from == MdbAllocationStatus.READY && to == MdbAllocationStatus.DELETING) {
            Thread.ofVirtual().start(() -> {
                try {
                    atlasMdbService.dropDatabaseUser(allocation);
                    atlasMdbService.dropDatabase(allocation);
                    transition(allocation.getId(), MdbAllocationEventType.RECLAIM_COMPLETE, "system(atlas-reclaim)");
                } catch (Exception e) {
                    log.warn("[MdbAllocation FSM] Atlas reclaim failed for allocation {}: {}",
                        allocation.getId(), e.getMessage());
                    transition(allocation.getId(), MdbAllocationEventType.RECLAIM_FAILED, "system(atlas-reclaim)");
                }
            });
        }

        // === DELETING → DELETED ===
        if (to == MdbAllocationStatus.DELETED) {
            allocation.setDeletedAt(Instant.now());
        }
    }

    // ========== Result DTO ==========

    public record TransitionResult(boolean accepted, MdbAllocationStatus newStatus) {
        public static TransitionResult accepted(MdbAllocationStatus s) { return new TransitionResult(true, s); }
        public static TransitionResult rejected(MdbAllocationStatus s) { return new TransitionResult(false, s); }
    }
}
