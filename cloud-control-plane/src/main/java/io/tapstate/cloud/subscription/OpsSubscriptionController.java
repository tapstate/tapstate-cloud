package io.tapstate.cloud.subscription;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.api.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Ops-only subscription management endpoints.
 * <p>
 * All endpoints are authenticated by a static Bearer token, not by a user
 * session — the Ops console is a privileged service caller, not a logged-in
 * Cloud Console user. When the token is not configured the endpoints fail
 * closed with 503 instead of being left open.
 * <p>
 * This controller lives under {@code /v1/api/internal/} and is separate from
 * the self-serve subscription API in {@link SubscriptionController}, because
 * the two sources produce different audit trails: Ops operations go through
 * source {@code ops} in the event center, while user self-serve goes through
 * the {@code UserOperationEventInterceptor} under source {@code user}.
 */
@RestController
@RequestMapping("/v1/api/internal/orgs")
@Hidden
@Slf4j
@Tag(name = "Ops Subscription",
     description = "Ops-privileged subscription endpoints — authenticated by static token, not user session")
public class OpsSubscriptionController extends BaseController {

    private final SubscriptionService subscriptionService;
    private final String opsToken;

    public OpsSubscriptionController(
            SubscriptionService subscriptionService,
            @Value("${tapstate.cloud.ops.subscription-token:}") String opsToken) {
        this.subscriptionService = subscriptionService;
        this.opsToken = opsToken;
    }

    @PostMapping("/{orgId}/subscriptions/{subscriptionId}/cancel-now")
    @Operation(
        summary = "Ops-triggered immediate subscription cancellation (static token)",
        description = "Initiates immediate cancellation of the target subscription on behalf of the customer. "
            + "Same downstream effects as the user-facing cancel-now endpoint (Stripe cancel, FSM→CANCELLED, "
            + "Cluster termination, MDB reclaim), but the operation is attributed to the named Ops operator "
            + "and an Ops-specific audit event is written to the event center (source=ops, "
            + "type=subscription.immediate-cancel). A reason is required — it is stored verbatim in the "
            + "event payload for audit traceability."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription cancelled — cascade termination running asynchronously"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing operator or reason in request body"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Ops static token missing or invalid"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Subscription or organization not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Subscription already in CANCELLED state"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Ops subscription endpoint not configured")
    })
    public ResponseEntity<ApiResponse<SubscriptionCancelResponse>> cancelNow(
            @Parameter(description = "Organization UUID") @PathVariable String orgId,
            @Parameter(description = "Subscription UUID") @PathVariable String subscriptionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody OpsCancelRequest request) {

        requireOpsToken(authorization);

        Subscription updated = subscriptionService.cancelNowByOps(
                subscriptionId, orgId, request.operator(), request.reason());

        return ok(new SubscriptionCancelResponse(
                updated.getId().toHexString(),
                updated.getStatus().name(),
                updated.getOrgId().toHexString(),
                request.operator(),
                request.reason()));
    }

    /**
     * Authenticate the Ops static Bearer token. Fail closed: an unset configured
     * token disables the endpoint (503); missing/malformed header is 401, wrong
     * token is 401. Comparison is constant-time.
     */
    private void requireOpsToken(String authorization) {
        if (opsToken == null || opsToken.isBlank()) {
            throw new BusinessException(AppErrorCode.SUBSCRIPTION_OPS_DISABLED,
                HttpStatus.SERVICE_UNAVAILABLE.value());
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(AppErrorCode.SUBSCRIPTION_OPS_TOKEN_MISSING,
                HttpStatus.UNAUTHORIZED.value());
        }
        String presented = authorization.substring("Bearer ".length()).trim();
        byte[] expected = opsToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException(AppErrorCode.SUBSCRIPTION_OPS_TOKEN_INVALID,
                HttpStatus.UNAUTHORIZED.value());
        }
    }

    // ========== DTOs ==========

    public record OpsCancelRequest(
        @NotBlank @Size(max = 100)
        @Parameter(description = "Ops operator identifier — recorded as the event actor",
                   example = "alice")
        String operator,

        @NotBlank @Size(max = 500)
        @Parameter(description = "Mandatory cancellation reason — stored verbatim in the event payload for audit",
                   example = "Customer filed refund request via support ticket 12345")
        String reason
    ) {}

    public record SubscriptionCancelResponse(
        String subscriptionId,
        String status,
        String orgId,
        String operator,
        String reason
    ) {}
}
