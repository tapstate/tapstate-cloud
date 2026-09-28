package io.tapstate.cloud.cluster.fsm;

/**
 * Events that drive the Cluster state machine.
 * <p>
 * Each event corresponds to a discrete trigger — either from an external actor
 * (GC user action, GC auto-provisioning), from the Cluster Server SDK (C2
 * heartbeat, runtime feedback), or from internal lifecycle tasks (heartbeat
 * timeout scanner, auto-provisioning virtual thread).
 */
public enum ClusterEventType {

    // ========== Creation / Provisioning ==========
    /** GC auto-provisioning picks up the PENDING record → starts provisioning */
    START_PROVISION,
    /** CloudFormation CREATE_COMPLETE + k3s Secret + EC2 bootstrap all succeed */
    PROVISION_SUCCESS,
    /** CloudFormation CREATE_FAILED / k3s Secret timeout / EC2 bootstrap timeout */
    PROVISION_FAIL,

    // ========== Runtime Operations ==========
    /** User (or system) explicitly triggers retry — only valid from PROVISIONING_FAILED */
    RETRY,
    /** GC heartbeat scanner fires — runtime lastHeartbeatAt expired (>5 min) */
    HEARTBEAT_TIMEOUT,
    /** Cluster Server C2 heartbeat arrives — previously DEGRADED runtime is healthy again */
    HEARTBEAT_RECOVERY,

    // ========== Termination ==========
    /** User (or system) initiates termination — drains pipelines first */
    INITIATE_TERMINATION,
    /** Pipeline graceful stop completes — safe to tear down infrastructure */
    DRAIN_COMPLETE,
    /** Drain times out / runtime refuses — force tear down */
    DRAIN_FAILED,
}
