package io.tapstate.cloud.feishu;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sends a signed production invitation request to the production Cloud Control Plane. */
@Component
public class ProductionTrialInviteRelayClient {
    private static final String TIMESTAMP_HEADER = "X-Tapstate-Invite-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Tapstate-Invite-Signature";

    private final RestClient restClient;
    private final String productionEndpoint;
    private final String sharedSecret;

    @Autowired
    public ProductionTrialInviteRelayClient(
            RestClient.Builder restClientBuilder,
            @Value("${FEISHU_PRODUCTION_INVITE_API_URL:}") String productionEndpoint,
            @Value("${FEISHU_PROD_INVITE_SHARED_SECRET:}") String sharedSecret) {
        this(buildRestClient(restClientBuilder), productionEndpoint, sharedSecret);
    }

    ProductionTrialInviteRelayClient(RestClient restClient, String productionEndpoint, String sharedSecret) {
        this.restClient = restClient;
        this.productionEndpoint = productionEndpoint == null ? "" : productionEndpoint.trim();
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    }

    public ProductionTrialInvite issue(String chatId, String messageId) {
        URI endpoint = validateEndpoint();
        long timestamp = Instant.now().getEpochSecond();
        String signature = sign(sharedSecret, timestamp, chatId, messageId);
        ProductionTrialInvite result = restClient.post()
            .uri(endpoint)
            .header(TIMESTAMP_HEADER, Long.toString(timestamp))
            .header(SIGNATURE_HEADER, signature)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ProductionInviteRequest(chatId, messageId))
            .retrieve()
            .body(ProductionTrialInvite.class);
        if (result == null || result.url() == null || result.url().isBlank() || result.expiresAt() == null) {
            throw new IllegalStateException("Production invite relay returned an incomplete response");
        }
        return result;
    }

    static String sign(String secret, long timestamp, String chatId, String messageId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload(timestamp, chatId, messageId)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    static byte[] payload(long timestamp, String chatId, String messageId) {
        return ("production-invite\n" + timestamp + "\n" + chatId + "\n" + messageId)
            .getBytes(StandardCharsets.UTF_8);
    }

    private URI validateEndpoint() {
        if (productionEndpoint.isBlank() || sharedSecret.isBlank()) {
            throw new IllegalStateException("Production invite relay is not configured");
        }
        URI endpoint = URI.create(productionEndpoint);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("Production invite relay must use an HTTPS URL without credentials");
        }
        return endpoint;
    }

    private static RestClient buildRestClient(RestClient.Builder builder) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        return builder.requestFactory(requestFactory).build();
    }

    public record ProductionInviteRequest(String chatId, String messageId) {}

    public record ProductionTrialInvite(String url, Instant expiresAt) {}
}
