package io.tapstate.cloud.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class JiraTicketClient {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String cloudId;
    private final String serviceDeskId;
    private final String requestTypeId;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient httpClient;
    private final JiraAccessTokenProvider accessTokens;

    @Autowired
    public JiraTicketClient(
            @Value("${tapstate.cloud.jira.cloud-id:}") String cloudId,
            @Value("${tapstate.cloud.jira.service-desk-id:}") String serviceDeskId,
            @Value("${tapstate.cloud.jira.request-type-id:}") String requestTypeId,
            @Value("${tapstate.cloud.jira.oauth.client-id:}") String clientId,
            @Value("${tapstate.cloud.jira.oauth.client-secret:}") String clientSecret,
            @Value("${tapstate.cloud.jira.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${tapstate.cloud.jira.request-timeout-seconds:10}") long requestTimeoutSeconds,
            JiraAccessTokenProvider accessTokens) {
        this(cloudId, serviceDeskId, requestTypeId, clientId, clientSecret,
            createHttpClient(connectTimeoutSeconds), requestTimeoutSeconds, accessTokens);
    }

    JiraTicketClient(String cloudId, String serviceDeskId, String requestTypeId, String clientId,
            String clientSecret, HttpClient httpClient, long requestTimeoutSeconds,
            JiraAccessTokenProvider accessTokens) {
        this.cloudId = cloudId == null ? "" : cloudId.trim();
        this.serviceDeskId = serviceDeskId == null ? "" : serviceDeskId.trim();
        this.requestTypeId = requestTypeId == null ? "" : requestTypeId.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        long requestSeconds = Math.max(1, Math.min(requestTimeoutSeconds, 30));
        this.httpClient = httpClient;
        this.requestTimeout = Duration.ofSeconds(requestSeconds);
        this.accessTokens = accessTokens;
    }

    private static HttpClient createHttpClient(long connectTimeoutSeconds) {
        long connectSeconds = Math.max(1, Math.min(connectTimeoutSeconds, 15));
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    private final Duration requestTimeout;

    public String getCloseTransitionName() {
        return closeTransitionName;
    }

    @Value("${tapstate.cloud.jira.close-transition-name:Close Request}")
    private String closeTransitionName;

    public boolean isConfigured() {
        return !cloudId.isBlank() && !serviceDeskId.isBlank() && !requestTypeId.isBlank()
            && !clientId.isBlank() && !clientSecret.isBlank();
    }

    public void requireConfiguration() {
        requireConfigured();
    }

    public List<Customer> findExactCustomers(String email) {
        requireConfigured();
        String query = "query=" + encode(email);
        java.util.ArrayList<Customer> matches = new java.util.ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int start = page * PAGE_SIZE;
            JsonNode body = sendJson("GET", "/servicedesk/" + encode(serviceDeskId)
                + "/customer?" + query + "&start=" + start + "&limit=" + PAGE_SIZE, null, false);
            JsonNode values = body.path("values");
            if (!values.isArray()) throw integrationFailure("customer response format");
            for (JsonNode value : values) {
                String customerEmail = value.path("emailAddress").asText(null);
                String accountId = value.path("accountId").asText(null);
                if (customerEmail != null && customerEmail.equalsIgnoreCase(email)
                        && accountId != null && !accountId.isBlank()) {
                    matches.add(new Customer(customerEmail, accountId));
                }
            }
            if (body.path("isLastPage").asBoolean(false)) return List.copyOf(matches);
            if (values.isEmpty()) return List.copyOf(matches);
        }
        throw integrationFailure("customer directory pagination limit");
    }

    public JsonNode createRequest(String accountId, String summary, String description) {
        Map<String, Object> payload = Map.of(
            "serviceDeskId", serviceDeskId,
            "requestTypeId", requestTypeId,
            "raiseOnBehalfOf", accountId,
            "requestFieldValues", Map.of("summary", summary, "description", description)
        );
        return sendJson("POST", "/request", payload, true);
    }

    public JsonNode getRequest(String issueKey) {
        return sendJson("GET", "/request/" + encode(issueKey), null, false);
    }

    public List<Transition> getTransitions(String issueKey) {
        java.util.ArrayList<Transition> transitions = new java.util.ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int start = page * PAGE_SIZE;
            JsonNode body = sendJson("GET", "/request/" + encode(issueKey)
                + "/transition?start=" + start + "&limit=" + PAGE_SIZE, null, false);
            JsonNode values = body.path("values");
            if (!values.isArray()) throw integrationFailure("transition response format");
            for (JsonNode value : values) {
                String id = value.path("id").asText(null);
                String name = value.path("name").asText(null);
                if (id != null && name != null) transitions.add(new Transition(id, name));
            }
            if (body.path("isLastPage").asBoolean(false) || values.isEmpty()
                    || values.size() < PAGE_SIZE) {
                return List.copyOf(transitions);
            }
        }
        throw integrationFailure("transition pagination limit");
    }

    public void transition(String issueKey, String transitionId) {
        sendJson("POST", "/request/" + encode(issueKey) + "/transition",
            Map.of("id", transitionId), false);
    }

    private JsonNode sendJson(String method, String path, Object payload, boolean createCall) {
        requireConfigured();
        try {
            String body = payload == null ? null : MAPPER.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", authorization());
            if (path.startsWith("/servicedesk/") && path.contains("/customer?")) {
                builder.header("X-ExperimentalApi", "opt-in");
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("JSM request failed: opId={} method={} path={} status={}",
                    io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path), status);
                if (createCall && (status == 400 || status == 422)) {
                    throw business(AppErrorCode.TICKET_REQUEST_REJECTED, 400);
                }
                if (createCall && (status == 408 || status >= 500)) {
                    throw business(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503);
                }
                throw integrationFailure("HTTP " + status);
            }
            String responseBody = response.body();
            if (responseBody == null || responseBody.isBlank()) return MAPPER.createObjectNode();
            return MAPPER.readTree(responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("JSM request timed out: opId={} method={} path={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path));
            if (createCall) throw business(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503);
            throw integrationFailure("request timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("JSM request interrupted: opId={} method={} path={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path));
            if (createCall) throw business(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503);
            throw integrationFailure("request interrupted");
        } catch (IOException e) {
            log.warn("JSM I/O failure: opId={} method={} path={} exception={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path), e.getClass().getSimpleName());
            if (createCall) throw business(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503);
            throw integrationFailure("request failed");
        }
    }

    private URI uri(String path) {
        return URI.create("https://api.atlassian.com/ex/jira/" + encode(cloudId)
            + "/rest/servicedeskapi" + path);
    }

    private String authorization() {
        return "Bearer " + accessTokens.getAccessToken(clientId, clientSecret);
    }

    private void requireConfigured() {
        if (!isConfigured()) throw business(AppErrorCode.TICKET_NOT_CONFIGURED, 503);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String pathWithoutQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static BusinessException integrationFailure(String diagnostic) {
        log.warn("JSM integration failure: opId={} reason={}",
            io.tapstate.cloud.common.api.OpIdHolder.get(), diagnostic);
        return business(AppErrorCode.TICKET_INTEGRATION_FAILED, 502);
    }

    private static BusinessException business(AppErrorCode code, int status) {
        return new BusinessException(code, status);
    }

    public record Customer(String email, String accountId) {}
    public record Transition(String id, String name) {}
}
