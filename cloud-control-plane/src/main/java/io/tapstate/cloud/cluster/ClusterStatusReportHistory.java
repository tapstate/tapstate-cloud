package io.tapstate.cloud.cluster;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * C2 status report history — time-series collection storing every heartbeat snapshot.
 * <p>
 * This collection uses MongoDB time-series optimization (created explicitly in
 * {@link io.tapstate.cloud.config.MongoInitListener}) with:
 * <ul>
 *   <li>{@code timeField} = {@link #timestamp} — the wall-clock instant the report arrived</li>
 *   <li>{@code metaField} = {@link #clusterId} — partitions data per-cluster for efficient range queries</li>
 *   <li>Granularity = SECONDS (heartbeat cadence is tens of seconds)</li>
 * </ul>
 * <p>
 * Unlike {@link Cluster#lastStatusReport} (latest-only embedded snapshot), this
 * collection keeps every accepted status report indefinitely — no compaction, no
 * overwrites. Use it for fleet-wide diagnostics, trend analysis, and audit trails.
 * <p>
 * Time-series collection constraints from MongoDB 5.0+:
 * <ul>
 *   <li>Inserts inside multi-doc transactions are supported; updates/deletes are restricted</li>
 *   <li>{@code timestamp} is indexed automatically — do NOT add explicit @Indexed</li>
 *   <li>Secondary indexes on meta fields (clusterId + timestamp range) must be
 *       declared here with @CompoundIndex for efficient lookups</li>
 * </ul>
 */
@Document(collection = "cluster_status_report_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@org.springframework.data.mongodb.core.index.CompoundIndex(
    name = "clusterId_timestamp_idx", def = "{'clusterId': 1, 'timestamp': -1}")
@Schema(description = "Time-series history of C2 status reports — every heartbeat accepted from every runtime")
public class ClusterStatusReportHistory {

    @Id
    @Schema(description = "History document ObjectId")
    private ObjectId id;

    @Schema(description = "Cluster ObjectId string — time-series meta field, partitions data per-cluster", example = "6475a2b3c1d4e5f6a7b8c9d0")
    private String clusterId;

    @Schema(description = "Wall-clock instant the control plane accepted this report — time-series time field (UTC)")
    private Instant timestamp;

    // ========== Full StatusReportRequest snapshot — every field preserved ==========

    @Schema(description = "Client-generated unique nonce from the runtime SDK")
    private String nonce;

    @Schema(description = "Tapstate runtime version (e.g. '2.1.0')")
    private String runtimeVersion;

    @Schema(description = "Milliseconds since runtime process started")
    private Long uptimeMs;

    @Schema(description = "Currently running pipeline count at the time of report")
    private Integer activePipelines;

    @Schema(description = "ISO-8601 UTC instant of last runtime error at report time, null if none")
    private String lastErrorAt;
}
