package io.tapstate.cloud.cluster;

import io.tapstate.cloud.cluster.fsm.ClusterEventType;
import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.*;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.region.RegionService;
import io.tapstate.cloud.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Cluster service — create, list, retry, status-report, cancel, lifecycle transitions.
 * <p>
 * All state transitions are driven through {@link ClusterStateMachineService}
 * which in turn wraps the Spring Statemachine {@code StateMachineFactory}.
 * This replaces the previous inline hardcoded state changes; every transition
 * now goes through a validated path defined in {@code ClusterStateMachineConfig}.
 * <p>
 * Subscription validity gate (PAST_DUE / CANCELLED block new provisioning) lives here —
 * state machine only governs Cluster's own lifecycle, not the guard that blocks entry.
 * <p>
 * <b>Week 2 allocation integration</b>: {@link #createCluster} also creates an
 * {@link MdbAllocation} record via {@link AtlasMdbService} — the Cluster state
 * machine's PROVISIONING effect then calls {@code createDatabase} +
 * {@code createDatabaseUser} on the shared Atlas instance. Both flows are
 * async and run in parallel (one Cluster FSM, one Atlas MDB FSM).
 * <p>
 * Scheduled tasks: {@link #checkHeartbeats()} runs every 60s, auto-firing
 * {@code HEARTBEAT_TIMEOUT} for READY clusters whose last C2 heartbeat is stale.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterService {

    private final ClusterRepository clusterRepository;
    private final MemberRepository memberRepository;
    private final RegionService regionService;
    private final SubscriptionService subscriptionService;
    private final ClusterStateMachineService clusterStateMachineService;

    // MDB allocation — Atlas-backed shared-instance per-customer database provisioning
    private final AtlasMdbService atlasMdbService;
    private final MdbAllocationRepository allocationRepo;

    private final SecureRandom secureRandom = new SecureRandom();

    // ========== Create Cluster + MdbAllocation ==========

    /**
     * Create a new Cluster + its MdbAllocation in one transaction.
     * <p>
     * This is the internal entry point — called by the subscription
     * orchestration flow after {@code SubscriptionService.createDefaultSubscription}
     * (auto on org creation) or a future explicit subscription-create endpoint.
     * <p>
     * Returns immediately with PROVISIONING_PENDING state — the state machine's
     * transition effect fires async Atlas provisioning + simulated CloudFormation.
     *
     * @return the persisted Cluster (state PROVISIONING_PENDING, transition to PROVISIONING fired)
     */
    @Transactional
    public Cluster createCluster(String orgId, String region, String name, String specKey, String creatorEmail) {
        // Subscription validity gate — blocked for PAST_DUE / CANCELLED
        subscriptionService.assertProvisioningAllowed(orgId);

        if (!memberRepository.existsByOrgIdAndUserEmail(orgId, creatorEmail)) {
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }
        regionService.getByCode(region);
        if (clusterRepository.existsByOrgIdAndRegionAndName(orgId, region, name)) {
            throw new BusinessException(AppErrorCode.CLUSTER_ALREADY_EXISTS, 409, name, orgId, region);
        }

        // ---- Cluster record ----
        String clusterId = generateId("cluster-");
        Cluster cluster = Cluster.builder()
            .id(clusterId)
            .orgId(orgId)
            .region(region)
            .name(name)
            .specKey(specKey != null ? specKey : "basic")
            .state(ClusterState.PROVISIONING_PENDING)
            .runtimeId(generateId("rt-"))
            .bootstrapToken(generateSecureToken())
            .sdkStaticToken(generateSecureToken())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .tags(new HashMap<>(Map.of("organization_id", orgId, "cluster_id", "", "runtime_id", "")))
            .build();

        Cluster saved = clusterRepository.save(cluster);
        saved.getTags().put("cluster_id", saved.getId());
        saved.getTags().put("runtime_id", saved.getRuntimeId());
        saved = clusterRepository.save(saved);

        // ---- MdbAllocation — pick Atlas instance + record ----
        MdbAllocation allocation = createAndPersistAllocation(orgId, saved, region);

        // Link allocation to cluster so the state machine can find it
        saved.setMdbAllocationId(allocation.getId());
        saved = clusterRepository.save(saved);

        // Fire START_PROVISION event → state machine moves PENDING → PROVISIONING
        clusterStateMachineService.transition(saved.getId(),
            ClusterEventType.START_PROVISION, "gc:" + creatorEmail);

        log.info("Cluster created: {} (sm transition START_PROVISION fired), allocation={}", saved.getId(), allocation.getId());
        return saved;
    }

    /** Pick Atlas instance via {@link AtlasMdbService#createMdb} and persist a PROVISIONING_PENDING allocation. */
    private MdbAllocation createAndPersistAllocation(String orgId, Cluster cluster, String region) {
        AtlasInstanceConfig instance;
        try {
            instance = atlasMdbService.createMdb(orgId, cluster.getId(), region);
        } catch (MdbException e) {
            log.error("No Atlas instance available for region={} — Cluster {} creation blocked", region, cluster.getId());
            throw new BusinessException(AppErrorCode.CLUSTER_PROVISION_FAILED, 503, "No Atlas capacity");
        }

        String databaseName = atlasMdbService.generateDatabaseName(cluster.getId());
        String dbUsername = atlasMdbService.generateDatabaseUsername(databaseName);
        String dbPassword = atlasMdbService.generateDatabasePassword();

        MdbAllocation allocation = MdbAllocation.builder()
            .orgId(orgId)
            .clusterId(cluster.getId())
            .atlasInstanceId(instance.getId())
            .atlasProjectId(instance.getAtlasProjectId())
            .atlasClusterName(instance.getAtlasClusterName())
            .databaseName(databaseName)
            .dbUsername(dbUsername)
            .dbPassword(dbPassword)
            .status(MdbAllocationStatus.PROVISIONING_PENDING)
            .build();

        MdbAllocation saved = allocationRepo.save(allocation);
        log.info("MdbAllocation created: instance={}, db={}, user={}",
            instance.getId(), databaseName, dbUsername);
        return saved;
    }

    // ========== List / Get ==========

    public List<Cluster> listOrgClusters(String orgId, String userEmail) {
        if (!memberRepository.existsByOrgIdAndUserEmail(orgId, userEmail)) {
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }
        return clusterRepository.findByOrgId(orgId);
    }

    public Cluster getCluster(String clusterId, String orgId, String userEmail) {
        if (!memberRepository.existsByOrgIdAndUserEmail(orgId, userEmail)) {
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }
        return clusterRepository.findByIdAndOrgId(clusterId, orgId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_NOT_FOUND, 404, clusterId));
    }

    /**
     * Internal lookup by runtime-owned SDK static token (used by C2 status-report filter).
     * Does NOT require org+user — auth is via SDK token itself.
     * <p>
     * Rejects null/blank tokens explicitly — MongoDB's findByXxx(null) matches any
     * document where the field is null/missing, which would incorrectly let a
     * TERMINATED cluster (its sdkStaticToken is cleared) slip through.
     */
    public Cluster getBySdkToken(String sdkToken) {
        if (sdkToken == null || sdkToken.isBlank()) {
            throw new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, 401);
        }
        return clusterRepository.findBySdkStaticToken(sdkToken)
            .orElseThrow(() -> new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, 401));
    }

    // ========== Retry ==========

    @Transactional
    public Cluster retryCluster(String clusterId, String orgId, String userEmail) {
        Cluster cluster = getCluster(clusterId, orgId, userEmail);

        if (cluster.getState() != ClusterState.PROVISIONING_FAILED) {
            throw new BusinessException(AppErrorCode.CLUSTER_INVALID_STATE, 409, cluster.getState());
        }

        cluster.setProvisionErrorCode(null);
        cluster.setProvisionErrorDetail(null);
        cluster.setUpdatedAt(Instant.now());
        cluster = clusterRepository.save(cluster);

        clusterStateMachineService.transition(clusterId, ClusterEventType.RETRY, "gc:" + userEmail);

        return clusterRepository.findById(clusterId).orElse(cluster);
    }

    // ========== Cancel subscription → Cluster terminate ==========

    /**
     * Terminate this Cluster (cancel subscription resource).
     * <p>
     * Valid states: READY / DEGRADED / PROVISIONING_FAILED — anything but the
     * transient {@code PROVISIONING_PENDING} / {@code PROVISIONING}. TERMINATED
     * is terminal and cannot be cancelled again.
     * <p>
     * Triggers {@link ClusterEventType#INITIATE_TERMINATION} → state machine
     * handles the transition + side effects (CloudFormation delete, k3s Secret
     * cleanup, Atlas DB teardown — Week 2 scaffold, real calls in Week 3).
     *
     * @return post-transition Cluster state
     */
    @Transactional
    public Cluster cancelCluster(String clusterId, String orgId, String userEmail) {
        Cluster cluster = getCluster(clusterId, orgId, userEmail);

        ClusterState state = cluster.getState();
        // Transient states cannot be cancelled — wait for completion first
        if (state == ClusterState.PROVISIONING_PENDING || state == ClusterState.PROVISIONING) {
            throw new BusinessException(AppErrorCode.CLUSTER_CANCEL_NOT_ALLOWED, 409, state);
        }
        // Already terminal — idempotent success
        if (state == ClusterState.TERMINATED) {
            return cluster;
        }

        // Fire INITIATE_TERMINATION → state machine moves to TERMINATED
        clusterStateMachineService.transition(clusterId, ClusterEventType.INITIATE_TERMINATION,
            "gc:cancel-subscription/" + userEmail);

        return clusterRepository.findById(clusterId).orElse(cluster);
    }

    // ========== C2 Status Report — runtime → GC heartbeat + metrics ==========

    /**
     * Accept C2 status report from the runtime SDK.
     * <p>
     * Enforces nonce replay protection (same nonce twice → 409 auth.nonce-replay),
     * updates heartbeat timestamp, and auto-recovers DEGRADED clusters via the
     * state-machine HEARTBEAT_RECOVERY event.
     * <p>
     * Note: state mutation goes THROUGH the state machine, not direct writes —
     * the DB read in {@link #getCluster} would see stale state otherwise.
     */
    @Transactional
    public void recordStatusReport(String clusterId, String nonce) {
        Cluster cluster = clusterRepository.findById(clusterId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_NOT_FOUND, 404, clusterId));

        ClusterState prevState = cluster.getState();

        // Nonce replay guard — Cluster.lastC2Nonce stores the previously accepted nonce
        if (nonce != null && nonce.equals(cluster.getLastC2Nonce())) {
            throw new BusinessException(AppErrorCode.NONCE_REPLAY, 409);
        }

        // Always update heartbeat + nonce (even for DEGRADED recovery)
        cluster.setLastHeartbeatAt(Instant.now());
        cluster.setLastC2Nonce(nonce);
        clusterRepository.save(cluster);

        // If previously DEGRADED, fire HEARTBEAT_RECOVERY → state machine moves DEGRADED → READY.
        // READY clusters just get a fresh heartbeat — no state transition needed.
        if (prevState == ClusterState.DEGRADED) {
            clusterStateMachineService.transition(clusterId, ClusterEventType.HEARTBEAT_RECOVERY, "runtime:c2-heartbeat");
        }
    }

    // ========== Scheduled heartbeat scanner ==========

    /**
     * Every 60s — scan all READY clusters whose last C2 heartbeat is older than 5min
     * and fire {@link ClusterEventType#HEARTBEAT_TIMEOUT} so the state machine moves
     * them to DEGRADED. Manual {@link #recordStatusReport} from runtime fires the
     * recovery event when it comes back online.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void checkHeartbeats() {
        Instant threshold = Instant.now().minus(5, ChronoUnit.MINUTES);
        List<Cluster> expired = clusterRepository.findReadyClustersWithExpiredHeartbeat(
            ClusterState.READY, threshold);
        if (expired.isEmpty()) return;

        log.info("[HeartbeatScanner] {} READY clusters with stale heartbeat (>5min)", expired.size());
        for (Cluster cluster : expired) {
            clusterStateMachineService.transition(cluster.getId(),
                ClusterEventType.HEARTBEAT_TIMEOUT, "system:heartbeat-scanner");
        }
    }

    // ========== Helpers ==========

    /**
     * Find all Clusters belonging to an Organization — used by subscription-cancel
     * cascade (Subscription → CANCELLED → terminate all clusters under that org).
     */
    public List<Cluster> findByOrgId(String orgId) {
        return clusterRepository.findByOrgId(orgId);
    }

    private String generateId(String prefix) {
        byte[] bytes = new byte[8];
        secureRandom.nextBytes(bytes);
        return prefix + HexFormat.of().formatHex(bytes);
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
