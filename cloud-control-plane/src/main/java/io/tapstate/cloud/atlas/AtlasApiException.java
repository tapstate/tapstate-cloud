package io.tapstate.cloud.atlas;

import org.springframework.http.HttpStatus;

/**
 * Unchecked exception wrapping any Atlas Admin API failure — covers network,
 * HTTP error codes, and business-level Atlas errorCodes (e.g. USER_ALREADY_EXISTS
 * which we downgrade via call-site check, but still surface for other errors).
 * <p>
 * The {@link #statusCode()} is 0 for network-level errors and the HTTP status
 * code otherwise. {@link #errorCode()} carries the Atlas business errorCode
 * (e.g. "DATABASE_USER_ALREADY_EXISTS", "GROUP_NOT_FOUND") when available.
 */
public class AtlasApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final String path;

    public AtlasApiException(int statusCode, String errorCode, String path) {
        super(buildMessage(statusCode, errorCode, path));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.path = path;
    }

    public AtlasApiException(int statusCode, String errorCode, String path, Throwable cause) {
        super(buildMessage(statusCode, errorCode, path), cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.path = path;
    }

    public int statusCode() { return statusCode; }
    public String errorCode() { return errorCode; }
    public String path() { return path; }

    private static String buildMessage(int statusCode, String errorCode, String path) {
        StringBuilder sb = new StringBuilder("Atlas Admin API error: path=").append(path);
        if (statusCode > 0) sb.append(", status=").append(statusCode)
            .append(" (").append(HttpStatus.valueOf(statusCode).getReasonPhrase()).append(")");
        if (errorCode != null) sb.append(", errorCode=").append(errorCode);
        return sb.toString();
    }
}
