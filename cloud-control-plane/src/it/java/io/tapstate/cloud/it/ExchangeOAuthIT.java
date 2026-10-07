package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.ApiClient;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.*;
import io.tapstate.cloud.sdk.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cloud Console → Cluster Console OAuth flow (ADR-0125 D3/D4).
 * <p>
 * Full happy path + security invariants:
 * <ol>
 *   <li>Cloud user login → session cookie</li>
 *   <li>GET /v1/api/clusters/{cid}/resolve?orgId={oid} → exchange_code (60s TTL)</li>
 *   <li>Cluster Server back-channel: POST /v1/api/auth/exchange with
 *       X-Cluster-Identity-Secret: {bootstrapToken} + body → JWT (RS256, 15min)</li>
 *   <li>JWT decoded — verify claims (org_id, cluster_id, runtime_id, iss, exp, jti)</li>
 *   <li>Replay consumed exchange_code → 401 (one-shot)</li>
 *   <li>Wrong cluster identity secret → 401</li>
 *   <li>Resolve unauthenticated (no session) → 401</li>
 * </ol>
 */
@DisplayName("ExchangeOAuthIT — resolve → exchange_code → JWT handoff")
class ExchangeOAuthIT extends BaseIT {

    /** Login + create org + create READY cluster via subscription → fetch bootstrapToken from DB. */
    private String[] setupProvisionedCluster() throws Exception {
        signupAndLogin("exchange@tapstate.io", "StrongPass123!");

        String orgId = orgsApi.create(Map.of(
            "name", "Exchange",
            "slug", "exchange")).getData().getId();

        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("ExchangeCluster").specKey("basic"));

        // Poll for cluster via raw HTTP
        String clusterId = null;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.net.http.HttpClient hc = java.net.http.HttpClient.newHttpClient();
        for (int i = 0; i < 10; i++) {
            java.net.http.HttpResponse<String> r = hc.send(java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(BASE_URL + "/v1/api/orgs/" + orgId + "/clusters"))
                .header("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue)
                .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(r.body()).path("data");
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode c : arr) {
                    if ("ExchangeCluster".equals(c.path("name").asText())) clusterId = c.path("id").asText();
                }
            }
            if (clusterId != null) break;
            Thread.sleep(500);
        }
        assertNotNull(clusterId, "Cluster should be created");

        waitForReady(orgId, clusterId);

        // sdkStaticToken = per-Cluster service identity secret (for exchange endpoint)
        String bootstrapToken = mongoshEval(
            "db.clusters.findOne({_id: ObjectId('" + clusterId + "')}, {sdkStaticToken:1}).sdkStaticToken")
            .trim();
        assertNotNull(bootstrapToken);
        assertFalse(bootstrapToken.isBlank());
        return new String[]{orgId, clusterId, bootstrapToken};
    }

    /** Decode JWT payload (Base64url, no sig verify — just structure check). */
    private static String decodeJwtPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]));
    }

    @Test
    @DisplayName("T1 — Full happy path: resolve → exchange → JWT with correct claims")
    void fullHappyPath_claimsValid() throws Exception {
        String[] t = setupProvisionedCluster();
        String orgId = t[0], clusterId = t[1], bootstrapToken = t[2];

        // Step 1: Issue exchange_code via resolve (needs Cloud session cookie)
        ResolveResponse resolve = resolveApi.resolveCluster(clusterId, orgId).getData();
        assertNotNull(resolve);
        assertTrue(Boolean.TRUE.equals(resolve.isSuccess()), "resolve success should be true");
        String exchangeCode = resolve.getExchangeCode();
        assertNotNull(exchangeCode);

        // Step 2: Exchange → JWT (Cluster Server back-channel)
        ExchangeResponse exchResp = exchangeApi.exchange(
            new ExchangeRequest().exchangeCode(exchangeCode).clusterId(clusterId),
            bootstrapToken, null).getData();
        assertNotNull(exchResp);
        String jwt = exchResp.getJwt();
        assertNotNull(jwt);

        // Step 3: Decode claims
        String claimsJson = decodeJwtPayload(jwt);
        System.out.println("[ExchangeOAuthIT] JWT claims: " + claimsJson);

        assertTrue(claimsJson.contains("\"iss\":\"https://cloud.tapstate.io\""), "Missing iss");
        assertTrue(claimsJson.contains("\"cluster_id\":\"" + clusterId + "\""), "Missing cluster_id");
        assertTrue(claimsJson.contains("\"org_id\":\"" + orgId + "\""), "Missing org_id");
        assertTrue(claimsJson.contains("\"sub\":\"exchange@tapstate.io\""), "Missing sub");
        assertTrue(claimsJson.contains("\"exp\""), "Missing exp");
        assertTrue(claimsJson.contains("\"iat\""), "Missing iat");
        assertTrue(claimsJson.contains("\"jti\""), "Missing jti");
    }

    @Test
    @DisplayName("T2 — Replay consumed exchange_code → 401 (one-shot token)")
    void replayConsumedCode_returns401() throws Exception {
        String[] t = setupProvisionedCluster();
        String orgId = t[0], clusterId = t[1], bootstrapToken = t[2];

        String code = resolveApi.resolveCluster(clusterId, orgId).getData().getExchangeCode();

        // First: ok
        exchangeApi.exchange(
            new ExchangeRequest().exchangeCode(code).clusterId(clusterId),
            bootstrapToken, null);

        // Replay: 401
        ApiException ex = assertThrows(ApiException.class,
            () -> exchangeApi.exchange(
                new ExchangeRequest().exchangeCode(code).clusterId(clusterId),
                bootstrapToken, null));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("T3 — Wrong cluster identity secret → 401")
    void wrongIdentitySecret_returns401() throws Exception {
        String[] t = setupProvisionedCluster();
        String orgId = t[0], clusterId = t[1];

        String code = resolveApi.resolveCluster(clusterId, orgId).getData().getExchangeCode();

        ApiException ex = assertThrows(ApiException.class,
            () -> exchangeApi.exchange(
                new ExchangeRequest().exchangeCode(code).clusterId(clusterId),
                "wrong-secret", null));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("T4 — Resolve unauthenticated (no session) → 401")
    void resolve_unauthenticated_returns401() {
        ApiClient anon = new ApiClient();
        anon.setBasePath(BASE_URL);
        ResolveExchangeApi anonResolve = new ResolveExchangeApi(anon);

        ApiException ex = assertThrows(ApiException.class,
            () -> anonResolve.resolveCluster("fake-cluster", "fake-org"));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("T5 — Exchange via Authorization Bearer (alternative header)")
    void exchange_viaBearerHeader_alsoWorks() throws Exception {
        String[] t = setupProvisionedCluster();
        String orgId = t[0], clusterId = t[1], bootstrapToken = t[2];

        String code = resolveApi.resolveCluster(clusterId, orgId).getData().getExchangeCode();

        ExchangeResponse resp = exchangeApi.exchange(
            new ExchangeRequest().exchangeCode(code).clusterId(clusterId),
            null, "Bearer " + bootstrapToken).getData();  // Bearer scheme in Authorization header
        assertNotNull(resp.getJwt());
    }
}
