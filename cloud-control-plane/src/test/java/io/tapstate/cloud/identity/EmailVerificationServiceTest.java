package io.tapstate.cloud.identity;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String HMAC_KEY = "local-unit-test-hmac-key-at-least-32-bytes";

    @Mock private InvitationService invitations;
    @Mock private EmailVerificationChallengeRepository challenges;
    @Mock private RegistrationCommitService registrationCommitService;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private VerificationCodeMailer mailer;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(invitations, challenges, registrationCommitService, mongoTemplate, mailer);
        ReflectionTestUtils.setField(service, "hmacKey", HMAC_KEY);
    }

    @Test
    void expiredCodeIsRejectedWithoutCallingRegistrationCommit() {
        String email = "guest@example.com";
        ObjectId invitationId = new ObjectId();
        EmailVerificationChallenge expired = EmailVerificationChallenge.builder()
            .id(new ObjectId()).key(challengeKey(invitationId, email)).codeHmac("unused")
            .expiresAt(Instant.now().minusSeconds(1)).attempts(0).build();
        when(invitations.requireUsable("invitation", email)).thenReturn(
            OrganizationInvitation.builder().id(invitationId).build());
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(EmailVerificationChallenge.class))).thenReturn(null);
        when(challenges.findByKey(expired.getKey())).thenReturn(Optional.of(expired));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.verifyAndRegister("invitation", email, "123456", "password-123", "Guest"));

        assertEquals(AppErrorCode.EMAIL_CODE_EXPIRED, exception.getErrorCode());
        verifyNoInteractions(registrationCommitService);
    }

    @Test
    void wrongCodeIncrementsAttemptsButDoesNotConsumeInvitationOrCreateAnAccount() throws Exception {
        String email = "guest@example.com";
        ObjectId invitationId = new ObjectId();
        String key = challengeKey(invitationId, email);
        EmailVerificationChallenge challenge = EmailVerificationChallenge.builder()
            .id(new ObjectId()).key(key).codeHmac(codeHmac(key, "123456"))
            .expiresAt(Instant.now().plusSeconds(600)).attempts(1).build();
        when(invitations.requireUsable("invitation", email)).thenReturn(
            OrganizationInvitation.builder().id(invitationId).build());
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(EmailVerificationChallenge.class))).thenReturn(challenge);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.verifyAndRegister("invitation", email, "654321", "password-123", "Guest"));

        assertEquals(AppErrorCode.EMAIL_CODE_INVALID, exception.getErrorCode());
        verifyNoInteractions(registrationCommitService);
        verify(invitations, never()).consume(any(), any());
    }

    private String challengeKey(ObjectId invitationId, String email) {
        return InvitationService.hash(invitationId.toHexString() + ":" + email);
    }

    private String codeHmac(String challengeKey, String code) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((challengeKey + ":" + code).getBytes(StandardCharsets.UTF_8)));
    }
}
