package io.tapstate.cloud.cluster;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * C2 status-report payload — runtime Cluster → Global Control Plane heartbeat + metrics.
 * <p>
 * Every field is intentionally infrastructure-level only. User-facing data
 * (Source/Pipeline config, Atlas credentials, business state) must NEVER flow
 * through this endpoint (ADR-0124 D3).
 * <p>
 * This class serves a dual purpose:
 * <ol>
 *   <li>HTTP request body deserialization (Spring MVC @RequestBody)</li>
 *   <li>MongoDB embedded document stored on {@link Cluster#lastStatusReport} —
 *       the latest snapshot of runtime health, overwritten on every heartbeat</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "C2 status report payload — runtime heartbeat + infrastructure metrics")
public class StatusReportRequest {

    @Schema(description = "Unique nonce per request — MUST change every call. Same value twice → 409 auth.nonce-replay", requiredMode = Schema.RequiredMode.REQUIRED)
    private String nonce;

    @Schema(description = "Tapstate runtime version (e.g. '2.1.0')")
    private String runtimeVersion;

    @Schema(description = "Milliseconds since runtime process started")
    private Long uptimeMs;

    @Schema(description = "Currently running pipeline count")
    private Integer activePipelines;

    @Schema(description = "ISO-8601 UTC instant of last runtime error, null if none")
    private String lastErrorAt;
}
