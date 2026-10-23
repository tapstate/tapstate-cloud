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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
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
    private final String operatorId;
    private final String issuerEmail;
    private final String consoleUrl;

    public FeishuInviteController(InvitationService invitations, FeishuBotClient bot,
            @Value("${FEISHU_EVENT_ENCRYPT_KEY:}") String encryptKey,
            @Value("${FEISHU_EVENT_VERIFICATION_TOKEN:}") String verificationToken,
            @Value("${FEISHU_INVITE_OPERATOR_OPEN_ID:}") String operatorId,
            @Value("${tapstate.cloud.auth.trial-issuer-email:}") String issuerEmail,
            @Value("${tapstate.cloud.auth.console-url:http://localhost:5173}") String consoleUrl) {
        this.invitations = invitations;
        this.bot = bot;
        this.encryptKey = encryptKey;
        this.verificationToken = verificationToken;
        this.operatorId = operatorId;
        this.issuerEmail = issuerEmail;
        this.consoleUrl = consoleUrl;
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
        if (!event.path("header").path("event_type").asText().equals("im.message.receive_v1")) {
            return ResponseEntity.ok(Map.of());
        }
        JsonNode payload = event.path("event");
        JsonNode sender = payload.path("sender");
        JsonNode message = payload.path("message");
        if (operatorId.isBlank() || issuerEmail.isBlank()
                || !sender.path("sender_type").asText().equals("user")
                || !sender.path("sender_id").path("open_id").asText().equals(operatorId)
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
        if (!command.trim().equals("\u751f\u6210\u9080\u8bf7\u94fe\u63a5")) {
            return ResponseEntity.ok(Map.of());
        }
        String messageId = message.path("message_id").asText();
        InvitationService.IssuedInvitation issued = invitations.createBotTrialCode(issuerEmail, stableToken(messageId));
        String url = consoleUrl.replaceAll("/$", "") + "/auth#invite=" + issued.token();
        var result = bot.sendToChat(message.path("chat_id").asText(), "trial-invite", messageId,
            "Cloud trial invitation (single use)\n" + url + "\nExpires: " + issued.invitation().expiresAt());
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
        byte[] bytes = mac.doFinal(("trial-invite:" + consoleUrl + ":" + operatorId + ":" + messageId).getBytes(StandardCharsets.UTF_8));
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 12; i++) token.append(ALPHABET.charAt(bytes[i] & 31));
        return token.toString();
    }
}
