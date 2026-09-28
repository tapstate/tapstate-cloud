package io.tapstate.cloud.common.api;

/**
 * Business error code contract — every error code value must implement this.
 * <p>
 * Mirrors hare {@code com.qz.hare.common.exception.ErrorCode} exactly.
 * {@link #getCode()} is also the key in {@code messages*.properties} files.
 */
public interface ErrorCode {

    /**
     * Resource key in messages.properties (e.g. {@code auth.email-already-registered}).
     */
    String getCode();

    /**
     * Default English description (used when ResourceBundle lookup misses).
     */
    String getDescription();
}
