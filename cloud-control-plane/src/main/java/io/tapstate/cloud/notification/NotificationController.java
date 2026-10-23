package io.tapstate.cloud.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.OrgAccessService;
import io.tapstate.cloud.org.Organization;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api/orgs/{orgId}/deliveries")
@RequiredArgsConstructor
public class NotificationController extends BaseController {

    private final NotificationDeliveryRepository deliveryRepository;
    private final OrgAccessService orgAccessService;

    @Operation(operationId = "listNotificationDeliveries")
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationDeliveryPage>> list(
            @PathVariable String orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireMember(orgId, email);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<NotificationDelivery> rows = orgAccessService.isAdmin(org, email)
            ? deliveryRepository.findByOrgIdOrderByCreatedAtDesc(org.getId(), pageable)
            : deliveryRepository.findByOrgIdAndRecipientOrderByCreatedAtDesc(org.getId(), email, pageable);
        return ok(new NotificationDeliveryPage(
            rows.getContent().stream().map(NotificationDeliveryResponse::from).toList(),
            safePage,
            safeSize,
            rows.getTotalElements()));
    }

    @Operation(operationId = "getNotificationDelivery")
    @GetMapping("/{deliveryId}")
    public ResponseEntity<ApiResponse<NotificationDeliveryResponse>> get(
            @PathVariable String orgId,
            @PathVariable String deliveryId) {
        String email = CurrentUser.getEmail();
        if (email == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        Organization org = orgAccessService.requireMember(orgId, email);
        ObjectId id;
        try {
            id = new ObjectId(deliveryId);
        } catch (RuntimeException e) {
            throw new BusinessException(AppErrorCode.NOTIFICATION_NOT_FOUND, 404, deliveryId);
        }
        NotificationDelivery delivery = deliveryRepository.findByIdAndOrgId(id, org.getId())
            .orElseThrow(() -> new BusinessException(AppErrorCode.NOTIFICATION_NOT_FOUND, 404, deliveryId));
        if (!orgAccessService.isAdmin(org, email) && !email.equals(delivery.getRecipient())) {
            throw new BusinessException(AppErrorCode.NOTIFICATION_NOT_FOUND, 404, deliveryId);
        }
        return ok(NotificationDeliveryResponse.from(delivery));
    }
}
