package io.tapstate.cloud.cluster;

/**
 * Cluster lifecycle states — Spring Statemachine model (ADR-0124 D3).
 * <p>
 * Week 2 implements 6 states (draining + terminated deferred to Week 4):
 * <pre>
 * provisioning_pending → provisioning → ready / provisioning_failed
 * provisioning_failed → provisioning_pending (retry)
 * ready → degraded → ready (heartbeat expiry / recovery)
 * </pre>
 */
public enum ClusterState {
    /** Record created, waiting for StateMachine to trigger auto-provisioning */
    PROVISIONING_PENDING,

    /** CloudFormation stack + k3s Secret + EC2 bootstrap in progress */
    PROVISIONING,

    /** CloudFormation CREATE_COMPLETE + all resources ready + runtime healthy */
    READY,

    /** Provisioning failed — CloudFormation CREATE_FAILED / k3s Secret timeout / Atlas API failure / EC2 bootstrap timeout */
    PROVISIONING_FAILED,

    /** Previously READY but SDK C2 heartbeat expired (> 5 min) */
    DEGRADED,

    // === Week 4+ (not implemented in Week 2) ===
    /** Destruction triggered, waiting for Pipeline graceful stop */
    DRAINED,

    /** CloudFormation DELETE_COMPLETE + k3s Secret deleted — all resources cleaned up */
    TERMINATED
}
