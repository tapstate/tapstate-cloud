package io.tapstate.cloud.resolve;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * GC exchange endpoint — atomic redemption of exchange_code → resolve JWT (ADR-0125 D4).
 * <p>
 * This is called BACK-CHANNEL by the Cluster Server (not the browser):
 * <ol>
 *   <li>Browser: GET {base_uri}/auth/exchange?code={exchange_code} → Cluster Server</li>
 *   <li>Cluster Server backend: POST /v1/api/auth/exchange → this endpoint (carrying both
 *       the raw exchange_code AND the Cluster's own service identity Secret)</li>
 *   <li>GC verifies: Secret matches + code unconsumed + unexpired + cluster_id bound
 *       → atomic conditional write consumes code → signs resolve JWT (RS256, 15min)</li>
 *   <li>GC returns JWT to Cluster Server → Cluster Server establishes HttpOnly Cookie
 *       for the browser; JWT never appears in browser traffic (back-channel only).</li>
 * </ol>
 * <p>
 * Security: Cluster service identity Secret is per-Cluster (k3s Secret isolation) —
 * only the owning Cluster Server can redeem its exchange_codes (ADR-0125 D4).
 */
@RestController
@RequestMapping("/v1/api/auth")
@RequiredArgsConstructor
@Tag(name = "Exchange",
     description = "Atomic exchange_code → resolve JWT redemption. Called by Cluster Server backend only — JWT never reaches the browser (ADR-0125).")
@Slf4j
public class ExchangeController extends BaseController {

    private final ExchangeService exchangeService;

    /**
     * Redeem an exchange_code for a resolve JWT (back-channel only).
     * <p>
     * Caller must present its per-Cluster service identity Secret via
     * {@code X-Cluster-Identity-Secret} header (or {@code Authorization: Bearer}
     * — Week 2 accepts both for testing convenience).
     *
     * @param request  exchange_code + cluster_id
     * @param identitySecret  per-Cluster service identity Secret (header)
     */
    @PostMapping("/exchange")
    @Operation(
        summary = "Exchange exchange_code for resolve JWT (back-channel)",
        description = "Step 2 of the Cloud Console → Cluster Console session handoff (ADR-0125).\n\n" +
                      "Caller: Cluster Server backend SDK (NOT browser).\n\n" +
                      "Auth: Cluster must present its per-Cluster service identity Secret via\n" +
                      "  - Header X-Cluster-Identity-Secret (preferred), OR\n" +
                      "  - Header Authorization: Bearer <secret>\n\n" +
                      "Atomic conditional write in one DB round-trip:\n" +
                      "  UPDATE exchange_codes SET consumed_at = NOW()\n" +
                      "  WHERE code_hash = sha256(exchange_code)\n" +
                      "  AND consumed_at IS NULL\n" +
                      "  AND expires_at > NOW()\n" +
                      "  AND cluster_id = ?\n\n" +
                      "On success: signs resolve JWT (RS256, 15min) with claims: " +
                      "sub=user_email, org_id, cluster_id, scope=workload.\n" +
                      "On failure: returns 401 with domain error code."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "exchange_code redeemed, JWT issued"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Exchange failed:\n" +
            "  - exchange.cluster-identity-mismatch: service secret missing/doesn't match\n" +
            "  - exchange.code-invalid: code not found / already consumed / expired\n" +
            "  - exchange.cluster-bound-mismatch: code issued for different cluster")
    })
    public ResponseEntity<ApiResponse<ExchangeResponse>> exchange(
            @Valid @RequestBody ExchangeRequest request,
            @RequestHeader(value = "X-Cluster-Identity-Secret", required = false) String identitySecretHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // Extract Cluster service identity secret — header takes precedence over Bearer
        String presentedSecret = identitySecretHeader;
        if (presentedSecret == null && authHeader != null && authHeader.startsWith("Bearer ")) {
            presentedSecret = authHeader.substring(7);
        }

        if (presentedSecret == null || presentedSecret.isBlank()) {
            return fail(AppErrorCode.EXCHANGE_CLUSTER_IDENTITY_MISMATCH, HttpStatus.UNAUTHORIZED);
        }

        // Verify Cluster service identity
        if (!exchangeService.verifyClusterServiceSecret(request.clusterId(), presentedSecret)) {
            return fail(AppErrorCode.EXCHANGE_CLUSTER_IDENTITY_MISMATCH, HttpStatus.UNAUTHORIZED);
        }

        // Atomic redeem + issue JWT — Service throws BusinessException; GlobalExceptionHandler handles i18n
        ExchangeService.RedeemedExchange redeemed =
            exchangeService.redeemExchangeCode(request.exchangeCode(), request.clusterId());

        log.info("Exchange succeeded for cluster={}, user={}, userId={}, jti={}",
            redeemed.clusterId(), redeemed.userEmail(), redeemed.userId(), redeemed.jti());
        return ok(new ExchangeResponse(
            redeemed.jwt(), redeemed.expiresAt(), redeemed.jti(),
            redeemed.userEmail(), redeemed.userId(), redeemed.orgId(), redeemed.clusterId()));
    }

    // ========== DTOs ==========

    public record ExchangeRequest(
        @NotBlank @Parameter(description = "Raw exchange_code from browser URL param (base64url 256-bit)")
        String exchangeCode,

        @NotBlank @Parameter(description = "Target Cluster UUID — must match the one bound when code was issued")
        String clusterId
    ) {}

    public record ExchangeResponse(
        @Schema(description = "Resolve JWT (RS256, 15min) — carry to Cluster Console workload requests") String jwt,
        @Schema(description = "JWT expiration timestamp (ISO-8601 UTC)") String expiresAt,
        @Schema(description = "JWT jti (unique token ID — for audit + Cloud-side revocation)") String jti,
        @Schema(description = "User email bound to this session") String userEmail,
        @Schema(description = "Stable user UUID (Issue #5 item 1)") String userId,
        @Schema(description = "Organization UUID bound to this session") String orgId,
        @Schema(description = "Cluster UUID bound to this session") String clusterId
    ) {}
}
