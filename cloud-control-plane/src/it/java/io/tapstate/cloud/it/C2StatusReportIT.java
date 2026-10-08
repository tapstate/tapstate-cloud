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
 *   <li>All request fields persisted as embedded document on Cluster.lastStatusReport (latest-only)</li>
 *   <li>Every request persisted as history in {@code cluster_status_report_history} time-series collection (accumulating)</li>
 * </ul>
 */
@DisplayName("C2StatusReportIT — Runner static-token auth + nonce replay + latest-snapshot + time-series history")
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
        String token = mongoshEval(
            "db.clusters.findOne({_id: ObjectId('" + clusterId + "')}, {sdkStaticToken:1}).sdkStaticToken")
            .trim();
        assertFalse(token.isBlank(), "SDK static token should not be blank");
        return new String[]{orgId, clusterId, token};
    }

    /** POST /v1/api/clusters/{clusterId}/status-report with optional Bearer. */
    private HttpResponse<String> sendStatusReport(String pathClusterId, String bearerToken, String nonce)
        throws Exception {
        return sendStatusReport(pathClusterId, bearerToken, nonce, "2.1.0", 12345L, 1, null);
    }

    /** POST with full payload — includes runtimeVersion, lastErrorAt for persistence verification. */
    private HttpResponse<String> sendStatusReport(String pathClusterId, String bearerToken, String nonce,
                                                   String runtimeVersion, Long uptimeMs, Integer activePipelines,
                                                   String lastErrorAt) throws Exception {
        var bodyMap = new java.util.HashMap<String, Object>();
        bodyMap.put("nonce", nonce);
        if (runtimeVersion != null) bodyMap.put("runtimeVersion", runtimeVersion);
        if (uptimeMs != null) bodyMap.put("uptimeMs", uptimeMs);
        if (activePipelines != null) bodyMap.put("activePipelines", activePipelines);
        if (lastErrorAt != null) bodyMap.put("lastErrorAt", lastErrorAt);

        String body = mapper.writeValueAsString(bodyMap);
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/v1/api/clusters/" + pathClusterId + "/status-report"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
        if (bearerToken != null) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        return c2Client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Query Cluster.lastStatusReport embedded document from MongoDB, return as JsonNode. */
    private JsonNode fetchLastStatusReport(String clusterId) throws Exception {
        String output = mongoshEval(
            "const d = db.clusters.findOne({_id: ObjectId('" + clusterId + "')}, {lastStatusReport:1});" +
            "if (!d || !d.lastStatusReport) print('NOT_FOUND');" +
            // EJSON.stringify emits strict JSON; printjson emits shell syntax
            // (unquoted keys, Long("...")) that Jackson cannot parse.
            "else print(JSON.stringify(d.lastStatusReport));").trim();
        if (output.equals("NOT_FOUND")) return null;
        return mapper.readTree(output);
    }

    /**
     * Query time-series history records for a cluster, sorted oldest first.
     * Returns a JsonNode array or empty array if none found.
     */
    private JsonNode fetchHistoryRecords(String clusterId) throws Exception {
        String output = mongoshEval(
            "const recs = db.cluster_status_report_history" +
            ".find({clusterId: '" + clusterId + "'})" +
            ".sort({timestamp: 1}).toArray();" +
            "print(JSON.stringify(recs));").trim();
        if (output.isBlank()) return mapper.createArrayNode();
        return mapper.readTree(output);
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

    @Test
    @DisplayName("T7 — Dual-write: Cluster.lastStatusReport latest-only + time-series history accumulates")
    void dualWrite_latestSnapshotAndAccumulatingHistory() throws Exception {
        String[] t = createProvisionedClusterAndToken("Dual");

        // ---- Report 1 ----
        HttpResponse<String> r1 = sendStatusReport(t[1], t[2], "nonce-h1",
            "2.1.0", 9876543210L, 42, "2026-09-30T10:30:00Z");
        assertEquals(200, r1.statusCode(), "Report 1 body: " + r1.body());

        // ---- Report 2 (different values) ----
        Thread.sleep(10); // ensure distinct timestamp in time-series
        HttpResponse<String> r2 = sendStatusReport(t[1], t[2], "nonce-h2",
            "3.0.0", 100L, 0, null);
        assertEquals(200, r2.statusCode(), "Report 2 body: " + r2.body());

        // ---- Report 3 (different values) ----
        Thread.sleep(10);
        HttpResponse<String> r3 = sendStatusReport(t[1], t[2], "nonce-h3",
            "3.0.1", 999999L, 7, "2026-09-30T12:00:00Z");
        assertEquals(200, r3.statusCode(), "Report 3 body: " + r3.body());

        // ============= Cluster.lastStatusReport — latest-only =============
        JsonNode embedded = fetchLastStatusReport(t[1]);
        assertNotNull(embedded, "lastStatusReport embedded document should exist");
        assertEquals("nonce-h3", embedded.path("nonce").asText(),
            "Cluster.lastStatusReport should hold the LATEST nonce");
        assertEquals("3.0.1", embedded.path("runtimeVersion").asText(),
            "Cluster.lastStatusReport should hold the LATEST runtimeVersion");
        assertEquals(7, embedded.path("activePipelines").asInt(),
            "Cluster.lastStatusReport should hold the LATEST activePipelines");

        // ============= Time-series history — accumulating =============
        JsonNode history = fetchHistoryRecords(t[1]);
        assertTrue(history.isArray(), "History should be a JSON array");
        assertEquals(3, history.size(),
            "All 3 reports should be preserved in time-series history (got " + history.size() + ")");

        // Verify each history record carries the correct original values
        assertEquals("nonce-h1", history.get(0).path("nonce").asText(), "History[0] nonce");
        assertEquals("2.1.0", history.get(0).path("runtimeVersion").asText(), "History[0] runtimeVersion");
        assertEquals(42, history.get(0).path("activePipelines").asInt(), "History[0] activePipelines");
        assertEquals("2026-09-30T10:30:00Z", history.get(0).path("lastErrorAt").asText(), "History[0] lastErrorAt");

        assertEquals("nonce-h2", history.get(1).path("nonce").asText(), "History[1] nonce");
        assertEquals("3.0.0", history.get(1).path("runtimeVersion").asText(), "History[1] runtimeVersion");
        assertEquals(0, history.get(1).path("activePipelines").asInt(), "History[1] activePipelines");

        assertEquals("nonce-h3", history.get(2).path("nonce").asText(), "History[2] nonce");
        assertEquals("3.0.1", history.get(2).path("runtimeVersion").asText(), "History[2] runtimeVersion");
        assertEquals(7, history.get(2).path("activePipelines").asInt(), "History[2] activePipelines");
        assertEquals("2026-09-30T12:00:00Z", history.get(2).path("lastErrorAt").asText(), "History[2] lastErrorAt");

        // Verify time-series structure: timestamp field exists on every record
        for (int i = 0; i < history.size(); i++) {
            assertTrue(history.get(i).has("timestamp"),
                "History[" + i + "] should have time-series timeField 'timestamp'");
            assertTrue(history.get(i).path("timestamp").isObject() || !history.get(i).path("timestamp").asText().isBlank(),
                "History[" + i + "] timestamp should not be empty");
        }
    }
}
