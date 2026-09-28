package io.tapstate.cloud.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

/**
 * Convenience base for all Cloud Control Plane controllers.
 * <p>
 * Provides {@code ok(T)} / {@code ok()} / {@code okCreated(T)} / {@code fail(...)} helpers
 * that auto-populate {@code opId} from the thread-local context, and resolve i18n messages
 * via {@link MessageUtils} when an {@link ErrorCode} enum value is provided.
 */
public abstract class BaseController {

    protected <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return buildOk(200, data);
    }

    protected <T> ResponseEntity<ApiResponse<T>> ok() {
        return buildOk(200, null);
    }

    protected <T> ResponseEntity<ApiResponse<T>> ok(String msg, T data) {
        ApiResponse<T> resp = ApiResponse.ok(msg, data);
        resp.setOpId(OpIdHolder.get());
        return ResponseEntity.ok(resp);
    }

    protected <T> ResponseEntity<ApiResponse<T>> okCreated(T data) {
        return buildOk(201, data);
    }

    /**
     * Create a success response with custom HTTP status code.
     */
    protected <T> ResponseEntity<ApiResponse<T>> okStatus(HttpStatus status, T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setOpId(OpIdHolder.get());
        return ResponseEntity.status(status).body(resp);
    }

    // ==================== i18n fail helpers ====================

    /**
     * Build error response from {@link ErrorCode} enum value — i18n message auto-resolved.
     */
    protected <T> ResponseEntity<ApiResponse<T>> fail(ErrorCode errorCode, HttpStatus httpStatus, Object... params) {
        Locale locale = LocaleHolder.get();
        String msg = MessageUtils.getMessage(locale, errorCode, params);
        ApiResponse<T> resp = ApiResponse.fail(errorCode.getCode(), msg);
        resp.setOpId(OpIdHolder.get());
        return ResponseEntity.status(httpStatus).body(resp);
    }

    /**
     * Build error response from raw code string + explicit message (no i18n — used when caller already resolved).
     */
    protected <T> ResponseEntity<ApiResponse<T>> fail(String code, String msg, int httpStatus) {
        ApiResponse<T> resp = ApiResponse.fail(code, msg);
        resp.setOpId(OpIdHolder.get());
        return ResponseEntity.status(httpStatus).body(resp);
    }

    /**
     * Build error response from raw code string + explicit message with HttpStatus.
     */
    protected <T> ResponseEntity<ApiResponse<T>> fail(String code, String msg, HttpStatus httpStatus) {
        ApiResponse<T> resp = ApiResponse.fail(code, msg);
        resp.setOpId(OpIdHolder.get());
        return ResponseEntity.status(httpStatus).body(resp);
    }

    // ==================== private ====================

    private <T> ResponseEntity<ApiResponse<T>> buildOk(int status, T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setOpId(OpIdHolder.get());
        return ResponseEntity.status(status).body(resp);
    }
}
