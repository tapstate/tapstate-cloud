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
 * End-to-end full lifecycle integration test for the Subscription / Cluster /
 * MdbAllocation chain:
 * <pre>
 * 1. register a new user                       → users document matches expectations
 * 2. create a subscription                     → Subscription + Cluster + MdbAllocation
 *                                                records exist with the expected states
 * 3. FSM-driven auto-provisioning              → EC2/Cluster runtime/DNS simulated (mock mode),
 *                                                both Cluster and MdbAllocation READY,
 *                                                subscription resources join to READY
 * 4. cancel the subscription                   → CANCELLED / TERMINATED / DELETED
 * 5. resource release                          → credentials cleared, Atlas slot released
 * </pre>
 * Unlike API-level assertions elsewhere, every phase is verified against the
 * raw MongoDB documents, independently of the response DTOs. The IaC layer and
 * Atlas run in mock mode, making the flow fast and deterministic while still
 * exercising the same FSM-triggered code paths. Real cloud release
 * (CloudFormation / DNS / Atlas Admin API) is covered by the credential-gated
 * AwsClusterDeployIT / AwsAtlasFullLifecycleIT.
 */
@DisplayName("SubscriptionClusterLifecycleIT — register, subscribe, FSM deploy, cancel and reclaim")
class SubscriptionClusterLifecycleIT extends BaseIT {

    private final ObjectMapper mapper = new ObjectMapper();
    private String orgId;
    private String userEmail;

    @BeforeEach
    void setUpOrg() throws Exception {
        userEmail = "lifecycle-" + System.nanoTime() + "@tapstate.io";
        signupAndLogin(userEmail, "StrongPass123!");
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

    private JsonNode rawUser(String email) throws Exception {
        return mongoJson("print(JSON.stringify(db.users.findOne({email: '" + email + "'})))");
    }

    private JsonNode rawSubscription() throws Exception {
        return mongoJson("print(JSON.stringify(db.subscriptions.findOne({orgId: ObjectId('"
            + orgId + "')})))");
    }

    private JsonNode rawCluster(String clusterId) throws Exception {
        return mongoJson("print(JSON.stringify(db.clusters.findOne({_id: ObjectId('"
            + clusterId + "')})))");
    }

    private JsonNode rawAllocation(String clusterId) throws Exception {
        return mongoJson("print(JSON.stringify(db.mdb_allocations.findOne({clusterId: ObjectId('"
            + clusterId + "')})))");
    }

    private int clusterCount() throws Exception {
        return mongoJson("print(JSON.stringify(db.clusters.countDocuments({orgId: ObjectId('"
            + orgId + "')})))").asInt();
    }

    private int mdbClusterCapacity(String mdbClusterId) throws Exception {
        return mongoJson("const c = db.mdb_clusters.findOne({_id: ObjectId('"
            + mdbClusterId + "')}); print(JSON.stringify(c ? c.currentAllocations : -1))").asInt();
    }

    /** Raw GET /subscription — SDK model is only regenerated from a running app. */
    private JsonNode subscriptionDto() throws Exception {
        HttpResponse<String> resp = get("/v1/api/orgs/" + orgId + "/subscription",
            Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue));
        assertEquals(200, resp.statusCode(), "GET /subscription failed: " + resp.body());
        return mapper.readTree(resp.body()).path("data");
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
    @DisplayName("Full lifecycle — register, subscribe, dual-ready gate, cancel, release")
    void subscriptionClusterFullLifecycle() throws Exception {
        // ---------- 1. Registration: users record matches expectations ----------
        JsonNode user = rawUser(userEmail);
        assertFalse(user.isNull(), "Registration must persist a users document");
        assertEquals(userEmail, user.path("email").asText());
        assertEquals(userEmail.split("@")[0], user.path("displayName").asText());
        assertTrue(user.path("emailVerified").asBoolean(), "Week 2 preview auto-verifies email");
        assertEquals("active", user.path("status").asText());
        String passwordHash = user.path("passwordHash").asText("");
        assertTrue(passwordHash.startsWith("$2a$") || passwordHash.startsWith("$2b$"),
            "password must be stored as a BCrypt hash, got: " + passwordHash);
        assertNotEquals("StrongPass123!", passwordHash,
            "plaintext password must never be persisted");

        // ---------- 2. Before explicit subscription: TRIAL shell, no resources ----------
        JsonNode shell = rawSubscription();
        assertFalse(shell.isNull(), "Organization creation must persist a subscription shell");
        assertEquals("TRIAL", shell.path("status").asText());
        assertTrue(shell.path("resourceState").isMissingNode() || shell.path("resourceState").isNull(),
            "subscription shell carries no resource state before an explicit subscribe");
        assertEquals(0, clusterCount(), "No Cluster should exist before subscribing");

        // ---------- 3. User creates a subscription with a Cluster ----------
        subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest()
                .region("ap-southeast-1").clusterName("Lifecycle-Cluster").specKey("basic"));
        String clusterId = findClusterIdByName("Lifecycle-Cluster");
        assertNotNull(clusterId, "Cluster must be visible right after subscription");

        // ---------- 3a. Raw Subscription document matches expectations ----------
        JsonNode subscription = rawSubscription();
        assertEquals(1,
            mongoJson("print(JSON.stringify(db.subscriptions.countDocuments({})))").asInt(),
            "Exactly one subscription document");
        assertEquals(orgId, subscription.path("orgId").asText());
        assertEquals("TRIAL", subscription.path("status").asText());
        assertEquals("PROVISIONING", subscription.path("resourceState").asText(),
            "resources must be PROVISIONING immediately after subscribing — the join "
                + "must not flip to READY before both FSMs finish");
        Instant periodStart = dateField(subscription, "currentPeriodStart");
        Instant periodEnd = dateField(subscription, "currentPeriodEnd");
        assertNotNull(periodStart);
        assertNotNull(periodEnd);
        assertEquals(30, Duration.between(periodStart, periodEnd).toDays(),
            "Trial period must span 30 days");

        // ---------- 3b. Raw Cluster document matches expectations ----------
        JsonNode cluster = rawCluster(clusterId);
        assertEquals(orgId, cluster.path("orgId").asText());
        assertEquals("ap-southeast-1", cluster.path("region").asText());
        assertEquals("Lifecycle-Cluster", cluster.path("name").asText());
        assertEquals("basic", cluster.path("specKey").asText());
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
        JsonNode tags = cluster.path("tags");
        assertEquals(orgId, tags.path("organization_id").asText());
        assertEquals(clusterId, tags.path("cluster_id").asText());
        assertEquals(runtimeId, tags.path("runtime_id").asText());

        // ---------- 3c. Raw MdbAllocation document matches expectations ----------
        JsonNode allocation = rawAllocation(clusterId);
        assertFalse(allocation.isNull(), "subscribe must persist an MdbAllocation");
        assertEquals(allocationId, allocation.path("_id").asText());
        assertEquals(orgId, allocation.path("orgId").asText());
        assertEquals(clusterId, allocation.path("clusterId").asText());
        assertEquals(subscription.path("_id").asText(), allocation.path("subscriptionId").asText());
        String expectedDb = "mdb_" + clusterId;
        assertEquals(expectedDb, allocation.path("databaseName").asText());
        assertEquals(expectedDb + "_operator", allocation.path("operatorDbName").asText());
        assertEquals(expectedDb + "_views", allocation.path("viewsDbName").asText());
        assertEquals("owner_" + expectedDb, allocation.path("dbUsername").asText());
        assertTrue(allocation.path("dbPassword").asText("").matches("[0-9a-f]{64}"),
            "dbPassword must be a 64-hex-char random secret");
        String mdbClusterId = allocation.path("mdbClusterId").asText(null);
        assertTrue(mdbClusterId != null && mdbClusterId.matches("[0-9a-f]{24}"),
            "allocation must reference a shared MdbCluster");
        assertEquals(1, mdbClusterCapacity(mdbClusterId),
            "subscribe must reserve exactly one Atlas capacity slot");

        // ---------- 4. FSM auto-provisioning: both resources converge ----------
        // 4a. Cluster FSM: simulated EC2 runtime + DNS fields, then READY.
        for (int i = 0; i < 60; i++) {
            JsonNode current = rawCluster(clusterId);
            String state = current.path("state").asText();
            if ("READY".equals(state)) {
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
        Instant clusterReadyAt = dateField(cluster, "readyAt");
        assertNotNull(clusterReadyAt, "readyAt must be stamped on the READY transition");
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

        // 4b. MdbAllocation FSM: Atlas db + user provisioned → READY.
        Instant allocationReadyAt = null;
        for (int i = 0; i < 30; i++) {
            JsonNode current = rawAllocation(clusterId);
            if ("READY".equals(current.path("status").asText())) {
                allocation = current;
                allocationReadyAt = dateField(current, "readyAt");
                break;
            }
            if ("PROVISIONING_FAILED".equals(current.path("status").asText())) {
                fail("Atlas allocation failed: " + current.path("provisionErrorDetail"));
            }
            Thread.sleep(1000);
        }
        assertEquals("READY", allocation.path("status").asText(),
            "MdbAllocation must reach READY");
        assertNotNull(allocationReadyAt, "allocation readyAt must be stamped");

        // 4c. Subscription resources join to READY — only after BOTH are READY.
        JsonNode readySubscription = null;
        for (int i = 0; i < 30; i++) {
            JsonNode current = rawSubscription();
            if ("READY".equals(current.path("resourceState").asText())) {
                readySubscription = current;
                break;
            }
            Thread.sleep(1000);
        }
        assertNotNull(readySubscription, "subscription resources must become READY");
        Instant resourcesReadyAt = dateField(readySubscription, "resourcesReadyAt");
        assertNotNull(resourcesReadyAt, "resourcesReadyAt must be stamped");
        assertFalse(resourcesReadyAt.isBefore(clusterReadyAt),
            "resource READY must not predate Cluster READY");
        assertFalse(resourcesReadyAt.isBefore(allocationReadyAt),
            "resource READY must not predate MdbAllocation READY");

        // The same state is exposed through the public subscription DTO.
        assertEquals("READY", subscriptionDto().path("resourceState").asText());

        // ---------- 5. Cancel → terminal states and resource release ----------
        subscriptionApi.cancelSubscription(orgId);

        boolean terminated = false;
        boolean mdbReclaimed = false;
        for (int i = 0; i < 40; i++) {
            JsonNode currentCluster = rawCluster(clusterId);
            JsonNode currentAllocation = rawAllocation(clusterId);
            if (!terminated && "TERMINATED".equals(currentCluster.path("state").asText())) {
                terminated = true;
                cluster = currentCluster;
            }
            if (!mdbReclaimed && "DELETED".equals(currentAllocation.path("status").asText())) {
                mdbReclaimed = true;
                allocation = currentAllocation;
            }
            if (terminated && mdbReclaimed) break;
            Thread.sleep(1000);
        }

        assertTrue(terminated, "Cluster must reach TERMINATED after cancel");
        assertTrue(mdbReclaimed, "MdbAllocation must reach DELETED after cancel");

        // Cluster: terminal timestamp set, credentials cleared (AWS resources
        // released by the real-mode path; in mock the terminal bookkeeping stands).
        assertNotNull(dateField(cluster, "terminatedAt"),
            "terminatedAt must be stamped on termination");
        assertFalse(cluster.hasNonNull("sdkStaticToken"),
            "SDK static token must be cleared on termination");
        assertFalse(cluster.hasNonNull("bootstrapToken"),
            "bootstrap token must be cleared on termination");

        // Allocation: deletedAt stamped and the Atlas capacity slot released.
        assertNotNull(dateField(allocation, "deletedAt"),
            "deletedAt must be stamped when the allocation is reclaimed");
        assertEquals(0, mdbClusterCapacity(mdbClusterId),
            "the shared Atlas cluster slot must be released on reclaim");

        // Subscription document reflects CANCELLED.
        JsonNode cancelled = rawSubscription();
        assertEquals("CANCELLED", cancelled.path("status").asText());

        // ---------- 6. Repeated cancel is idempotent ----------
        subscriptionApi.cancelSubscription(orgId);
        assertEquals("CANCELLED", rawSubscription().path("status").asText());
        assertEquals("TERMINATED", rawCluster(clusterId).path("state").asText());
        assertEquals("DELETED", rawAllocation(clusterId).path("status").asText());
        assertEquals(0, mdbClusterCapacity(mdbClusterId),
            "idempotent cancel must not drive capacity below zero");
    }
}
