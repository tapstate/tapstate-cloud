package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end lifecycle integration test for the Subscription / Cluster chain:
 * <pre>
 * POST /subscription
 *   → Subscription document (TRIAL, 30-day period)
 *   → Cluster document (PENDING → PROVISIONING, tokens + tags + MDB link)
 *   → Cluster FSM async effect runs the runtime deployment/configuration:
 *       PROVISIONING → READY, runtime fields populated
 * cancel subscription
 *   → Cluster TERMINATED (credentials cleared) + MdbAllocation reclaimed
 *   → Subscription CANCELLED
 * </pre>
 * <p>
 * Unlike the API-level assertions elsewhere, every phase here is verified
 * against the raw MongoDB documents in {@code subscriptions} and
 * {@code clusters}, so the persisted state is checked independently of the
 * response DTOs. The IaC layer runs in mock mode, making the flow fast and
 * deterministic while still exercising the same FSM-triggered path.
 */
@DisplayName("SubscriptionClusterLifecycleIT — subscribe, FSM deploy, cancel and reclaim")
class SubscriptionClusterLifecycleIT extends BaseIT {

    private final ObjectMapper mapper = new ObjectMapper();
    private String orgId;

    @BeforeEach
    void setUpOrg() throws Exception {
        signupAndLogin("lifecycle-" + System.nanoTime() + "@tapstate.io", "StrongPass123!");
        orgId = orgsApi.create(Map.of(
            "name", "Lifecycle-Test",
            "slug", "lifecycle-" + System.nanoTime()
        )).getData().getId();
    }

    // ========== Raw Mongo helpers ==========

    /** Run a mongosh script that prints a single JSON document or value. */
    private JsonNode mongoJson(String eval) throws Exception {
        Process p = new ProcessBuilder("mongosh", "--quiet", "--eval", eval, MONGO_DB)
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return mapper.readTree(out.isBlank() ? "null" : out);
    }

    private JsonNode rawSubscription() throws Exception {
        return mongoJson("print(JSON.stringify(db.subscriptions.findOne({orgId: ObjectId('"
            + orgId + "')})))");
    }

    private JsonNode rawCluster(String clusterId) throws Exception {
        return mongoJson("print(JSON.stringify(db.clusters.findOne({_id: ObjectId('"
            + clusterId + "')})))");
    }

    private int clusterCount() throws Exception {
        return mongoJson("print(JSON.stringify(db.clusters.countDocuments({orgId: ObjectId('"
            + orgId + "')})))").asInt();
    }

    private String mdbAllocationStatus(String clusterId) throws Exception {
        return mongoJson("const a = db.mdb_allocations.findOne({clusterId: ObjectId('"
            + clusterId + "')}); print(JSON.stringify(a ? a.status : 'NONE'))").asText();
    }

    /** Locate the Cluster id by name via raw HTTP (SDK list returns wrong type). */
    private String findClusterIdByName(String name) throws Exception {
        HttpResponse<String> resp = get("/v1/api/orgs/" + orgId + "/clusters",
            Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue));
        JsonNode clusters = mapper.readTree(resp.body()).path("data");
        if (clusters.isArray()) {
            for (JsonNode c : clusters) {
                if (name.equals(c.path("name").asText())) return c.path("id").asText();
            }
        }
        return null;
    }

    /** Read a date field rendered by mongosh (plain ISO string or extended $date form). */
    private Instant dateField(JsonNode doc, String field) {
        JsonNode node = doc.path(field);
        if (node.isTextual()) {
            return Instant.parse(node.asText());
        }
        String value = node.path("$date").asText(null);
        return value == null ? null : Instant.parse(value);
    }

    // ========== Full lifecycle ==========

    @Test
    @DisplayName("Lifecycle — subscribe: DB records correct; FSM deploys runtime; "
        + "cancel: resources reclaimed and states terminal")
    void subscriptionClusterFullLifecycle() throws Exception {
        // ---------- 1. Before explicit subscription: TRIAL shell, no Cluster ----------
        JsonNode shell = rawSubscription();
        assertFalse(shell.isNull(), "Organization creation must persist a subscription shell");
        assertEquals("TRIAL", shell.path("status").asText());
        assertEquals(0, clusterCount(), "No Cluster should exist before subscribing");

        // ---------- 2. User creates a subscription with a Cluster ----------
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region("ap-southeast-1").clusterName("Lifecycle-Cluster").specKey("basic"));
        String clusterId = findClusterIdByName("Lifecycle-Cluster");
        assertNotNull(clusterId, "Cluster must be visible right after subscription");

        // ---------- 3. Raw Subscription document matches expectations ----------
        JsonNode subscription = rawSubscription();
        assertEquals(1,
            mongoJson("print(JSON.stringify(db.subscriptions.countDocuments({})))").asInt(),
            "Exactly one subscription document");
        assertEquals(orgId, subscription.path("orgId").asText());
        assertEquals("TRIAL", subscription.path("status").asText());
        Instant periodStart = dateField(subscription, "currentPeriodStart");
        Instant periodEnd = dateField(subscription, "currentPeriodEnd");
        assertNotNull(periodStart);
        assertNotNull(periodEnd);
        assertEquals(30, Duration.between(periodStart, periodEnd).toDays(),
            "Trial period must span 30 days");

        // ---------- 4. Raw Cluster document matches expectations ----------
        JsonNode cluster = rawCluster(clusterId);
        assertEquals(orgId, cluster.path("orgId").asText());
        assertEquals("ap-southeast-1", cluster.path("region").asText());
        assertEquals("Lifecycle-Cluster", cluster.path("name").asText());
        assertEquals("basic", cluster.path("specKey").asText());
        // START_PROVISION was fired synchronously; READY may already follow in mock mode
        assertTrue(
            "PROVISIONING".equals(cluster.path("state").asText())
                || "READY".equals(cluster.path("state").asText()),
            "Cluster must have entered the FSM provisioning flow, got: "
                + cluster.path("state").asText());

        String runtimeId = cluster.path("runtimeId").asText(null);
        assertNotNull(runtimeId);
        assertTrue(runtimeId.startsWith("rt-"), "runtimeId must use the rt- prefix");
        assertNotNull(cluster.path("bootstrapToken").asText(null),
            "bootstrap token must be provisioned");
        assertNotNull(cluster.path("sdkStaticToken").asText(null),
            "SDK static token must be provisioned");
        String allocationId = cluster.path("mdbAllocationId").asText(null);
        assertNotNull(allocationId, "mdbAllocationId must be linked");
        assertTrue(allocationId.matches("[0-9a-f]{24}"),
            "mdbAllocationId must be a 24-hex ObjectId, got: " + allocationId);
        // Tags are populated synchronously during Cluster creation
        JsonNode tags = cluster.path("tags");
        assertEquals(orgId, tags.path("organization_id").asText());
        assertEquals(clusterId, tags.path("cluster_id").asText());
        assertEquals(runtimeId, tags.path("runtime_id").asText());

        // ---------- 5. FSM async effect deploys/configures the runtime → READY ----------
        Instant readiedAt = null;
        for (int i = 0; i < 60; i++) {
            JsonNode current = rawCluster(clusterId);
            String state = current.path("state").asText();
            if ("READY".equals(state)) {
                readiedAt = dateField(current, "readyAt");
                cluster = current;
                break;
            }
            if ("PROVISIONING_FAILED".equals(state)) {
                fail("Runtime deployment failed: " + current.path("provisionErrorDetail"));
            }
            Thread.sleep(1000);
        }
        assertEquals("READY", cluster.path("state").asText(),
            "FSM must drive the Cluster to READY via its async deployment effect");
        assertNotNull(readiedAt, "readyAt must be stamped on the READY transition");

        // Runtime deployment/config fields populated by the FSM transition effect
        assertNotNull(cluster.path("ec2InstanceId").asText(null));
        assertTrue(cluster.path("ec2InstanceId").asText().startsWith("i-"),
            "ec2InstanceId must use the i- prefix");
        assertNotNull(cluster.path("ec2PublicIp").asText(null),
            "runtime public IP must be recorded");
        assertEquals(clusterId + ".dev.cloud.tapstate.com",
            cluster.path("publicDns").asText(),
            "publicDns must follow the dev naming rule");
        assertNotNull(dateField(cluster, "lastHeartbeatAt"),
            "runtime heartbeat timestamp must be recorded");

        // ---------- 6. User cancels → resource reclamation and terminal states ----------
        subscriptionApi.cancelSubscription(orgId);

        boolean terminated = false;
        boolean mdbReclaimed = false;
        for (int i = 0; i < 40; i++) {
            JsonNode current = rawCluster(clusterId);
            if (!terminated && "TERMINATED".equals(current.path("state").asText())) {
                terminated = true;
                cluster = current;
            }
            if (!mdbReclaimed && "DELETED".equals(mdbAllocationStatus(clusterId))) {
                mdbReclaimed = true;
            }
            if (terminated && mdbReclaimed) break;
            Thread.sleep(1000);
        }

        assertTrue(terminated, "Cluster must reach TERMINATED after cancel");
        assertTrue(mdbReclaimed, "MdbAllocation must be reclaimed after cancel");

        // Reclaimed Cluster: terminal timestamp set, credentials cleared
        assertNotNull(dateField(cluster, "terminatedAt"),
            "terminatedAt must be stamped on termination");
        assertFalse(cluster.hasNonNull("sdkStaticToken"),
            "SDK static token must be cleared on termination");
        assertFalse(cluster.hasNonNull("bootstrapToken"),
            "bootstrap token must be cleared on termination");

        // Subscription document reflects CANCELLED
        JsonNode cancelled = rawSubscription();
        assertEquals("CANCELLED", cancelled.path("status").asText());

        // ---------- 7. Repeated cancel is idempotent ----------
        subscriptionApi.cancelSubscription(orgId);
        assertEquals("CANCELLED", rawSubscription().path("status").asText());
        assertEquals("TERMINATED", rawCluster(clusterId).path("state").asText());
    }
}
