package io.tapstate.cloud.mdb.fsm;

/**
 * Events that drive the MdbAllocation state machine.
 * <p>
 * Mirrors the Cluster state machine's pattern. Events are fired by
 * {@link io.tapstate.cloud.mdb.MdbAllocationService} (or the async state
 * machine transition effects) to move the allocation through its lifecycle.
 */
public enum MdbAllocationEventType {

    /** Allocation record created, Atlas instance selected — start provisioning. */
    START_PROVISION,

    /** Atlas createDatabase + createDatabaseUser both succeeded. */
    PROVISION_SUCCESS,

    /** Atlas API call failed — allocation permanently failed. */
    PROVISION_FAIL,

    /** Allocation ready, user requested reclamation (before actual teardown). */
    INITIATE_RECLAIM,

    /** Atlas dropDatabaseUser + dropDatabase both succeeded. */
    RECLAIM_COMPLETE,

    /** Reclaim failed — resources may be partially cleaned up. */
    RECLAIM_FAILED
}
