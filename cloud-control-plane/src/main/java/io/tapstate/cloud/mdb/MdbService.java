package io.tapstate.cloud.mdb;

/**
 * Abstract MDB (MongoDB / Materialized Database) service — allocation engine
 * for per-customer database resources on shared infrastructure.
 * <p>
 * The default implementation is {@code AtlasMdbService} which carves databases
 * out of pre-administered Atlas instances. Swappable: a future {@code LocalMdbService}
 * could allocate from a local replica set for dev/test.
 * <p>
 * Lifecycle (per-Cluster):
 * <ol>
 *   <li>{@link #createMdb(String, String, String)} — pick an Atlas instance by region + capacity</li>
 *   <li>{@link #createDatabase(MdbAllocation)} — call Atlas to create the customer database</li>
 *   <li>{@link #createDatabaseUser(MdbAllocation)} — call Atlas to create the database owner user</li>
 *   <li>{@link #destroyMdb(MdbAllocation)} — release (call {@link #dropDatabase} + {@link #dropDatabaseUser})</li>
 * </ol>
 * {@link #destroyMdb} is a no-op for shared-instance Atlas because the instance
 * itself is admin-managed; only the per-allocation database + user are torn down.
 * <p>
 * All methods throw {@link MdbException} on failure — callers catch this and
 * transition the allocation record to {@link MdbAllocationStatus#PROVISIONING_FAILED}.
 */
public interface MdbService {

    /**
     * Step 1: Find the least-loaded ACTIVE Atlas instance matching the given region
     * and atomically increment its {@code currentCustomers}.
     * <p>
     * Returns the selected {@link AtlasInstanceConfig} snapshot — caller creates the
     * {@link MdbAllocation} record from this, then calls {@link #createDatabase} +
     * {@link #createDatabaseUser} in the async state machine flow.
     *
     * @param orgId     owning organization
     * @param clusterId owning cluster
     * @param region    required region — matches Atlas instance config's region field
     * @return selected Atlas instance config
     * @throws MdbException no ACTIVE instance for this region / all at capacity
     */
    AtlasInstanceConfig createMdb(String orgId, String clusterId, String region);

    /** No-op for shared Atlas instances — instance itself is admin-managed. */
    default void destroyMdb(MdbAllocation allocation) {
        // Atlas instance is shared — nothing to tear down at instance level.
        // Per-allocation resources released via dropDatabase + dropDatabaseUser.
    }

    /**
     * Call Atlas API to create the customer database on the allocated instance.
     * <p>
     * In Atlas, databases are created implicitly on first write — so this step
     * verifies connectivity and seeds the database if the implementation chooses
     * to (e.g. createCollection). Record's {@link MdbAllocation#status} stays
     * {@link MdbAllocationStatus#PROVISIONING} during the call.
     */
    void createDatabase(MdbAllocation allocation);

    /**
     * Call Atlas API to create the customer database user with {@code readWrite}
     * on the allocated database. Atlas username + password come from
     * {@link MdbAllocation#getDbUsername()} / {@link MdbAllocation#getDbPassword()}.
     */
    void createDatabaseUser(MdbAllocation allocation);

    /** Drop the allocated database (Atlas admin API deleteCollection on each collection, or admin deleteDatabase). */
    void dropDatabase(MdbAllocation allocation);

    /** Drop the allocated database user on Atlas. */
    void dropDatabaseUser(MdbAllocation allocation);
}
