package io.tapstate.cloud.progress;

import java.time.Instant;

/**
 * One historical execution attempt of a deployment operation, captured when
 * the operation is retried after a failure.
 *
 * @param status     outcome of this attempt
 * @param message    outcome message — success note or error detail
 * @param startedAt  attempt start time
 * @param finishedAt attempt end time
 */
public record StepAttempt(StepStatus status, String message,
                          Instant startedAt, Instant finishedAt) {
}
