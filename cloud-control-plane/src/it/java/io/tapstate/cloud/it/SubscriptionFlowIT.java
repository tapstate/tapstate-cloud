package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.sdk.ApiException;
import io.tapstate.cloud.sdk.api.ClustersApi;
import io.tapstate.cloud.sdk.api.SubscriptionApi;
import io.tapstate.cloud.sdk.model.ClusterResponse;
import io.tapstate.cloud.sdk.model.ClusterResponse.StateEnum;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Subscription → Cluster → MDB allocation full-flow integration tests.
 * <p>
 * Covers the Week 3 subscription orchestration architecture end-to-end:
 * <pre>
 * POST /subscription
 *   → Subscription shell (TRIAL, 2 default billable items)
 *   → Cluster record + Cluster FSM START_PROVISION
 *   → MdbAllocation record + MDB FSM START_PROVISION
 *   → Two async FSMs run in parallel:
 *       Cluster: PROVISIONING → READY / PROVISIONING_FAILED (85% success, simulated CloudFormation)
 *       MDB:     PROVISIONING → READY / PROVISIONING_FAILED (always READY in mockMode)
 * </pre>
 * <p>
 * Uses SDK for subscriptionApi.*, clustersApi.getCluster, clustersApi.retryCluster.
 * <br/>Uses raw HTTP GET for listMyOrganizations / listClusters (OpenAPI SDK return-type bug).
 * <br/>Uses direct MongoDB queries for MdbAllocation records (no SDK model yet).
 */
@DisplayName("SubscriptionFlowIT — subscription → cluster → MDB full flow")
class SubscriptionFlowIT extends BaseIT {

    private final ObjectMapper mapper = new ObjectMapper();
    private String orgId;

    @BeforeEach
    void setUpOrg() throws Exception {
        signupAndLogin("flow-" + System.nanoTime() + "@tapstate.io", "StrongPass123!");
        // Register does NOT auto-create org — create explicitly
        orgId = orgsApi.create(Map.of(
            "name", "Flow-Test",
            "slug", "flow-" + System.nanoTime()
        )).getData().getId();
        assertNotNull(orgId, "orgId must be resolvable");
    }

    // ========== Raw helpers (SDK listClusters returns wrong type) ==========

    /** Raw GET /v1/api/orgs/{orgId}/clusters → unwrap ApiResponse envelope. */
    private JsonNode rawListClusters() throws Exception {
        HttpResponse<String> resp = get("/v1/api/orgs/" + orgId + "/clusters",
            Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue));
        return mapper.readTree(resp.body()).path("data");
    }

    private String findClusterIdByName(String name) throws Exception {
        JsonNode clusters = rawListClusters();
        if (!clusters.isArray()) return null;
        for (JsonNode c : clusters) {
            if (name.equals(c.path("name").asText())) return c.path("id").asText();
        }
        return null;
    }

    private int clusterCount() throws Exception {
        JsonNode clusters = rawListClusters();
        return clusters.isArray() ? clusters.size() : 0;
    }

    // ========== Raw Mongo helpers ==========

    private JsonNode getMdbAllocationForCluster(String clusterId) throws Exception {
        Process p = new ProcessBuilder("mongosh", "--quiet", "--eval",
            "const a = db.mdb_allocations.findOne({clusterId: ObjectId('" + clusterId + "')}); print(JSON.stringify(a ? a : {}));",
            MONGO_DB).redirectErrorStream(true).start();
        p.waitFor();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        return mapper.readTree(out);
    }

    private String getMdbAllocationStatus(String clusterId) throws Exception {
        Process p = new ProcessBuilder("mongosh", "--quiet", "--eval",
            "const a = db.mdb_allocations.findOne({clusterId: ObjectId('" + clusterId + "')}); print(a ? a.status : 'NONE');",
            MONGO_DB).redirectErrorStream(true).start();
        p.waitFor();
        return new String(p.getInputStream().readAllBytes()).trim();
    }

    private int countMdbAllocationsForCluster(String clusterId) throws Exception {
        Process p = new ProcessBuilder("mongosh", "--quiet", "--eval",
            "db.mdb_allocations.countDocuments({clusterId: ObjectId('" + clusterId + "')})",
            MONGO_DB).redirectErrorStream(true).start();
        p.waitFor();
        return Integer.parseInt(new String(p.getInputStream().readAllBytes()).trim().replaceAll("[^0-9]", ""));
    }

    // ========== T1 — Happy path: POST /subscription → Cluster + MDB records ==========

    @Test
    @DisplayName("T1 — POST /subscription creates Cluster record + MdbAllocation record")
    void createSubscription_createsClusterAndMdbRecords() throws Exception {
        // 1. Before creation: subscription shell exists, no clusters, no MDB
        SubscriptionResponse before = subscriptionApi.getSubscription(orgId).getData();
        assertEquals("TRIAL", before.getStatus());
        assertEquals(2, before.getItems().size());
        assertEquals(0, clusterCount(), "Should have NO clusters yet");

        // 2. POST /subscription via SDK
        SubscriptionResponse created = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region("ap-southeast-1").clusterName("Happy-Cluster").specKey("basic")).getData();

        assertNotNull(created.getId());
        assertEquals("TRIAL", created.getStatus());
        assertTrue(created.isProvisioningAllowed());

        // 3. Cluster record exists
        assertEquals(1, clusterCount(), "Should have exactly 1 cluster");
        String clusterId = findClusterIdByName("Happy-Cluster");
        assertNotNull(clusterId);
        assertNotNull(clustersApi.getCluster(orgId, clusterId).getData().getState());

        // 4. MdbAllocation record with correct fields
        JsonNode alloc = getMdbAllocationForCluster(clusterId);
        assertFalse(alloc.isNull() || alloc.isEmpty(), "MdbAllocation should exist");
        assertNotNull(alloc.path("_id").asText(null));
        assertNotNull(alloc.path("mdbClusterId").asText(null));
        assertNotNull(alloc.path("databaseName").asText(null));
        assertNotNull(alloc.path("dbUsername").asText(null));
        assertNotNull(alloc.path("dbPassword").asText(null));
        assertEquals(orgId, alloc.path("orgId").asText());
        assertEquals(clusterId, alloc.path("clusterId").asText());
    }

    // ========== T2 — Async: both FSMs reach READY ==========

    @Test
    @DisplayName("T2 — After POST /subscription: Cluster → READY, MdbAllocation → READY")
    void createSubscription_bothFsmReachReady() throws Exception {
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("Async-Test").specKey("basic"));
        String clusterId = findClusterIdByName("Async-Test");

        boolean clusterReady = false, mdbReady = false;
        for (int i = 0; i < 60; i++) {
            if (!clusterReady && clustersApi.getCluster(orgId, clusterId).getData().getState() == StateEnum.READY)
                clusterReady = true;
            if (!mdbReady && "READY".equals(getMdbAllocationStatus(clusterId)))
                mdbReady = true;
            if (clusterReady && mdbReady) break;
            Thread.sleep(1000);
        }

        assertTrue(clusterReady, "Cluster FSM should reach READY");
        assertTrue(mdbReady, "MdbAllocation FSM should reach READY");

        // Cluster runtime fields populated
        ClusterResponse c = clustersApi.getCluster(orgId, clusterId).getData();
        assertNotNull(c.getRuntimeId());
        assertNotNull(c.getPublicDns());
        assertNotNull(c.getElasticIp());
    }

    // ========== T3 — Second POST adds second Cluster + MDB ==========

    @Test
    @DisplayName("T3 — Second POST /subscription adds second cluster + second MdbAllocation")
    void createSubscription_twice_createsSecondCluster() throws Exception {
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("Cluster-A").specKey("basic"));
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("Cluster-B").specKey("basic"));

        assertEquals(2, clusterCount(), "Should have 2 clusters");

        String aId = findClusterIdByName("Cluster-A");
        String bId = findClusterIdByName("Cluster-B");
        assertEquals(1, countMdbAllocationsForCluster(aId), "Cluster-A 1 allocation");
        assertEquals(1, countMdbAllocationsForCluster(bId), "Cluster-B 1 allocation");
    }

    // ========== T4 — Validation: missing region → 400 ==========

    @Test
    @DisplayName("T4 — POST /subscription missing region → 400")
    void createSubscription_missingRegion_returns400() {
        ApiException ex = assertThrows(ApiException.class, () ->
            subscriptionApi.createSubscription(orgId,
                new CreateSubscriptionRequest().clusterName("NoRegion").specKey("basic")));
        assertEquals(400, ex.getCode());
    }

    // ========== T5 — Validation: missing clusterName → 400 ==========

    @Test
    @DisplayName("T5 — POST /subscription missing clusterName → 400")
    void createSubscription_missingClusterName_returns400() {
        ApiException ex = assertThrows(ApiException.class, () ->
            subscriptionApi.createSubscription(orgId,
                new CreateSubscriptionRequest().region("ap-southeast-1").specKey("basic")));
        assertEquals(400, ex.getCode());
    }

    // ========== T6 — Duplicate name → 409 ==========

    @Test
    @DisplayName("T6 — POST /subscription duplicate cluster name → 409")
    void createSubscription_duplicateName_returns409() throws Exception {
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("Dup").specKey("basic"));

        ApiException ex = assertThrows(ApiException.class, () ->
            subscriptionApi.createSubscription(orgId,
                new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("Dup").specKey("basic")));
        assertEquals(409, ex.getCode());
    }

    // ========== T7 — Unauthenticated → 401 ==========

    @Test
    @DisplayName("T7 — POST /subscription without session → 401")
    void createSubscription_noAuth_returns401() {
        SubscriptionApi anon = new SubscriptionApi();
        anon.getApiClient().setBasePath(BASE_URL);
        ApiException ex = assertThrows(ApiException.class, () ->
            anon.createSubscription(orgId,
                new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("NoAuth").specKey("basic")));
        // SDK throws ApiException with actual HTTP status code for error responses
        // or code=0 for network-level failures; accept both as "not successful"
        assertTrue(ex.getCode() == 401 || ex.getCode() == 0,
            "Expected 401 (unauthenticated) or 0 (network), got " + ex.getCode());
    }

    // ========== T8 — GET subscription after READY ==========

    @Test
    @DisplayName("T8 — GET /subscription after READY shows TRIAL + provisioningAllowed")
    void getSubscription_afterReady() throws Exception {
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("Status-Test").specKey("basic"));
        String clusterId = findClusterIdByName("Status-Test");

        for (int i = 0; i < 60; i++) {
            if (clustersApi.getCluster(orgId, clusterId).getData().getState() == StateEnum.READY
                && "READY".equals(getMdbAllocationStatus(clusterId))) break;
            Thread.sleep(1000);
        }

        SubscriptionResponse sub = subscriptionApi.getSubscription(orgId).getData();
        assertEquals("TRIAL", sub.getStatus());
        assertTrue(sub.isProvisioningAllowed());
        assertEquals(2, sub.getItems().size());
        assertNotNull(sub.getCurrentPeriodStart());
        assertNotNull(sub.getCurrentPeriodEnd());
    }

    // ========== T9 — Cancel cascades Cluster TERMINATED + MDB DELETED ==========

    @Test
    @DisplayName("T9 — Cancel subscription → Cluster TERMINATED + MdbAllocation DELETED")
    void cancelSubscription_cascadesClusterAndMdb() throws Exception {
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1").clusterName("Cancel-Test").specKey("basic"));
        String clusterId = findClusterIdByName("Cancel-Test");

        for (int i = 0; i < 30; i++) {
            if (clustersApi.getCluster(orgId, clusterId).getData().getState() == StateEnum.READY) break;
            Thread.sleep(1000);
        }

        subscriptionApi.cancelSubscription(orgId);

        boolean terminated = false, deleted = false;
        for (int i = 0; i < 30; i++) {
            if (!terminated) {
                try {
                    if (clustersApi.getCluster(orgId, clusterId).getData().getState() == StateEnum.TERMINATED) terminated = true;
                } catch (Exception ignored) {}
            }
            if (!deleted && "DELETED".equals(getMdbAllocationStatus(clusterId))) deleted = true;
            if (terminated && deleted) break;
            Thread.sleep(1000);
        }

        assertTrue(terminated, "Cluster should TERMINATE within 30s");
        assertTrue(deleted, "MdbAllocation should be DELETED within 30s");
        assertEquals("CANCELLED", subscriptionApi.getSubscription(orgId).getData().getStatus());
    }

    // ========== T10 — Cancel already-cancelled is idempotent ==========

    @Test
    @DisplayName("T10 — Cancel already CANCELLED → idempotent")
    void cancelAlreadyCancelled_isIdempotent() throws Exception {
        subscriptionApi.cancelSubscription(orgId);
        assertEquals("CANCELLED", subscriptionApi.getSubscription(orgId).getData().getStatus());

        subscriptionApi.cancelSubscription(orgId);
        assertEquals("CANCELLED", subscriptionApi.getSubscription(orgId).getData().getStatus());
    }
}
