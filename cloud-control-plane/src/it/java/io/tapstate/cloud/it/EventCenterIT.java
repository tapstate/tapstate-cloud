package io.tapstate.cloud.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bson.types.ObjectId;

import java.net.http.HttpResponse;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level acceptance for the event center: idempotent ingestion, policy
 * evaluation, alert/delivery records and organization isolation.
 */
class EventCenterIT extends BaseIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("external events are idempotent, policy-matched and organization-scoped")
    void eventCenterEndToEnd() throws Exception {
        String email = "event-center-" + System.nanoTime() + "@tapstate.io";
        signupAndLogin(email, "StrongPass123!");
        String orgId = orgsApi.create(organizationRequest("Event Center IT")).getData().getId();

        String policy = """
            {"name":"Critical events","enabled":true,"eventTypes":["test.alert"],
             "minSeverity":"INFO","recipients":["%s"],"channel":"EMAIL","cooldownSeconds":60}
            """.formatted(email);
        HttpResponse<String> created = post("/v1/api/orgs/" + orgId + "/alert-policies", policy,
            Map.of("Cookie", cookie()));
        assertEquals(201, created.statusCode(), created.body());

        String event = """
            {"orgId":"%s","source":"external","type":"test.alert","severity":"WARNING",
             "subject":"A critical external event","actor":"%s","dedupeKey":"dedupe-1",
             "payload":{"key":"value","password":"must-not-be-stored"}}
            """.formatted(orgId, email);
        HttpResponse<String> first = post("/v1/api/internal/events", event,
            Map.of("X-Event-Token", "it-event-token"));
        assertEquals(200, first.statusCode(), first.body());
        HttpResponse<String> duplicate = post("/v1/api/internal/events", event,
            Map.of("X-Event-Token", "it-event-token"));
        assertEquals(200, duplicate.statusCode(), duplicate.body());

        HttpResponse<String> events = get("/v1/api/orgs/" + orgId + "/events?type=test.alert",
            Map.of("Cookie", cookie()));
        assertEquals(200, events.statusCode(), events.body());
        JsonNode eventItems = JSON.readTree(events.body()).get("data").get("items");
        assertEquals(1, eventItems.size(), "a repeated dedupe key must not create a second event");
        assertTrue(eventItems.get(0).get("payload").get("password").asText().equals("[redacted]"),
            "secret-shaped payload keys must be redacted");

        HttpResponse<String> types = get("/v1/api/orgs/" + orgId + "/events/types?q=test.",
            Map.of("Cookie", cookie()));
        assertEquals(200, types.statusCode(), types.body());
        JsonNode typeOptions = JSON.readTree(types.body()).get("data");
        assertTrue(typeOptions.toString().contains("\"test.alert\""),
            "the policy editor typeahead must see the ingested type, got " + typeOptions);

        HttpResponse<String> alerts = get("/v1/api/orgs/" + orgId + "/alerts",
            Map.of("Cookie", cookie()));
        assertEquals(200, alerts.statusCode(), alerts.body());
        assertEquals(1, JSON.readTree(alerts.body()).get("data").size());

        HttpResponse<String> deliveries = get("/v1/api/orgs/" + orgId + "/deliveries",
            Map.of("Cookie", cookie()));
        assertEquals(200, deliveries.statusCode(), deliveries.body());
        JsonNode deliveryPage = JSON.readTree(deliveries.body()).get("data");
        JsonNode deliveryRows = deliveryPage.get("items");
        assertEquals(1, deliveryRows.size());
        assertEquals(0, deliveryPage.get("page").asInt());
        assertEquals(20, deliveryPage.get("size").asInt(), "the default page size is 20 rows");
        assertTrue(deliveryPage.get("total").asInt() >= 1);
        assertEquals("PENDING", deliveryRows.get(0).get("status").asText());
    }

    @Test
    @DisplayName("another organization cannot read this organization's events")
    void crossOrganizationAccessIsRejected() throws Exception {
        String owner = "event-owner-" + System.nanoTime() + "@tapstate.io";
        signupAndLogin(owner, "StrongPass123!");
        String orgId = orgsApi.create(organizationRequest("Owner Org")).getData().getId();

        String event = """
            {"orgId":"%s","source":"external","type":"test.alert","severity":"INFO",
             "subject":"owner only","dedupeKey":"dedupe-owner"}
            """.formatted(orgId);
        assertEquals(200, post("/v1/api/internal/events", event,
            Map.of("X-Event-Token", "it-event-token")).statusCode());

        String intruder = "event-intruder-" + System.nanoTime() + "@tapstate.io";
        signupAndLogin(intruder, "StrongPass123!");
        HttpResponse<String> response = get("/v1/api/orgs/" + orgId + "/events",
            Map.of("Cookie", cookie()));
        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    @DisplayName("a runtime Cluster pushes events with its SDK token and they reach the alert pipeline")
    void clusterEventPushIsScopedAndAlerts() throws Exception {
        String email = "cluster-events-" + System.nanoTime() + "@tapstate.io";
        signupAndLogin(email, "StrongPass123!");
        String orgId = orgsApi.create(organizationRequest("Cluster Events IT")).getData().getId();

        String token = "cluster-event-token-" + System.nanoTime();
        String clusterId = seedCluster(orgId, token);

        String policy = """
            {"name":"Task failures","enabled":true,"eventTypes":["cluster.task.*"],
             "minSeverity":"INFO","recipients":["%s"],"channel":"EMAIL","cooldownSeconds":0}
            """.formatted(email);
        HttpResponse<String> created = post("/v1/api/orgs/" + orgId + "/alert-policies", policy,
            Map.of("Cookie", cookie()));
        assertEquals(201, created.statusCode(), created.body());

        String event = """
            {"type":"cluster.task.failed","severity":"CRITICAL","subject":"Task failed",
             "dedupeKey":"task-42-failed","payload":{"taskId":"task-42","reason":"source unreachable"}}
            """;
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + token);
        HttpResponse<String> first = post("/v1/api/clusters/" + clusterId + "/events", event, bearer);
        assertEquals(200, first.statusCode(), first.body());
        HttpResponse<String> duplicate = post("/v1/api/clusters/" + clusterId + "/events", event, bearer);
        assertEquals(200, duplicate.statusCode(), duplicate.body());

        HttpResponse<String> events = get("/v1/api/orgs/" + orgId + "/events?type=cluster.task.failed",
            Map.of("Cookie", cookie()));
        assertEquals(200, events.statusCode(), events.body());
        JsonNode eventItems = JSON.readTree(events.body()).get("data").get("items");
        assertEquals(1, eventItems.size(), "a repeated dedupe key must not create a second event");
        assertEquals("cluster", eventItems.get(0).get("source").asText());
        assertEquals(clusterId, eventItems.get(0).get("payload").get("clusterId").asText());
        assertEquals("task-42", eventItems.get(0).get("payload").get("taskId").asText());

        HttpResponse<String> alerts = get("/v1/api/orgs/" + orgId + "/alerts",
            Map.of("Cookie", cookie()));
        assertEquals(200, alerts.statusCode(), alerts.body());
        JsonNode alertItems = JSON.readTree(alerts.body()).get("data");
        assertEquals(1, alertItems.size(), "the pushed event must match the policy exactly once");
        assertEquals("CRITICAL", alertItems.get(0).get("severity").asText());

        HttpResponse<String> wrongCluster = post("/v1/api/clusters/" + new ObjectId() + "/events", event, bearer);
        assertEquals(401, wrongCluster.statusCode(), wrongCluster.body());
    }

    /** Insert a READY Cluster with a known SDK token so the push endpoint can authenticate it. */
    private String seedCluster(String orgId, String sdkToken) throws Exception {
        String output = mongoshEval("""
            const cid = new ObjectId();
            const now = new Date();
            db.clusters.insertOne({
              _id: cid,
              orgId: ObjectId('%s'),
              name: 'event-it',
              region: 'ap-southeast-1',
              state: 'READY',
              sdkStaticToken: '%s',
              createdAt: now,
              updatedAt: now,
              _class: 'io.tapstate.cloud.cluster.Cluster'
            });
            print('CLUSTER=' + cid.toHexString());
            """.formatted(orgId, sdkToken));
        return output.lines()
            .map(String::strip)
            .filter(line -> line.startsWith("CLUSTER="))
            .map(line -> line.substring("CLUSTER=".length()).strip())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "seedCluster produced no id, mongosh output:\n" + output));
    }

    private String cookie() {
        return "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue;
    }
}
