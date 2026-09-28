package io.tapstate.cloud.mdb;

/**
 * Allocation lifecycle states for {@link MdbAllocation}.
 * <p>
 * Mirrors the Cluster state machine's PROVISIONING_PENDING / PROVISIONING /
 * PROVISIONING_FAILED / READY pattern so both async flows can be tracked
 * uniformly. Terminal states: READY (allocated), PROVISIONING_FAILED
 * (permanent failure), DELETED (release confirmed on Atlas).
 */
public enum MdbAllocationStatus {

    /** Record created, Atlas instance selected, awaiting createDatabase + createUser. */
    PROVISIONING_PENDING,

    /** Atlas API calls in progress — polling Atlas for cluster + DB readiness. */
    PROVISIONING,

    /** Atlas database + user created, connection info returned to caller. */
    READY,

    /** Atlas provisioning failed — error detail on the allocation record. */
    PROVISIONING_FAILED,

    /** Termination in progress — deleteDatabase + deleteUser pending. */
    DELETING,

    /** Atlas resources released, record is a tombstone. */
    DELETED
}
