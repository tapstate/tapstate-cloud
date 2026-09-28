package io.tapstate.cloud.cluster;

import io.tapstate.cloud.cluster.fsm.ClusterEventType;
import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.region.RegionService;
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
 * Cluster service — create, list, retry, status-report, lifecycle transitions.
 * <p>
 * All state transitions are driven through {@link ClusterStateMachineService}
 * which in turn wraps the Spring Statemachine {@code StateMachineFactory}.
 * Every transition goes through a validated path defined in
 * {@code ClusterStateMachineConfig}.
 * <p>
 * <b>Creation flow</b>: Cluster records are created by the subscription
 * orchestration layer (see {@link io.tapstate.cloud.subscription.SubscriptionService}).
 * This service is the internal entry point that creates the Cluster document
 * and fires the {@code START_PROVISION} state-machine event. The caller
 * (SubscriptionService) is responsible for subscription validity checks —
 * this service no longer has a SubscriptionService dependency.
 * <p>
 * <b>MDB allocation</b> is handled independently by
 * {@link io.tapstate.cloud.mdb.MdbAllocationService} — its state machine
 * runs in parallel with this one.
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
    private final ClusterStateMachineService clusterStateMachineService;

    private final SecureRandom secureRandom = new SecureRandom();

    // ========== Create Cluster (internal — called by SubscriptionService) ==========

    /**
     * Create a new Cluster record and fire the START_PROVISION state-machine event.
     * <p>
     * This is the internal entry point — called by the subscription orchestration
     * flow after {@code SubscriptionService} has validated the subscription is
     * active/valid. No subscription validity gate here (that lives upstream).
     * <p>
     * Returns immediately with PROVISIONING_PENDING state — the state machine's
     * transition effect fires async CloudFormation simulation. MDB allocation
     * is handled separately by {@link io.tapstate.cloud.mdb.MdbAllocationService}.
     *
     * @return the persisted Cluster (state PROVISIONING_PENDING, START_PROVISION fired)
     */
    @Transactional
    public Cluster createCluster(String orgId, String region, String name, String specKey, String creatorEmail) {
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

        // Fire START_PROVISION event → state machine moves PENDING → PROVISIONING
        clusterStateMachineService.transition(saved.getId(),
            ClusterEventType.START_PROVISION, "subscription:" + creatorEmail);

        log.info("Cluster created: {} (sm transition START_PROVISION fired)", saved.getId());
        return saved;
    }

    // ========== Link MDB allocation (called by SubscriptionService after allocation) ==========

    /**
     * Link an MdbAllocation to this Cluster — called after MdbAllocationService.allocateMdb
     * succeeds so the Cluster runtime can resolve its Atlas DB credentials via the allocation.
     */
    @Transactional
    public void updateMdbAllocationId(String clusterId, String allocationId) {
        clusterRepository.findById(clusterId).ifPresent(cluster -> {
            cluster.setMdbAllocationId(allocationId);
            clusterRepository.save(cluster);
        });
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

    // ========== Retry (user endpoint — kept) ==========

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

    // ========== Cascade termination (called by SubscriptionCancelListener) ==========

    /**
     * Terminate this Cluster (called by SubscriptionCancelListener cascade).
     * <p>
     * Valid states: READY / DEGRADED / PROVISIONING_FAILED — anything but the
     * transient {@code PROVISIONING_PENDING} / {@code PROVISIONING}. TERMINATED
     * is terminal and returns as-is.
     * <p>
     * Triggers {@link ClusterEventType#INITIATE_TERMINATION} → state machine
     * handles the transition + async side effects.
     */
    @Transactional
    public Cluster terminateCluster(String clusterId, String orgId) {
        Cluster cluster = clusterRepository.findByIdAndOrgId(clusterId, orgId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_NOT_FOUND, 404, clusterId));

        ClusterState state = cluster.getState();
        // Transient states — skip; subscription cancel listener will handle others
        if (state == ClusterState.PROVISIONING_PENDING || state == ClusterState.PROVISIONING) {
            log.info("[ClusterService] terminateCluster skipped {} (state={} — still provisioning)",
                clusterId, state);
            return cluster;
        }
        // Already terminal — idempotent
        if (state == ClusterState.TERMINATED) {
            return cluster;
        }

        clusterStateMachineService.transition(clusterId, ClusterEventType.INITIATE_TERMINATION,
            "subscription-cascade");

        return clusterRepository.findById(clusterId).orElse(cluster);
    }

    // ========== C2 Status Report — runtime → GC heartbeat + metrics ==========

    /**
     * Accept C2 status report from the runtime SDK.
     * <p>
     * Enforces nonce replay protection (same nonce twice → 409 auth.nonce-replay),
     * updates heartbeat timestamp, and auto-recovers DEGRADED clusters via the
     * state-machine HEARTBEAT_RECOVERY event.
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
        if (prevState == ClusterState.DEGRADED) {
            clusterStateMachineService.transition(clusterId, ClusterEventType.HEARTBEAT_RECOVERY, "runtime:c2-heartbeat");
        }
    }

    // ========== Scheduled heartbeat scanner ==========

    /**
     * Every 60s — scan all READY clusters whose last C2 heartbeat is older than 5min
     * and fire {@link ClusterEventType#HEARTBEAT_TIMEOUT} so the state machine moves
     * them to DEGRADED.
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
