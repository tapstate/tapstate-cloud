package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.sdk.api.ClustersApi;
import io.tapstate.cloud.sdk.api.OrganizationsApi;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C2 status-report — runtime → Global Control Plane heartbeat + infrastructure metrics.
 * <p>
 * Covers (Week 2 — Gap Closure #1, ADR-0122 D3 — C2 contract):
 * <ul>
 *   <li>Bearer {@code sdk-static-token} auth — valid → 200</li>
 *   <li>Token belongs to different cluster than path variable → 401</li>
 *   <li>Missing Bearer → 400 (sdk.token-missing)</li>
 *   <li>Nonce replay → 409 (auth.nonce-replay)</li>
 *   <li>New nonce after replay → accepted again (replay only blocks SAME nonce)</li>
 * </ul>
 */
@DisplayName("C2StatusReportIT — Runner static-token auth + nonce replay")
class C2StatusReportIT extends BaseIT {

    private HttpClient c2Client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @BeforeEach
    void setUp() {
        super.setUp();
        c2Client = HttpClient.newBuilder().build();
    }

    /** Create a READY cluster via subscription flow, fetch its sdkStaticToken via mongosh. */
    private String[] createProvisionedClusterAndToken(String name) throws Exception {
        signupAndLogin("c2-" + name.toLowerCase() + "@tapstate.io", "StrongPass123!");

        String orgId = orgsApi.create(Map.of(
            "name", "C2-" + name,
            "slug", "c2-" + name.toLowerCase())).getData().getId();

        // Create cluster via subscription flow
        SubscriptionApi subApi = new SubscriptionApi(client);
        subApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName(name).specKey("basic"));

        // Poll clusters list via raw HTTP until we find our cluster
        String clusterId = null;
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> resp = get("/v1/api/orgs/" + orgId + "/clusters",
                Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue));
            JsonNode arr = mapper.readTree(resp.body()).path("data");
            if (arr.isArray()) {
                for (JsonNode c : arr) {
                    if (name.equals(c.path("name").asText())) {
                        clusterId = c.path("id").asText();
                        break;
                    }
                }
            }
            if (clusterId != null) break;
            Thread.sleep(500);
        }
        assertNotNull(clusterId, "Cluster should be created");

        waitForReady(orgId, clusterId);

        // Fetch sdkStaticToken from Mongo — response DTO intentionally hides it
        Process p = new ProcessBuilder("mongosh", "--quiet", "--eval",
            "db.clusters.findOne({_id: '" + clusterId + "'}, {sdkStaticToken:1}).sdkStaticToken",
            MONGO_DB)
            .redirectErrorStream(true).start();
        String token = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        assertFalse(token.isBlank(), "SDK static token should not be blank");
        return new String[]{orgId, clusterId, token};
    }

    /** POST /v1/api/clusters/{clusterId}/status-report with optional Bearer. */
    private HttpResponse<String> sendStatusReport(String pathClusterId, String bearerToken, String nonce)
        throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "nonce", nonce,
            "uptimeMs", 12345L,
            "activePipelines", 1));
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/v1/api/clusters/" + pathClusterId + "/status-report"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
        if (bearerToken != null) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        return c2Client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("T1 — Valid Bearer sdk-static-token → 200 accepted")
    void validToken_returns200() throws Exception {
        String[] t = createProvisionedClusterAndToken("Valid");
        HttpResponse<String> r = sendStatusReport(t[1], t[2], "nonce-1");
        assertEquals(200, r.statusCode(), "Body: " + r.body());
    }

    @Test
    @DisplayName("T2 — Nonce replay → 409 auth.nonce-replay")
    void replayNonce_returns409() throws Exception {
        String[] t = createProvisionedClusterAndToken("Replay");

        HttpResponse<String> first = sendStatusReport(t[1], t[2], "dup-nonce");
        assertEquals(200, first.statusCode());

        HttpResponse<String> replay = sendStatusReport(t[1], t[2], "dup-nonce");
        assertEquals(409, replay.statusCode(), "Body: " + replay.body());
    }

    @Test
    @DisplayName("T3 — Missing Bearer → 400 sdk.token-missing")
    void missingBearer_returns400() throws Exception {
        String[] t = createProvisionedClusterAndToken("NoBearer");
        HttpResponse<String> r = sendStatusReport(t[1], null, "nonce-a");
        assertEquals(400, r.statusCode(), "Body: " + r.body());
    }

    @Test
    @DisplayName("T4 — Wrong Bearer → 401 sdk.token-invalid")
    void wrongBearer_returns401() throws Exception {
        String[] t = createProvisionedClusterAndToken("WrongToken");
        HttpResponse<String> r = sendStatusReport(t[1], "definitely-not-a-token", "nonce-x");
        assertEquals(401, r.statusCode(), "Body: " + r.body());
    }

    @Test
    @DisplayName("T5 — Path clusterId ≠ token's owning cluster → 4xx")
    void tokenClusterMismatch_returns4xx() throws Exception {
        String[] t = createProvisionedClusterAndToken("Mismatch");
        HttpResponse<String> r = sendStatusReport("cluster-nonexistent-999", t[2], "nonce-mismatch");
        assertTrue(r.statusCode() >= 400 && r.statusCode() < 500,
            "Expected 4xx for token-cluster mismatch, got " + r.statusCode() + ": " + r.body());
    }

    @Test
    @DisplayName("T6 — Different nonce after replay → accepted again")
    void freshNonce_afterReplay_ok() throws Exception {
        String[] t = createProvisionedClusterAndToken("Recover");

        // Dupe → 409
        sendStatusReport(t[1], t[2], "same");
        HttpResponse<String> dup = sendStatusReport(t[1], t[2], "same");
        assertEquals(409, dup.statusCode());

        // Different → 200
        HttpResponse<String> ok = sendStatusReport(t[1], t[2], "different");
        assertEquals(200, ok.statusCode());
    }
}
