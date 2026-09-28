package io.tapstate.cloud.common.api;

/**
 * String-code constants — wrappers around {@link AppErrorCode} enum values.
 * <p>
 * Kept for backward compatibility. New code should use {@code AppErrorCode} enum
 * and call {@link MessageUtils#getMessage(Locale, ErrorCode, Object...)} for i18n.
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    // ---------- Auth ----------
    public static final String AUTH_EMAIL_ALREADY_REGISTERED = AppErrorCode.EMAIL_ALREADY_REGISTERED.getCode();
    public static final String AUTH_INVALID_CREDENTIALS = AppErrorCode.INVALID_CREDENTIALS.getCode();
    public static final String AUTH_SESSION_NOT_FOUND = AppErrorCode.SESSION_NOT_FOUND.getCode();
    public static final String AUTH_UNAUTHORIZED = AppErrorCode.UNAUTHORIZED.getCode();

    // ---------- Org ----------
    public static final String ORG_NOT_FOUND = AppErrorCode.ORG_NOT_FOUND.getCode();
    public static final String ORG_NOT_MEMBER = AppErrorCode.ORG_NOT_MEMBER.getCode();
    public static final String ORG_ALREADY_EXISTS = AppErrorCode.ORG_ALREADY_EXISTS.getCode();

    // ---------- Cluster ----------
    public static final String CLUSTER_NOT_FOUND = AppErrorCode.CLUSTER_NOT_FOUND.getCode();
    public static final String CLUSTER_ALREADY_EXISTS = AppErrorCode.CLUSTER_ALREADY_EXISTS.getCode();
    public static final String CLUSTER_INVALID_STATE = AppErrorCode.CLUSTER_INVALID_STATE.getCode();

    // ---------- Region ----------
    public static final String REGION_NOT_FOUND = AppErrorCode.REGION_NOT_FOUND.getCode();

    // ---------- Exchange (ADR-0125) ----------
    public static final String EXCHANGE_CODE_CONSUMED = AppErrorCode.EXCHANGE_CODE_CONSUMED.getCode();
    public static final String EXCHANGE_CODE_EXPIRED = AppErrorCode.EXCHANGE_CODE_EXPIRED.getCode();
    public static final String EXCHANGE_CODE_CLUSTER_MISMATCH = AppErrorCode.EXCHANGE_CODE_CLUSTER_MISMATCH.getCode();
    public static final String EXCHANGE_CLUSTER_IDENTITY_MISMATCH = AppErrorCode.EXCHANGE_CLUSTER_IDENTITY_MISMATCH.getCode();

    // ---------- SDK ----------
    public static final String SDK_TOKEN_MISSING = AppErrorCode.SDK_TOKEN_MISSING.getCode();
    public static final String SDK_TOKEN_INVALID = AppErrorCode.SDK_TOKEN_INVALID.getCode();

    // ---------- Generic ----------
    public static final String BAD_REQUEST = AppErrorCode.BAD_REQUEST.getCode();
    public static final String SYSTEM_ERROR = AppErrorCode.SYSTEM_ERROR.getCode();
}
