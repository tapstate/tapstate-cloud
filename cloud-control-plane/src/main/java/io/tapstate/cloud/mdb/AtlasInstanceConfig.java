package io.tapstate.cloud.mdb;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Admin-configured Atlas instance — a pre-provisioned MongoDB Atlas cluster
 * that Tapstate Cloud dynamically carves databases out of for each customer.
 * <p>
 * One Atlas instance can serve N customers (enforced by {@link #maxCustomers} —
 * actual count tracked via {@link #currentCustomers} with atomic increment in
 * {@code findAvailableInstance}). When an instance reaches capacity, the
 * allocation engine picks the next region-matched instance with available slots.
 * <p>
 * Atlas credentials ({@link #atlasPublicKey} / {@link #atlasPrivateKey}) are the
 * Programmatic API Key pair with PROJECT_OWNER / ORG_OWNER role — never exposed
 * to end users, only used server-side for Atlas REST API calls.
 * <p>
 * Connection string ({@link #connectionString}) is the standard MongoDB URI used
 * by Tapstate runtime SDK when it connects to its allocated database — this IS
 * shared infrastructure, so the URI stays the same per instance; per-user
 * isolation is via database name + database user.
 */
@Document(collection = "atlas_instance_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Admin-configured Atlas instance — shared infrastructure for per-customer database allocation")
public class AtlasInstanceConfig {

    @Id
    @Schema(description = "Config ID")
    private ObjectId id;

    /** Atlas Project ID — the group that owns this cluster. */
    @Schema(description = "Atlas Project ID")
    private String atlasProjectId;

    /** Atlas Cluster name (within the project). */
    @Schema(description = "Atlas Cluster name", example = "tapstate-shared-ap-southeast-1")
    private String atlasClusterName;

    /**
     * Atlas API base URL — defaults to {@code https://cloud.mongodb.com}.
     * Separate field so admin can override (staging, different cloud).
     */
    @Builder.Default
    @Schema(description = "Atlas API base URL", example = "https://cloud.mongodb.com")
    private String atlasApiBaseUrl = "https://cloud.mongodb.com";

    // ========== Region + spec — matching dimension for allocation ==========

    @Indexed
    @Schema(description = "AWS/Cloud region this Atlas cluster serves", example = "ap-southeast-1")
    private String region;

    @Schema(description = "Atlas cluster tier/spec", example = "M10")
    private String atlasTier;

    @Schema(description = "Admin display name", example = "Singapore Shared M10")
    private String displayName;

    // ========== Capacity tracking — prevents over-subscription ==========

    /** Hard limit on per-instance customer count. */
    @Schema(description = "Maximum number of customer databases this instance can host")
    private int maxCustomers;

    /** Current assigned customer count — incremented atomically on allocation, decremented on deletion. */
    @Schema(description = "Current number of allocated customer databases")
    @Builder.Default
    private int currentCustomers = 0;

    // ========== Atlas credentials — SENSITIVE, never serialized to API response ==========

    /** Atlas API Public Key (programmatic API key id). */
    @Schema(description = "Atlas API Public Key — SENSITIVE", hidden = true)
    private String atlasPublicKey;

    /** Atlas API Private Key — kept server-side only. */
    @Schema(description = "Atlas API Private Key — SENSITIVE", hidden = true)
    private String atlasPrivateKey;

    // ========== Runtime connection info — passed to Cluster runtime ==========

    /**
     * Standard MongoDB Atlas connection string ({@code mongodb+srv://...}).
     * Point to the Atlas cluster — customer runtime connects to this URI with
     * its own allocated database name + database user credentials.
     */
    @Schema(description = "Standard MongoDB Atlas connection string")
    private String connectionString;

    // ========== Lifecycle ==========

    @Schema(description = "Config status — ACTIVE = available for allocation")
    @Builder.Default
    private ConfigStatus status = ConfigStatus.ACTIVE;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum ConfigStatus { ACTIVE, DISABLED }
}
