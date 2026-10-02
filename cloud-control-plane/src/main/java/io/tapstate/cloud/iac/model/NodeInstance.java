package io.tapstate.cloud.iac.model;

import io.tapstate.cloud.iac.CloudProvider;

/**
 * Handle to a compute node created by a {@link io.tapstate.cloud.iac.ComputeInfrastructure}.
 * <p>
 * Carries only the data required to contact the node (SSH) and to identify it for
 * termination; provider-specific bookkeeping stays inside the implementation.
 *
 * @param provider   provider that owns the node
 * @param clusterId  Cluster id hex the node was created for
 * @param instanceId provider instance identifier
 * @param publicIp   node public IPv4 address
 * @param privateIp  node private IPv4 address
 */
public record NodeInstance(
    CloudProvider provider,
    String clusterId,
    String instanceId,
    String publicIp,
    String privateIp
) {
}
