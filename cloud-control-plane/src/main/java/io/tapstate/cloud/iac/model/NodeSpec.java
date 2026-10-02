package io.tapstate.cloud.iac.model;

import java.util.List;
import java.util.Map;

/**
 * Provider-independent specification of a compute node hosting one Cluster.
 * <p>
 * Built by the orchestration layer from the persisted Cluster entity; consumed by
 * a {@link io.tapstate.cloud.iac.ComputeInfrastructure} implementation. No field
 * names any specific cloud vendor beyond the optional machine-type hint, which is
 * interpreted by the active provider.
 *
 * @param clusterId    Cluster id hex — drives resource naming and identity tags
 * @param region       provider region code (interpretation belongs to the provider)
 * @param instanceType provider machine type (e.g. AWS {@code t3.medium})
 * @param diskSizeGb   root disk size in GB
 * @param sshPublicKey OpenSSH-encoded public key injected into the node
 * @param ingressRules inbound firewall rules to attach to the node
 * @param tags         identity tags applied to every created resource
 */
public record NodeSpec(
    String clusterId,
    String region,
    String instanceType,
    int diskSizeGb,
    String sshPublicKey,
    List<IngressRule> ingressRules,
    Map<String, String> tags
) {

    /**
     * Create a spec, defaulting the rule list and tag map when null.
     */
    public NodeSpec {
        ingressRules = ingressRules != null ? List.copyOf(ingressRules) : List.of();
        tags = tags != null ? Map.copyOf(tags) : Map.of();
    }
}
