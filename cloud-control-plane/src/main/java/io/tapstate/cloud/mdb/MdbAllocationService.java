package io.tapstate.cloud.mdb;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.fsm.MdbAllocationEventType;
import io.tapstate.cloud.mdb.fsm.MdbAllocationStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * MDB allocation orchestration service — high-level idempotent API for
 * per-Cluster Atlas database provisioning and reclamation.
 * <p>
 * This is the layer that SubscriptionService calls into. It owns:
 * <ul>
 *   <li>Picking an MdbCluster slot via {@link AtlasMdbService#createMdb}</li>
 *   <li>Persisting {@link MdbAllocation} with all three db names + dbUser credentials</li>
 *   <li>Driving the async provisioning state machine</li>
 *   <li>Reclaiming allocation resources on subscription cancel (also idempotent)</li>
 * </ul>
 * Idempotency: calling {@link #allocateMdb} multiple times for the same
 * {@code clusterId} is safe — existing allocation is returned (or advanced if
 * still PENDING). Each intermediate step (slot reservation, db user creation)
 * is checked before execution.
 * <p>
 * PO decisions incorporated — see {@link AtlasMdbService} doc for the full list.
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
     * Allocate an Atlas database for a new Cluster. Fully idempotent — returns
     * existing allocation if one already exists for this clusterId.
     *
     * @param orgId         owning organization
     * @param clusterId     Cluster ObjectId (hex string accepted)
     * @param region        AWS region
     * @param subscriptionId Subscription ObjectId (hex string accepted) — main lifecycle link
     * @return allocation record (state PROVISIONING_PENDING immediately,
     *         transitions to PROVISIONING / READY asynchronously)
     */
    @Transactional
    public MdbAllocation allocateMdb(String orgId, String clusterId, String region, String subscriptionId) {
        ObjectId clusterObjId = new ObjectId(clusterId);

        // Idempotency: check if allocation already exists for this cluster
        MdbAllocation existing = allocationRepo.findByClusterId(clusterObjId).orElse(null);
        if (existing != null) {
            switch (existing.getStatus()) {
                case PROVISIONING_PENDING -> {
                    log.info("[MdbAllocationService] allocateMdb retry: allocation {} still PENDING, firing START_PROVISION",
                        existing.getId());
                    mdbSmService.transition(existing.getId(), MdbAllocationEventType.START_PROVISION,
                        "subscription:allocate-retry");
                    return allocationRepo.findById(existing.getId()).orElse(existing);
                }
                case PROVISIONING, READY -> {
                    log.info("[MdbAllocationService] allocateMdb idempotent: allocation {} already exists (status={})",
                        existing.getId(), existing.getStatus());
                    return existing;
                }
                case PROVISIONING_FAILED -> {
                    log.warn("[MdbAllocationService] allocateMdb: allocation {} in PROVISIONING_FAILED — caller must decide retry",
                        existing.getId());
                    return existing;
                }
                case DELETING, DELETED -> {
                    throw new BusinessException(AppErrorCode.BAD_REQUEST, 400,
                        "Allocation " + existing.getId() + " is already deleted; cannot re-allocate for same cluster");
                }
            }
        }

        // === Step 1: Pick MdbCluster slot ===
        MdbCluster cluster;
        try {
            cluster = atlasMdbService.createMdb(orgId, clusterId, region);
        } catch (MdbException e) {
            log.error("[MdbAllocationService] No MdbCluster capacity for region={}, cluster={}", region, clusterId);
            throw new BusinessException(AppErrorCode.CLUSTER_PROVISION_FAILED, 503,
                "No Atlas capacity for region " + region);
        }

        // === Step 2: Generate per-allocation credentials ===
        String databaseName = atlasMdbService.generateDatabaseName(clusterId);
        String operatorDbName = atlasMdbService.generateOperatorDbName(databaseName);
        String viewsDbName = atlasMdbService.generateViewsDbName(databaseName);
        String dbUsername = atlasMdbService.generateDatabaseUsername(databaseName);
        String dbPassword = atlasMdbService.generateDatabasePassword();

        // === Step 3: Persist allocation record ===
        MdbAllocation allocation = MdbAllocation.builder()
            .orgId(new ObjectId(orgId))
            .subscriptionId(new ObjectId(subscriptionId))
            .clusterId(clusterObjId)
            .mdbClusterId(cluster.getId())
            .databaseName(databaseName)
            .operatorDbName(operatorDbName)
            .viewsDbName(viewsDbName)
            .dbUsername(dbUsername)
            .dbPassword(dbPassword)
            .status(MdbAllocationStatus.PROVISIONING_PENDING)
            .build();

        MdbAllocation saved = allocationRepo.save(allocation);
        log.info("[MdbAllocationService] Allocation created: mdbCluster={}, db={}, operatorDb={}, viewsDb={}, user={}, cluster={}",
            cluster.getId(), databaseName, operatorDbName, viewsDbName, dbUsername, clusterId);

        // === Step 4: Fire state machine → async Atlas provisioning ===
        mdbSmService.transition(saved.getId(), MdbAllocationEventType.START_PROVISION,
            "subscription:allocate");

        return allocationRepo.findById(saved.getId()).orElse(saved);
    }

    // ========== reclaimMdb ==========

    /**
     * Reclaim (release) the Atlas databases for a Cluster. Idempotent —
     * already-deleted / already-deleting allocations return immediately.
     *
     * @param clusterId Cluster ObjectId
     */
    @Transactional
    public void reclaimMdb(ObjectId clusterId) {
        MdbAllocation allocation = allocationRepo.findByClusterId(clusterId).orElse(null);
        if (allocation == null) {
            log.info("[MdbAllocationService] reclaimMdb: no allocation for cluster={} — nothing to do (idempotent)", clusterId);
            return;
        }

        switch (allocation.getStatus()) {
            case DELETING, DELETED -> {
                log.info("[MdbAllocationService] reclaimMdb: allocation {} already in status={} — idempotent",
                    allocation.getId(), allocation.getStatus());
                return;
            }
            case PROVISIONING_PENDING, PROVISIONING, READY -> {
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
}
