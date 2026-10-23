package io.tapstate.cloud.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-environment event-center check. The automated part proves the deployed
 * API path and durable alert/delivery rows; the human run still opens the real
 * mailbox, which is the plan's live verification.
 * <p>
 * Requires {@code CP_EVENT_INGEST_TOKEN} in addition to {@link RealEnvIT}'s
 * bindings. When it is absent the test skips instead of silently passing.
 */
class RealEnvAlertNotificationIT extends RealEnvIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("deployed control plane accepts an event, matches a policy and records delivery")
    void deployedEventCenterRecordsAlertAndDelivery() throws Exception {
        String ingestToken = System.getenv("CP_EVENT_INGEST_TOKEN");
        assumeTrue(ingestToken != null && !ingestToken.isBlank(),
            "CP_EVENT_INGEST_TOKEN is not set — skipping real-env event-center test");
        loginAsConfiguredUser();
        String orgId = resolveOrgId();
        String type = "cp.real.env." + System.nanoTime();

        String policy = """
            {"name":"Real environment verification","enabled":true,"eventTypes":["%s"],
             "minSeverity":"INFO","recipients":["%s"],"channel":"EMAIL","cooldownSeconds":60}
            """.formatted(type, USER_EMAIL);
        HttpResponse<String> policyResponse = post("/v1/api/orgs/" + orgId + "/alert-policies", policy);
        assertEquals(201, policyResponse.statusCode(), policyResponse.body());

        String event = """
            {"orgId":"%s","source":"external","type":"%s","severity":"WARNING",
             "subject":"Real environment event-center verification","actor":"%s",
             "dedupeKey":"real-env-%s"}
            """.formatted(orgId, type, USER_EMAIL, System.nanoTime());
        HttpResponse<String> eventResponse = post("/v1/api/internal/events", event,
            Map.of("X-Event-Token", ingestToken));
        assertEquals(200, eventResponse.statusCode(), eventResponse.body());

        HttpResponse<String> alerts = get("/v1/api/orgs/" + orgId + "/alerts");
        assertEquals(200, alerts.statusCode(), alerts.body());
        JsonNode alertRows = JSON.readTree(alerts.body()).get("data");
        assertEquals(1, alertRows.size(), "the deployed control plane did not create exactly one alert");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Cookie", cookie())
            .GET());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return post(path, body, Map.of());
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Content-Type", "application/json")
            .header("Cookie", cookie())
            .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return send(builder);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String cookie() {
        return "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue;
    }
}
