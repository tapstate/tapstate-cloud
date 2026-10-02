package io.tapstate.cloud.iac;

import io.tapstate.cloud.iac.model.NodeInstance;
import io.tapstate.cloud.iac.model.NodeSpec;

/**
 * Compute infrastructure abstraction — creates and destroys the node (and all its
 * supporting resources such as firewall rules and disks) that hosts one Cluster.
 * <p>
 * Implementations MUST be blocking: a successful {@link #provision} returns only
 * once the node exists with a usable public address, and {@link #terminate} returns
 * only once every resource has been reclaimed. Long-running callers are expected to
 * run the invocation on a virtual thread.
 */
public interface ComputeInfrastructure {

    /**
     * Create the node plus its supporting resources and wait until it can be
     * addressed over the network.
     *
     * @param spec node specification
     * @return handle to the created node
     */
    NodeInstance provision(NodeSpec spec);

    /**
     * Destroy every resource created for the given Cluster and wait for completion.
     * <p>
     * Idempotent: resources that no longer exist are treated as success.
     *
     * @param region    provider region the node was created in
     * @param clusterId Cluster id hex the node was created for
     */
    void terminate(String region, String clusterId);

    /**
     * Provider identity of this implementation.
     */
    CloudProvider provider();
}
