package io.tapstate.cloud.mdb;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Per-customer database allocation record — tracks which shared Atlas instance
 * a customer's database lives on, plus the runtime credentials the Cluster SDK
 * uses to connect to it.
 * <p>
 * Lifetime: created when a Customer subscribes (Cluster provisioning), moved to
 * {@link MdbAllocationStatus#READY} once database + user exist on Atlas, and
 * deleted when the subscription is cancelled (release the database + user on Atlas,
 * decrement {@link AtlasInstanceConfig#currentCustomers}).
 * <p>
 * Customer database is isolated by database name + database user — both unique
 * per Cluster. Connection uses the parent {@link AtlasInstanceConfig#connectionString}
 * plus the allocated {@link #databaseName} + {@link #dbUsername} + {@link #dbPassword}.
 */
@Document(collection = "mdb_allocations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndex(name = "cluster_unique", def = "{'clusterId': 1}", unique = true)
@CompoundIndex(name = "org_db_unique", def = "{'orgId': 1, 'databaseName': 1}", unique = true)
@Schema(description = "Atlas database allocation record — per-Cluster DB + user on a shared Atlas instance")
public class MdbAllocation {

    @Id
    @Schema(description = "Allocation ID")
    private ObjectId id;

    /** Owning Organization — allocation belongs to org, not user directly. */
    @Schema(description = "Organization ID")
    private ObjectId orgId;

    /** Cluster that owns this allocation (one allocation per Cluster). */
    @Schema(description = "Cluster ID")
    private ObjectId clusterId;

    /** Parent subscription ID — links allocation to subscription lifecycle. */
    @Schema(description = "Subscription ID")
    private ObjectId subscriptionId;

    // ========== Atlas link ==========

    @Schema(description = "ID of the parent AtlasInstanceConfig this allocation lives on")
    private ObjectId atlasInstanceId;

    /** Duplicated from config at creation time so allocation stays self-contained. */
    @Schema(description = "Atlas Project ID (snapshot from config at creation)")
    private String atlasProjectId;

    @Schema(description = "Atlas Cluster name (snapshot from config at creation)")
    private String atlasClusterName;

    // ========== Per-allocation DB + user ==========

    @Schema(description = "Allocated MongoDB database name on Atlas", example = "tapstate_cluster_abc123")
    private String databaseName;

    @Schema(description = "Allocated database username (database owner role)", example = "cluster_abc123_owner")
    private String dbUsername;

    /**
     * Database user password — plaintext stored server-side only.
     * NEVER returned in API responses; the Cluster runtime reads it via internal lookup.
     */
    @Schema(description = "Database user password — server-side only", hidden = true)
    private String dbPassword;

    // ========== Status ==========

    @Schema(description = "Allocation lifecycle state")
    @Builder.Default
    private MdbAllocationStatus status = MdbAllocationStatus.PROVISIONING_PENDING;

    @Schema(description = "Provisioning failure error code (only when status = PROVISIONING_FAILED)")
    private String provisionErrorCode;

    @Schema(description = "Diagnostic detail (only when PROVISIONING_FAILED or DELETING)")
    private String provisionErrorDetail;

    @Schema(description = "Atlas API response correlation id for last operation")
    private String atlasRequestId;

    // ========== Timestamps ==========

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Schema(description = "When allocation transitioned to READY")
    private Instant readyAt;

    @Schema(description = "When allocation transitioned to DELETED")
    private Instant deletedAt;

    /**
     * Build the full MongoDB connection URI the Cluster runtime uses.
     * Takes the shared instance connection string + allocated db user + db name.
     * <p>
     * Example output: {@code mongodb+srv://cluster_abc_owner:p***@tapstate-shared.mongodb.net/tapstate_cluster_abc}
     */
    public String buildRuntimeConnectionString(String instanceConnectionString) {
        if (instanceConnectionString == null) return null;
        // Strip existing user:password from the connection string — we inject our own
        // mongodb+srv://host.tapstate.net/dbname?authSource=admin
        String stripped = instanceConnectionString.replaceFirst("mongodb\\+srv://[^/@]*@", "mongodb+srv://");
        // Inject our user:password before the host
        String userPart = "mongodb+srv://" + dbUsername + ":" + dbPassword + "@";
        String rest = stripped.substring("mongodb+srv://".length());
        // Append database name + authSource=admin (Atlas default auth DB)
        int q = rest.indexOf('?');
        if (q > 0) {
            rest = rest.substring(0, q) + "/" + databaseName + "?authSource=admin&" + rest.substring(q + 1);
        } else {
            rest = rest + "/" + databaseName + "?authSource=admin";
        }
        return userPart + rest;
    }
}
