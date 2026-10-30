package io.tapstate.cloud.identity;

import io.tapstate.cloud.config.LegalConfig;
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
    private final LegalConfig legalConfig;

    @Transactional
    public User complete(OrganizationInvitation invitation, EmailVerificationChallenge challenge,
                         String email, String password, String displayName, String organizationName, Instant now) {
        return complete(invitation, challenge, email, password, displayName, organizationName, now, false);
    }

    @Transactional
    public User complete(OrganizationInvitation invitation, EmailVerificationChallenge challenge,
                         String email, String password, String displayName, String organizationName,
                         Instant now, boolean deferOrganizationCreation) {
        boolean createsOrganization = invitation == null || invitationService.isTrial(invitation);
        if (createsOrganization && !deferOrganizationCreation && (organizationName == null || organizationName.isBlank()
                || organizationName.length() > 100)) {
            throw new BusinessException(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST.value());
        }
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

        if (invitation != null) {
            invitationService.consume(invitation, email);
        }
        User user = users.save(User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .displayName(displayName == null || displayName.isBlank()
                ? email.substring(0, email.indexOf('@')) : displayName.trim())
            .emailVerified(true)
            .status("active")
            .agreedLegalVersion(legalConfig.getCurrentVersion())
            .agreedLegalAt(now)
            .build());
        if (createsOrganization && !deferOrganizationCreation) {
            organizations.createTrialOrganization(organizationName, email);
        } else if (!createsOrganization) {
            members.save(Member.builder().orgId(invitation.getOrgId()).userEmail(email).role("member").joinedAt(now).build());
        }
        return user;
    }
}
