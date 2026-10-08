package io.tapstate.cloud.progress;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Detailed status of one deployment operation.
 *
 * @param operation     stable operation code
 * @param displayName   human-readable operation name
 * @param sequence      presentation/dependency order
 * @param weight        weight in the aggregate percentage
 * @param status        current status
 * @param message       success message or failure error detail
 * @param startedAt     most recent attempt start time
 * @param finishedAt    most recent attempt end time
 * @param attemptsCount number of attempts made (1 = the current attempt only)
 */
@Schema(description = "Detailed status of one deployment operation")
public record DeploymentStepResponse(
    @Schema(description = "Stable operation code") String operation,
    @Schema(description = "Human-readable operation name") String displayName,
    @Schema(description = "Presentation/dependency order") int sequence,
    @Schema(description = "Weight in the aggregate percentage") int weight,
    @Schema(description = "Current status: PENDING / RUNNING / RETRYING / SUCCEEDED / FAILED / SKIPPED") String status,
    @Schema(description = "Success message or failure error detail") String message,
    @Schema(description = "Most recent attempt start time (ISO-8601 UTC)") Instant startedAt,
    @Schema(description = "Most recent attempt end time (ISO-8601 UTC)") Instant finishedAt,
    @Schema(description = "Number of attempts made (1 = the current attempt only)") int attemptsCount
) {

    static DeploymentStepResponse from(DeploymentStep step) {
        int attempts = step.getAttempts() == null ? 0 : step.getAttempts().size();
        // The current attempt is in addition to the archived ones.
        int attemptsCount = step.getStatus() == StepStatus.PENDING && attempts == 0 ? 0 : attempts + 1;
        return new DeploymentStepResponse(
            step.getOperation(),
            displayName(step.getOperation()),
            step.getSequence(),
            step.getWeight(),
            step.getStatus().name(),
            step.getMessage(),
            step.getStartedAt(),
            step.getFinishedAt(),
            attemptsCount);
    }

    private static String displayName(String code) {
        try {
            return DeploymentOperation.fromCode(code).displayName();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }
}
