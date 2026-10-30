package io.tapstate.cloud.cluster;

import io.tapstate.cloud.cluster.fsm.ClusterEventType;
import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.region.RegionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;

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
    private final ClusterStatusReportHistoryRepository historyRepository;
    private final MemberRepository memberRepository;
    private final RegionService regionService;
    private final ClusterStateMachineService clusterStateMachineService;
    private final MongoTemplate mongoTemplate;
    private final ClusterPartialWriter clusterPartialWriter;
    private final io.tapstate.cloud.iac.ClusterProvisioningService clusterProvisioningService;

    private final SecureRandom secureRandom = new SecureRandom();

    // ========== Create Cluster (internal — called by SubscriptionService) ==========

    /**
     * Create a new Cluster record. The START_PROVISION state-machine event is
     * NOT fired here — the caller invokes {@link #startProvisioning(ObjectId, String)}
     * only after the Cluster -> MdbAllocation link is durable.
     * <p>
     * This is the internal entry point — called by the subscription orchestration
     * flow after {@code SubscriptionService} has validated the subscription is
     * active/valid. No subscription validity gate here (that lives upstream).
     *
     * @param clusterVersion version string pinned onto the Cluster; used as the runtime image tag
     * @return the persisted Cluster (state PROVISIONING_PENDING, provisioning not yet started)
     */
    @Transactional
    public Cluster createCluster(String orgId, ObjectId subscriptionId, String region,
                                 String name, String specKey, String creatorEmail,
                                 String clusterVersion) {
        if (!memberRepository.existsByOrgIdAndUserEmail(new ObjectId(orgId), creatorEmail)) {
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }
        regionService.getByCode(region);
        if (clusterRepository.existsByOrgIdAndRegionAndNameAndStateNot(
                new ObjectId(orgId), region, name, ClusterState.TERMINATED)) {
            throw new BusinessException(AppErrorCode.CLUSTER_ALREADY_EXISTS, 409, name, orgId, region);
        }

        // Keep the display name intact; normalize it for DNS and add a stable
        // Cluster-ID suffix so concurrent creates cannot claim the same label.
        ObjectId clusterId = new ObjectId();
        String baseDnsLabel = DnsLabels.normalize(name);
        if (!DnsLabels.isValidLabel(baseDnsLabel)) {
            throw new BusinessException(AppErrorCode.CLUSTER_NAME_DNS_INVALID, 400,
                name, baseDnsLabel, DnsLabels.MAX_LABEL_LENGTH);
        }
        String dnsLabel = uniqueDnsLabel(baseDnsLabel, clusterId);

        // ---- Cluster record ----
        Cluster cluster = Cluster.builder()
            .id(clusterId)
            .orgId(new ObjectId(orgId))
            .subscriptionId(subscriptionId)
            .region(region)
            .name(name)
            .dnsLabel(dnsLabel)
            .specKey(specKey != null ? specKey : "basic")
            .state(ClusterState.PROVISIONING_PENDING)
            .clusterVersion(clusterVersion)
            .runtimeId(generateId("rt-"))
            .bootstrapToken(generateSecureToken())
            .sdkStaticToken(generateSecureToken())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .tags(new HashMap<>(Map.of("organization_id", orgId, "cluster_id", "", "runtime_id", "")))
            .build();

        Cluster saved;
        try {
            saved = clusterRepository.save(cluster);
        } catch (DuplicateKeyException e) {
            // The existence pre-checks and the insert are not atomic: a
            // concurrent create can win either active-Cluster unique index.
            // Name the conflict from the index instead of issuing another
            // query, since the failed write has aborted this transaction.
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.contains("dns_label_active_unique")) {
                throw new BusinessException(AppErrorCode.CLUSTER_DNS_LABEL_CONFLICT, 409, dnsLabel);
            }
            throw new BusinessException(AppErrorCode.CLUSTER_ALREADY_EXISTS, 409, name, orgId, region);
        }
        saved.getTags().put("cluster_id", saved.getId().toHexString());
        saved.getTags().put("runtime_id", saved.getRuntimeId());
        saved = clusterRepository.save(saved);

        // The record stays PROVISIONING_PENDING until the caller links the
        // MdbAllocation and invokes startProvisioning. Firing here would race
        // the subscription flow, whose allocation link is saved only after this
        // returns: the async provisioning run can load and persist a snapshot
        // that predates the link and erase it.
        log.info("Cluster created: {} (provisioning pending until started)",
            saved.getId().toHexString());
        return saved;
    }

    private String uniqueDnsLabel(String baseDnsLabel, ObjectId clusterId) {
        String clusterIdSuffix = clusterId.toHexString();
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = attempt == 0 ? clusterIdSuffix : clusterIdSuffix + "-" + attempt;
            int prefixLength = DnsLabels.MAX_LABEL_LENGTH - suffix.length() - 1;
            String prefix = baseDnsLabel.substring(0, Math.min(baseDnsLabel.length(), prefixLength))
                .replaceAll("-+$", "");
            String candidate = prefix + "-" + suffix;
            if (DnsLabels.isValidLabel(candidate)
                    && !clusterRepository.existsByDnsLabelAndStateNot(candidate, ClusterState.TERMINATED)) {
                return candidate;
            }
        }
        throw new BusinessException(AppErrorCode.CLUSTER_DNS_LABEL_CONFLICT, 409, clusterIdSuffix);
    }

    /**
     * Fire START_PROVISION on a previously created Cluster. The subscription
     * flow calls this only after the Cluster -> MdbAllocation link is
     * persisted, so the asynchronous provisioning run observes the link from
     * its very first document load.
     */
    public void startProvisioning(ObjectId clusterId, String trigger) {
        clusterStateMachineService.transition(clusterId,
            ClusterEventType.START_PROVISION, trigger);
    }

    // ========== Link MDB allocation (called by SubscriptionService after allocation) ==========

    /**
     * Link an MdbAllocation to this Cluster — called after MdbAllocationService.allocateMdb
     * succeeds so the Cluster runtime can resolve its Atlas DB credentials via the allocation.
     */
    @Transactional
    public void updateMdbAllocationId(ObjectId clusterId, ObjectId allocationId) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(clusterId)),
            new Update().set("mdbAllocationId", allocationId), Cluster.class);
    }

    // ========== List / Get ==========

    public List<Cluster> listOrgClusters(String orgId, String userEmail) {
        if (!memberRepository.existsByOrgIdAndUserEmail(new ObjectId(orgId), userEmail)) {
            log.warn("[Cluster] list denied: user={} is not a member of org={}", userEmail, orgId);
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }
        List<Cluster> clusters = clusterRepository.findByOrgId(new ObjectId(orgId));
        log.debug("[Cluster] list org={} user={} -> {} cluster(s)", orgId, userEmail, clusters.size());
        return clusters;
    }

    public Cluster getCluster(String clusterId, String orgId, String userEmail) {
        if (!memberRepository.existsByOrgIdAndUserEmail(new ObjectId(orgId), userEmail)) {
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }
        return clusterRepository.findByIdAndOrgId(new ObjectId(clusterId), new ObjectId(orgId))
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
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(cluster.getId())
                .and("state").is(ClusterState.PROVISIONING_FAILED)),
            new Update().unset("provisionErrorCode").unset("provisionErrorDetail")
                .set("updatedAt", cluster.getUpdatedAt()), Cluster.class);

        clusterStateMachineService.transition(new ObjectId(clusterId), ClusterEventType.RETRY, "gc:" + userEmail);

        return clusterRepository.findById(new ObjectId(clusterId)).orElse(cluster);
    }

    /**
     * Internal entry for retry-cleanup — called from ClusterController's retry-cleanup
     * endpoint when a user discovers a cleanup failure (cleanupErrorCode != null) and
     * wants to retry resource cleanup.
     * <p>
     * Idempotent: if the Cluster has no cleanup failure, returns as-is without calling
     * the infrastructure layer. If terminate succeeds, cleanup error fields are cleared.
     *
     * @return Cluster after cleanup attempt (success clears cleanupErrorCode; failure leaves it)
     */
    @Transactional
    public Cluster retryCleanup(String clusterId, String orgId, String userEmail) {
        Cluster cluster = getCluster(clusterId, orgId, userEmail);

        // Idempotent: no cleanup failure → nothing to do
        if (cluster.getCleanupErrorCode() == null) {
            return cluster;
        }

        // Terminated is terminal — cleanup already succeeded
        if (cluster.getState() == ClusterState.TERMINATED) {
            return cluster;
        }

        try {
            clusterProvisioningService.terminate(cluster);
            // terminate() cleared cleanupErrorCode on success — confirm and return
            Cluster afterTerminate = clusterRepository.findById(new ObjectId(clusterId)).orElse(cluster);

            // If state was never TERMINATED and cleanup succeeded, drive FSM to TERMINATED
            if (afterTerminate.getState() != ClusterState.TERMINATED
                && afterTerminate.getCleanupErrorCode() == null) {
                clusterStateMachineService.transition(new ObjectId(clusterId),
                    ClusterEventType.INITIATE_TERMINATION, "gc:retry-cleanup");
            }
            return clusterRepository.findById(new ObjectId(clusterId)).orElse(afterTerminate);
        } catch (Exception e) {
            // Cleanup failure was already persisted by terminate() itself.
            // Surface as a coded error so the user sees the AWS error directly.
            throw new BusinessException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, 500, safeMessage(e));
        }
    }

    private static String safeMessage(Throwable t) {
        return t != null && t.getMessage() != null ? t.getMessage() : t != null ? t.toString() : "unknown";
    }

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
        Cluster cluster = clusterRepository.findByIdAndOrgId(new ObjectId(clusterId), new ObjectId(orgId))
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

        clusterStateMachineService.transition(new ObjectId(clusterId), ClusterEventType.INITIATE_TERMINATION,
            "subscription-cascade");

        return clusterRepository.findById(new ObjectId(clusterId)).orElse(cluster);
    }

    // ========== C2 Status Report — runtime → GC heartbeat + metrics ==========

    /**
     * Accept C2 status report from the runtime SDK.
     * <p>
     * Enforces nonce replay protection (same nonce twice → 409 auth.nonce-replay),
     * updates heartbeat timestamp, stores the full request as an embedded snapshot
     * on {@link Cluster#getLastStatusReport()}, appends a full history record to the
     * {@code cluster_status_report_history} time-series collection, and auto-recovers
     * DEGRADED clusters via the state-machine HEARTBEAT_RECOVERY event.
     * <p>
     * Storage dual-write:
     * <ul>
     *   <li>{@code Cluster.lastStatusReport} — latest-only embedded document, serves as
     *       the fleet console's "current status" snapshot; written via a single
     *       atomic {@code updateFirst} with optimistic locking on {@code lastC2Nonce}.</li>
     *   <li>{@code cluster_status_report_history} — time-series collection, keeps every
     *       report ever accepted, for trend/diagnostics/audit.</li>
     * </ul>
     * The method is deliberately NOT {@code @Transactional}: MongoDB does not support
     * inserts into a time-series collection inside a multi-document transaction (error 263
     * OperationNotSupportedInTransaction). The Cluster update is already atomic as a single
     * {@code updateFirst}; the history append runs independently and does not need
     * cross-collection atomicity with the Cluster write.
     */
    public void recordStatusReport(String clusterId, StatusReportRequest request) {
        Cluster cluster = clusterRepository.findById(new ObjectId(clusterId))
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_NOT_FOUND, 404, clusterId));

        ClusterState prevState = cluster.getState();

        String nonce = request.getNonce();

        // Ingress double-check: reject missing/null/blank nonce before any persistence.
        // The controller's @Valid already covers this on the normal HTTP path, but
        // the service layer defends against direct bean invocation or test bypasses.
        if (nonce == null || nonce.isBlank()) {
            throw new BusinessException(AppErrorCode.BAD_REQUEST, 400, "nonce must not be blank");
        }

        // Nonce replay guard — Cluster.lastC2Nonce stores the previously accepted nonce
        if (nonce.equals(cluster.getLastC2Nonce())) {
            throw new BusinessException(AppErrorCode.NONCE_REPLAY, 409);
        }

        Instant now = Instant.now();

        // --- 1. Update Cluster document — latest-only snapshot ---
        long matched = mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(cluster.getId())
                .and("lastC2Nonce").is(cluster.getLastC2Nonce())),
            new Update().set("lastHeartbeatAt", now).set("lastC2Nonce", nonce)
                .set("lastStatusReport", request), Cluster.class).getMatchedCount();
        if (matched == 0) throw new BusinessException(AppErrorCode.NONCE_REPLAY, 409);

        // --- 2. Append to time-series history — every report preserved forever ---
        ClusterStatusReportHistory history = ClusterStatusReportHistory.builder()
            .clusterId(clusterId)
            .timestamp(now)
            .nonce(request.getNonce())
            .runtimeVersion(request.getRuntimeVersion())
            .uptimeMs(request.getUptimeMs())
            .activePipelines(request.getActivePipelines())
            .lastErrorAt(request.getLastErrorAt())
            .build();
        historyRepository.save(history);

        // --- 3. State-machine recovery ---
        if (prevState == ClusterState.DEGRADED) {
            clusterStateMachineService.transition(new ObjectId(clusterId), ClusterEventType.HEARTBEAT_RECOVERY, "runtime:c2-heartbeat");
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
        if (expired.isEmpty()) {
            log.info("[HeartbeatScanner] tick: 0 READY clusters with stale heartbeat (threshold={})", threshold);
            return;
        }

        log.info("[HeartbeatScanner] {} READY clusters with stale heartbeat (>5min), firing HEARTBEAT_TIMEOUT",
            expired.size());
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
        return clusterRepository.findByOrgId(new ObjectId(orgId));
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
