package io.tapstate.cloud.event;

/**
 * Severity attached to a platform event and used as the minimum threshold of an
 * alert policy.
 */
public enum EventSeverity {
    INFO,
    WARNING,
    CRITICAL;

    public boolean atLeast(EventSeverity threshold) {
        return ordinal() >= threshold.ordinal();
    }
}
