/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned facade classes — NOT auto-generated.
 * Protected by .openapi-generator-ignore.
 */

package io.tapstate.cloud.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cached JWKS (RFC 7517) public key set for offline resolve JWT verification.
 * <p>
 * Per {@link CloudControlPlaneSdk} — one instance per SDK. Fetches and caches RSA
 * public keys from the Global Control Plane's {@code /v1/jwks.json} endpoint.
 * <p>
 * Thread-safety: read path ({@link #findKey}) is lock-free (single atomic reference
 * read). Write path (refresh) is guarded by compare-and-swap — only one refresh
 * runs at a time; concurrent callers wait on the refreshed result or use the
 * stale cache (offline-first per ADR-0125 D6).
 * <p>
 * Cache lifecycle:
 * <ol>
 *   <li>Lazy-loaded on first {@link #findKey(String)} call — blocking HTTP GET</li>
 *   <li>Background refresh when TTL expires (default 10 min — ADR-0125 D6)</li>
 *   <li>On refresh failure — keep stale keys, log warning, continue serving</li>
 * </ol>
 */
final class JwksKeyCache {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jwksUrl;
    private final Duration refreshInterval;

    /** Map of kid → RSAPublicKey (immutable snapshot per refresh). */
    private final AtomicReference<Map<String, RSAPublicKey>> keys =
        new AtomicReference<>(Collections.emptyMap());

    /** Last successful JWKS fetch timestamp (epoch ms). */
    private final AtomicLong lastRefreshEpochMs = new AtomicLong(0);

    /** Refresh in-progress flag — prevents thundering herd on TTL expiry. */
    private final AtomicReference<Thread> refreshInProgress = new AtomicReference<>(null);

    JwksKeyCache(String jwksUrl, Duration refreshInterval) {
        this.jwksUrl = jwksUrl;
        this.refreshInterval = refreshInterval;
    }

    /** Build with default 10-min interval (ADR-0125 D6). */
    JwksKeyCache(String jwksUrl) {
        this(jwksUrl, Duration.ofMinutes(10));
    }

    /**
     * Find an RSA public key by kid — triggers lazy fetch or refresh as needed.
     *
     * @param kid JWT header {@code kid} value to match
     * @return matching {@link RSAPublicKey}
     * @throws CloudControlPlaneException if cache is empty (never fetched)
     *                                    AND refresh failed
     * @throws JwtVerificationException.JwkNotFound if no key with this kid
     *         exists in the current cache (stale keys are still used — we check
     *         kid match; if kid isn't there after a successful fetch, the key
     *         genuinely doesn't exist)
     */
    RSAPublicKey findKey(String kid) throws CloudControlPlaneException {
        if (kid == null || kid.isBlank()) {
            return null; // caller handles as MissingKeyId
        }

        // Try current cache first (lock-free read)
        Map<String, RSAPublicKey> current = keys.get();
        RSAPublicKey key = current.get(kid);
        if (key != null) {
            return key;
        }

        // Cache is stale or empty — attempt refresh
        refreshIfExpired();

        // Re-check after refresh (or if refresh was a no-op due to TTL)
        current = keys.get();
        key = current.get(kid);
        if (key != null) {
            return key;
        }

        // Still not found — cache might be empty (first fetch failed)
        if (current.isEmpty()) {
            throw CloudControlPlaneException.networkError(
                "JWKS cache is empty — refresh from " + jwksUrl + " failed",
                new IllegalStateException("No keys cached"));
        }

        // We have keys, but kid doesn't match any — genuine mismatch (e.g. GC rotated key,
        // or JWT was signed by a different GC instance). Return null → caller handles.
        return null;
    }

    /**
     * Force-refresh JWKS immediately (e.g. after a key-not-found to rule out
     * stale cache before giving up).
     * <p>
     * Thread-safe — concurrent callers will wait for the in-flight refresh.
     */
    void forceRefresh() throws CloudControlPlaneException {
        refresh(true);
    }

    /** Visible-for-testing. */
    Map<String, RSAPublicKey> getKeysSnapshot() {
        return Collections.unmodifiableMap(keys.get());
    }

    /** Visible-for-testing. */
    long getLastRefreshEpochMs() {
        return lastRefreshEpochMs.get();
    }

    /** Visible-for-testing — inject a known key into the cache. */
    void testInjectKey(String kid, RSAPublicKey key) {
        Map<String, RSAPublicKey> current = keys.get();
        Map<String, RSAPublicKey> updated = new LinkedHashMap<>(current);
        updated.put(kid, key);
        keys.set(Collections.unmodifiableMap(updated));
    }

    // ========== Internals ==========

    private void refreshIfExpired() throws CloudControlPlaneException {
        long last = lastRefreshEpochMs.get();
        long now = System.currentTimeMillis();
        long ttlMs = refreshInterval.toMillis();

        // First fetch (last == 0) OR TTL expired
        if (last == 0 || (now - last) >= ttlMs) {
            refresh(false);
        }
    }

    /**
     * Refresh the JWKS cache. Blocks on in-progress refresh (single-flight).
     *
     * @param force true = skip TTL check (but still single-flight)
     */
    private void refresh(boolean force) throws CloudControlPlaneException {
        Thread me = Thread.currentThread();

        // Single-flight: if another thread is refreshing, wait for it
        while (true) {
            Thread other = refreshInProgress.get();
            if (other == null) break;
            if (other == me) return; // reentrant — shouldn't happen, guard anyway
            // Wait for the in-flight refresh to complete
            try {
                other.join(5000);
                if (refreshInProgress.get() == other) {
                    // Other thread is still stuck; proceed ourselves
                    break;
                }
                return; // other thread completed the refresh
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return; // give up on interrupted, caller will use stale cache
            }
        }

        // Claim the refresh slot
        if (!refreshInProgress.compareAndSet(null, me)) {
            // Someone else just claimed it — fall through to wait loop
            refresh(force);
            return;
        }

        try {
            fetchAndUpdate();
        } catch (CloudControlPlaneException e) {
            // Stale-on-failure: keep the old cache, log and continue
            log.warn("JWKS refresh from {} failed — keeping {} cached keys: {}",
                jwksUrl, keys.get().size(), e.getMessage());
            // Propagate only if cache was empty (first fetch failed)
            if (keys.get().isEmpty()) {
                throw e;
            }
        } finally {
            refreshInProgress.set(null);
        }
    }

    private void fetchAndUpdate() throws CloudControlPlaneException {
        log.debug("Fetching JWKS from {}", jwksUrl);
        String json;
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(jwksUrl);
            try {
                json = http.execute(get, response -> {
                    int status = response.getCode();
                    if (status < 200 || status >= 300) {
                        throw new RuntimeException(new CloudControlPlaneException(
                            "jwks.fetch-" + status, status,
                            "JWKS endpoint returned HTTP " + status));
                    }
                    return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                });
            } catch (RuntimeException re) {
                if (re.getCause() instanceof CloudControlPlaneException cpe) {
                    throw cpe;
                }
                throw CloudControlPlaneException.networkError(
                    "Failed to fetch JWKS from " + jwksUrl + ": " + re.getMessage(), re);
            }
        } catch (CloudControlPlaneException cpe) {
            throw cpe;
        } catch (Exception e) {
            throw CloudControlPlaneException.networkError(
                "Failed to fetch JWKS from " + jwksUrl + ": " + e.getMessage(), e);
        }

        Map<String, RSAPublicKey> newKeys;
        try {
            newKeys = parseJwks(json);
        } catch (Exception e) {
            throw new CloudControlPlaneException(
                "jwks.parse-error", 200,
                "Failed to parse JWKS JSON: " + e.getMessage(), e);
        }

        keys.set(newKeys);
        lastRefreshEpochMs.set(System.currentTimeMillis());
        log.info("JWKS refreshed from {}: {} RSA key(s) cached", jwksUrl, newKeys.size());
    }

    /**
     * Parse RFC 7517 JWKS JSON → kid-keyed RSA public keys.
     * Skips any JWK entries that are not RSA (kty ≠ "RSA").
     */
    static Map<String, RSAPublicKey> parseJwks(String jwksJson) throws Exception {
        JsonNode root = MAPPER.readTree(jwksJson);
        JsonNode keysNode = root.get("keys");
        if (keysNode == null || !keysNode.isArray()) {
            throw new IllegalArgumentException("JWKS JSON missing 'keys' array");
        }

        Map<String, RSAPublicKey> result = new LinkedHashMap<>();
        for (JsonNode jwk : keysNode) {
            String kty = jwk.path("kty").asText();
            if (!"RSA".equals(kty)) {
                log.debug("Skipping non-RSA JWK with kty={}", kty);
                continue;
            }
            String kid = jwk.path("kid").asText();
            String nStr = jwk.path("n").asText();
            String eStr = jwk.path("e").asText();

            if (kid.isBlank() || nStr.isBlank() || eStr.isBlank()) {
                log.warn("Skipping RSA JWK with missing kid/n/e");
                continue;
            }

            try {
                BigInteger n = base64UrlDecodeToBigInt(nStr);
                BigInteger e = base64UrlDecodeToBigInt(eStr);
                RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(n, e));
                result.put(kid, pub);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException | NumberFormatException ex) {
                log.warn("Skipping RSA JWK kid={} — invalid parameters: {}", kid, ex.getMessage());
            }
        }
        return result;
    }

    private static BigInteger base64UrlDecodeToBigInt(String b64) {
        byte[] bytes = Base64.getUrlDecoder().decode(b64);
        return new BigInteger(1, bytes); // signum=1 for unsigned BigInteger
    }
}
