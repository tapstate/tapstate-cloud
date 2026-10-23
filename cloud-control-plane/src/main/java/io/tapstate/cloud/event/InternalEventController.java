package io.tapstate.cloud.event;

import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Generic external notification entry point. A caller authenticates with a
 * deployment secret, not a user session; a blank configured token disables the
 * endpoint instead of leaving it open.
 */
@RestController
@RequestMapping("/v1/api/internal/events")
public class InternalEventController extends BaseController {

    private final EventCenterService eventCenterService;
    private final String ingestToken;

    public InternalEventController(
            EventCenterService eventCenterService,
            @Value("${tapstate.cloud.events.ingest-token:}") String ingestToken) {
        this.eventCenterService = eventCenterService;
        this.ingestToken = ingestToken;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EventResponse>> ingest(
            @RequestHeader(value = "X-Event-Token", required = false) String token,
            @RequestBody EventIngestRequest request) {
        if (ingestToken == null || ingestToken.isBlank()) {
            return fail(AppErrorCode.EVENT_INGEST_DISABLED, HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (token == null || !MessageDigest.isEqual(
                ingestToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return fail(AppErrorCode.EVENT_INGEST_TOKEN_INVALID, HttpStatus.UNAUTHORIZED);
        }
        return ok(EventResponse.from(eventCenterService.ingest(request)));
    }
}
