package io.tapstate.cloud.feishu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.identity.InvitationService;
import io.tapstate.cloud.tawk.FeishuBotClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

class FeishuInviteControllerTest {
    private final InvitationService invitations = mock(InvitationService.class);
    private final FeishuBotClient bot = mock(FeishuBotClient.class);
    private final FeishuInviteController controller = new FeishuInviteController(
        invitations, bot, "private-encrypt-key", "verification", "oc_source", "https://console.test");
    private final ObjectMapper json = new ObjectMapper();

    private byte[] event(String sender, String text) throws Exception {
        return json.writeValueAsBytes(Map.of("header", Map.of("token", "verification", "event_type", "im.message.receive_v1"),
            "event", Map.of("sender", Map.of("sender_type", "user", "sender_id", Map.of("open_id", sender)),
                "message", Map.of("message_id", "om_one", "chat_id", sender.equals("stranger") ? "oc_other" : "oc_source", "message_type", "text",
                    "mentions", new Object[] {Map.of("key", "@_user_1")},
                    "content", json.writeValueAsString(Map.of("text", "@_user_1 " + text))))));
    }
    private org.springframework.http.ResponseEntity<?> receive(byte[] body) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        var sha = MessageDigest.getInstance("SHA-256");
        sha.update((timestamp + "nonce" + "private-encrypt-key").getBytes(StandardCharsets.UTF_8));
        return controller.receive(body, timestamp, "nonce", HexFormat.of().formatHex(sha.digest(body)));
    }
    @Test void rejectsUnverifiedRequests() throws Exception {
        assertEquals(403, controller.receive(event("operator", "invite"), "0", "nonce", "bad").getStatusCode().value());
        verifyNoInteractions(invitations, bot);
    }
    @Test void ignoresOtherChatsAndUnrecognizedCommands() throws Exception {
        receive(event("stranger", "\u751f\u6210\u9080\u8bf7\u94fe\u63a5"));
        receive(event("operator", "other"));
        verifyNoInteractions(invitations, bot);
    }
    @Test void retriesUseSameTokenAndReplyToOriginatingChat() throws Exception {
        Instant expiry = Instant.now().plusSeconds(604800);
        when(invitations.createBotTrialCode(anyString())).thenAnswer(invocation ->
            new InvitationService.IssuedInvitation(new InvitationService.InvitationView("id", null, null,
                Instant.now(), expiry, "pending"), invocation.getArgument(0)));
        when(bot.sendToChat(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new FeishuBotClient.DeliveryResult(true, null));
        byte[] body = event("operator", "\u751f\u6210\u9080\u8bf7\u94fe\u63a5");
        assertEquals(200, receive(body).getStatusCode().value());
        assertEquals(200, receive(event("another-member", "\u751f\u6210\u9080\u8bf7\u94fe\u63a5")).getStatusCode().value());
        var tokens = ArgumentCaptor.forClass(String.class);
        verify(invitations, times(2)).createBotTrialCode(tokens.capture());
        assertEquals(tokens.getAllValues().get(0), tokens.getAllValues().get(1));
        assertTrue(tokens.getValue().matches("[2-9A-HJ-NP-Z]{12}"));
        verify(bot, times(2)).sendToChat(eq("oc_source"), eq("trial-invite"), eq("om_one"),
            contains("https://console.test/auth#invite=" + tokens.getValue()));
    }
    @Test void sdkEventNeedsNoIssuerEmailOrWebhookVerificationToken() throws Exception {
        var sdkEvent = com.lark.oapi.core.utils.Jsons.DEFAULT.fromJson(
            new String(event("operator", "\u751f\u6210\u9080\u8bf7\u94fe\u63a5"), StandardCharsets.UTF_8),
            com.lark.oapi.service.im.v1.model.P2MessageReceiveV1.class);
        var receiver = new FeishuInviteController(invitations, bot, "private-encrypt-key", "", "oc_source", "https://console.test");
        when(invitations.createBotTrialCode(anyString())).thenAnswer(invocation ->
            new InvitationService.IssuedInvitation(new InvitationService.InvitationView("id", null, null,
                Instant.now(), Instant.now().plusSeconds(604800), "pending"), invocation.getArgument(0)));
        when(bot.sendToChat(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new FeishuBotClient.DeliveryResult(true, null));
        assertEquals(200, receiver.handleVerifiedEvent(json.readTree(
            com.lark.oapi.core.utils.Jsons.DEFAULT.toJson(sdkEvent))).getStatusCode().value());
        verify(invitations).createBotTrialCode(anyString());
    }
    @Test void verifiesChallenge() throws Exception {
        var response = receive(json.writeValueAsBytes(Map.of("type", "url_verification", "token", "verification", "challenge", "abc")));
        assertEquals(Map.of("challenge", "abc"), response.getBody());
        verifyNoInteractions(invitations, bot);
    }
    @Test void decryptsEncryptedChallenge() throws Exception {
        byte[] clear = json.writeValueAsBytes(Map.of("type", "url_verification", "token", "verification", "challenge", "encrypted"));
        byte[] key = MessageDigest.getInstance("SHA-256").digest("private-encrypt-key".getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        var cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
            new javax.crypto.spec.IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(clear);
        byte[] envelope = new byte[16 + encrypted.length];
        System.arraycopy(encrypted, 0, envelope, 16, encrypted.length);
        var response = receive(json.writeValueAsBytes(Map.of("encrypt", java.util.Base64.getEncoder().encodeToString(envelope))));
        assertEquals(Map.of("challenge", "encrypted"), response.getBody());
    }
    @Test void rejectsWrongVerificationToken() throws Exception {
        assertEquals(403, receive(json.writeValueAsBytes(Map.of("type", "url_verification", "token", "wrong", "challenge", "abc"))).getStatusCode().value());
        verifyNoInteractions(invitations, bot);
    }
    @Test void returnsRetryableFailureWhenDeliveryFails() throws Exception {
        when(invitations.createBotTrialCode(anyString())).thenAnswer(invocation ->
            new InvitationService.IssuedInvitation(new InvitationService.InvitationView("id", null, null,
                Instant.now(), Instant.now().plusSeconds(604800), "pending"), invocation.getArgument(0)));
        when(bot.sendToChat(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new FeishuBotClient.DeliveryResult(false, "network_error"));
        assertEquals(503, receive(event("operator", "\u751f\u6210\u9080\u8bf7\u94fe\u63a5")).getStatusCode().value());
    }

    @Test void productionRelayCannotIssueFromTheDevEnvironment() throws Exception {
        setProductionSecret("shared-production-relay-secret");
        ReflectionTestUtils.setField(controller, "cloudEnvironment", "dev");

        var response = signedProductionRequest("oc_source", "om_one", Instant.now().getEpochSecond());

        assertEquals(404, response.getStatusCode().value());
        verifyNoInteractions(invitations);
    }

    @Test void productionCommandsRelayAndReplyWithTheProductionLink() throws Exception {
        var relay = mock(ProductionTrialInviteRelayClient.class);
        controller.setProductionInviteRelayClient(relay);
        when(relay.issue("oc_source", "om_one")).thenReturn(
            new ProductionTrialInviteRelayClient.ProductionTrialInvite(
                "https://cloud.tapstate.com/auth#invite=A2B3C4D5E6F7", Instant.now().plusSeconds(604800)));
        when(bot.sendToChat(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new FeishuBotClient.DeliveryResult(true, null));

        assertEquals(200, receive(event("operator", "\u751f\u4ea7\u73af\u5883\u9080\u8bf7\u7801\u7b7e\u53d1")).getStatusCode().value());
        assertEquals(200, receive(event("another-member", "\u751f\u6210\u751f\u4ea7\u73af\u5883\u9080\u8bf7\u94fe\u63a5")).getStatusCode().value());

        verify(relay, times(2)).issue("oc_source", "om_one");
        verifyNoInteractions(invitations);
        verify(bot, times(2)).sendToChat(eq("oc_source"), eq("trial-invite"), eq("om_one"),
            contains("https://cloud.tapstate.com/auth#invite=A2B3C4D5E6F7"));
    }

    @Test void productionRelayCreatesAndReusesTheSameProductionInvitation() throws Exception {
        setProductionEnvironment("shared-production-relay-secret");
        Instant expiry = Instant.now().plusSeconds(604800);
        when(invitations.createBotTrialCode(anyString())).thenAnswer(invocation ->
            new InvitationService.IssuedInvitation(new InvitationService.InvitationView("id", null, null,
                Instant.now(), expiry, "pending"), invocation.getArgument(0)));

        var first = signedProductionRequest("oc_source", "om_one", Instant.now().getEpochSecond());
        var second = signedProductionRequest("oc_source", "om_one", Instant.now().getEpochSecond());

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());
        var firstLink = (ProductionTrialInviteRelayClient.ProductionTrialInvite) first.getBody();
        var secondLink = (ProductionTrialInviteRelayClient.ProductionTrialInvite) second.getBody();
        assertTrue(firstLink.url().startsWith("https://console.test/auth#invite="));
        assertEquals(firstLink.url(), secondLink.url());
        assertEquals(expiry, firstLink.expiresAt());
        assertEquals(firstLink.expiresAt(), secondLink.expiresAt());
        var token = ArgumentCaptor.forClass(String.class);
        verify(invitations, times(2)).createBotTrialCode(token.capture());
        assertEquals(token.getAllValues().get(0), token.getAllValues().get(1));
        assertTrue(token.getValue().matches("[2-9A-HJ-NP-Z]{12}"));
    }

    @Test void productionRelayRejectsBadSignaturesStaleRequestsAndWrongChats() throws Exception {
        setProductionEnvironment("shared-production-relay-secret");
        long freshTimestamp = Instant.now().getEpochSecond();

        assertEquals(403, controller.createProductionInvite(
            new ProductionTrialInviteRelayClient.ProductionInviteRequest("oc_source", "om_one"),
            Long.toString(freshTimestamp), "bad-signature").getStatusCode().value());
        assertEquals(403, signedProductionRequest("oc_source", "om_two", freshTimestamp - 301)
            .getStatusCode().value());
        assertEquals(403, signedProductionRequest("oc_other", "om_three", freshTimestamp)
            .getStatusCode().value());
        verifyNoInteractions(invitations);
    }

    @Test void productionRelayFailureNeverFallsBackToDevIssuance() throws Exception {
        var relay = mock(ProductionTrialInviteRelayClient.class);
        controller.setProductionInviteRelayClient(relay);
        when(relay.issue("oc_source", "om_one")).thenThrow(new ResourceAccessException("production unavailable"));

        assertEquals(503, receive(event("operator", "\u751f\u4ea7\u73af\u5883\u9080\u8bf7\u7801\u7b7e\u53d1"))
            .getStatusCode().value());
        verifyNoInteractions(invitations, bot);
    }

    private void setProductionSecret(String secret) {
        ReflectionTestUtils.setField(controller, "productionInviteSharedSecret", secret);
    }

    private void setProductionEnvironment(String secret) {
        setProductionSecret(secret);
        ReflectionTestUtils.setField(controller, "cloudEnvironment", "prod");
    }

    private org.springframework.http.ResponseEntity<?> signedProductionRequest(String chatId, String messageId,
            long timestamp) throws Exception {
        String secret = "shared-production-relay-secret";
        String signature = ProductionTrialInviteRelayClient.sign(secret, timestamp, chatId, messageId);
        return controller.createProductionInvite(
            new ProductionTrialInviteRelayClient.ProductionInviteRequest(chatId, messageId),
            Long.toString(timestamp), signature);
    }

}
