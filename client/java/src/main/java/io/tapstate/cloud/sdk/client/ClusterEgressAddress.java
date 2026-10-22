package io.tapstate.cloud.sdk.client;

/** Credential-free current address projection, scoped to the SDK's configured Cluster. */
public record ClusterEgressAddress(String organizationId, String clusterId,
        String ec2PublicIp, String egressAddressStatus) { }
