package io.tapstate.cloud.identity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private final InvitationService invitationService;
    private final TrialCodePolicy trialCodePolicy;
    private final UserRepository users;
    private final EmailVerificationChallengeRepository challenges;
    private final RegistrationCommitService registrationCommitService;
    private final MongoTemplate mongoTemplate;
    private final VerificationCodeMailer mailer;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${tapstate.cloud.auth.otp-hmac-key:}")
    private String hmacKey;

    @Value("${tapstate.cloud.auth.otp.max-attempts:5}")
    private int maxCodeAttempts;

    @Value("${tapstate.cloud.auth.otp.sends-per-hour:5}")
    private int maxHourlySends;

    @Value("${tapstate.cloud.auth.otp.cooldown-seconds:60}")
    private long cooldownSeconds;

    @Value("${tapstate.cloud.auth.otp.lifetime-minutes:10}")
    private long codeLifetimeMinutes;

    public void requestCode(String token, String suppliedEmail, String sourceAddress) {
        String email = InvitationService.normalizeEmail(suppliedEmail);
        OrganizationInvitation invitation = resolveInvitation(token, email);
        requireHmacKey();

        Instant now = Instant.now();
        int emailCount = consumeRateLimit("email", InvitationService.hash(email), now);
        int sourceCount = consumeRateLimit("source",
            InvitationService.hash(sourceAddress == null ? "unknown" : sourceAddress), now);
        if (emailCount > maxHourlySends || sourceCount > maxHourlySends) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS.value());
        }
        if (users.existsByEmail(email)) {
            throw new BusinessException(AppErrorCode.EMAIL_ALREADY_REGISTERED, HttpStatus.CONFLICT.value(), email);
        }

        String key = challengeKey(invitation == null ? null : invitation.getId(), email);
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        Optional<EmailVerificationChallenge> reservation = reserveChallenge(key,
            invitation == null ? null : invitation.getId(), email, codeHmac(key, code), now, false);
        deliverCode(reservation, email, code, now);
    }

    public void requestLoginCode(String suppliedEmail, String sourceAddress) {
        String email = InvitationService.normalizeEmail(suppliedEmail);
        requireHmacKey();
        Instant now = Instant.now();
        int emailCount = consumeRateLimit("email", InvitationService.hash(email), now);
        int sourceCount = consumeRateLimit("source", InvitationService.hash(sourceAddress == null ? "unknown" : sourceAddress), now);
        if (emailCount > maxHourlySends || sourceCount > maxHourlySends) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS.value());
        }
        if (users.findByEmail(email).filter(user -> "active".equals(user.getStatus())).isEmpty()) return;
        String key = InvitationService.hash("login:" + email);
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        deliverCode(reserveChallenge(key, null, email, codeHmac(key, code), now, true), email, code, now);
    }

    public User verifyLoginCode(String suppliedEmail, String code) {
        String email = InvitationService.normalizeEmail(suppliedEmail);
        requireHmacKey();
        String key = InvitationService.hash("login:" + email);
        Instant now = Instant.now();
        EmailVerificationChallenge challenge = incrementAttempt(key, now);
        if (!MessageDigest.isEqual(challenge.getCodeHmac().getBytes(StandardCharsets.US_ASCII),
                codeHmac(key, code).getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, HttpStatus.BAD_REQUEST.value());
        }
        User user = users.findByEmail(email).filter(candidate -> "active".equals(candidate.getStatus()))
            .orElseThrow(() -> new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, HttpStatus.BAD_REQUEST.value()));
        Query query = Query.query(Criteria.where("_id").is(challenge.getId()).and("consumedAt").is(null)
            .and("expiresAt").gt(now).and("codeHmac").is(challenge.getCodeHmac()));
        EmailVerificationChallenge consumed = mongoTemplate.findAndModify(query,
            new Update().set("consumedAt", now), FindAndModifyOptions.options().returnNew(true),
            EmailVerificationChallenge.class);
        if (consumed == null) throw new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, HttpStatus.BAD_REQUEST.value());
        return user;
    }

    private void deliverCode(Optional<EmailVerificationChallenge> reservation, String email, String code, Instant now) {
        if (reservation.isEmpty()) {
            return;
        }
        EmailVerificationChallenge challenge = reservation.get();
        try {
            mailer.send(email, code);
        } catch (RuntimeException exception) {
            challenge.setExpiresAt(now);
            challenges.save(challenge);
            Throwable cause = exception;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            log.warn("Verification code delivery failed (mailer={}, cause={})",
                mailer.getClass().getName(), cause.getClass().getSimpleName());
            throw new BusinessException(AppErrorCode.EMAIL_DELIVERY_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE.value());
        }
    }

    public User verifyAndRegister(String token, String suppliedEmail, String code, String password,
                                  String displayName, String organizationName) {
        return verifyAndRegister(token, suppliedEmail, code, password, displayName, organizationName, false);
    }

    public User verifyAndRegister(String token, String suppliedEmail, String code, String password,
                                  String displayName, String organizationName,
                                  boolean deferOrganizationCreation) {
        String email = InvitationService.normalizeEmail(suppliedEmail);
        requireHmacKey();
        OrganizationInvitation invitation = resolveInvitation(token, email);
        String key = challengeKey(invitation == null ? null : invitation.getId(), email);
        Instant now = Instant.now();
        EmailVerificationChallenge challenge = incrementAttempt(key, now);
        if (!MessageDigest.isEqual(challenge.getCodeHmac().getBytes(StandardCharsets.US_ASCII),
                codeHmac(key, code).getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, HttpStatus.BAD_REQUEST.value());
        }
        return registrationCommitService.complete(invitation, challenge, email, password, displayName,
            organizationName, now, deferOrganizationCreation);
    }

    public User verifyAndRegister(String token, String suppliedEmail, String code, String password, String displayName) {
        return verifyAndRegister(token, suppliedEmail, code, password, displayName, null);
    }

    private Optional<EmailVerificationChallenge> reserveChallenge(String key, ObjectId invitationId, String email,
                                                                  String codeHmac, Instant now, boolean allowConsumed) {
        EmailVerificationChallenge existing = challenges.findByKey(key).orElse(null);
        if (existing == null) {
            try {
                return Optional.of(challenges.save(EmailVerificationChallenge.builder()
                    .key(key).invitationId(invitationId).email(email).codeHmac(codeHmac)
                    .issuedAt(now).expiresAt(now.plus(codeLifetimeMinutes, ChronoUnit.MINUTES))
                    .lastSentAt(now).attempts(0).resends(0).build()));
            } catch (DuplicateKeyException exception) {
                existing = challenges.findByKey(key).orElseThrow(() -> exception);
            }
        }
        if (existing.getConsumedAt() != null && !allowConsumed) {
            return Optional.empty();
        }
        if (existing.getLastSentAt() != null && existing.getLastSentAt().isAfter(now.minusSeconds(cooldownSeconds))) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_COOLDOWN, HttpStatus.TOO_MANY_REQUESTS.value());
        }
        Criteria reservationCriteria = Criteria.where("_id").is(existing.getId())
            .and("lastSentAt").lte(now.minusSeconds(cooldownSeconds)).and("codeHmac").is(existing.getCodeHmac());
        if (!allowConsumed) reservationCriteria.and("consumedAt").is(null);
        EmailVerificationChallenge reserved = mongoTemplate.findAndModify(
            Query.query(reservationCriteria),
            new Update().set("lastSentAt", now).set("expiresAt", now.plus(codeLifetimeMinutes, ChronoUnit.MINUTES))
                .set("issuedAt", now).set("codeHmac", codeHmac).unset("consumedAt").inc("resends", 1).set("attempts", 0),
            FindAndModifyOptions.options().returnNew(true), EmailVerificationChallenge.class);
        if (reserved == null) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_COOLDOWN, HttpStatus.TOO_MANY_REQUESTS.value());
        }
        return Optional.of(reserved);
    }

    private EmailVerificationChallenge incrementAttempt(String key, Instant now) {
        EmailVerificationChallenge challenge = mongoTemplate.findAndModify(
            Query.query(Criteria.where("key").is(key).and("consumedAt").is(null)
                .and("expiresAt").gt(now).and("attempts").lt(maxCodeAttempts)),
            new Update().inc("attempts", 1), FindAndModifyOptions.options().returnNew(true),
            EmailVerificationChallenge.class);
        if (challenge != null) {
            return challenge;
        }
        EmailVerificationChallenge current = challenges.findByKey(key)
            .orElseThrow(() -> new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, 400));
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_EXPIRED, HttpStatus.GONE.value());
        }
        if (current.getAttempts() >= maxCodeAttempts) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS.value());
        }
        throw new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, 400);
    }

    private int consumeRateLimit(String kind, String subjectHash, Instant now) {
        long hour = now.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        String id = kind + ":" + subjectHash + ":" + hour;
        Query query = Query.query(Criteria.where("_id").is(id));
        Update update = new Update().inc("count", 1).setOnInsert("expiresAt", now.plus(2, ChronoUnit.HOURS));
        try {
            AuthRateLimitWindow result = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), AuthRateLimitWindow.class);
            return result == null ? maxHourlySends + 1 : result.getCount();
        } catch (DuplicateKeyException exception) {
            AuthRateLimitWindow result = mongoTemplate.findAndModify(query, new Update().inc("count", 1),
                FindAndModifyOptions.options().returnNew(true), AuthRateLimitWindow.class);
            return result == null ? maxHourlySends + 1 : result.getCount();
        }
    }

    private OrganizationInvitation resolveInvitation(String token, String email) {
        if (token == null || token.isBlank()) {
            if (trialCodePolicy.isRequired()) {
                throw new BusinessException(AppErrorCode.INVITATION_REQUIRED, HttpStatus.FORBIDDEN.value());
            }
            return null;
        }
        return invitationService.requireUsable(token, email);
    }

    private String challengeKey(ObjectId invitationId, String email) {
        String scope = invitationId == null ? "open" : invitationId.toHexString();
        return InvitationService.hash(scope + ":" + email);
    }

    private void requireHmacKey() {
        if (hmacKey == null || hmacKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new BusinessException(AppErrorCode.EMAIL_DELIVERY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE.value());
        }
    }

    private String codeHmac(String key, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((key + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
