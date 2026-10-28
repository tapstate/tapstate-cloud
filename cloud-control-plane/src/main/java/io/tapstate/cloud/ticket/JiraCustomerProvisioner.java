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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates or reuses a Jira customer with separately configured OAuth service-account credentials.
 * Customer provisioning is disabled by default and requires explicit acknowledgement of site-wide administrator access.
 */
@Component
@Slf4j
public class JiraCustomerProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 50;

    private final boolean enabled;
    private final boolean siteAdminRiskAcknowledged;
    private final String cloudId;
    private final String serviceDeskId;
    private final String clientId;
    private final String clientSecret;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final JiraAccessTokenProvider accessTokens;

    @Autowired
    public JiraCustomerProvisioner(
            @Value("${tapstate.cloud.jira.customer-provisioning.enabled:false}") boolean enabled,
            @Value("${tapstate.cloud.jira.customer-provisioning.site-admin-risk-acknowledged:false}") boolean siteAdminRiskAcknowledged,
            @Value("${tapstate.cloud.jira.cloud-id:}") String cloudId,
            @Value("${tapstate.cloud.jira.service-desk-id:}") String serviceDeskId,
            @Value("${tapstate.cloud.jira.customer-provisioning.oauth.client-id:}") String clientId,
            @Value("${tapstate.cloud.jira.customer-provisioning.oauth.client-secret:}") String clientSecret,
            @Value("${tapstate.cloud.jira.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${tapstate.cloud.jira.request-timeout-seconds:10}") long requestTimeoutSeconds,
            JiraAccessTokenProvider accessTokens) {
        this(enabled, siteAdminRiskAcknowledged, cloudId, serviceDeskId, clientId, clientSecret, requestTimeoutSeconds,
            createHttpClient(connectTimeoutSeconds), accessTokens);
    }

    JiraCustomerProvisioner(boolean enabled, boolean siteAdminRiskAcknowledged, String cloudId, String serviceDeskId,
            String clientId, String clientSecret, long requestTimeoutSeconds, HttpClient httpClient,
            JiraAccessTokenProvider accessTokens) {
        this.enabled = enabled;
        this.siteAdminRiskAcknowledged = siteAdminRiskAcknowledged;
        this.cloudId = cloudId == null ? "" : cloudId.trim();
        this.serviceDeskId = serviceDeskId == null ? "" : serviceDeskId.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        long requestSeconds = Math.max(1, Math.min(requestTimeoutSeconds, 30));
        this.requestTimeout = Duration.ofSeconds(requestSeconds);
        this.httpClient = httpClient;
        this.accessTokens = accessTokens;
    }

    private static HttpClient createHttpClient(long connectTimeoutSeconds) {
        long connectSeconds = Math.max(1, Math.min(connectTimeoutSeconds, 15));
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConfigured() {
        return enabled && siteAdminRiskAcknowledged && !cloudId.isBlank() && !serviceDeskId.isBlank()
            && !clientId.isBlank() && !clientSecret.isBlank();
    }

    public void createOrAddCustomer(String email, String displayName) {
        requireConfiguration();
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            throw business(AppErrorCode.TICKET_CUSTOMER_NOT_READY, 409);
        }
        List<CustomerIdentity> matches = findExactUsers(normalizedEmail);
        String accountId;
        if (matches.size() > 1) {
            throw business(AppErrorCode.TICKET_CUSTOMER_NOT_READY, 409);
        } else if (matches.size() == 1) {
            accountId = matches.getFirst().accountId();
        } else {
            try {
                accountId = createCustomer(normalizedEmail, displayName);
            } catch (CustomerAlreadyExistsException e) {
                List<CustomerIdentity> createdByAnotherRequest = findExactUsers(normalizedEmail);
                if (createdByAnotherRequest.size() != 1) {
                    throw business(AppErrorCode.TICKET_CUSTOMER_NOT_READY, 409);
                }
                accountId = createdByAnotherRequest.getFirst().accountId();
            }
        }
        addCustomerToServiceDesk(accountId);
    }

    private List<CustomerIdentity> findExactUsers(String email) {
        String path = "/api/3/user/search?query=" + encode(email) + "&startAt=0&maxResults=" + MAX_RESULTS;
        JsonNode response = sendJson("GET", path, null, false);
        if (!response.isArray()) throw integrationFailure("user search response format");

        List<CustomerIdentity> matches = new ArrayList<>();
        for (JsonNode value : response) {
            String returnedEmail = value.path("emailAddress").asText(null);
            String accountId = value.path("accountId").asText(null);
            if (value.has("active") && !value.path("active").asBoolean()) continue;
            if (returnedEmail != null && normalizeEmail(returnedEmail).equals(email)
                    && accountId != null && !accountId.isBlank()) {
                matches.add(new CustomerIdentity(accountId));
            }
        }
        return List.copyOf(matches);
    }

    private String createCustomer(String email, String displayName) {
        int separator = email.indexOf('@');
        String fallbackName = separator > 0 ? email.substring(0, separator) : email;
        String safeName = displayName == null || displayName.isBlank() ? fallbackName : displayName.trim();
        if (safeName.length() > 255) safeName = safeName.substring(0, 255);
        JsonNode response = sendJson("POST", "/servicedeskapi/customer?strictConflictStatusCode=true",
            Map.of("email", email, "displayName", safeName), true);
        String responseEmail = response.path("emailAddress").asText(null);
        String accountId = response.path("accountId").asText(null);
        if (responseEmail == null || !normalizeEmail(responseEmail).equals(email)
                || accountId == null || accountId.isBlank()) {
            throw business(AppErrorCode.TICKET_CUSTOMER_NOT_READY, 409);
        }
        return accountId;
    }

    private void addCustomerToServiceDesk(String accountId) {
        sendJson("POST", "/servicedeskapi/servicedesk/" + encode(serviceDeskId) + "/customer",
            Map.of("accountIds", List.of(accountId)), false);
    }

    private JsonNode sendJson(String method, String path, Object payload, boolean createCustomerCall) {
        requireConfiguration();
        try {
            String body = payload == null ? null : MAPPER.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", authorization());
            if (body != null) {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (createCustomerCall && status == 409) throw new CustomerAlreadyExistsException();
            if (status < 200 || status >= 300) {
                log.warn("JSM customer provisioning failed: opId={} method={} path={} status={}",
                    io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path), status);
                throw integrationFailure("HTTP " + status);
            }
            String responseBody = response.body();
            if (responseBody == null || responseBody.isBlank()) return MAPPER.createObjectNode();
            return MAPPER.readTree(responseBody);
        } catch (CustomerAlreadyExistsException e) {
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("JSM customer provisioning timed out: opId={} method={} path={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path));
            throw integrationFailure("request timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("JSM customer provisioning interrupted: opId={} method={} path={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path));
            throw integrationFailure("request interrupted");
        } catch (IOException e) {
            log.warn("JSM customer provisioning I/O failure: opId={} method={} path={} exception={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), method, pathWithoutQuery(path), e.getClass().getSimpleName());
            throw integrationFailure("request failed");
        }
    }

    private URI uri(String path) {
        return URI.create("https://api.atlassian.com/ex/jira/" + encode(cloudId) + "/rest" + path);
    }

    private String authorization() {
        return "Bearer " + accessTokens.getAccessToken(clientId, clientSecret);
    }

    private void requireConfiguration() {
        if (!isConfigured()) throw business(AppErrorCode.TICKET_NOT_CONFIGURED, 503);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String pathWithoutQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static BusinessException integrationFailure(String diagnostic) {
        log.warn("JSM customer provisioning integration failure: opId={} reason={}",
            io.tapstate.cloud.common.api.OpIdHolder.get(), diagnostic);
        return business(AppErrorCode.TICKET_INTEGRATION_FAILED, 502);
    }

    private static BusinessException business(AppErrorCode code, int status) {
        return new BusinessException(code, status);
    }

    private record CustomerIdentity(String accountId) {}

    private static final class CustomerAlreadyExistsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CustomerAlreadyExistsException() {
            super("Customer already exists");
        }
    }
}
