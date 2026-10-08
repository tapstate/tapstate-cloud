package io.tapstate.cloud.progress;

/**
 * Lightweight progress summary embedded in subscription responses.
 *
 * @param overallStatus aggregate status
 * @param percent       completion percentage, 0–100
 */
public record DeploymentProgressSummary(String overallStatus, int percent) {
}
