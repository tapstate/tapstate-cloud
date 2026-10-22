package io.tapstate.cloud.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        String orgId = orgsApi.create(Map.of(
            "name", "Event Center IT",
            "slug", "event-center-" + System.nanoTime())).getData().getId();

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

        HttpResponse<String> alerts = get("/v1/api/orgs/" + orgId + "/alerts",
            Map.of("Cookie", cookie()));
        assertEquals(200, alerts.statusCode(), alerts.body());
        assertEquals(1, JSON.readTree(alerts.body()).get("data").size());

        HttpResponse<String> deliveries = get("/v1/api/orgs/" + orgId + "/deliveries",
            Map.of("Cookie", cookie()));
        assertEquals(200, deliveries.statusCode(), deliveries.body());
        JsonNode deliveryRows = JSON.readTree(deliveries.body()).get("data");
        assertEquals(1, deliveryRows.size());
        assertEquals("PENDING", deliveryRows.get(0).get("status").asText());
    }

    @Test
    @DisplayName("another organization cannot read this organization's events")
    void crossOrganizationAccessIsRejected() throws Exception {
        String owner = "event-owner-" + System.nanoTime() + "@tapstate.io";
        signupAndLogin(owner, "StrongPass123!");
        String orgId = orgsApi.create(Map.of(
            "name", "Owner Org",
            "slug", "owner-" + System.nanoTime())).getData().getId();

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

    private String cookie() {
        return "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue;
    }
}
