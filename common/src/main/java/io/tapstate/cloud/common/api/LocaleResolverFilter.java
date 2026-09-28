package io.tapstate.cloud.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Resolves per-request {@link Locale} from:
 * <ol>
 *   <li>{@code lang} cookie (like hare does) — takes priority</li>
 *   <li>{@code Accept-Language} HTTP header</li>
 *   <li>{@link Locale#getDefault()} / {@link Locale#ENGLISH} fallback</li>
 * </ol>
 * Stores result in {@link LocaleHolder} ThreadLocal for controllers/exception handlers
 * to read via {@code LocaleHolder.get()}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)  // AFTER OpIdFilter
@Slf4j
public class LocaleResolverFilter extends OncePerRequestFilter {

    private static final String LANG_COOKIE = "lang";
    private static final String LOCALE_ATTR = "TAPSTATE_LOCALE";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Locale locale = resolveLocale(request);
        LocaleHolder.set(locale);
        request.setAttribute(LOCALE_ATTR, locale);

        try {
            filterChain.doFilter(request, response);
        } finally {
            LocaleHolder.clear();
        }
    }

    private Locale resolveLocale(HttpServletRequest request) {
        // 1. Cookie (highest priority — user explicitly chose language in Cloud Console UI)
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (LANG_COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    try {
                        return Locale.forLanguageTag(cookie.getValue().replace('_', '-'));
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 2. Accept-Language header — parse first quality tag manually
        // (request.getLocale() may return JVM default when Spring LocaleResolver is absent)
        String acceptLang = request.getHeader("Accept-Language");
        if (acceptLang != null && !acceptLang.isBlank()) {
            String first = acceptLang.split(",")[0].trim();
            String tag = first.split(";")[0].trim();  // strip ;q=0.9
            if (!tag.isBlank()) {
                try {
                    return Locale.forLanguageTag(tag.replace('_', '-'));
                } catch (Exception ignored) {
                }
            }
        }

        // 3. Fallback
        return Locale.ENGLISH;
    }
}
