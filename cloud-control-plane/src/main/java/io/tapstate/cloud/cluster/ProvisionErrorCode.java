package io.tapstate.cloud.cluster;

/**
 * Provisioning failure error codes (ADR-0124 D3).
 * Persisted in {@code Cluster.provisionErrorCode} + {@code Cluster.provisionErrorDetail}.
 * Append-only: existing constants are an external API contract.
 */
public enum ProvisionErrorCode {
    /** CloudFormation CREATE_FAILED or ROLLBACK_COMPLETE */
    CLOUDFORMATION_FAILED,

    /** CloudFormation ValidateTemplate returned syntax error */
    CLOUDFORMATION_VALIDATE_FAILED,

    /** kubectl create secret failed (per-Cluster k3s Secret) */
    K3S_SECRET_CREATE_FAILED,

    /** Atlas Admin API createDatabase/createUser failed */
    ATLAS_DB_CREATE_FAILED,

    /** EC2 instance bootstrap timeout (10 min after CREATE_COMPLETE) */
    EC2_BOOTSTRAP_TIMEOUT,

    /** Total provisioning timeout (30 min) */
    PROVISIONING_TIMEOUT,

    /** No unexpired, valid TLS certificate covering the Cluster domain is stored */
    TLS_CERTIFICATE_UNAVAILABLE,

    /** TLS certificate upload or Nginx reload failed on the Cluster node */
    TLS_CERTIFICATE_INSTALL_FAILED,

    /** No deployable Cluster version is available (catalog empty) */
    CLUSTER_VERSION_UNAVAILABLE
}
