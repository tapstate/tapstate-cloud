package io.tapstate.cloud.progress;

/**
 * Aggregate status of all operations in one deployment category.
 */
public enum DeploymentOverallStatus {

    /** No operation has started. */
    PENDING,

    /** At least one operation finished, none failed, work remains. */
    IN_PROGRESS,

    /** At least one operation failed; the percentage is frozen. */
    FAILED,

    /** Every operation succeeded or was skipped. */
    SUCCEEDED
}
