package io.tapstate.cloud.iac.config;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.IacProperties;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import io.tapstate.cloud.mdb.MdbCluster;
import io.tapstate.cloud.mdb.MdbClusterRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Resolves the MongoDB connection URI rendered into a Cluster node's
 * {@code docker-compose.yml}.
 * <p>
 * The Cluster FSM and the MdbAllocation FSM start in parallel when a subscription
 * is created, so the EC2 node can finish provisioning before the Atlas database
 * and db user exist. Before the compose file is uploaded this resolver waits for
 * the Cluster's linked {@link MdbAllocation} to reach READY, loads its parent
 * {@link MdbCluster}, and assembles the per-allocation runtime URI via
 * {@link MdbAllocation#buildRuntimeConnectionString(String)}.
 * <p>
 * A Cluster without a linked allocation (e.g. legacy records), and every
 * Cluster while the Atlas allocation engine runs in mock mode (no real Atlas
 * user exists for the assembled per-allocation credentials), falls back to the
 * globally configured {@code tapstate.cloud.iac.runtime-atlas-uri}; when that
 * is blank the rendered compose file provisions a local MongoDB sidecar
 * instead.
 * The resolved URI carries the per-allocation database password and must never be
 * written to the Cluster document or logs in full.
 */
@Component
@Slf4j
public class RuntimeAtlasUriResolver {

    /** Upper bound for waiting on the parallel Atlas allocation flow. */
    static final Duration ALLOCATION_READY_TIMEOUT = Duration.ofMinutes(10);
    static final long POLL_INTERVAL_MS = 5_000;

    /** Allocation states from which READY can no longer be reached. */
    private static final Set<MdbAllocationStatus> TERMINAL_FAILURE_STATES = Set.of(
        MdbAllocationStatus.PROVISIONING_FAILED,
        MdbAllocationStatus.DELETING,
        MdbAllocationStatus.DELETED);

    private final MdbAllocationRepository allocationRepository;
    private final MdbClusterRepository mdbClusterRepository;
    private final IacProperties properties;

    /**
     * Mirrors {@code tapstate.cloud.mdb.atlas.mock} consumed by the allocation
     * engine. In mock mode no Atlas database user exists, so a linked
     * allocation's assembled credentials cannot authenticate; the node then
     * uses the global URI / local MongoDB sidecar instead.
     */
    private final boolean atlasMockMode;

    public RuntimeAtlasUriResolver(MdbAllocationRepository allocationRepository,
                                   MdbClusterRepository mdbClusterRepository,
                                   IacProperties properties,
                                   @Value("${tapstate.cloud.mdb.atlas.mock:true}")
                                   boolean atlasMockMode) {
        this.allocationRepository = allocationRepository;
        this.mdbClusterRepository = mdbClusterRepository;
        this.properties = properties;
        this.atlasMockMode = atlasMockMode;
    }

    /**
     * Resolve the runtime Atlas URI for the given Cluster.
     *
     * @throws IacException coded {@link AppErrorCode#IAC_MDB_NOT_READY} when the
     *                      allocation does not become READY in time, reaches a
     *                      terminal state, or the parent cluster lacks a
     *                      connection string
     */
    public String resolve(Cluster cluster) {
        return resolve(cluster, true);
    }

    /** Resolve an already READY allocation for HTTP callers without polling provisioning. */
    public String resolveReady(Cluster cluster) {
        return resolve(cluster, false);
    }

    private String resolve(Cluster cluster, boolean waitForReady) {
        ObjectId allocationId = cluster.getMdbAllocationId();
        String clusterId = cluster.getId().toHexString();

        if (allocationId == null || atlasMockMode) {
            // Unlinked/legacy Cluster, or an allocation engine running in mock
            // mode where no Atlas user exists for the assembled credentials:
            // keep the global configuration behavior, including a blank global
            // URI that selects the on-node MongoDB sidecar.
            String globalUri = properties.getRuntimeAtlasUri();
            log.info("[IaC] Cluster {} uses {} — falling back to "
                + "runtime-atlas-uri ({} sidecar)", clusterId,
                atlasMockMode ? "mock Atlas allocation" : "no linked MdbAllocation",
                (globalUri == null || globalUri.isBlank()) ? "local" : "configured");
            return globalUri == null ? "" : globalUri;
        }

        MdbAllocation allocation = waitForReady ? awaitReady(allocationId, clusterId) : readyAllocation(allocationId);
        MdbCluster mdbCluster = mdbClusterRepository.findById(allocation.getMdbClusterId())
            .orElseThrow(() -> new IacException(AppErrorCode.IAC_MDB_NOT_READY,
                "parent MdbCluster " + allocation.getMdbClusterId() + " not found"));

        String cleanConnectionString = mdbCluster.getConnectionString();
        if (cleanConnectionString == null || cleanConnectionString.isBlank()) {
            throw new IacException(AppErrorCode.IAC_MDB_NOT_READY,
                "MdbCluster " + mdbCluster.getId() + " has no connectionString seeded");
        }

        String uri = allocation.buildRuntimeConnectionString(cleanConnectionString);
        log.info("[IaC] Cluster {} runtime URI resolved: allocation={} mdbCluster={} db={} user={}",
            clusterId, allocationId.toHexString(), mdbCluster.getId().toHexString(),
            allocation.getDatabaseName(), allocation.getDbUsername());
        return uri;
    }

    private MdbAllocation readyAllocation(ObjectId allocationId) {
        return allocationRepository.findById(allocationId)
            .filter(allocation -> allocation.getStatus() == MdbAllocationStatus.READY)
            .orElseThrow(() -> new IacException(AppErrorCode.IAC_MDB_NOT_READY,
                "MdbAllocation " + allocationId + " is not READY"));
    }

    /** Poll the allocation until READY; fail coded on terminal state or timeout. */
    private MdbAllocation awaitReady(ObjectId allocationId, String clusterId) {
        long deadline = System.nanoTime() + ALLOCATION_READY_TIMEOUT.toNanos();
        MdbAllocationStatus lastStatus = null;

        while (System.nanoTime() < deadline) {
            MdbAllocation allocation = allocationRepository.findById(allocationId).orElse(null);
            if (allocation == null) {
                throw new IacException(AppErrorCode.IAC_MDB_NOT_READY,
                    "MdbAllocation " + allocationId + " no longer exists");
            }
            lastStatus = allocation.getStatus();
            if (lastStatus == MdbAllocationStatus.READY) {
                return allocation;
            }
            if (TERMINAL_FAILURE_STATES.contains(lastStatus)) {
                throw new IacException(AppErrorCode.IAC_MDB_NOT_READY,
                    "MdbAllocation " + allocationId + " reached terminal state " + lastStatus
                        + " (error=" + allocation.getProvisionErrorDetail() + ")");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IacException(AppErrorCode.IAC_MDB_NOT_READY, e,
                    "interrupted while waiting for MdbAllocation " + allocationId);
            }
        }

        throw new IacException(AppErrorCode.IAC_MDB_NOT_READY,
            "MdbAllocation " + allocationId + " for Cluster " + clusterId
                + " did not reach READY within " + ALLOCATION_READY_TIMEOUT
                + " (lastStatus=" + lastStatus + ")");
    }
}
