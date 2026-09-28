package io.tapstate.cloud.cluster;

/**
 * Cluster lifecycle state machine events (ADR-0124 D3).
 * <p>
 * Each Cluster has its own StateMachine instance; these events drive
 * the state transitions defined in {@link ClusterStateMachineConfig}.
 */
public enum ClusterEvent {
    /** User clicked "Create Cluster" → trigger CloudFormation CreateStack */
    CREATE,

    /** CloudFormation CREATE_COMPLETE + k3s Secret ready + EC2 bootstrap OK */
    PROVISION_SUCCESS,

    /** CloudFormation CREATE_FAILED / ROLLBACK_COMPLETE / k3s timeout / Atlas failure */
    PROVISION_FAILED,

    /** User clicked "Retry" on provisioning_failed Cluster */
    RETRY,

    /** Ready Cluster heartbeat expired (> 5 min) */
    HEARTBEAT_EXPIRED,

    /** Degraded Cluster heartbeat recovered */
    HEARTBEAT_RECOVERED,

    // === Week 4+ (not wired in Week 2) ===
    /** User triggered destroy → start graceful shutdown */
    DESTROY,

    /** CloudFormation DELETE_COMPLETE + k3s Secret deleted */
    DELETE_COMPLETE
}
