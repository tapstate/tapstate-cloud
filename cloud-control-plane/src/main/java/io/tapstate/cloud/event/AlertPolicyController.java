package io.tapstate.cloud.event;

import io.swagger.v3.oas.annotations.Operation;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.org.OrgAccessService;
import io.tapstate.cloud.org.Organization;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/api/orgs/{orgId}/alert-policies")
@RequiredArgsConstructor
public class AlertPolicyController extends BaseController {

    private final AlertPolicyService policyService;
    private final OrgAccessService orgAccessService;

    @Operation(operationId = "listAlertPolicies")
    @GetMapping
    public ResponseEntity<ApiResponse<List<AlertPolicyResponse>>> list(@PathVariable String orgId) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireAdmin(orgId, email);
        List<AlertPolicyResponse> body = policyService.list(org.getId()).stream()
            .map(AlertPolicyResponse::from).toList();
        return ok(body);
    }

    @Operation(operationId = "createAlertPolicy")
    @PostMapping
    public ResponseEntity<ApiResponse<AlertPolicyResponse>> create(
            @PathVariable String orgId,
            @RequestBody AlertPolicyRequest request) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireAdmin(orgId, email);
        return okCreated(AlertPolicyResponse.from(policyService.create(org.getId(), request)));
    }

    @Operation(operationId = "updateAlertPolicy")
    @PutMapping("/{policyId}")
    public ResponseEntity<ApiResponse<AlertPolicyResponse>> update(
            @PathVariable String orgId,
            @PathVariable String policyId,
            @RequestBody AlertPolicyRequest request) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireAdmin(orgId, email);
        return ok(AlertPolicyResponse.from(policyService.update(org.getId(), policyId, request)));
    }

    @Operation(operationId = "deleteAlertPolicy")
    @DeleteMapping("/{policyId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String orgId,
            @PathVariable String policyId) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireAdmin(orgId, email);
        policyService.delete(org.getId(), policyId);
        return ok();
    }
}
