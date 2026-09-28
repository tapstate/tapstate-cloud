/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned facade classes — NOT auto-generated.
 * Protected by .openapi-generator-ignore.
 */

package io.tapstate.cloud.sdk.client;

/**
 * Domain exception for Cloud Control Plane SDK errors.
 * <p>
 * Carries the business error code returned by the Global Control Plane
 * (e.g. {@code exchange.code-consumed}, {@code exchange.cluster-identity-mismatch})
 * plus the HTTP status code and server message for diagnostics.
 * <p>
 * Error codes mirror the backend {@code AppErrorCode} enum (ADR-0024).
 */
public class CloudControlPlaneException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Business error code from the server — e.g. "exchange.code-invalid". */
    private final String errorCode;

    /** HTTP status code from the server response — e.g. 401, 404. */
    private final int httpStatus;

    /** Server-supplied diagnostic message (may be localized). */
    private final String serverMessage;

    public CloudControlPlaneException(String errorCode, int httpStatus, String serverMessage) {
        super(format(errorCode, httpStatus, serverMessage));
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.serverMessage = serverMessage;
    }

    public CloudControlPlaneException(String errorCode, int httpStatus, String serverMessage, Throwable cause) {
        super(format(errorCode, httpStatus, serverMessage), cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.serverMessage = serverMessage;
    }

    /** I/O or network-level failure — no server response was received. */
    public static CloudControlPlaneException networkError(String message, Throwable cause) {
        return new CloudControlPlaneException("sdk.network-error", 0, message, cause);
    }

    /** Server returned an unexpected success envelope — code ≠ "ok" but HTTP 200. */
    public static CloudControlPlaneException unexpectedFailure(String errorCode, String serverMessage) {
        return new CloudControlPlaneException(errorCode, 200, serverMessage);
    }

    /** Server returned a non-2xx HTTP status. */
    public static CloudControlPlaneException httpError(int httpStatus, String errorCode, String serverMessage) {
        return new CloudControlPlaneException(errorCode != null ? errorCode : "sdk.http-" + httpStatus,
            httpStatus, serverMessage);
    }

    private static String format(String errorCode, int httpStatus, String serverMessage) {
        return String.format("CloudControlPlane error [code=%s, http=%d]: %s",
            errorCode, httpStatus, serverMessage != null ? serverMessage : "(no message)");
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getServerMessage() {
        return serverMessage;
    }
}
