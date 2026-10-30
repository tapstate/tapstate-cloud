package io.tapstate.cloud.mdb;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Per-Cluster database allocation record — tracks which shared Atlas Cluster
 * ({@link MdbCluster}) a Cluster's per-customer databases live on, plus the
 * runtime credentials the Cluster SDK uses to connect to them.
 * <p>
 * Lifetime: created when a Subscription is provisioned, transitions to
 * {@link MdbAllocationStatus#READY} once the Atlas db + dbUser exist, moved to
 * {@link MdbAllocationStatus#DELETED} on Subscription cancel.
 * <p>
 * Database naming (PO decision — full 24-char Cluster ObjectId hex):
 * <pre>
 *   main db        = mdb_{clusterId.toHexString()}
 *   operator db    = mdb_{clusterId.toHexString()}_operator
 *   views db       = mdb_{clusterId.toHexString()}_views
 * </pre>
 * <p>
 * dbUser roles are {@code dbAdmin} + {@code readWrite} on each of the three
 * databases (plus clusterMonitor / read config / read local). readWrite carries
 * the runtime's data and migration privileges; dbAdmin additionally authorizes
 * dropping the allocation's own databases, so the separate admin dbUser
 * credential path is gone.
 * <p>
 * Connection string construction: clean parent {@link MdbCluster#connectionString}
 * + per-allocation dbUser/dbPassword + main database name ({@code authSource=admin}).
 */
@Document(collection = "mdb_allocations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndex(name = "cluster_unique", def = "{'clusterId': 1}", unique = true)
@CompoundIndex(name = "org_db_unique", def = "{'orgId': 1, 'databaseName': 1}", unique = true)
@Schema(description = "Atlas database allocation record — per-Cluster DB + user on a shared Atlas Cluster")
public class MdbAllocation {

    @Id
    @Schema(description = "Allocation ObjectId")
    private ObjectId id;

    /** Owning Organization — allocation belongs to the org, not a user directly. */
    @Schema(description = "Organization ObjectId (isolation boundary)")
    private ObjectId orgId;

    /** Subscription that owns this allocation — main lifecycle driver. */
    @Schema(description = "Subscription ObjectId — main lifecycle link")
    private ObjectId subscriptionId;

    /** Cluster that owns this allocation — one allocation per Cluster (enforced by unique index). */
    @Schema(description = "Cluster ObjectId — unique per Cluster")
    private ObjectId clusterId;

    /** Parent MdbCluster this allocation lives on. */
    @Schema(description = "FK to MdbCluster — the shared Atlas cluster")
    private ObjectId mdbClusterId;

    // ========== Per-allocation DB + user ==========

    /** main db: {@code mdb_{clusterId.toHexString()}} (24-char full ObjectId hex). */
    @Schema(description = "Main database name", example = "mdb_6475a2b3c1d4e5f6a7b8c9d1")
    private String databaseName;

    /** operator db: {@code mdb_{clusterId.toHexString()}_operator}. */
    @Schema(description = "Operator state database name", example = "mdb_6475a2b3c1d4e5f6a7b8c9d1_operator")
    private String operatorDbName;

    /** views db: {@code mdb_{clusterId.toHexString()}_views}. */
    @Schema(description = "Views database name", example = "mdb_6475a2b3c1d4e5f6a7b8c9d1_views")
    private String viewsDbName;

    /** Atlas db username — owner_{databaseName} pattern, ≤ 64 chars. */
    @Schema(description = "Atlas database username", example = "owner_mdb_6475a2b3c1d4e5f6a7b8c9d1")
    private String dbUsername;

    /** Plaintext password — 32-byte SecureRandom → 48-char hex. Stored server-side only. */
    @Schema(description = "Database user password — SENSITIVE, never serialized", hidden = true)
    private String dbPassword;

    // ========== Status ==========

    @Schema(description = "Allocation lifecycle state")
    @Builder.Default
    private MdbAllocationStatus status = MdbAllocationStatus.PROVISIONING_PENDING;

    @Schema(description = "Provisioning failure error code (only when PROVISIONING_FAILED)")
    private String provisionErrorCode;

    @Schema(description = "Diagnostic detail (only when PROVISIONING_FAILED / DELETING)")
    private String provisionErrorDetail;

    // ========== Reclaim retry state ==========

    /** Number of reclaim retries already attempted (transient Atlas API
     * failures trigger bounded backoff retries instead of permanent orphaning). */
    @Schema(description = "Number of reclaim retries already attempted")
    @Builder.Default
    private int reclaimRetryCount = 0;

    /** Scheduled time for the next reclaim retry (exponential backoff).
     * Null means no retry scheduled yet / retries exhausted. */
    @Schema(description = "When the next reclaim retry is scheduled (exponential backoff)")
    private Instant reclaimNextRetryAt;

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
     * Takes the clean shared-cluster connection string + injects our dbUser/dbPassword
     * + main database name + {@code authSource=admin} (Atlas default).
     * <p>
     * Supports both Atlas URI flavors:
     * <pre>
     *   mongodb+srv://host                       → mongodb+srv://user:pass@host/db?authSource=admin
     *   mongodb://host1:27017,host2:27017[/...]  → mongodb://user:pass@host1:27017,host2:27017/db?authSource=admin
     * </pre>
     * Any credentials or options embedded in the input are replaced: the runtime
     * authenticates exclusively as this allocation's dbUser against the main db.
     *
     * @param instanceConnectionString clean URI from MdbCluster (no credentials embedded)
     */
    public String buildRuntimeConnectionString(String instanceConnectionString) {
        if (instanceConnectionString == null) return null;

        int schemeEnd = instanceConnectionString.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException(
                "Invalid MongoDB connection string (no scheme): " + instanceConnectionString);
        }
        String scheme = instanceConnectionString.substring(0, schemeEnd + 3);
        String authorityAndPath = instanceConnectionString.substring(schemeEnd + 3);

        // Strip any embedded credentials; keep the host list and preserve any
        // existing options (authSource is always forced to admin).
        int at = authorityAndPath.indexOf('@');
        String rest = at >= 0 ? authorityAndPath.substring(at + 1) : authorityAndPath;
        int question = rest.indexOf('?');
        String hostAndPath = question >= 0 ? rest.substring(0, question) : rest;
        String existingOptions = question >= 0 ? rest.substring(question + 1) : "";
        int slash = hostAndPath.indexOf('/');
        String hostList = slash >= 0 ? hostAndPath.substring(0, slash) : hostAndPath;

        String options = existingOptions.isBlank()
            ? "authSource=admin"
            : "authSource=admin&" + existingOptions;

        return scheme + dbUsername + ":" + dbPassword + "@" + hostList
            + "/" + databaseName + "?" + options;
    }
}
