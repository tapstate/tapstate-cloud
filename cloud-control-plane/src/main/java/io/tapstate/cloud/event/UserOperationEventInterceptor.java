package io.tapstate.cloud.event;

import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.OpIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures successful mutating API calls as {@code user.operation} events.
 * <p>
 * This is the user-operation source of the event center. It records method,
 * path and status only — never the request body — so credentials and customer
 * data cannot enter an event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserOperationEventInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern ORG_PATH = Pattern.compile("^/v1/api/orgs/([0-9a-fA-F]{24})(?:/|$)");

    private final EventCenterService eventCenterService;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (ex != null || response.getStatus() / 100 != 2) {
            return;
        }
        String method = request.getMethod();
        if (!MUTATING.contains(method)) {
            return;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/v1/api/internal/")
            || path.startsWith("/v1/api/auth/")
            || path.startsWith("/v1/api/webhooks/")) {
            return;
        }
        Matcher matcher = ORG_PATH.matcher(path);
        if (!matcher.find()) {
            return;
        }
        String email = CurrentUser.getEmail();
        if (email == null) {
            return;
        }
        Object opId = request.getAttribute(OpIdFilter.ATTRIBUTE_NAME);
        String dedupeKey = opId == null || opId.toString().isBlank()
            ? UUID.randomUUID().toString()
            : opId.toString();
        String type = "user.operation." + method.toLowerCase(Locale.ROOT);
        eventCenterService.recordBestEffort(new EventIngestRequest(
            matcher.group(1),
            "user",
            type,
            EventSeverity.INFO,
            truncate(method + " " + path, 300),
            email,
            Instant.now(),
            dedupeKey,
            Map.of("method", method, "path", path, "status", response.getStatus())));
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
