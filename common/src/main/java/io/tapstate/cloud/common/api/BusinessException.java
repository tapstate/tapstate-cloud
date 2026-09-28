package io.tapstate.cloud.common.api;

/**
 * Business-domain exception — carries a typed {@link ErrorCode} enum value
 * + the HTTP status code to return.
 * <p>
 * Thrown from services when a specific error code (and i18n message) should be
 * returned to the client. {@link GlobalExceptionHandler} catches this and
 * resolves the message via {@link MessageUtils}.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object[] params;
    private final int httpStatus;

    /**
     * Full constructor — explicit HTTP status + optional i18n MessageFormat params.
     */
    public BusinessException(ErrorCode errorCode, int httpStatus, Object... params) {
        super(errorCode.getDescription());
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.params = params;
    }

    /** Convenience constructor — HTTP 400 Bad Request. */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, 400);
    }

    /** Convenience constructor — HTTP 400 Bad Request with i18n params. */
    public BusinessException(ErrorCode errorCode, Object p1) {
        this(errorCode, 400, new Object[]{p1});
    }

    /** Convenience constructor — explicit HTTP status without i18n params. */
    public BusinessException(ErrorCode errorCode, int httpStatus) {
        this(errorCode, httpStatus, new Object[0]);
    }

    public ErrorCode getErrorCode() { return errorCode; }
    public Object[] getParams() { return params; }
    public int getHttpStatus() { return httpStatus; }
}
