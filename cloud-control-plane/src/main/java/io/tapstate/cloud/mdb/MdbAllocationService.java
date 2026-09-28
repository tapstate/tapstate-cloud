package io.tapstate.cloud.mdb;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.fsm.MdbAllocationEventType;
import io.tapstate.cloud.mdb.fsm.MdbAllocationStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * MDB allocation orchestration service — high-level idempotent API for
 * per-customer Atlas database provisioning and reclamation.
 * <p>
 * This is the layer that SubscriptionService calls into — it owns:
 * <ul>
 *   <li>Allocating an Atlas instance slot via {@link AtlasMdbService}</li>
 *   <li>Creating / reusing {@link MdbAllocation} records with full idempotency</li>
 *   <li>Driving the allocation state machine ({@link MdbAllocationStateMachineService})</li>
 *   <li>Reclaiming allocation resources on subscription cancel (also idempotent)</li>
 * </ul>
 * Idempotency contract: calling {@link #allocateMdb} multiple times for the same
 * (subscriptionId, clusterId) is safe — each pending step (instance slot reservation,
 * database creation, user creation) is checked before execution so retries after
 * partial failure converge correctly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MdbAllocationService {

    private final MdbAllocationRepository allocationRepo;
    private final AtlasMdbService atlasMdbService;
    private final MdbAllocationStateMachineService mdbSmService;

    private final SecureRandom secureRandom = new SecureRandom();

    // ========== allocateMdb ==========

    /**
     * Allocate an Atlas database for a new Cluster.
     * <p>
     * Fully idempotent — if an allocation already exists for this clusterId,
     * return it as-is (or advance it if still in PROVISIONING_PENDING).
     * <p>
     * Lifecycle:
     * <ol>
     *   <li>Check if allocation already exists (clusterId unique index handles this in DB too)</li>
     *   <li>Pick Atlas instance slot via {@link AtlasMdbService#createMdb}</li>
     *   <li>Persist allocation record with PROVISIONING_PENDING state</li>
     *   <li>Fire {@link MdbAllocationEventType#START_PROVISION} — state machine
     *       handles the async Atlas API calls (createDatabase + createDatabaseUser)</li>
     * </ol>
     *
     * @return the allocation record (state PROVISIONING_PENDING immediately,
     *         transitions to PROVISIONING asynchronously)
     */
    @Transactional
    public MdbAllocation allocateMdb(String orgId, String clusterId, String region, String subscriptionId) {
        // Idempotency: check if allocation already exists for this cluster
        MdbAllocation existing = allocationRepo.findByClusterId(clusterId).orElse(null);
        if (existing != null) {
            switch (existing.getStatus()) {
                case PROVISIONING_PENDING -> {
                    // Was created but state machine event never fired — fire now
                    log.info("[MdbAllocationService] allocateMdb retry: allocation {} still PENDING, firing START_PROVISION",
                        existing.getId());
                    mdbSmService.transition(existing.getId(), MdbAllocationEventType.START_PROVISION,
                        "subscription:allocate-retry");
                    return allocationRepo.findById(existing.getId()).orElse(existing);
                }
                case PROVISIONING, READY -> {
                    // Already allocated or in progress — return as-is
                    log.info("[MdbAllocationService] allocateMdb idempotent: allocation {} already exists (status={})",
                        existing.getId(), existing.getStatus());
                    return existing;
                }
                case PROVISIONING_FAILED -> {
                    // Previous attempt failed — let caller decide whether to retry
                    // Return the failed record; caller can check status
                    log.warn("[MdbAllocationService] allocateMdb: allocation {} in PROVISIONING_FAILED — caller must decide retry",
                        existing.getId());
                    return existing;
                }
                case DELETING, DELETED -> {
                    // Previously reclaimed — this shouldn't happen for an active subscription
                    // unless it's a reactivate-after-cancel scenario; for now, reject
                    throw new BusinessException(AppErrorCode.BAD_REQUEST, 400,
                        "Allocation " + existing.getId() + " is already deleted; cannot re-allocate for same cluster");
                }
            }
        }

        // === Step 1: Pick Atlas instance slot ===
        AtlasInstanceConfig instance;
        try {
            instance = atlasMdbService.createMdb(orgId, clusterId, region);
        } catch (MdbException e) {
            log.error("[MdbAllocationService] No Atlas instance available for region={}, cluster={}", region, clusterId);
            throw new BusinessException(AppErrorCode.CLUSTER_PROVISION_FAILED, 503, "No Atlas capacity for region " + region);
        }

        // === Step 2: Generate per-allocation credentials ===
        String databaseName = atlasMdbService.generateDatabaseName(clusterId);
        String dbUsername = atlasMdbService.generateDatabaseUsername(databaseName);
        String dbPassword = atlasMdbService.generateDatabasePassword();

        // === Step 3: Persist allocation record ===
        MdbAllocation allocation = MdbAllocation.builder()
            .id(generateId("mdb-"))
            .orgId(orgId)
            .clusterId(clusterId)
            .atlasInstanceId(instance.getId())
            .atlasProjectId(instance.getAtlasProjectId())
            .atlasClusterName(instance.getAtlasClusterName())
            .databaseName(databaseName)
            .dbUsername(dbUsername)
            .dbPassword(dbPassword)
            .subscriptionId(subscriptionId)
            .status(MdbAllocationStatus.PROVISIONING_PENDING)
            .build();

        MdbAllocation saved = allocationRepo.save(allocation);
        log.info("[MdbAllocationService] Allocation created: instance={}, db={}, user={}, cluster={}",
            instance.getId(), databaseName, dbUsername, clusterId);

        // === Step 4: Fire state machine → async Atlas provisioning ===
        mdbSmService.transition(saved.getId(), MdbAllocationEventType.START_PROVISION,
            "subscription:allocate");

        return allocationRepo.findById(saved.getId()).orElse(saved);
    }

    // ========== reclaimMdb ==========

    /**
     * Reclaim (release) the Atlas database for a Cluster.
     * <p>
     * Idempotent — already-deleted or already-deleting allocations return
     * immediately without error. PROVISIONING_FAILED allocations are also
     * safe to reclaim (no resources to clean up, just mark DELETED).
     * <p>
     * Lifecycle: fires {@link MdbAllocationEventType#INITIATE_RECLAIM} →
     * state machine handles async Atlas dropUser + dropDatabase.
     */
    @Transactional
    public void reclaimMdb(String clusterId) {
        MdbAllocation allocation = allocationRepo.findByClusterId(clusterId).orElse(null);
        if (allocation == null) {
            log.info("[MdbAllocationService] reclaimMdb: no allocation for cluster={} — nothing to do (idempotent)", clusterId);
            return;
        }

        switch (allocation.getStatus()) {
            case DELETING, DELETED -> {
                // Already reclaiming or reclaimed — idempotent no-op
                log.info("[MdbAllocationService] reclaimMdb: allocation {} already in status={} — idempotent",
                    allocation.getId(), allocation.getStatus());
                return;
            }
            case PROVISIONING_PENDING, PROVISIONING, READY -> {
                // Fire INITIATE_RECLAIM → state machine handles async teardown
                mdbSmService.transition(allocation.getId(), MdbAllocationEventType.INITIATE_RECLAIM,
                    "subscription:reclaim");
                log.info("[MdbAllocationService] reclaimMdb: allocation {} transitioning to DELETING", allocation.getId());
            }
            case PROVISIONING_FAILED -> {
                // Nothing to clean up on Atlas side — just mark DELETED directly
                allocation.setStatus(MdbAllocationStatus.DELETED);
                allocation.setDeletedAt(Instant.now());
                allocation.setUpdatedAt(Instant.now());
                allocationRepo.save(allocation);
                log.info("[MdbAllocationService] reclaimMdb: failed allocation {} — marked DELETED directly", allocation.getId());
            }
        }
    }

    // ========== Helpers ==========

    private String generateId(String prefix) {
        byte[] bytes = new byte[8];
        secureRandom.nextBytes(bytes);
        return prefix + HexFormat.of().formatHex(bytes);
    }
}
