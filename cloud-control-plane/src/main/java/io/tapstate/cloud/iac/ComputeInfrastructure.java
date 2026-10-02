package io.tapstate.cloud.iac;

import io.tapstate.cloud.iac.model.NodeInstance;
import io.tapstate.cloud.iac.model.NodeSpec;

import java.util.List;

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
     * <p>
     * Port 22 is initially reachable from any source so the control plane can
     * observe its real egress address through the connection itself; the caller
     * MUST follow with {@link #restrictSshIngress} as soon as that is known.
     * Authentication stays key-only throughout.
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
     * Replace the temporary any-source port-22 rule with rules limited to the
     * observed control-plane egress ranges.
     * <p>
     * Default no-op for providers whose node firewall is managed differently.
     *
     * @param region    provider region the node was created in
     * @param clusterId Cluster id hex the node was created for
     * @param cidrs     source CIDRs allowed to connect on port 22
     */
    default void restrictSshIngress(String region, String clusterId, List<String> cidrs) {
    }

    /**
     * Provider identity of this implementation.
     */
    CloudProvider provider();
}
