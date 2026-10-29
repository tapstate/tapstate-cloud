package io.tapstate.cloud.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.identity.InvitationService;
import io.tapstate.cloud.tawk.FeishuBotClient;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.bind.annotation.*;

/** Accepts authenticated mentions of the configured app bot for trial invitation issuance. */
@Hidden
@RestController
@RequestMapping("/v1/api/webhooks/feishu")
public class FeishuInviteController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final InvitationService invitations;
    private final FeishuBotClient bot;
    private final String encryptKey;
    private final String verificationToken;
    private final String allowedChatId;
    private final String consoleUrl;
    private ProductionTrialInviteRelayClient productionInviteRelayClient;

    @Value("${FEISHU_PROD_INVITE_SHARED_SECRET:}")
    private String productionInviteSharedSecret;

    @Value("${tapstate.cloud.iac.environment:dev}")
    private String cloudEnvironment;

    public FeishuInviteController(InvitationService invitations, FeishuBotClient bot,
            @Value("${FEISHU_EVENT_ENCRYPT_KEY:}") String encryptKey,
            @Value("${FEISHU_EVENT_VERIFICATION_TOKEN:}") String verificationToken,
            @Value("${FEISHU_INVITE_ALLOWED_CHAT_ID:}") String allowedChatId,
            @Value("${tapstate.cloud.auth.console-url:http://localhost:5173}") String consoleUrl) {
        this.invitations = invitations;
        this.bot = bot;
        this.encryptKey = encryptKey;
        this.verificationToken = verificationToken;
        this.allowedChatId = allowedChatId;
        this.consoleUrl = consoleUrl;
    }

    @Autowired
    void setProductionInviteRelayClient(ProductionTrialInviteRelayClient productionInviteRelayClient) {
        this.productionInviteRelayClient = productionInviteRelayClient;
    }

    @PostMapping
    public ResponseEntity<?> receive(@RequestBody byte[] body,
            @RequestHeader(value = "X-Lark-Request-Timestamp", defaultValue = "") String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", defaultValue = "") String nonce,
            @RequestHeader(value = "X-Lark-Signature", defaultValue = "") String signature) throws Exception {
        if (encryptKey.isBlank() || verificationToken.isBlank()) {
            return ResponseEntity.status(503).build();
        }
        JsonNode event;
        try {
            if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 300
                    || nonce.isBlank() || !validSignature(body, timestamp, nonce, signature)) {
                return ResponseEntity.status(403).build();
            }
            event = JSON.readTree(body);
            if (event.has("encrypt")) {
                byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptKey.getBytes(StandardCharsets.UTF_8));
                byte[] encrypted = Base64.getDecoder().decode(event.path("encrypt").asText());
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(encrypted, 0, 16)));
                event = JSON.readTree(cipher.doFinal(Arrays.copyOfRange(encrypted, 16, encrypted.length)));
            }
        } catch (IllegalArgumentException | java.security.GeneralSecurityException | java.io.IOException invalid) {
            return ResponseEntity.badRequest().build();
        }
        String token = event.path("header").path("token").asText(event.path("token").asText());
        if (!MessageDigest.isEqual(verificationToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).build();
        }
        if (event.path("type").asText().equals("url_verification")) {
            return ResponseEntity.ok(Map.of("challenge", event.path("challenge").asText()));
        }
        return handleVerifiedEvent(event);
    }

    @PostMapping("/production-invite")
    public ResponseEntity<?> createProductionInvite(
            @RequestBody ProductionTrialInviteRelayClient.ProductionInviteRequest request,
            @RequestHeader(value = "X-Tapstate-Invite-Timestamp", defaultValue = "") String timestamp,
            @RequestHeader(value = "X-Tapstate-Invite-Signature", defaultValue = "") String signature) throws Exception {
        if (!"prod".equalsIgnoreCase(cloudEnvironment)) {
            return ResponseEntity.notFound().build();
        }
        if (productionInviteSharedSecret == null || productionInviteSharedSecret.isBlank()
                || allowedChatId.isBlank()) {
            return ResponseEntity.status(503).build();
        }
        if (request == null || request.chatId() == null || request.messageId() == null
                || request.chatId().isBlank() || request.messageId().isBlank()
                || request.messageId().length() > 128 || !request.messageId().startsWith("om_")) {
            return ResponseEntity.badRequest().build();
        }
        if (!allowedChatId.equals(request.chatId())) {
            return ResponseEntity.status(403).build();
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException invalidTimestamp) {
            return ResponseEntity.status(403).build();
        }
        long now = Instant.now().getEpochSecond();
        if (issuedAt < now - 300 || issuedAt > now + 300) {
            return ResponseEntity.status(403).build();
        }
        String expectedSignature = ProductionTrialInviteRelayClient.sign(
            productionInviteSharedSecret, issuedAt, request.chatId(), request.messageId());
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            return ResponseEntity.status(403).build();
        }

        String token = stableProductionToken(request.messageId());
        InvitationService.IssuedInvitation issued = invitations.createBotTrialCode(token);
        String url = consoleUrl.replaceAll("/$", "") + "/auth#invite=" + issued.token();
        return ResponseEntity.ok(new ProductionTrialInviteRelayClient.ProductionTrialInvite(
            url, issued.invitation().expiresAt()));
    }

    ResponseEntity<?> handleVerifiedEvent(JsonNode event) throws Exception {
        if (!event.path("header").path("event_type").asText().equals("im.message.receive_v1")) {
            return ResponseEntity.ok(Map.of());
        }
        JsonNode payload = event.path("event");
        JsonNode sender = payload.path("sender");
        JsonNode message = payload.path("message");
        if (allowedChatId.isBlank()
                || !sender.path("sender_type").asText().equals("user")
                || !message.path("chat_id").asText().equals(allowedChatId)
                || !message.path("message_type").asText().equals("text")
                || message.path("message_id").asText().isBlank()
                || message.path("chat_id").asText().isBlank()
                || !message.path("mentions").isArray() || message.path("mentions").isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        String command;
        try {
            command = JSON.readTree(message.path("content").asText()).path("text").asText();
        } catch (java.io.IOException invalid) {
            return ResponseEntity.ok(Map.of());
        }
        for (JsonNode mention : message.path("mentions")) {
            String key = mention.path("key").asText();
            if (!key.isBlank()) command = command.replace(key, "");
        }
        String normalizedCommand = command.trim();
        boolean productionCommand = normalizedCommand.equals("\u751f\u4ea7\u73af\u5883\u9080\u8bf7\u7801\u7b7e\u53d1")
            || normalizedCommand.equals("\u751f\u6210\u751f\u4ea7\u73af\u5883\u9080\u8bf7\u94fe\u63a5");
        if (!productionCommand && !normalizedCommand.equals("\u751f\u6210\u9080\u8bf7\u94fe\u63a5")) {
            return ResponseEntity.ok(Map.of());
        }
        String messageId = message.path("message_id").asText();
        String chatId = message.path("chat_id").asText();
        String url;
        Instant expiresAt;
        if (productionCommand) {
            if (productionInviteRelayClient == null) {
                return ResponseEntity.status(503).build();
            }
            ProductionTrialInviteRelayClient.ProductionTrialInvite issued;
            try {
                issued = productionInviteRelayClient.issue(chatId, messageId);
            } catch (IllegalArgumentException | IllegalStateException | RestClientException unavailable) {
                return ResponseEntity.status(503).build();
            }
            url = issued.url();
            expiresAt = issued.expiresAt();
        } else {
            InvitationService.IssuedInvitation issued = invitations.createBotTrialCode(stableToken(messageId));
            url = consoleUrl.replaceAll("/$", "") + "/auth#invite=" + issued.token();
            expiresAt = issued.invitation().expiresAt();
        }
        var result = bot.sendToChat(message.path("chat_id").asText(), "trial-invite", messageId,
            (productionCommand ? "Cloud production trial invitation" : "Cloud dev trial invitation")
                + " (single use)\n" + url + "\nExpires: " + expiresAt);
        return result.delivered() ? ResponseEntity.ok(Map.of()) : ResponseEntity.status(503).build();
    }

    private boolean validSignature(byte[] body, String timestamp, String nonce, String signature) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((timestamp + nonce + encryptKey).getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(digest.digest(body), HexFormat.of().parseHex(signature));
    }

    private String stableToken(String messageId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(encryptKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(("trial-invite:" + consoleUrl + ":" + allowedChatId + ":" + messageId).getBytes(StandardCharsets.UTF_8));
        return invitationCode(bytes);
    }

    private String stableProductionToken(String messageId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(productionInviteSharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(("trial-invite:production:" + consoleUrl + ":" + allowedChatId + ":" + messageId)
            .getBytes(StandardCharsets.UTF_8));
        return invitationCode(bytes);
    }

    private static String invitationCode(byte[] bytes) {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 12; i++) token.append(ALPHABET.charAt(bytes[i] & 31));
        return token.toString();
    }
}
