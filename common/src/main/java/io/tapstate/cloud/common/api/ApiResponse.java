package io.tapstate.cloud.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Global API response envelope — every endpoint MUST return this shape.
 * <p>
 * Mirrors the hare project's {@code ResponseMessage<T>} pattern but uses
 * the tapstate field names: {@code opId / code / msg / data}.
 *
 * @param <T> payload type (object or array)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Global API response envelope")
public class ApiResponse<T> {

    /** Business success code. */
    public static final String CODE_OK = "ok";

    @Schema(description = "Request correlation ID — unique per request",
            example = "3f2a1b4c-5d6e-7f8a-9b0c-1d2e3f4a5b6c")
    private String opId;

    @Schema(description = "Business result code — 'ok' on success, error code on failure",
            example = "ok")
    private String code = CODE_OK;

    @Schema(description = "Detailed message — success info or failure reason")
    private String msg;

    @Schema(description = "Response payload — object or array, null on void responses")
    private T data;

    /**
     * Create success response with data.
     */
    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setData(data);
        return r;
    }

    /**
     * Create success response (void).
     */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>();
    }

    /**
     * Create success response with message + data.
     */
    public static <T> ApiResponse<T> ok(String msg, T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setMsg(msg);
        r.setData(data);
        return r;
    }

    /**
     * Create failure response with code + message.
     */
    public static <T> ApiResponse<T> fail(String code, String msg) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }

    /**
     * Create failure response from exception.
     */
    public static <T> ApiResponse<T> fail(Exception e) {
        return fail(e.getClass().getSimpleName(), e.getMessage());
    }
}
