package io.tapstate.cloud.tawk;

import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/** Sends short text alerts to the configured Feishu custom bot. */
@Component
@Slf4j
public class FeishuBotClient {

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String webhookUrl;
    private final String signingSecret;

    public FeishuBotClient(
            RestClient.Builder restClientBuilder,
            @Value("${tapstate.cloud.tawk.feishu.webhook-url:}") String webhookUrl,
            @Value("${tapstate.cloud.tawk.feishu.signing-secret:}") String signingSecret) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.webhookUrl = webhookUrl;
        this.signingSecret = signingSecret;
    }

    DeliveryResult send(String text) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return DeliveryResult.failed("recipient_not_configured");
        }
        if (signingSecret == null || signingSecret.isBlank()) {
            return DeliveryResult.failed("signing_secret_not_configured");
        }

        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("msg_type", "text");
        requestBody.put("content", Map.of("text", text));
        try {
            requestBody.put("timestamp", Long.toString(timestamp));
            requestBody.put("sign", sign(timestamp, signingSecret));
        } catch (Exception e) {
            log.warn("Feishu message signing failed: {}", e.getClass().getSimpleName());
            return DeliveryResult.failed("signing_failed");
        }

        try {
            Map<String, Object> response = restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(RESPONSE_TYPE);
            if (isSuccess(response)) {
                return DeliveryResult.success();
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

    private static boolean isSuccess(Map<String, Object> response) {
        if (response == null) {
            return false;
        }
        Object code = response.get("code");
        if (code instanceof Number number) {
            return number.intValue() == 0;
        }
        Object statusCode = response.get("StatusCode");
        return statusCode instanceof Number number && number.intValue() == 0;
    }

    private static String sign(long timestamp, String secret) throws Exception {
        String key = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
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
