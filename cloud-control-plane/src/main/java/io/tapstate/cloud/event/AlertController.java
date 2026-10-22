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
@RequestMapping("/v1/api/orgs/{orgId}/alerts")
@RequiredArgsConstructor
public class AlertController extends BaseController {

    private final AlertQueryService alertQueryService;
    private final OrgAccessService orgAccessService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AlertResponse>>> list(
            @PathVariable String orgId,
            @RequestParam(required = false) Integer limit) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireMember(orgId, email);
        List<AlertResponse> body = alertQueryService
            .list(orgId, email, orgAccessService.isAdmin(org, email), limit)
            .stream().map(AlertResponse::from).toList();
        return ok(body);
    }

    @GetMapping("/{alertId}")
    public ResponseEntity<ApiResponse<AlertResponse>> get(
            @PathVariable String orgId,
            @PathVariable String alertId) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireMember(orgId, email);
        AlertRecord alert = alertQueryService.get(orgId, alertId, email,
            orgAccessService.isAdmin(org, email));
        return ok(AlertResponse.from(alert));
    }
}
