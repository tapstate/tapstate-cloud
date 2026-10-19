package io.tapstate.cloud.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.Member;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.org.OrganizationService;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RegistrationCommitService {

    private final InvitationService invitationService;
    private final UserRepository users;
    private final MemberRepository members;
    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationService organizations;

    @Transactional
    public User complete(OrganizationInvitation invitation, EmailVerificationChallenge challenge,
                         String email, String password, String displayName, String organizationName, Instant now) {
        Query challengeQuery = Query.query(Criteria.where("_id").is(challenge.getId())
            .and("consumedAt").is(null).and("expiresAt").gt(now).and("codeHmac").is(challenge.getCodeHmac()));
        EmailVerificationChallenge consumed = mongoTemplate.findAndModify(challengeQuery,
            new Update().set("consumedAt", now), FindAndModifyOptions.options().returnNew(true),
            EmailVerificationChallenge.class);
        if (consumed == null) {
            throw new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, HttpStatus.BAD_REQUEST.value());
        }
        if (users.existsByEmail(email)) {
            // Keep the result indistinguishable from an invalid code to avoid exposing account existence.
            throw new BusinessException(AppErrorCode.EMAIL_CODE_INVALID, HttpStatus.BAD_REQUEST.value());
        }

        invitationService.consume(invitation, email);
        User user = users.save(User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .displayName(displayName == null || displayName.isBlank()
                ? email.substring(0, email.indexOf('@')) : displayName.trim())
            .emailVerified(true)
            .status("active")
            .build());
        if (invitationService.isTrial(invitation)) {
            if (organizationName == null || organizationName.isBlank() || organizationName.length() > 100) {
                throw new BusinessException(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST.value());
            }
            organizations.createTrialOrganization(organizationName, email);
        } else {
            members.save(Member.builder().orgId(invitation.getOrgId()).userEmail(email).role("member").joinedAt(now).build());
        }
        return user;
    }
}
