package io.tapstate.cloud.event;

public enum AlertStatus {
    /** Alert was produced and deliveries were created. */
    PENDING,
    /** Alert matched a policy but was withheld by the quiet window. */
    SUPPRESSED
}
