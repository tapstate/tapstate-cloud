package io.tapstate.cloud.mdb;

/**
 * Abstract MDB (MongoDB / Materialized Database) service — allocation engine
 * for per-Cluster database resources on shared infrastructure.
 * <p>
 * Default implementation is {@link AtlasMdbService} which carves databases
 * out of pre-administered Atlas clusters ({@link MdbCluster}). Swappable:
 * a future LocalMdbService could allocate from a local replica set.
 * <p>
 * Lifecycle (per-Cluster):
 * <ol>
 *   <li>{@link #createMdb(String, String, String)} — pick an MdbCluster by region + capacity</li>
 *   <li>{@link #createDatabase(MdbAllocation)} — verify Atlas cluster reachability</li>
 *   <li>{@link #createDatabaseUser(MdbAllocation)} — call Atlas Admin API to create the dbAdmin+readWrite-scoped user</li>
 *   <li>{@link #destroyMdb(MdbAllocation)} — release via dropDatabase + dropDatabaseUser (shared-instance is admin-managed)</li>
 * </ol>
 * All methods throw {@link MdbException} on failure — callers catch this and
 * transition the allocation record to {@link MdbAllocationStatus#PROVISIONING_FAILED}.
 */
public interface MdbService {

    /**
     * Find the least-loaded ACTIVE MdbCluster matching the given region and
     * atomically increment its {@code currentAllocations} via findAndModify.
     * <p>
     * Returns the reserved MdbCluster snapshot — caller persists the
     * {@link MdbAllocation} from this, then triggers the async state machine
     * flow that calls {@link #createDatabase} + {@link #createDatabaseUser}.
     *
     * @param orgId     owning organization (ISO hex or ObjectId string)
     * @param clusterId owning cluster (ISO hex or ObjectId string)
     * @param region    required region — matches MdbCluster.region
     * @return reserved MdbCluster with incremented currentAllocations
     * @throws MdbException no ACTIVE MdbCluster for this region / all at capacity
     */
    MdbCluster createMdb(String orgId, String clusterId, String region);

    /** No-op for shared MdbCluster instances — instance itself is admin-managed. */
    default void destroyMdb(MdbAllocation allocation) {
        // Shared Atlas cluster is admin-provisioned — nothing to tear down at instance level.
        // Per-allocation resources released via dropDatabase + dropDatabaseUser.
    }

    /**
     * Verify Atlas cluster reachability. Atlas has no explicit create-db endpoint —
     * databases are created implicitly on first write. This step validates the
     * MdbCluster.cluster.stateName == "IDLE" and seeds a marker document if needed.
     * Record's {@link MdbAllocation#status} is {@link MdbAllocationStatus#PROVISIONING}
     * during the call.
     */
    void createDatabase(MdbAllocation allocation);

    /**
     * Call Atlas Admin API to create the database user with {@code dbAdmin} and
     * {@code readWrite} roles on the three allocation databases
     * (main + operator + views). readWrite is required for the runtime's data
     * access and bootstrap migrations; dbAdmin additionally permits dropping the
     * databases on reclaim. Atlas username / password come from
     * {@link MdbAllocation#getDbUsername()} / {@link MdbAllocation#getDbPassword()}.
     * <p>
     * Idempotent: 409 USER_ALREADY_EXISTS treated as success.
     */
    void createDatabaseUser(MdbAllocation allocation);

    /** Drop the three allocation databases via MongoClient (dbAdmin role has authority). */
    void dropDatabase(MdbAllocation allocation);

    /** Drop the allocation's database user via Atlas Admin API. 404 treated as success. */
    void dropDatabaseUser(MdbAllocation allocation);
}
