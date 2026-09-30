/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned facade classes — NOT auto-generated.
 * Protected by .openapi-generator-ignore.
 */

package io.tapstate.cloud.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.C2StatusReportApi;
import io.tapstate.cloud.sdk.api.ExchangeApi;
import io.tapstate.cloud.sdk.model.ExchangeRequest;
import io.tapstate.cloud.sdk.model.ExchangeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-level facade for the Tapstate Cloud Global Control Plane.
 * <p>
 * Wraps the auto-generated {@link ExchangeApi} and adds offline JWT verification
 * via cached JWKS (RFC 7517). Designed for the Cluster runtime SDK (Pipeline Runtime
 * + Cluster Console) to consume directly — these methods handle Cluster service
 * identity authentication, envelope unwrapping, JWKS fetch+cache, and JWT
 * verification. The caller gets clean typed results and specific exception subtypes.
 * <p>
 * Thread-safe once constructed. Multiple SDK instances may coexist with different
 * base URLs in the same JVM (each has its own fresh ApiClient and JWKS cache).
 *
 * <h3>Function 1 — exchange_code → resolve JWT (ADR-0125)</h3>
 * <pre>
 * CloudControlPlaneSdk sdk = CloudControlPlaneSdk.builder()
 *     .baseUrl("https://cloud.tapstate.io")
 *     .sdkStaticToken(System.getenv("SDK_STATIC_TOKEN"))
 *     // clusterId defaults to System.getenv("CLUSTER_ID") — set it explicitly if needed
 *     .build();
 *
 * JwtExchangeResult result = sdk.exchangeForJwt(exchangeCode);
 * // result.jwt()      → carry as bearer token on workload requests
 * // result.expiresAt() → JWT expiration (15min default)
 * </pre>
 *
 * <h3>Function 2 — offline JWKS JWT verification (ADR-0122 D5 / ADR-0125 D6)</h3>
 * <pre>
 * // After Session 1 (exchange + cookie established), workload requests use verifyJwt()
 * VerifiedJwt verified = sdk.verifyJwt(jwt);
 * // verified.userEmail() → who
 * // verified.clusterId() → cross-check if this Cluster is the right one
 * // verified.scope()     → ["workload:read", "workload:write"]
 *
 * // GC downtime does NOT block workload auth — JWKS is cached 10 min default
 * </pre>
 *
 * <h3>Planned Function 3 — C2 status reporting</h3>
 * Runtime → GC heartbeat + infrastructure metrics (static-token auth, nonce replay protection).
 */
public final class CloudControlPlaneSdk {

    private static final Logger log = LoggerFactory.getLogger(CloudControlPlaneSdk.class);

    /** Default issuer the Global Control Plane signs resolve JWTs with (ADR-0122 D4). */
    static final String DEFAULT_ISSUER = "https://cloud.tapstate.io";

    /** Default JWKS refresh interval — 10 min (ADR-0125 D6). */
    static final Duration DEFAULT_JWKS_REFRESH = Duration.ofMinutes(10);

    /** Small clock-skew tolerance when verifying exp (JJWT default; override if needed). */
    static final long JWT_CLOCK_SKEW_SECONDS = 30;

    /** HTTP {@code User-Agent} header value sent with every GC request. */
    static final String USER_AGENT = "TapState Cloud SDK";

    // ========== Configuration (immutable after build) ==========

    /** The Global Control Plane base URL — e.g. https://cloud.tapstate.io */
    private final String baseUrl;

    /** Expected {@code iss} claim in resolve JWTs. */
    private final String issuer;

    /**
     * Cluster UUID — read from environment variable {@code CLUSTER_ID} by default.
     * Used by {@link #exchangeForJwt(String)} and {@link #reportStatus(...)}; {@link #verifyJwt(String)} does NOT
     * need it because the Cluster already knows which org/cluster it belongs to —
     * it just asks the SDK "is this JWT valid?" and reads org_id/cluster_id from
     * the returned {@link VerifiedJwt} if it wants to scope-check.
     */
    private final String clusterId;

    /**
     * Per-Cluster static SDK Bearer token for C2 status-report auth (ADR-0124 D3).
     * this is the long-lived token on {@code Cluster.sdkStaticToken} set at provisioning time.
     * Null for SDKs that never call C2 (verify-only or exchange-only).
     */
    private final String sdkStaticToken;

    // ========== Dependencies ==========

    /** ExchangeCode → JWT (Function 1). */
    private final ExchangeApi exchangeApi;

    /** JWKS → cached RSA keys (Function 2). */
    private final JwksKeyCache jwksCache;

    /** Runtime → GC C2 status-report (Function 3). Built lazily — only needed when callers invoke reportStatus(). */
    private C2StatusReportApi c2StatusReportApi;

    // ========== Construction ==========

    private CloudControlPlaneSdk(String baseUrl, String issuer,
                                  String clusterId, String sdkStaticToken,
                                  ExchangeApi exchangeApi,
                                  JwksKeyCache jwksCache) {
        this.baseUrl = baseUrl;
        this.issuer = issuer;
        this.clusterId = clusterId;
        this.sdkStaticToken = sdkStaticToken;
        this.exchangeApi = exchangeApi;
        this.jwksCache = jwksCache;
    }

    private static ExchangeApi buildExchangeApi(String baseUrl) {
        // Fresh ApiClient per instance — never mutate Configuration global singleton.
        ApiClient client = new ApiClient();
        client.setBasePath(baseUrl);
        client.setUserAgent(USER_AGENT);
        return new ExchangeApi(client);
    }

    private static JwksKeyCache buildJwksCache(String baseUrl, Duration refreshInterval) {
        String jwksUrl = baseUrl.replaceAll("/+$", "") + "/v1/api/jwks.json";
        return new JwksKeyCache(jwksUrl, refreshInterval);
    }

    /** Lazily-build a C2StatusReportApi backed by the same ApiClient baseUrl as ExchangeApi. */
    private synchronized C2StatusReportApi getOrBuildC2Api() {
        if (c2StatusReportApi == null) {
            ApiClient client = new ApiClient();
            client.setBasePath(baseUrl);
            client.setUserAgent(USER_AGENT);
            c2StatusReportApi = new C2StatusReportApi(client);
        }
        return c2StatusReportApi;
    }

    // ========== Function 1: exchange_code → resolve JWT ==========

    /**
     * Redeem an {@code exchange_code} for a resolve JWT (ADR-0125 back-channel).
     * <p>
     * This is Step 2 of the Cloud Console → Cluster Console session handoff.
     * The browser carries the raw {@code exchange_code} in a URL query parameter
     * and lands on {@code {base_uri}/auth/exchange?code={exchange_code}}. The
     * Cluster Server backend calls THIS method — never exposing the JWT to the
     * browser (back-channel only).
     * <p>
     * The target Cluster UUID is read from the {@code CLUSTER_ID} environment
     * variable at SDK build time — not passed as a parameter. The Cluster knows
     * its own identity; callers don't need to repeat it.
     *
     * @param exchangeCode the raw one-time code from the browser URL param (60s TTL)
     * @return typed {@link JwtExchangeResult} containing the JWT and metadata
     * @throws CloudControlPlaneException on any failure — network, auth, or business error
     */
    public JwtExchangeResult exchangeForJwt(String exchangeCode)
            throws CloudControlPlaneException {

        if (sdkStaticToken == null) {
            throw new CloudControlPlaneException(
                "sdk.not-configured", 0,
                "CloudControlPlaneSdk has no sdkStaticToken configured — cannot call exchange");
        }
        if (clusterId == null) {
            throw new CloudControlPlaneException(
                "sdk.not-configured", 0,
                "CloudControlPlaneSdk has no clusterId — either set env CLUSTER_ID or call builder.clusterId(...)");
        }

        log.debug("Exchanging exchange_code for resolve JWT: cluster={}", clusterId);

        ExchangeRequest request = new ExchangeRequest()
            .exchangeCode(exchangeCode)
            .clusterId(clusterId);

        // Generator's ApiClient response interceptor automatically unwraps the
        // ApiResponse envelope (code="ok" + data=...) — on success we get
        // ExchangeResponse directly, non-2xx throws ApiException.
        ExchangeResponse data;
        try {
            // Generated ExchangeApi returns ApiResponseExchangeResponse envelope — unwrap .getData()
            data = exchangeApi.exchange(request, sdkStaticToken, null).getData();
        } catch (ApiException apiEx) {
            handleApiException(apiEx);
            throw new AssertionError("handleApiException must always throw");
        }

        if (data == null || data.getJwt() == null) {
            throw CloudControlPlaneException.unexpectedFailure(
                "sdk.empty-response",
                "Server returned success but JWT payload is null");
        }

        Instant expiresAt;
        try {
            expiresAt = Instant.parse(data.getExpiresAt());
        } catch (Exception e) {
            throw new CloudControlPlaneException(
                "sdk.invalid-expires-at",
                200,
                "Server returned JWT with unparseable expiresAt: '" + data.getExpiresAt() + "'",
                e);
        }

        return new JwtExchangeResult(
            data.getJwt(),
            expiresAt,
            data.getJti(),
            data.getUserEmail(),
            data.getOrgId(),
            data.getClusterId()
        );
    }

    // ========== Function 2: offline JWKS JWT verification ==========

    /**
     * Verify a resolve JWT offline using cached JWKS (ADR-0122 D5 / ADR-0125 D6).
     * <p>
     * The Cluster Server backend calls this for every workload request after the
     * initial exchange — NEVER calls GC online for per-request auth. Verification:
     * <ol>
     *   <li>Extract {@code kid} from JWT header → look up matching RSA key in cache</li>
     *   <li>JJWT verifies RS256 signature + {@code exp} not in past + {@code iss} matches issuer</li>
     *   <li>Parse all remaining claims</li>
     * </ol>
     * <p>
     * This method does NOT check {@code org_id}, {@code cluster_id}, or {@code aud} —
     * the caller (Cluster) already knows its own org/cluster identity and can
     * compare the values in the returned {@link VerifiedJwt} against its own
     * records if cross-scope rejection is needed. The SDK's job is answer one
     * question: "was this JWT signed by the issuer we trust, and is it still valid?"
     * <p>
     * Thread-safe — JWKS cache read path is lock-free. JWKS refresh (every 10 min)
     * uses compare-and-swap single-flight.
     *
     * @param jwt the resolve JWT string from {@code Authorization: Bearer}
     * @return typed {@link VerifiedJwt} with all extracted claims
     * @throws CloudControlPlaneException if JWKS cache is empty (first fetch failed)
     * @throws JwtVerificationException for any JWT-level verification failure —
     *         typed subtype lets the caller distinguish signature vs expired vs parse failures
     */
    public VerifiedJwt verifyJwt(String jwt)
            throws CloudControlPlaneException, JwtVerificationException {

        log.debug("Verifying resolve JWT");

        // Step 1: Parse JWT header to extract kid (without verifying signature).
        String kid;
        try {
            String[] segments = jwt.split("\\.");
            if (segments.length < 2) {
                throw new JwtVerificationException.ParseError(
                    new IllegalArgumentException("JWT must have at least 3 dot-separated segments"));
            }
            byte[] headerBytes = Base64.getUrlDecoder().decode(segments[0]);
            JsonNode headerJson = new ObjectMapper().readTree(headerBytes);
            kid = headerJson.path("kid").asText();
        } catch (JwtVerificationException jve) {
            throw jve;
        } catch (Exception e) {
            throw new JwtVerificationException.ParseError(e);
        }

        if (kid == null || kid.isBlank()) {
            throw new JwtVerificationException.MissingKeyId();
        }

        // Step 2: Find the matching RSA key in JWKS cache
        RSAPublicKey key = jwksCache.findKey(kid);
        if (key == null) {
            log.info("JWKS cache miss for kid={} — forcing refresh and retrying", kid);
            try {
                jwksCache.forceRefresh();
            } catch (CloudControlPlaneException e) {
                log.warn("JWKS refresh failed during verifyJwt — key kid={} still not found", kid);
            }
            key = jwksCache.findKey(kid);
            if (key == null) {
                throw new JwtVerificationException.JwkNotFound(kid);
            }
        }

        // Step 3: JJWT verifies RS256 signature + exp + iss
        Claims claims;
        try {
            claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .clockSkewSeconds(JWT_CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
        } catch (io.jsonwebtoken.JwtException je) {
            classifyJwtException(je, kid);
            throw new AssertionError("classifyJwtException must always throw");
        }

        // Step 4: Extract all claims into VerifiedJwt (caller does any scope checks it needs)
        String jwtUserId = claims.get("user_id", String.class);
        String jwtClusterId = claims.get("cluster_id", String.class);
        String jwtOrgId = claims.get("org_id", String.class);

        Set<String> jwtsAudience = claims.getAudience();
        String aud = (jwtsAudience != null && !jwtsAudience.isEmpty())
            ? jwtsAudience.iterator().next() : null;

        @SuppressWarnings("unchecked")
        List<String> scope = claims.get("scope", List.class);

        Instant expiresAt = claims.getExpiration() != null
            ? claims.getExpiration().toInstant() : null;

        return new VerifiedJwt(
            jwtUserId,                     // stable user UUID (Issue #5 item 1)
            claims.getSubject(),           // userEmail
            jwtOrgId,
            jwtClusterId,
            claims.getId(),               // jti
            expiresAt,
            claims.getIssuer(),
            aud,
            scope != null ? scope : Collections.emptyList()
        );
    }

    // ========== verifyRequest — Cloud→Cluster callback RS256 signature verification (Issue #5 item 4) ==========

    /**
     * Verify a Cloud-signed revocation callback request (Issue #5 item 4).
     * <p>
     * Canonical signature input (MUST match exactly what Cloud signs):
     * <pre>method + "|" + ts + "|" + nonce + "|" + data</pre>
     * <p>
     * Signature algorithm: RS256 with the Cloud's JWKS public key (same key used for JWT signing).
     *
     * @param method  HTTP method (e.g. "POST")
     * @param ts      Timestamp from query param (epoch ms string)
     * @param nonce   Random string from query param
     * @param data    Request body (or jti for bodyless revocation callbacks)
     * @param sign    Signature from query param (Base64Url)
     * @return true if signature is valid, false otherwise
     */
    public boolean verifyRequest(String method, String ts, String nonce, String data, String sign) {
        String canonical = method + "|" + ts + "|" + nonce + "|" + data;
        try {
            Map<String, RSAPublicKey> cached = jwksCache.getKeysSnapshot();
            if (cached.isEmpty()) {
                log.warn("verifyRequest: JWKS cache is empty");
                return false;
            }
            byte[] signBytes = Base64.getUrlDecoder().decode(sign);
            // Try every cached key — callback signatures carry no kid, so during JWKS
            // overlap (old key + new key both in cache) we must attempt all keys until
            // one verifies. ExchangeService signs with the ACTIVE key at sign-time, so
            // whichever of the overlap keys happened to produce this signature will match.
            for (Map.Entry<String, RSAPublicKey> e : cached.entrySet()) {
                java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
                sig.initVerify(e.getValue());
                sig.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (sig.verify(signBytes)) {
                    log.debug("verifyRequest passed with kid={}", e.getKey());
                    return true;
                }
            }
            log.warn("verifyRequest: no cached JWKS key verified the signature (tried {} keys)", cached.size());
            return false;
        } catch (Exception e) {
            log.warn("verifyRequest failed: {}", e.getMessage());
            return false;
        }
    }

    // ========== Function 3 — C2 status report (runtime → Global Control Plane) ==========

    /** Default C2 bounded-retry policy (Issue #19 acceptance criterion 3). */
    static final int C2_MAX_ATTEMPTS = 3;
    static final Duration C2_INITIAL_BACKOFF = Duration.ofMillis(500);
    static final double C2_BACKOFF_MULTIPLIER = 2.0;

    /**
     * Send a C2 status-report heartbeat to the Global Control Plane (ADR-0124 D3 — C2 contract).
     * <p>
     * This is the runtime SDK's inbound auth → GC heartbeat path. Called by the runtime's
     * periodic health-report scheduler (every ~30s). Uses the Cluster's static SDK token
     * for authentication, not a resolve JWT — so GC-side C2 failures do NOT affect the
     * workload auth path (Function 2).
     * <p>
     * Bounded retry: up to {@value #C2_MAX_ATTEMPTS} attempts with exponential backoff
     * Only retries 5xx and connection-level failures; 4xx (401 token invalid, 403 cluster
     * mismatch, 409 nonce replay) are NOT retried — they would never succeed on retry.
     *
     * @param nonce             unique per-request nonce (same value twice → GC returns 409)
     * @param runtimeVersion    Tapstate runtime version string, e.g. "2.1.0" (null allowed)
     * @param uptimeMs          milliseconds since runtime process started (null allowed)
     * @param activePipelines   currently-running pipeline count (null allowed)
     * @param lastErrorAt       ISO-8601 UTC instant of last runtime error, or null if clean
     * @throws CloudControlPlaneException.C2TokenMissing   SDK token not configured — cannot call C2
     * @throws CloudControlPlaneException.C2AuthFailed      GC returned 401/403 — token invalid / cluster mismatch
     * @throws CloudControlPlaneException.C2NonceReplay     GC returned 409 — same nonce used twice
     * @throws CloudControlPlaneException.C2Transient      all retry attempts exhausted on 5xx / network
     */
    public void reportStatus(String nonce, String runtimeVersion, Long uptimeMs,
                             Integer activePipelines, String lastErrorAt) throws CloudControlPlaneException {
        if (sdkStaticToken == null || sdkStaticToken.isEmpty()) {
            throw new CloudControlPlaneException.C2TokenMissing();
        }
        if (clusterId == null || clusterId.isEmpty()) {
            throw new CloudControlPlaneException(
                "c2.cluster-id-missing", 0,
                "C2 reportStatus: clusterId not configured — cannot build endpoint path");
        }
        try {
            doReportStatus(nonce, runtimeVersion, uptimeMs, activePipelines, lastErrorAt, 1);
        } catch (ApiException e) {
            throw classifyC2Error(e);
        }
    }

    private void doReportStatus(String nonce, String runtimeVersion, Long uptimeMs,
                                Integer activePipelines, String lastErrorAt,
                                int attempt) throws ApiException {
        var request = new io.tapstate.cloud.sdk.model.StatusReportRequest()
            .nonce(nonce)
            .runtimeVersion(runtimeVersion)
            .uptimeMs(uptimeMs)
            .activePipelines(activePipelines)
            .lastErrorAt(lastErrorAt);

        io.tapstate.cloud.sdk.api.C2StatusReportApi api = getOrBuildC2Api();
        try {
            api.report(clusterId, request, "Bearer " + sdkStaticToken);
        } catch (ApiException ae) {
            int code = ae.getCode();
            boolean retryable = code == 0 || code >= 500;
            if (retryable && attempt < C2_MAX_ATTEMPTS) {
                long backoffMs = (long) (C2_INITIAL_BACKOFF.toMillis()
                    * Math.pow(C2_BACKOFF_MULTIPLIER, attempt - 1));
                log.warn("C2 status-report attempt {}/{} failed (code={}), retrying in {}ms",
                    attempt, C2_MAX_ATTEMPTS, code, backoffMs);
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ae;  // give up on interrupt
                }
                doReportStatus(nonce, runtimeVersion, uptimeMs, activePipelines, lastErrorAt, attempt + 1);
            } else {
                throw ae;  // propagate — call classifyC2Error to map
            }
        }
    }

    /**
     * Classify a C2 API error into a typed CloudControlPlaneException.
     * 4xx → terminal (auth / nonce), 5xx + connect → transient, never retry-worthy after exhaustion.
     */
    private CloudControlPlaneException classifyC2Error(ApiException e) {
        int code = e.getCode();
        if (code == 401) {
            return new CloudControlPlaneException.C2AuthFailed(
                "C2 status-report: SDK token invalid or missing", e);
        }
        if (code == 403) {
            return new CloudControlPlaneException.C2AuthFailed(
                "C2 status-report: token does not own path clusterId", e);
        }
        if (code == 409) {
            return new CloudControlPlaneException.C2NonceReplay(
                "C2 status-report: nonce already seen — must generate a fresh UUID for each call", e);
        }
        // 5xx (>=500) or connect failure (0) — transient; retries exhausted already
        return new CloudControlPlaneException.C2Transient(
            "C2 status-report failed after " + C2_MAX_ATTEMPTS + " attempts (code=" + code + ") — GC may be unreachable or overloaded", e);
    }

    // ========== Helpers ==========

    /**
     * Classify a JJWT {@code JwtException} into one of our typed {@link JwtVerificationException}
     * subtypes and throw it. Always throws — never returns normally.
     */
    private void classifyJwtException(io.jsonwebtoken.JwtException je, String kid)
            throws JwtVerificationException {
        String className = je.getClass().getSimpleName();
        String msg = je.getMessage() != null ? je.getMessage() : "";
        String msgLower = msg.toLowerCase();
        long now = System.currentTimeMillis() / 1000;

        if (className.contains("Expired")) {
            long expEpoch = tryExtractExp(je);
            throw new JwtVerificationException.Expired(expEpoch, now);
        }
        if (je instanceof io.jsonwebtoken.security.SignatureException) {
            throw new JwtVerificationException.SignatureMismatch(kid);
        }
        // JJWT 0.12 IncorrectClaimException covers iss/aud/exp/etc mismatches
        if (className.contains("IncorrectClaim") || className.contains("MissingClaim")) {
            if (msgLower.contains("iss")) {
                String actualIss = tryExtractClaim(je, "iss");
                throw new JwtVerificationException.BadIssuer(actualIss != null ? actualIss : "unknown", issuer);
            }
        }
        // Fallback — generic parse/validation error
        throw new JwtVerificationException.ParseError(je);
    }

    private long tryExtractExp(io.jsonwebtoken.JwtException je) {
        // Best-effort: extract exp epoch from the exception message
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("exp=(-?\\d+)").matcher(je.getMessage());
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (Exception ignored) {}
        return 0;
    }

    private String tryExtractClaim(io.jsonwebtoken.JwtException je, String claim) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(claim + ".*['\"]([^'\"]+)['\"]").matcher(je.getMessage());
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    private void handleApiException(ApiException apiEx) throws CloudControlPlaneException {
        int httpStatus = apiEx.getCode();
        String responseBody = apiEx.getResponseBody();
        String errorCode = "sdk.http-" + httpStatus;
        String serverMessage = apiEx.getMessage();

        if (responseBody != null && !responseBody.isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(responseBody, Map.class);
                if (parsed.get("code") instanceof String c) errorCode = c;
                if (parsed.get("msg") instanceof String m) serverMessage = m;
            } catch (Exception parseEx) {
                log.debug("Failed to parse error response JSON — using raw body", parseEx);
                serverMessage = responseBody;
            }
        }
        throw CloudControlPlaneException.httpError(httpStatus, errorCode, serverMessage);
    }

    /** Get the Global Control Plane base URL this SDK instance was configured with. */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Get the Cluster UUID this SDK was built with (read from {@code CLUSTER_ID}
     * env var or set explicitly via {@link Builder#clusterId(String)}). Callers can
     * compare this against {@link VerifiedJwt#clusterId()} for scope validation.
     */
    public String getClusterId() {
        return clusterId;
    }

    /** Get the JWKS cache — package-private, for tests. */
    JwksKeyCache getJwksCache() {
        return jwksCache;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl;
        private String issuer = DEFAULT_ISSUER;
        /** Cluster UUID — defaults to {@code System.getenv("CLUSTER_ID")}. */
        private String clusterId = System.getenv("CLUSTER_ID");
        private Duration jwksRefreshInterval = DEFAULT_JWKS_REFRESH;
        private String sdkStaticToken;

        private Builder() {}

        /** Global Control Plane base URL — e.g. "https://cloud.tapstate.io". Required. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Expected {@code iss} claim in resolve JWTs. Default: {@code https://cloud.tapstate.io}
         * (ADR-0122 D4 — matches GC's {@code tapstate.cloud.issuer} config default).
         */
        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        /**
         * Cluster UUID used by {@link CloudControlPlaneSdk#exchangeForJwt(String)}.
         * Defaults to reading the {@code CLUSTER_ID} environment variable at
         * builder-creation time — override here for tests or to pin a specific ID.
         */
        public Builder clusterId(String clusterId) {
            this.clusterId = clusterId;
            return this;
        }

        /**
         * JWKS refresh interval — how often to re-fetch {@code /v1/api/jwks.json} in the
         * background when cache expires. Default: 10 minutes (ADR-0125 D6).
         * During key rotation GC adds new keys to JWKS before removing old ones — the
         * overlap window gives callers time to pick up the new kid.
         */
        public Builder jwksRefreshInterval(Duration interval) {
            this.jwksRefreshInterval = interval;
            return this;
        }

        /**
         * Per-Cluster static SDK Bearer token for C2 status-report auth (ADR-0124 D3).
         * Set on {@code Cluster.sdkStaticToken} at provisioning time.
         * Required for {@link CloudControlPlaneSdk#reportStatus(...)}; leave null if you
         * only use exchange / verifyJwt / verifyRequest.
         */
        public Builder sdkStaticToken(String sdkStaticToken) {
            this.sdkStaticToken = sdkStaticToken;
            return this;
        }

        public CloudControlPlaneSdk build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            ExchangeApi exchangeApi = buildExchangeApi(baseUrl);
            JwksKeyCache jwksCache = buildJwksCache(baseUrl, jwksRefreshInterval);
            return new CloudControlPlaneSdk(baseUrl, issuer, clusterId, sdkStaticToken, exchangeApi, jwksCache);
        }

        /**
         * Build an SDK with pre-injected collaborators — for unit tests only.
         */
        CloudControlPlaneSdk buildForTest(String clusterId, ExchangeApi exchangeApi, JwksKeyCache jwksCache) {
            return new CloudControlPlaneSdk(baseUrl, issuer, clusterId, sdkStaticToken, exchangeApi, jwksCache);
        }
    }
}
