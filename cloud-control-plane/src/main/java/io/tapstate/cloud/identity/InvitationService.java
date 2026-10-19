package io.tapstate.cloud.identity;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.Member;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.org.Organization;
import io.tapstate.cloud.org.OrganizationRepository;
import io.tapstate.cloud.org.OrganizationService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class InvitationService {

    private final OrganizationInvitationRepository invitations;
    private final OrganizationRepository organizations;
    private final MemberRepository members;
    private final UserRepository users;
    private final MongoTemplate mongoTemplate;
    private final OrganizationService organizationService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${tapstate.cloud.auth.trial-issuer-email:}")
    private String trialIssuerEmail;

    public IssuedInvitation createTrialCode(String issuerEmail) {
        if (trialIssuerEmail == null || trialIssuerEmail.isBlank()
                || !normalizeEmail(trialIssuerEmail).equals(normalizeEmail(issuerEmail))) {
            throw new BusinessException(AppErrorCode.UNAUTHORIZED, HttpStatus.FORBIDDEN.value());
        }
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now();
        OrganizationInvitation invitation = invitations.save(OrganizationInvitation.builder()
            .tokenHash(hash(token))
            .purpose("trial")
            .createdByEmail(normalizeEmail(issuerEmail))
            .createdAt(now)
            .expiresAt(now.plus(7, ChronoUnit.DAYS))
            .build());
        return new IssuedInvitation(view(invitation), token);
    }

    public boolean isTrial(OrganizationInvitation invitation) {
        return "trial".equals(invitation.getPurpose());
    }

    public IssuedInvitation create(String orgId, String ownerEmail, String invitedEmail) {
        ObjectId organizationId = parseOrganizationId(orgId);
        String normalizedOwner = normalizeEmail(ownerEmail);
        Member owner = members.findByOrgIdAndUserEmail(organizationId, normalizedOwner)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ORG_OWNER_REQUIRED, HttpStatus.FORBIDDEN.value()));
        Organization organization = organizations.findById(organizationId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId));
        if (!"owner".equals(owner.getRole()) || !normalizedOwner.equals(normalizeEmail(organization.getOwnerEmail()))) {
            throw new BusinessException(AppErrorCode.ORG_OWNER_REQUIRED, HttpStatus.FORBIDDEN.value());
        }

        String normalizedInviteEmail = invitedEmail == null || invitedEmail.isBlank()
            ? null : normalizeEmail(invitedEmail);
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now();
        OrganizationInvitation invitation = invitations.save(OrganizationInvitation.builder()
            .tokenHash(hash(token))
            .orgId(organizationId)
            .createdByEmail(normalizedOwner)
            .invitedEmail(normalizedInviteEmail)
            .createdAt(now)
            .expiresAt(now.plus(7, ChronoUnit.DAYS))
            .build());
        return new IssuedInvitation(view(invitation), token);
    }

    public List<InvitationView> list(String orgId, String ownerEmail) {
        ObjectId organizationId = parseOrganizationId(orgId);
        requireOwner(organizationId, ownerEmail);
        return invitations.findByOrgIdOrderByCreatedAtDesc(organizationId).stream().map(this::view).toList();
    }

    public InvitationView revoke(String orgId, String invitationId, String ownerEmail) {
        ObjectId organizationId = parseOrganizationId(orgId);
        requireOwner(organizationId, ownerEmail);
        OrganizationInvitation invitation = invitations.findById(parseInvitationId(invitationId))
            .filter(item -> organizationId.equals(item.getOrgId()))
            .orElseThrow(() -> new BusinessException(AppErrorCode.INVITATION_INVALID, 404));
        if (invitation.getConsumedAt() != null) {
            throw new BusinessException(AppErrorCode.INVITATION_CONSUMED, 409);
        }
        Instant now = Instant.now();
        OrganizationInvitation revoked = mongoTemplate.findAndModify(
            Query.query(Criteria.where("_id").is(invitation.getId()).and("orgId").is(organizationId)
                .and("consumedAt").is(null).and("revokedAt").is(null)),
            new Update().set("revokedAt", now), FindAndModifyOptions.options().returnNew(true),
            OrganizationInvitation.class);
        invitation = revoked != null ? revoked : invitations.findById(invitation.getId()).orElse(invitation);
        if (invitation.getConsumedAt() != null) {
            throw new BusinessException(AppErrorCode.INVITATION_CONSUMED, 409);
        }
        return view(invitation);
    }

    public OrganizationInvitation requireUsable(String token, String email) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(AppErrorCode.INVITATION_REQUIRED, 400);
        }
        return requireUsableHash(hash(token), email);
    }

    public OrganizationInvitation requireUsableHash(String tokenHash, String email) {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new BusinessException(AppErrorCode.INVITATION_REQUIRED, 400);
        }
        OrganizationInvitation invitation = invitations.findByTokenHash(tokenHash)
            .orElseThrow(() -> new BusinessException(AppErrorCode.INVITATION_INVALID, 400));
        if (invitation.getRevokedAt() != null) {
            throw new BusinessException(AppErrorCode.INVITATION_REVOKED, 410);
        }
        if (invitation.getConsumedAt() != null) {
            throw new BusinessException(AppErrorCode.INVITATION_CONSUMED, 409);
        }
        if (!invitation.getExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(AppErrorCode.INVITATION_EXPIRED, 410);
        }
        if (invitation.getInvitedEmail() != null && email != null
                && !invitation.getInvitedEmail().equals(normalizeEmail(email))) {
            throw new BusinessException(AppErrorCode.INVITATION_EMAIL_MISMATCH, 403);
        }
        return invitation;
    }

    public String accessType(String token) {
        return isTrial(requireUsable(token, null)) ? "trial" : "organization";
    }

    @Transactional
    public User acceptForExistingUser(String token, String authenticatedEmail) {
        return acceptForExistingUser(token, authenticatedEmail, null);
    }

    @Transactional
    public User acceptForExistingUser(String token, String authenticatedEmail, String organizationName) {
        String email = normalizeEmail(authenticatedEmail);
        User user = users.findByEmail(email)
            .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, email));
        OrganizationInvitation invitation = requireUsable(token, email);
        if (isTrial(invitation)) {
            requireOrganizationName(organizationName);
            consume(invitation, email);
            organizationService.createTrialOrganization(organizationName, email);
            return user;
        }
        consume(invitation, email);
        if (!members.existsByOrgIdAndUserEmail(invitation.getOrgId(), email)) {
            members.save(Member.builder().orgId(invitation.getOrgId()).userEmail(email).role("member").joinedAt(Instant.now()).build());
        }
        return user;
    }

    public void requireOrganizationName(String organizationName) {
        if (organizationName == null || organizationName.isBlank() || organizationName.length() > 100) {
            throw new BusinessException(AppErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST.value());
        }
    }

    OrganizationInvitation consume(OrganizationInvitation invitation, String email) {
        Instant now = Instant.now();
        Criteria criteria = Criteria.where("_id").is(invitation.getId())
            .and("revokedAt").is(null)
            .and("consumedAt").is(null)
            .and("expiresAt").gt(now);
        if (invitation.getInvitedEmail() != null) {
            criteria.and("invitedEmail").is(normalizeEmail(email));
        }
        Query query = Query.query(criteria);
        Update update = new Update().set("consumedAt", now).set("consumedByEmail", normalizeEmail(email));
        OrganizationInvitation consumed = mongoTemplate.findAndModify(query, update,
            FindAndModifyOptions.options().returnNew(true), OrganizationInvitation.class);
        if (consumed == null) {
            OrganizationInvitation current = invitations.findById(invitation.getId()).orElse(invitation);
            if (current.getRevokedAt() != null) {
                throw new BusinessException(AppErrorCode.INVITATION_REVOKED, 410);
            }
            if (current.getConsumedAt() != null) {
                throw new BusinessException(AppErrorCode.INVITATION_CONSUMED, 409);
            }
            if (!current.getExpiresAt().isAfter(now)) {
                throw new BusinessException(AppErrorCode.INVITATION_EXPIRED, 410);
            }
            if (current.getInvitedEmail() != null && !current.getInvitedEmail().equals(normalizeEmail(email))) {
                throw new BusinessException(AppErrorCode.INVITATION_EMAIL_MISMATCH, 403);
            }
            throw new BusinessException(AppErrorCode.INVITATION_CONSUMED, 409);
        }
        return consumed;
    }

    private void requireOwner(ObjectId orgId, String ownerEmail) {
        String email = normalizeEmail(ownerEmail);
        Organization organization = organizations.findById(orgId)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId));
        Member member = members.findByOrgIdAndUserEmail(orgId, email)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ORG_OWNER_REQUIRED, 403));
        if (!"owner".equals(member.getRole()) || !email.equals(normalizeEmail(organization.getOwnerEmail()))) {
            throw new BusinessException(AppErrorCode.ORG_OWNER_REQUIRED, 403);
        }
    }

    private InvitationView view(OrganizationInvitation invitation) {
        String state = invitation.getRevokedAt() != null ? "revoked"
            : invitation.getConsumedAt() != null ? "consumed"
            : !invitation.getExpiresAt().isAfter(Instant.now()) ? "expired" : "active";
        return new InvitationView(invitation.getId().toHexString(),
            invitation.getOrgId() == null ? null : invitation.getOrgId().toHexString(),
            invitation.getInvitedEmail(), invitation.getCreatedAt(), invitation.getExpiresAt(), state);
    }

    private ObjectId parseOrganizationId(String id) {
        try {
            return new ObjectId(id);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, id);
        }
    }

    private ObjectId parseInvitationId(String id) {
        try {
            return new ObjectId(id);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(AppErrorCode.INVITATION_INVALID, HttpStatus.NOT_FOUND.value());
        }
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedInvitation(InvitationView invitation, String token) {}

    public record InvitationView(String id, String orgId, String invitedEmail, Instant createdAt,
                                 Instant expiresAt, String status) {}

}
