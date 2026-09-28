package io.tapstate.cloud.resolve;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.common.CurrentUser;
import io.tapstate.cloud.config.JwtKeysConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**
 * GC resolve + exchange-code issuance endpoint (ADR-0125 D3).
 * <p>
 * This endpoint does NOT issue the resolve JWT directly — it issues a one-time
 * exchange_code (60-second TTL, base64url-encoded 32-byte random) that the
 * browser passes to the target Cluster Server via URL param. The Cluster Server
 * then performs the backend → GC atomic redemption via POST /v1/auth/exchange,
 * which is where the JWT is actually signed (back-channel only — JWT never
 * touches the browser).
 * <p>
 * JWKS is also published here — {@code GET /v1/jwks.json} (RFC 7517) for
 * runtime offline JWT verification (ADR-0122 D5 / ADR-0125 D6).
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Resolve / Exchange",
     description = "Cloud Console → Cluster Console handoff. /resolve issues a one-time exchange_code; " +
                   "/auth/exchange (handled by ExchangeController) atomically redeems it for a resolve JWT. " +
                   "JWKS endpoint publishes public key for runtime offline JWT verification.")
@Slf4j
public class ResolveController extends BaseController {

    private final ExchangeService exchangeService;
    private final ClusterRepository clusterRepo;
    private final KeyPair jwtSigningKeyPair;

    public ResolveController(ExchangeService exchangeService,
                             ClusterRepository clusterRepo,
                             KeyPair jwtSigningKeyPair) {
        this.exchangeService = exchangeService;
        this.clusterRepo = clusterRepo;
        this.jwtSigningKeyPair = jwtSigningKeyPair;
    }

    // ========== Resolve — issue exchange_code (Step 1 of ADR-0125) ==========

    /**
     * Request an exchange_code to enter a target Cluster Console.
     * <p>
     * Four conditions are enforced (ADR-0125 D3): Cloud session valid →
     * user ∈ org → cluster exists + belongs to org → cluster not in
     * provisioning_failed or terminated state.
     * <p>
     * Returns {@code base_uri + exchange_code + expires_at}. Browser does NOT
     * receive a JWT here — JWT is issued back-channel in Step 2.
     */
    @GetMapping("/clusters/{clusterId}/resolve")
    @Operation(
        summary = "Issue exchange_code for Cluster Console handoff",
        description = "Part 1 of the Cloud Console → Cluster Console session handoff (ADR-0125).\n\n" +
                      "Caller must have a valid Cloud Console session (cookie).\n\n" +
                      "Four conditions enforced:\n" +
                      "  1. Cloud session is valid\n" +
                      "  2. User is a member of the Org\n" +
                      "  3. Cluster exists and belongs to Org\n" +
                      "  4. Cluster is NOT in PROVISIONING_FAILED or TERMINATED state\n\n" +
                      "Returns a one-time exchange_code (60s TTL, base64url 256-bit random) " +
                      "plus the Cluster origin base_uri.\n\n" +
                      "The browser then does NOT get a JWT here — it GETs " +
                      "`{base_uri}/auth/exchange?code={exchange_code}` on the Cluster Server. " +
                      "The Cluster Server's GC SDK back-channel-calls POST /v1/auth/exchange " +
                      "(ExchangeController) where the resolve JWT is atomically signed."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "exchange_code issued"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "No valid Cloud session"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "User not Org member or Cluster not accessible"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Cluster not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cluster in PROVISIONING_FAILED / TERMINATED — cannot resolve")
    })
    public ResponseEntity<ApiResponse<ResolveResponse>> resolveCluster(
            @Parameter(description = "Cluster UUID to resolve") @PathVariable String clusterId,
            @Parameter(description = "Organization UUID (recommended — validates Org ownership)") @RequestParam String orgId) {

        String userEmail = CurrentUser.getEmail();
        if (userEmail == null) {
            return fail(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        ExchangeService.IssuedExchangeCode issued = exchangeService.issueExchangeCode(userEmail, orgId, clusterId);

        log.info("Resolve code issued for cluster={}, user={}, baseUri={}", clusterId, userEmail, issued.baseUri());
        return ok(ResolveResponse.success(
            issued.baseUri(), issued.rawCode(), issued.expiresAt().toString()));
    }

    // ========== JWKS endpoint (ADR-0122 D5 / ADR-0125 D6) ==========

    /**
     * Publish RSA public keys for runtime offline verify of resolve JWT signatures.
     * <p>
     * Returns raw JWKS JSON (RFC 7517 format — NOT wrapped in ApiResponse).
     * Every Cluster Server's GC SDK caches this JWKS and does NOT call GC for
     * every workload request — this ensures GC downtime never blocks Cluster
     * workload handling. Key rotation: 24h interval, 1h overlap.
     */
    @GetMapping("/jwks.json")
    @Operation(
        summary = "JWKS public key set (RFC 7517)",
        description = "Publishes RSA public keys for verifying resolve JWT signatures.\n\n" +
                      "Runtime SDK caches this JWKS and performs offline verification " +
                      "(ADR-0122 D5 / ADR-0125 D6) — GC never called on per-request workload auth.\n\n" +
                      "Key rotation: 24h interval with 1h overlap period.\n\n" +
                      "Returns raw JWKS JSON (RFC 7517 format) — NOT wrapped in ApiResponse."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "JWKS JSON document")
    })
    public ResponseEntity<Map<String, Object>> jwks() {
        RSAPublicKey publicKey = (RSAPublicKey) jwtSigningKeyPair.getPublic();

        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("kid", JwtKeysConfig.KID);
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("n", base64UrlEncode(publicKey.getModulus().toByteArray()));
        jwk.put("e", base64UrlEncode(publicKey.getPublicExponent().toByteArray()));

        Map<String, Object> jwks = new LinkedHashMap<>();
        jwks.put("keys", List.of(jwk));

        return ResponseEntity.ok(jwks);
    }

    // ========== Helpers ==========

    static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ========== DTO ==========

    /** Response from /resolve — contains exchange_code, NOT a JWT. */
    public record ResolveResponse(
        @Schema(description = "Always true on success") boolean success,
        @Schema(description = "Cluster origin base URI for browser redirect", example = "https://c1a2b3.api.tapstate.io") String baseUri,
        @Schema(description = "One-time exchange_code (base64url, 256-bit random, 60s TTL)", example = "abc123...xyz") String exchangeCode,
        @Schema(description = "Expiration timestamp (ISO-8601 UTC)") String expiresAt
    ) {
        public static ResolveResponse success(String baseUri, String code, String expiresAt) {
            return new ResolveResponse(true, baseUri, code, expiresAt);
        }
    }
}
