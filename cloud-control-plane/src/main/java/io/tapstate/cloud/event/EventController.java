package io.tapstate.cloud.event;

import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.org.OrgAccessService;
import io.tapstate.cloud.org.Organization;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/api/orgs/{orgId}")
@RequiredArgsConstructor
public class EventController extends BaseController {

    private final EventCenterService eventCenterService;
    private final OrgAccessService orgAccessService;

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<EventPageResponse>> list(
            @PathVariable String orgId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) EventSeverity severity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireMember(orgId, email);
        EventPage page = eventCenterService.list(orgId, email, orgAccessService.isAdmin(org, email),
            from, to, source, type, severity, cursor, limit);
        List<EventResponse> items = page.items().stream().map(EventResponse::from).toList();
        return ok(new EventPageResponse(items, page.nextCursor()));
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<ApiResponse<EventResponse>> get(
            @PathVariable String orgId,
            @PathVariable String eventId) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireMember(orgId, email);
        PlatformEvent event = eventCenterService.get(orgId, eventId, email,
            orgAccessService.isAdmin(org, email));
        return ok(EventResponse.from(event));
    }

    public record EventPageResponse(List<EventResponse> items, String nextCursor) {
    }
}
