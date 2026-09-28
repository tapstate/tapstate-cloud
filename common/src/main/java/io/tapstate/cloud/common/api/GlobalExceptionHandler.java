package io.tapstate.cloud.common.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Global exception handler — maps thrown exceptions to {@link ApiResponse} envelopes
 * with i18n-resolved messages via {@link MessageUtils}.
 * <p>
 * Handler priority (highest → lowest for most specific first):
 * <ol>
 *   <li>{@link BusinessException} — typed error code + i18n params</li>
 *   <li>{@link IllegalArgumentException} / {@link IllegalStateException} — bad request fallback</li>
 *   <li>{@link Exception} — catch-all system_error</li>
 * </ol>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Typed business exception — carries AppErrorCode enum + optional MessageFormat params.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return i18nBuild(HttpStatus.valueOf(e.getHttpStatus()), e.getErrorCode(), e.getParams());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return i18nBuild(HttpStatus.BAD_REQUEST, AppErrorCode.BAD_REQUEST, details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException e) {
        return i18nBuild(HttpStatus.BAD_REQUEST, AppErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        return i18nBuild(HttpStatus.CONFLICT, AppErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("Unhandled exception in controller", e);
        return i18nBuild(HttpStatus.INTERNAL_SERVER_ERROR, AppErrorCode.SYSTEM_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> i18nBuild(HttpStatus httpStatus, ErrorCode errorCode, Object... params) {
        Locale locale = LocaleHolder.get();
        String msg = MessageUtils.getMessage(locale, errorCode, params);

        ApiResponse<Void> body = ApiResponse.fail(errorCode.getCode(), msg);
        body.setOpId(OpIdHolder.get());
        return ResponseEntity.status(httpStatus).body(body);
    }
}
