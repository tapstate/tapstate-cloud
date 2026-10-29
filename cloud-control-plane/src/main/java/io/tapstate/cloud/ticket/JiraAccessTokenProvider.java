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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Slf4j
public class JiraAccessTokenProvider {

    private static final URI TOKEN_ENDPOINT = URI.create("https://auth.atlassian.com/oauth/token");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Clock clock;
    private final ConcurrentMap<String, CachedToken> cachedTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> refreshLocks = new ConcurrentHashMap<>();

    @Autowired
    public JiraAccessTokenProvider(
            @Value("${tapstate.cloud.jira.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${tapstate.cloud.jira.request-timeout-seconds:10}") long requestTimeoutSeconds) {
        this(createHttpClient(connectTimeoutSeconds), requestTimeoutSeconds, Clock.systemUTC());
    }

    JiraAccessTokenProvider(HttpClient httpClient, long requestTimeoutSeconds, Clock clock) {
        this.httpClient = httpClient;
        long requestSeconds = Math.max(1, Math.min(requestTimeoutSeconds, 30));
        this.requestTimeout = Duration.ofSeconds(requestSeconds);
        this.clock = clock;
    }

    private static HttpClient createHttpClient(long connectTimeoutSeconds) {
        long connectSeconds = Math.max(1, Math.min(connectTimeoutSeconds, 15));
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public String getAccessToken(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw business(AppErrorCode.TICKET_NOT_CONFIGURED, 503);
        }
        CachedToken cached = cachedTokens.get(clientId);
        if (cached != null && cached.usableAt(clock.instant())) return cached.value();

        Object lock = refreshLocks.computeIfAbsent(clientId, ignored -> new Object());
        synchronized (lock) {
            cached = cachedTokens.get(clientId);
            if (cached != null && cached.usableAt(clock.instant())) return cached.value();
            CachedToken fresh = requestToken(clientId, clientSecret);
            cachedTokens.put(clientId, fresh);
            return fresh.value();
        }
    }

    private CachedToken requestToken(String clientId, String clientSecret) {
        try {
            String form = "grant_type=client_credentials&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret);
            HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Atlassian OAuth token exchange failed: opId={} status={}",
                    io.tapstate.cloud.common.api.OpIdHolder.get(), response.statusCode());
                throw integrationFailure("OAuth HTTP " + response.statusCode());
            }
            String responseBody = response.body();
            JsonNode body = responseBody == null || responseBody.isBlank() ? null : MAPPER.readTree(responseBody);
            String accessToken = body == null ? null : body.path("access_token").asText(null);
            String tokenType = body == null ? null : body.path("token_type").asText(null);
            long expiresIn = body == null ? 0 : body.path("expires_in").asLong(0);
            if (accessToken == null || accessToken.isBlank() || !"Bearer".equalsIgnoreCase(tokenType)
                    || expiresIn < 1 || expiresIn > 86400) {
                throw integrationFailure("invalid OAuth token response");
            }
            long cacheSeconds = expiresIn <= EXPIRY_SKEW_SECONDS ? 0 : expiresIn - EXPIRY_SKEW_SECONDS;
            return new CachedToken(accessToken, clock.instant().plusSeconds(cacheSeconds));
        } catch (BusinessException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("Atlassian OAuth token exchange timed out: opId={}",
                io.tapstate.cloud.common.api.OpIdHolder.get());
            throw integrationFailure("OAuth request timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Atlassian OAuth token exchange interrupted: opId={}",
                io.tapstate.cloud.common.api.OpIdHolder.get());
            throw integrationFailure("OAuth request interrupted");
        } catch (IOException e) {
            log.warn("Atlassian OAuth token exchange I/O failure: opId={} exception={}",
                io.tapstate.cloud.common.api.OpIdHolder.get(), e.getClass().getSimpleName());
            throw integrationFailure("OAuth request failed");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static BusinessException integrationFailure(String diagnostic) {
        log.warn("Atlassian OAuth integration failure: opId={} reason={}",
            io.tapstate.cloud.common.api.OpIdHolder.get(), diagnostic);
        return business(AppErrorCode.TICKET_INTEGRATION_FAILED, 502);
    }

    private static BusinessException business(AppErrorCode code, int status) {
        return new BusinessException(code, status);
    }

    private record CachedToken(String value, Instant refreshAt) {
        private boolean usableAt(Instant now) {
            return now.isBefore(refreshAt);
        }
    }
}
