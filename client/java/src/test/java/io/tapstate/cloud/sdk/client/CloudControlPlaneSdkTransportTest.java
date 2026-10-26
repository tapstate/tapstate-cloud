/*
 * Tapstate Cloud — Global Control Plane SDK
 * Transport-layer regression tests for C1 code-exchange (issue #33).
 * These tests hit a real loopback HttpServer through the real Apache
 * HttpClient that buildExchangeApi() constructs — not Mockito stubs.
 * They prove that one SDK invocation → one HTTP POST, even when the
 * server responds 503 or 429. Before the fix, Apache HttpClient 5's
 * HttpRequestRetryExec silently retried repeatable POST bodies on those
 * status codes, sending the same one-time code twice.
 */

package io.tapstate.cloud.sdk.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CloudControlPlaneSdkTransportTest {

    private static final String CLUSTER_ID = "c1a2b3c4-d5e6-7f89-0abc-def123456789";
    private static final String EXCHANGE_CODE = "abc123xyz789".repeat(4);
    private static final String SERVER_STATIC_TOKEN = "sdk-static-token-abc123";
    private static final String ISSUER = "https://test-issuer.local";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger exchangePostCount = new AtomicInteger();

    // RSA keypair + kid for the 200 happy-path JWT
    private KeyPair rsaKeyPair;
    private String jwtKid;

    /**
     * Per-test forceStatus — set by each @Test before buildSdk().
     * NOT static, so parallel execution (if ever enabled) cannot race.
     * Protected for package-private access by inner handlers.
     */
    int forceStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rsaKeyPair = kpg.generateKeyPair();
        jwtKid = "test-rsa-key-1";

        exchangePostCount.set(0);
        forceStatus = 200;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/v1/api/auth/exchange", new ExchangeHandler());
        server.createContext("/v1/api/jwks.json", new JwksHandler());
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private CloudControlPlaneSdk buildSdk() {
        return CloudControlPlaneSdk.builder()
            .baseUrl(baseUrl)
            .issuer(ISSUER)
            .clusterId(CLUSTER_ID)
            .sdkStaticToken(SERVER_STATIC_TOKEN)
            .jwksRefreshInterval(java.time.Duration.ofMinutes(10))
            .build();
    }

    // ========== Test cases ==========

    @Test
    @DisplayName("C1 exchange: server returns 503 → one POST total, no Apache auto-retry")
    void exchange_503_onePostNoRetry() {
        forceStatus = 503;

        CloudControlPlaneSdk sdk = buildSdk();

        CloudControlPlaneException ex = assertThrows(CloudControlPlaneException.class,
            () -> sdk.exchangeForJwt(EXCHANGE_CODE));

        assertEquals(503, ex.getHttpStatus(), "HTTP status must propagate as-is");
        assertEquals(1, exchangePostCount.get(),
            "Apache HttpClient auto-retry would have sent 2 POSTs after 503 — "
                + "C1 exchange uses a one-time code and must NOT retry");
    }

    @Test
    @DisplayName("C1 exchange: server returns 429 → one POST total, no Apache auto-retry")
    void exchange_429_onePostNoRetry() {
        forceStatus = 429;

        CloudControlPlaneSdk sdk = buildSdk();

        CloudControlPlaneException ex = assertThrows(CloudControlPlaneException.class,
            () -> sdk.exchangeForJwt(EXCHANGE_CODE));

        assertEquals(429, ex.getHttpStatus(), "HTTP status must propagate as-is");
        assertEquals(1, exchangePostCount.get(),
            "Apache HttpClient auto-retry would have sent 2 POSTs after 429 — "
                + "C1 exchange uses a one-time code and must NOT retry");
    }

    @Test
    @DisplayName("C1 exchange: server returns 200 success → one POST, returns JwtExchangeResult")
    void exchange_200_onePostAndSuccess() {
        forceStatus = 200;

        CloudControlPlaneSdk sdk = buildSdk();

        JwtExchangeResult result = assertDoesNotThrow(
            () -> sdk.exchangeForJwt(EXCHANGE_CODE));

        assertNotNull(result.jwt());
        assertNotNull(result.expiresAt());
        assertEquals(1, exchangePostCount.get(), "200 success path should also be exactly one POST");
    }

    // ========== HttpHandler implementations ==========

    /**
     * POST /v1/api/auth/exchange
     * <p>
     * Three modes driven by the outer test instance's {@link #forceStatus}:
     * <ul>
     *   <li>503 / 429 — return that HTTP status with a JSON error envelope (no JWT required)</li>
     *   <li>200 — return a full ApiResponse envelope with a JWT signed by our test RSA key</li>
     * </ul>
     * Every POST increments {@link CloudControlPlaneSdkTransportTest#exchangePostCount}.
     */
    class ExchangeHandler implements HttpHandler {

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            try (InputStream in = exchange.getRequestBody()) { /* drain body */ }

            if (!"POST".equalsIgnoreCase(method)) {
                sendJson(exchange, 405, Map.of("code", "method-not-allowed", "msg", "only POST"));
                return;
            }

            exchangePostCount.incrementAndGet();

            int status = forceStatus;
            if (status == 200) {
                String jwt = buildSignedJwt();
                Instant expiry = Instant.now().plusSeconds(900);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("jwt", jwt);
                data.put("expiresAt", expiry.toString());
                data.put("jti", "test-jti-001");
                data.put("userEmail", "test@example.com");
                data.put("userId", "user-001");
                data.put("orgId", "org-001");
                data.put("clusterId", CLUSTER_ID);
                data.put("organizationName", "Test Org");
                data.put("clusterName", "test-cluster");
                data.put("region", "ap-southeast-1");
                data.put("ec2PublicIp", "10.0.0.1");

                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("opId", "test-op-001");
                envelope.put("code", "ok");
                envelope.put("msg", "ok");
                envelope.put("data", data);

                sendJson(exchange, 200, envelope);
            } else {
                Map<String, Object> envelope = Map.of(
                    "opId", "test-op-001",
                    "code", "sdk.http-" + status,
                    "msg", "synthetic server status " + status
                );
                sendJson(exchange, status, envelope);
            }
        }

        private String buildSignedJwt() {
            Instant now = Instant.now();
            return Jwts.builder()
                .issuer(ISSUER)
                .subject("test-user")
                .claim("cluster_id", CLUSTER_ID)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(900)))
                .header().keyId(jwtKid).and()
                .signWith(rsaKeyPair.getPrivate())
                .compact();
        }

        private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /**
     * GET /v1/api/jwks.json — serves a single-key JWKS containing our test
     * RSA public key. The SDK's JwksKeyCache fetches this to verify the
     * JWT's signature on the 200 success path.
     */
    class JwksHandler implements HttpHandler {

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            RSAPublicKey pubKey = (RSAPublicKey) rsaKeyPair.getPublic();

            Map<String, Object> key = new LinkedHashMap<>();
            key.put("kty", "RSA");
            key.put("kid", jwtKid);
            key.put("alg", "RS256");
            key.put("n", base64Url(pubKey.getModulus().toByteArray()));
            key.put("e", base64Url(pubKey.getPublicExponent().toByteArray()));
            key.put("use", "sig");

            Map<String, Object> jwks = Map.of("keys", List.of(key));

            byte[] bytes = mapper.writeValueAsBytes(jwks);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String base64Url(byte[] raw) {
            // Strip leading zero byte if present (Java BigInteger's sign bit)
            byte[] trimmed = (raw.length > 0 && raw[0] == 0)
                ? java.util.Arrays.copyOfRange(raw, 1, raw.length)
                : raw;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(trimmed);
        }
    }
}
