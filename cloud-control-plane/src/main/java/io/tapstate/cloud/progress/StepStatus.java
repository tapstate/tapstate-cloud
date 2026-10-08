package io.tapstate.cloud.progress;

/**
 * Lifecycle status of one deployment operation.
 */
public enum StepStatus {

    /** Not started yet. */
    PENDING,

    /** Currently executing. */
    RUNNING,

    /** An earlier attempt failed and the operation is being retried. */
    RETRYING,

    /** Finished successfully. */
    SUCCEEDED,

    /** Finished with a failure. */
    FAILED,

    /** Deliberately not executed (e.g. not needed in the current mode). */
    SKIPPED
}
