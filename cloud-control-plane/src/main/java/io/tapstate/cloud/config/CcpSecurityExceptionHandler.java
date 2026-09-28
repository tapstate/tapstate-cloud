package io.tapstate.cloud.config;

import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.LocaleHolder;
import io.tapstate.cloud.common.api.MessageUtils;
import io.tapstate.cloud.common.api.OpIdHolder;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

/**
 * Cloud Control Plane specific security exception handler — runs BEFORE
 * {@link io.tapstate.cloud.common.api.GlobalExceptionHandler} to catch
 * Spring Security exceptions and wrap them in ApiResponse envelope with i18n messages.
 * <p>
 * Note: LocaleResolverFilter runs at {@code HIGHEST_PRECEDENCE + 100} while
 * Spring Security filters run earlier, so LocaleHolder may be unset during
 * security exception handling. MessageUtils gracefully falls back to
 * {@link Locale#ENGLISH} in that case.
 */
@RestControllerAdvice(basePackages = "io.tapstate.cloud")
@Order(-1)
public class CcpSecurityExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException e) {
        Locale locale = LocaleHolder.get();
        String msg = MessageUtils.getMessage(locale, AppErrorCode.UNAUTHORIZED);
        ApiResponse<Void> body = ApiResponse.fail(AppErrorCode.UNAUTHORIZED.getCode(), msg);
        body.setOpId(OpIdHolder.get());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        Locale locale = LocaleHolder.get();
        String msg = MessageUtils.getMessage(locale, AppErrorCode.UNAUTHORIZED);
        ApiResponse<Void> body = ApiResponse.fail(AppErrorCode.UNAUTHORIZED.getCode(), msg);
        body.setOpId(OpIdHolder.get());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
