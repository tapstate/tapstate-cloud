package io.tapstate.cloud.tawk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Sends short text alerts through the configured Feishu application bot. */
@Component
@Slf4j
public class FeishuBotClient {

    private static final String TOKEN_ENDPOINT = "https://accounts.feishu.cn/oauth/v3/token";
    private static final String MESSAGE_ENDPOINT = "https://open.feishu.cn/open-apis/im/v1/messages"
        + "?receive_id_type=chat_id";
    private static final long TOKEN_REFRESH_SKEW_SECONDS = 60;
    private static final String IDEMPOTENCY_KEY_PREFIX = "tawk-";
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 50;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String appId;
    private final String appSecret;
    private final String chatId;
    private final String tokenEndpoint;
    private final String messageEndpoint;
    private volatile CachedAccessToken cachedAccessToken;

    @Autowired
    public FeishuBotClient(
            RestClient.Builder restClientBuilder,
            @Value("${tapstate.cloud.tawk.feishu.app-id:}") String appId,
            @Value("${tapstate.cloud.tawk.feishu.app-secret:}") String appSecret,
            @Value("${tapstate.cloud.tawk.feishu.chat-id:}") String chatId) {
        this(buildRestClient(restClientBuilder), appId, appSecret, chatId, TOKEN_ENDPOINT, MESSAGE_ENDPOINT);
    }

    FeishuBotClient(
            RestClient restClient,
            String appId,
            String appSecret,
            String chatId,
            String tokenEndpoint,
            String messageEndpoint) {
        this.restClient = restClient;
        this.appId = appId;
        this.appSecret = appSecret;
        this.chatId = chatId;
        this.tokenEndpoint = tokenEndpoint;
        this.messageEndpoint = messageEndpoint;
    }

    private static RestClient buildRestClient(RestClient.Builder restClientBuilder) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    DeliveryResult send(String propertyId, String eventId, String text) {
        if (isBlank(appId) || isBlank(appSecret) || isBlank(chatId)) {
            return DeliveryResult.failed("application_bot_not_configured");
        }
        if (isBlank(propertyId) || isBlank(eventId)) {
            return DeliveryResult.failed("event_identity_missing");
        }

        TokenResult tokenResult = getAccessToken();
        if (tokenResult.failureCategory() != null) {
            return DeliveryResult.failed(tokenResult.failureCategory());
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("receive_id", chatId);
        requestBody.put("msg_type", "text");
        requestBody.put("uuid", idempotencyKey(chatId, propertyId, eventId));
        try {
            requestBody.put("content", OBJECT_MAPPER.writeValueAsString(Map.of("text", text)));
        } catch (JsonProcessingException e) {
            log.warn("Feishu message serialization failed: {}", e.getClass().getSimpleName());
            return DeliveryResult.failed("message_serialization_failed");
        }

        try {
            Map<String, Object> response = restClient.post()
                .uri(messageEndpoint)
                .header("Authorization", "Bearer " + tokenResult.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(RESPONSE_TYPE);
            if (isSuccess(response)) {
                return DeliveryResult.success();
            }
            if (hasErrorCode(response, 60012)) {
                return DeliveryResult.success();
            }
            if (hasErrorCode(response, 99991663)) {
                cachedAccessToken = null;
                return DeliveryResult.failed("access_token_rejected");
            }
            return DeliveryResult.failed("feishu_rejected");
        } catch (RestClientResponseException e) {
            log.warn("Feishu message delivery returned HTTP {}", e.getStatusCode().value());
            return DeliveryResult.failed("http_" + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.warn("Feishu message delivery failed: {}", e.getClass().getSimpleName());
            return DeliveryResult.failed("network_error");
        } catch (RuntimeException e) {
            log.warn("Feishu message delivery failed: {}", e.getClass().getSimpleName());
            return DeliveryResult.failed("client_error");
        }
    }

    private TokenResult getAccessToken() {
        Instant now = Instant.now();
        CachedAccessToken cached = cachedAccessToken;
        if (isReusable(cached, now)) {
            return TokenResult.success(cached.value());
        }

        synchronized (this) {
            now = Instant.now();
            cached = cachedAccessToken;
            if (isReusable(cached, now)) {
                return TokenResult.success(cached.value());
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("grant_type", "client_credentials");
            requestBody.put("client_id", appId);
            requestBody.put("client_secret", appSecret);
            try {
                Map<String, Object> response = restClient.post()
                    .uri(tokenEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(RESPONSE_TYPE);
                if (!isSuccess(response)) {
                    return TokenResult.failed("token_rejected");
                }
                Object token = response.get("access_token");
                Object expiresIn = response.get("expires_in");
                if (!(token instanceof String accessToken) || accessToken.isBlank()
                        || !(expiresIn instanceof Number expires) || expires.longValue() <= 0) {
                    return TokenResult.failed("token_response_invalid");
                }
                long cacheSeconds = Math.max(1, expires.longValue() - TOKEN_REFRESH_SKEW_SECONDS);
                cachedAccessToken = new CachedAccessToken(accessToken, Instant.now().plusSeconds(cacheSeconds));
                return TokenResult.success(accessToken);
            } catch (RestClientResponseException e) {
                log.warn("Feishu token request returned HTTP {}", e.getStatusCode().value());
                return TokenResult.failed("token_http_" + e.getStatusCode().value());
            } catch (ResourceAccessException e) {
                log.warn("Feishu token request failed: {}", e.getClass().getSimpleName());
                return TokenResult.failed("token_network_error");
            } catch (RuntimeException e) {
                log.warn("Feishu token request failed: {}", e.getClass().getSimpleName());
                return TokenResult.failed("token_client_error");
            }
        }
    }

    private static boolean isReusable(CachedAccessToken token, Instant now) {
        return token != null && now.isBefore(token.refreshAt());
    }

    private static boolean isSuccess(Map<String, Object> response) {
        return response != null && response.get("code") instanceof Number code && code.intValue() == 0;
    }

    private static boolean hasErrorCode(Map<String, Object> response, int expected) {
        return response != null && response.get("code") instanceof Number code
            && code.intValue() == expected;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String idempotencyKey(String chatId, String propertyId, String eventId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] input = (chatId + "\n" + propertyId + "\n" + eventId)
                .getBytes(StandardCharsets.UTF_8);
            int hashLength = IDEMPOTENCY_KEY_MAX_LENGTH - IDEMPOTENCY_KEY_PREFIX.length();
            return IDEMPOTENCY_KEY_PREFIX + HexFormat.of().formatHex(digest.digest(input)).substring(0, hashLength);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record CachedAccessToken(String value, Instant refreshAt) {}

    private record TokenResult(String accessToken, String failureCategory) {
        private static TokenResult success(String token) {
            return new TokenResult(token, null);
        }

        private static TokenResult failed(String category) {
            return new TokenResult(null, category);
        }
    }

    record DeliveryResult(boolean delivered, String failureCategory) {
        static DeliveryResult success() {
            return new DeliveryResult(true, null);
        }

        static DeliveryResult failed(String category) {
            return new DeliveryResult(false, category);
        }
    }
}
