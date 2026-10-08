package io.tapstate.cloud.progress;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated deployment progress of one subscription and one operation
 * category: the overall status, the percentage and every operation's detail.
 *
 * @param subscriptionId subscription UUID
 * @param orgId          owning organization UUID
 * @param opType         operation category: INSTALL / UNINSTALL
 * @param overallStatus  aggregate status
 * @param percent        completion percentage, 0–100
 * @param startedAt      earliest operation start time
 * @param finishedAt     completion time (only when overallStatus = SUCCEEDED)
 * @param message        overall success message or aggregated failure detail
 * @param operations     operation details in sequence order
 */
@Schema(description = "Aggregated deployment progress of one subscription")
public record DeploymentProgressResponse(
    @Schema(description = "Subscription UUID") String subscriptionId,
    @Schema(description = "Owning organization UUID") String orgId,
    @Schema(description = "Operation category: INSTALL / UNINSTALL") String opType,
    @Schema(description = "Aggregate status: PENDING / IN_PROGRESS / FAILED / SUCCEEDED") String overallStatus,
    @Schema(description = "Completion percentage, 0-100") int percent,
    @Schema(description = "Earliest operation start time (ISO-8601 UTC)") Instant startedAt,
    @Schema(description = "Completion time, only when SUCCEEDED (ISO-8601 UTC)") Instant finishedAt,
    @Schema(description = "Overall success message or aggregated failure detail") String message,
    @Schema(description = "Operation details in sequence order") List<DeploymentStepResponse> operations
) {
}
