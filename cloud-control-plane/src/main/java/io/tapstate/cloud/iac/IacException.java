package io.tapstate.cloud.iac;

import io.tapstate.cloud.common.api.AppErrorCode;

/**
 * Internal failure raised by the IaC layer while provisioning or terminating a
 * Cluster.
 * <p>
 * Carries an {@link AppErrorCode} plus positional message parameters so the
 * orchestration layer can record a coded, user-diagnosable failure instead of an
 * opaque stack trace. Not an HTTP-facing type itself.
 */
public class IacException extends RuntimeException {

    private final transient AppErrorCode errorCode;
    private final transient Object[] params;

    public IacException(AppErrorCode errorCode, Throwable cause, Object... params) {
        super(errorCode.getCode(), cause);
        this.errorCode = errorCode;
        this.params = params;
    }

    public IacException(AppErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.params = new Object[]{message};
    }

    /** Coded failure category. */
    public AppErrorCode errorCode() {
        return errorCode;
    }

    /** Positional parameters for the error message. */
    public Object[] params() {
        return params;
    }
}
