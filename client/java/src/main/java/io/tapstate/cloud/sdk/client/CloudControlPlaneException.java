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

    // ========== C2 typed sub-exceptions ==========
    // Prefer these over the generic constructor so runtime callers can handle
    // auth failures vs transient backpressure vs nonce replay distinctly.

    /** C2 SDK token was not configured on the SDK — cannot call reportStatus. */
    public static final class C2TokenMissing extends CloudControlPlaneException {
        private static final long serialVersionUID = 1L;
        public C2TokenMissing() {
            super("c2.token-missing", 0, "sdkStaticToken was not configured — set via Builder.sdkStaticToken(...)");
        }
    }

    /** GC rejected the C2 call with 401/403 — token invalid / cluster mismatch / cluster revoked. */
    public static final class C2AuthFailed extends CloudControlPlaneException {
        private static final long serialVersionUID = 1L;
        public C2AuthFailed(int httpStatus, String msg, Throwable cause) {
            super("c2.auth-failed", httpStatus, msg, cause);
        }
    }

    /** GC rejected the C2 call with 409 — same nonce reused. Client must generate a fresh UUID every call. */
    public static final class C2NonceReplay extends CloudControlPlaneException {
        private static final long serialVersionUID = 1L;
        public C2NonceReplay(int httpStatus, String msg, Throwable cause) {
            super("c2.nonce-replay", httpStatus, msg, cause);
        }
    }

    /** All bounded-retry attempts exhausted (5xx or connection failure). GC may be unreachable. */
    public static final class C2Transient extends CloudControlPlaneException {
        private static final long serialVersionUID = 1L;
        public C2Transient(int httpStatus, String msg, Throwable cause) {
            super("c2.transient", httpStatus, msg, cause);
        }
    }

    /** GC returned HTTP 200 but the envelope did NOT carry the expected acceptance
     *  (code=ok + data.status=accepted). This is a business-level rejection —
     *  the request reached GC, GC chose not to accept it, and retrying the
     *  same nonce/content would not help. Terminal, no automatic retry. */
    public static final class C2BusinessRejected extends CloudControlPlaneException {
        private static final long serialVersionUID = 1L;
        public C2BusinessRejected(String msg) {
            super("c2.business-rejected", 200, msg);
        }
    }
}
