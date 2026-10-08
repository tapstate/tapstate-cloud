package io.tapstate.cloud.cluster;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Cluster record — per-user-isolated runtime EC2 + Docker instance.
 * <p>
 * Lifecycle driven by Spring Statemachine (ADR-0124 D3).
 * Auto-provisioning via CloudFormation API + per-Cluster k3s Secret + Atlas DB/user.
 * <p>
 * Isolation boundary: all resources tagged with organization_id / cluster_id / runtime_id (ADR-0124 D5).
 */
@Document(collection = "clusters")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndex(name = "org_region_name_unique", def = "{'orgId': 1, 'region': 1, 'name': 1}", unique = true)
@Schema(description = "Tapstate Cloud Cluster — per-user-isolated runtime EC2 instance")
public class Cluster {

    @Id
    @Schema(description = "Cluster ObjectId")
    private ObjectId id;

    @Schema(description = "Organization this Cluster belongs to (isolation boundary)", example = "6475a2b3c1d4e5f6a7b8c9d0")
    private ObjectId orgId;

    @Indexed
    @Schema(description = "Subscription that owns this Cluster — exactly one Cluster per Subscription")
    private ObjectId subscriptionId;

    @Schema(description = "AWS Region", example = "ap-southeast-1")
    private String region;

    @Schema(description = "Cluster display name", example = "Production Pipeline")
    private String name;

    @Schema(description = "Provisioning spec_key", example = "basic")
    @Builder.Default
    private String specKey = "basic";

    @Schema(description = "Current lifecycle state")
    private ClusterState state;

    @Schema(description = "Provisioning failure code (only when state = PROVISIONING_FAILED)")
    private ProvisionErrorCode provisionErrorCode;

    @Schema(description = "Diagnostic detail JSON (only when PROVISIONING_FAILED)")
    private String provisionErrorDetail;

    // ========== CloudFormation ==========
    @Schema(description = "CloudFormation stack ID", example = "arn:aws:cloudformation:ap-southeast-1:123:stack/tapstate-cluster-abc123")
    private String cloudFormationStackId;

    @Schema(description = "CloudFormation stack status (raw AWS status)")
    private String cloudFormationStackStatus;

    // ========== Runtime identity ==========
    @Schema(description = "Runtime instance UUID (bound to this Cluster)")
    private String runtimeId;

    @Schema(description = "Per-Cluster bootstrap token (one-time use for runtime registration)")
    private String bootstrapToken;

    @Schema(description = "Per-Cluster SDK static token — runtime uses this for C2 status-report Bearer auth")
    private String sdkStaticToken;

    // ========== AWS resources ==========
    @Schema(description = "EC2 instance ID")
    private String ec2InstanceId;

    @Schema(description = "EC2 node public IPv4 address")
    private String ec2PublicIp;

    @Schema(description = "Per-Cluster SSH public key (OpenSSH format)")
    private String sshPublicKey;

    @Schema(description = "Per-Cluster SSH private key, encrypted at rest")
    private String sshPrivateKeyEnc;

    @Schema(description = "Public DNS: {clusterId}.dev.cloud.tapstate.com")
    private String publicDns;

    @Schema(description = "DNS A record created for the Cluster node")
    private String dnsRecord;

    // ========== SDK status ==========
    @Schema(description = "Last SDK C2 heartbeat timestamp (UTC)")
    private Instant lastHeartbeatAt;

    @Schema(description = "Last accepted C2 nonce — used for replay protection (ADR-0124 D3)")
    private String lastC2Nonce;

    @Schema(description = "Latest C2 status-report snapshot — full runtime health payload stored as embedded document, overwritten on every heartbeat")
    private StatusReportRequest lastStatusReport;

    @Schema(description = "When Cluster transitioned to TERMINATED (resources cleaned up)")
    private Instant terminatedAt;

    // ========== Atlas MDB allocation — populated at Cluster create time ==========

    @Schema(description = "FK to MdbAllocation — this Cluster's per-customer Atlas database allocation")
    private ObjectId mdbAllocationId;

    // ========== Timestamps ==========
    @CreatedDate
    @Schema(description = "Cluster creation timestamp")
    private Instant createdAt;

    @LastModifiedDate
    @Schema(description = "Last state update timestamp")
    private Instant updatedAt;

    @Schema(description = "When Cluster transitioned to READY")
    private Instant readyAt;

    // ========== Isolation labels (ADR-0124 D5) ==========
    @Schema(description = "AWS resource tags — organization_id / cluster_id / runtime_id")
    @Builder.Default
    private java.util.Map<String, String> tags = new java.util.HashMap<>();
}
