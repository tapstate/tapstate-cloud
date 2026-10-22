package io.tapstate.cloud.cluster;

/** Lifecycle state of the Cluster address used by source database firewalls. */
public enum ClusterEgressAddressStatus {
    /** Older Clusters still use the EC2-assigned address, which may change on replacement. */
    DYNAMIC,

    /** A stable address is being attached to this Cluster. */
    STABILIZING,

    /** The stable address is being associated with the Cluster node. */
    ATTACHING,

    /** A Cluster-owned Elastic IP is associated with the current node. */
    STABLE,

    /** Provisioning or migration could not establish the stable address. */
    FAILED
}
