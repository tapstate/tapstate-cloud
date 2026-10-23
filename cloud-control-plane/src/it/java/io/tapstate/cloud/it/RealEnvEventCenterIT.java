package io.tapstate.cloud.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Full real-environment chain for the event center and alert service, run
 * against a pre-deployed Control Plane (awsdev by default).
 * <p>
 * A Cluster is seeded into the store with a known SDK token, then the test
 * drives only public HTTP contracts: the Cluster pushes an event with that
 * token, the organization's policy matches it, an alert is created and the
 * notification worker delivers it through the real SMTP transport. Delivery
 * reaching {@code SENT} is the automated proof; opening the recipient mailbox
 * remains the human part of the plan's live verification.
 * <p>
 * Requires the {@link RealEnvIT} bindings plus {@code MONGO_URI} (to seed and
 * remove the Cluster). The recipient defaults to {@code CP_EMAIL} and can be
 * overridden with {@code ALERT_TEST_MAILBOX}.
 */
class RealEnvEventCenterIT extends RealEnvIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("Cluster push -> policy match -> alert -> real email delivery SENT")
    void clusterEventReachesRealEmailDelivery() throws Exception {
        assumeTrue(mongoAvailable(),
            "MONGO_URI is required to seed a Cluster with an SDK token — skipping real-env event-center test");
        String mailbox = System.getenv().getOrDefault("ALERT_TEST_MAILBOX", USER_EMAIL);
        assumeTrue(mailbox != null && !mailbox.isBlank(),
            "ALERT_TEST_MAILBOX/CP_EMAIL is required to verify delivery — skipping");

        loginAsConfiguredUser();
        String orgId = resolveOrgId();
        String suffix = Long.toString(System.nanoTime());
        String token = "realenv-" + suffix;
        String clusterId = seedCluster(orgId, token);
        String policyId = null;
        String matchedType = "cluster.task.failed";
        String unmatchedType = "cluster.validation.succeeded";
        String dedupeKey = "realenv-" + suffix;

        try {
            policyId = createPolicy(orgId, mailbox);

            // --- 1. The Cluster pushes an event with its own SDK token ---
            String event = """
                {"type":"%s","severity":"CRITICAL","subject":"Real environment task failure",
                 "dedupeKey":"%s","payload":{"taskId":"realenv-task-1","reason":"source unreachable"}}
                """.formatted(matchedType, dedupeKey);
            HttpResponse<String> first = pushEvent(clusterId, token, event);
            assertEquals(200, first.statusCode(), first.body());

            // A retry with the same dedupeKey must not create a second event or alert.
            HttpResponse<String> duplicate = pushEvent(clusterId, token, event);
            assertEquals(200, duplicate.statusCode(), duplicate.body());

            // --- 2. Event center shows exactly one event with the authenticated Cluster identity ---
            JsonNode events = events(orgId, matchedType);
            assertEquals(1, events.size(), "a repeated dedupeKey must not create a second event");
            JsonNode stored = events.get(0);
            assertEquals("cluster", stored.get("source").asText());
            assertEquals(clusterId, stored.get("payload").get("clusterId").asText());
            assertEquals("realenv-task-1", stored.get("payload").get("taskId").asText());
            assertNotNull(stored.get("payload").get("clusterName"),
                "the authenticated Cluster name must be attached to the payload");

            // --- 3. A non-matching type enters the event center without creating an alert ---
            String unmatched = """
                {"type":"%s","severity":"INFO","subject":"Validation passed",
                 "dedupeKey":"realenv-validation-%s"}
                """.formatted(unmatchedType, suffix);
            assertEquals(200, pushEvent(clusterId, token, unmatched).statusCode());
            assertEquals(1, events(orgId, unmatchedType).size());
            JsonNode alerts = alerts(orgId);
            assertEquals(1, alerts.size(), "only the policy-matching event may create an alert");
            assertEquals("CRITICAL", alerts.get(0).get("severity").asText());

            // --- 4. The notification worker delivers through the real SMTP transport ---
            JsonNode sent = awaitDelivery(orgId, mailbox);
            assertEquals("SENT", sent.get("status").asText(),
                "the alert email was not delivered: " + sent);
            assertEquals(0, sent.get("attempts").asInt(),
                "a first-attempt success must not record retries: " + sent);
            assertTrue(sent.get("subject").asText().startsWith("[CRITICAL]"),
                "alert mail must carry the severity prefix, got: " + sent.get("subject").asText());

            // --- 5. Another Cluster's token cannot push for this Cluster ---
            String otherCluster = "000000000000000000000000";
            assertEquals(401, pushEvent(otherCluster, token, event).statusCode());
        } finally {
            if (policyId != null) {
                deletePolicy(orgId, policyId);
            }
            deleteCluster(clusterId);
        }
    }

    // ---- HTTP helpers ----

    private String createPolicy(String orgId, String mailbox) throws Exception {
        String policy = """
            {"name":"Real environment event center","enabled":true,"eventTypes":["cluster.task.*"],
             "minSeverity":"INFO","recipients":["%s"],"channel":"EMAIL","cooldownSeconds":0}
            """.formatted(mailbox);
        HttpResponse<String> response = post("/v1/api/orgs/" + orgId + "/alert-policies", policy);
        assertEquals(201, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("data").get("id").asText();
    }

    private void deletePolicy(String orgId, String policyId) throws Exception {
        try {
            send(HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1/api/orgs/" + orgId + "/alert-policies/" + policyId))
                .header("Cookie", cookie())
                .DELETE());
        } catch (RuntimeException ignored) {
            // Cleanup is best effort; a leftover policy does not hide a delivery failure.
        }
    }

    private HttpResponse<String> pushEvent(String clusterId, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/v1/api/clusters/" + clusterId + "/events"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private JsonNode events(String orgId, String type) throws Exception {
        HttpResponse<String> response = get("/v1/api/orgs/" + orgId + "/events?type=" + type);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("data").get("items");
    }

    private JsonNode alerts(String orgId) throws Exception {
        HttpResponse<String> response = get("/v1/api/orgs/" + orgId + "/alerts");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("data");
    }

    /** Poll the delivery list until the worker reports SENT for {@code mailbox}. */
    private JsonNode awaitDelivery(String orgId, String mailbox) throws Exception {
        JsonNode last = null;
        List<String> seen = new ArrayList<>();
        for (int attempt = 0; attempt < 40; attempt++) {
            HttpResponse<String> response = get("/v1/api/orgs/" + orgId + "/deliveries");
            assertEquals(200, response.statusCode(), response.body());
            for (JsonNode row : JSON.readTree(response.body()).get("data")) {
                if (!mailbox.equals(row.get("recipient").asText())) {
                    continue;
                }
                last = row;
                String state = row.get("status").asText();
                if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(state)) {
                    seen.add(state);
                    System.out.println("[real-env] delivery " + row.get("id").asText() + " -> " + state);
                }
                if ("SENT".equals(state) || "DEAD".equals(state)) {
                    return row;
                }
            }
            Thread.sleep(3000);
        }
        throw new AssertionError("no delivery row for " + mailbox + " after 120s; states seen=" + seen
            + " last=" + last);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Cookie", cookie())
            .GET());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Content-Type", "application/json")
            .header("Cookie", cookie())
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String cookie() {
        return "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue;
    }

    // ---- Store seeding (Cluster identity) ----

    /**
     * Seed a READY Cluster with a known SDK token for the test organization.
     * The Cluster push endpoint authenticates exactly as a real runtime does;
     * only the provisioning that would create this row is skipped.
     */
    private String seedCluster(String orgId, String token) throws Exception {
        String output = mongoshEval("""
            const cid = new ObjectId();
            const now = new Date();
            db.clusters.insertOne({
              _id: cid,
              orgId: ObjectId('%s'),
              name: 'realenv-event-%s',
              region: 'ap-southeast-1',
              state: 'READY',
              sdkStaticToken: '%s',
              createdAt: now,
              updatedAt: now,
              _class: 'io.tapstate.cloud.cluster.Cluster'
            });
            print('CLUSTER=' + cid.toHexString());
            """.formatted(orgId, UUID.randomUUID().toString().substring(0, 8), token));
        return output.lines()
            .map(String::strip)
            .filter(line -> line.startsWith("CLUSTER="))
            .map(line -> line.substring("CLUSTER=".length()).strip())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "seedCluster produced no id, mongosh output:\n" + output));
    }

    private void deleteCluster(String clusterId) {
        try {
            mongoshEval("db.clusters.deleteOne({_id: ObjectId('" + clusterId + "')}); print('DELETED');");
        } catch (Exception e) {
            // Best-effort cleanup; never mask the scenario failure with a cleanup error.
            System.out.println("[real-env] cluster cleanup failed for " + clusterId + ": " + e.getMessage());
        }
    }
}
