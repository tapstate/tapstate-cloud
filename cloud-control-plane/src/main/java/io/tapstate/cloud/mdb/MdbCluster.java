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
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Admin-configured shared Atlas Cluster — a pre-provisioned Atlas M10/M20/etc
 * that Tapstate Cloud dynamically carves databases out of for each paying Cluster.
 * <p>
 * This entity replaces {@code AtlasInstanceConfig}. Major simplifications:
 * <ul>
 *   <li>API Key credentials live in {@link AtlasConfig} — MdbCluster only holds
 *       {@code atlasConfigId} FK pointing to the key to use</li>
 *   <li>{@code projectId} / {@code name} replace the old doubly-prefixed
 *       {@code atlasProjectId} / {@code atlasClusterName}</li>
 *   <li>System auto-loads Atlas Admin API QueryOneCluster response into the
 *       {@code cluster} embedded document — admin seeds only the base fields</li>
 *   <li>Capacity counters renamed: {@code currentCustomers → currentAllocations},
 *       {@code maxCustomers → maxAllocations}</li>
 * </ul>
 * <p>
 * One MdbCluster can serve N customer allocations. Slot reservation uses
 * {@code findAndModify(currentAllocations < maxAllocations)} — optimistic concurrency.
 */
@Document(collection = "mdb_clusters")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Shared Atlas Cluster — admin-provisioned, per-customer database allocation carves out of this")
public class MdbCluster {

    @Id
    @Schema(description = "MdbCluster ObjectId")
    private ObjectId id;

    /** FK → atlas_configs._id — which API key to use for Atlas Admin API calls. */
    @Indexed
    @Schema(description = "FK to AtlasConfig — which API key to use for Atlas Admin API calls")
    private ObjectId atlasConfigId;

    /** Atlas Project / groupId — required by every Atlas Admin API path. */
    @Schema(description = "Atlas Project ID (groupId)", example = "pv420x")
    private String projectId;

    /** Atlas-internal Cluster name (within the project). */
    @Schema(description = "Atlas Cluster name", example = "tapstate-shared-ap-southeast-1")
    private String name;

    /** AWS region — allocation engine picks by region match. */
    @Indexed
    @Schema(description = "AWS/Cloud region this Atlas cluster serves", example = "ap-southeast-1")
    private String region;

    // ========== Capacity — atomic slot reservation ==========

    /** Hard upper bound on the number of per-customer allocations this cluster can host. */
    @Schema(description = "Maximum allocation capacity on this shared cluster")
    private int maxAllocations;

    /** Current allocation count — incremented atomically via findAndModify. */
    @Builder.Default
    @Schema(description = "Current allocated slot count — updated atomically on alloc / reclaim")
    private int currentAllocations = 0;

    // ========== Runtime connection info ==========

    /** Clean connection string ({@code mongodb+srv://host}) — no credentials injected. */
    @Schema(description = "Clean Atlas connection string (no embedded credentials)", example = "mongodb+srv://tapstate-shared.mongodb.net")
    private String connectionString;

    // ========== Status + auto-loaded cluster info ==========

    /** "ACTIVE" = available for allocation, "DISABLED" = excluded. */
    @Builder.Default
    @Schema(description = "ACTIVE = available for allocation", example = "ACTIVE")
    private String status = "ACTIVE";

    /**
     * Auto-populated Atlas Admin API QueryOneCluster response — contains stateName,
     * connectionStrings, tierName, providerName, regionName, createDate, mongoDBVersion, etc.
     * Admin seeds this as null; it gets populated the first time MdbCluster create()
     * or a scheduled refresh runs.
     */
    @Schema(description = "Auto-populated QueryOneCluster response from Atlas Admin API — null until first load")
    private org.bson.Document cluster;

    // ========== Timestamps ==========

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
