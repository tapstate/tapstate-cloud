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
    @Mock private UserRepository users;
    @Mock private EmailVerificationChallengeRepository challenges;
    @Mock private RegistrationCommitService registrationCommitService;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private VerificationCodeMailer mailer;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(invitations, new TrialCodePolicy(true), users, challenges,
            registrationCommitService, mongoTemplate, mailer);
        ReflectionTestUtils.setField(service, "hmacKey", HMAC_KEY);
    }

    @Test
    void missingTrialCodeIsRejectedWhileTheGateIsEnabled() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.requestCode(null, "guest@example.com", "127.0.0.1"));

        assertEquals(AppErrorCode.INVITATION_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(invitations, mailer, mongoTemplate);
    }

    @Test
    void verifiedEmailCanRegisterWithoutInvitationWhenTheGateIsDisabled() throws Exception {
        String email = "guest@example.com";
        String key = InvitationService.hash("open:" + email);
        EmailVerificationChallenge challenge = EmailVerificationChallenge.builder()
            .id(new ObjectId()).key(key).codeHmac(codeHmac(key, "123456"))
            .expiresAt(Instant.now().plusSeconds(600)).attempts(1).build();
        User registered = User.builder().email(email).displayName("Guest").status("active").build();
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(EmailVerificationChallenge.class))).thenReturn(challenge);
        when(registrationCommitService.complete(isNull(), eq(challenge), eq(email), eq("password-123"),
            eq("Guest"), eq("Guest Workspace"), any(Instant.class), eq(false))).thenReturn(registered);
        EmailVerificationService openService = new EmailVerificationService(invitations, new TrialCodePolicy(false), users,
            challenges, registrationCommitService, mongoTemplate, mailer);
        ReflectionTestUtils.setField(openService, "hmacKey", HMAC_KEY);
        ReflectionTestUtils.setField(openService, "maxHourlySends", 5);

        User result = openService.verifyAndRegister(null, email, "123456", "password-123", "Guest",
            "Guest Workspace");

        assertSame(registered, result);
        verify(invitations, never()).requireUsable(any(), any());
        verify(registrationCommitService).complete(isNull(), eq(challenge), eq(email), eq("password-123"),
            eq("Guest"), eq("Guest Workspace"), any(Instant.class), eq(false));
    }

    @Test
    void registeredEmailIsDirectedToSignInWithoutSendingACode() {
        Instant now = Instant.now();
        String registeredEmail = "registered@example.com";
        String unusedEmail = "unused@example.com";
        String unusedKey = InvitationService.hash("open:" + unusedEmail);
        when(challenges.findByKey(unusedKey)).thenReturn(Optional.empty());
        when(users.existsByEmail(anyString())).thenAnswer(invocation -> registeredEmail.equals(invocation.getArgument(0)));
        when(challenges.save(any(EmailVerificationChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(AuthRateLimitWindow.class))).thenAnswer(invocation -> new AuthRateLimitWindow(
                "email-window", 1, now.plusSeconds(7200)));

        EmailVerificationService openService = new EmailVerificationService(invitations, new TrialCodePolicy(false), users,
            challenges, registrationCommitService, mongoTemplate, mailer);
        ReflectionTestUtils.setField(openService, "hmacKey", HMAC_KEY);
        ReflectionTestUtils.setField(openService, "maxHourlySends", 5);

        assertDoesNotThrow(() -> openService.requestCode(null, unusedEmail, "127.0.0.1"));
        BusinessException existing = assertThrows(BusinessException.class,
            () -> openService.requestCode(null, registeredEmail, "127.0.0.1"));
        assertEquals(AppErrorCode.EMAIL_ALREADY_REGISTERED, existing.getErrorCode());

        verify(mailer).send(eq(unusedEmail), anyString());
        verify(mailer, never()).send(eq(registeredEmail), anyString());
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
