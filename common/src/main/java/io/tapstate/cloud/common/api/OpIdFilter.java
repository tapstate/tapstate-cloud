package io.tapstate.cloud.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Generates per-request correlation ID ({@code opId}) and stores it in:
 * <ul>
 *   <li>Request attribute (Spring controllers read via {@link OpIdHolder})</li>
 *   <li>Response header ({@code X-Request-ID}) — client can use for debugging</li>
 * </ul>
 * <p>
 * If the caller already sends {@code X-Request-ID} header, we reuse it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-ID";
    public static final String ATTRIBUTE_NAME = "TAPSTATE_OP_ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String opId = request.getHeader(HEADER_NAME);
        if (opId == null || opId.isBlank()) {
            opId = UUID.randomUUID().toString();
        }

        request.setAttribute(ATTRIBUTE_NAME, opId);
        response.setHeader(HEADER_NAME, opId);
        OpIdHolder.set(opId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            OpIdHolder.clear();
        }
    }
}
