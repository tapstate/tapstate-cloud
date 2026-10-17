package io.tapstate.cloud.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.Member;
import io.tapstate.cloud.org.MemberRepository;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class IdentityAuthenticationService {

    private final LinkedIdentityRepository identities;
    private final UserRepository users;
    private final MemberRepository members;
    private final InvitationService invitations;
    private final PasswordEncoder passwordEncoder;
    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    @Transactional
    public User authenticate(String provider, String subject, String email, boolean emailVerified,
                             String displayName, String flow, String currentSessionEmail,
                             Instant reauthenticatedAt, String invitationTokenHash) {
        String normalizedEmail = InvitationService.normalizeEmail(email);
        if (!emailVerified) {
            throw new BusinessException(AppErrorCode.SSO_EMAIL_UNVERIFIED, HttpStatus.FORBIDDEN.value());
        }
        LinkedIdentity linked = identities.findByProviderAndSubject(provider, subject).orElse(null);
        if ("link".equals(flow)) {
            String currentEmail = currentSessionEmail == null ? null : InvitationService.normalizeEmail(currentSessionEmail);
            if (currentEmail == null || reauthenticatedAt == null
                    || reauthenticatedAt.isBefore(Instant.now().minusSeconds(300))) {
                throw new BusinessException(AppErrorCode.REAUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED.value());
            }
            if (linked != null) {
                throw new BusinessException(AppErrorCode.IDENTITY_LINK_CONFLICT, HttpStatus.CONFLICT.value());
            }
            User currentUser = users.findByEmail(currentEmail)
                .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, currentEmail));
            identities.save(LinkedIdentity.builder().provider(provider).subject(subject).userEmail(currentEmail)
                .providerEmail(normalizedEmail).linkedAt(Instant.now()).build());
            return currentUser;
        }

        if (linked != null) {
            User user = users.findByEmail(linked.getUserEmail())
                .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, linked.getUserEmail()));
            if (!"active".equals(user.getStatus())) {
                throw new BusinessException(AppErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED.value());
            }
            if (invitationTokenHash != null && !invitationTokenHash.isBlank()) {
                String accountEmail = InvitationService.normalizeEmail(user.getEmail());
                OrganizationInvitation invitation = invitations.requireUsableHash(invitationTokenHash, accountEmail);
                invitations.consume(invitation, accountEmail);
                if (!members.existsByOrgIdAndUserEmail(invitation.getOrgId(), accountEmail)) {
                    members.save(Member.builder().orgId(invitation.getOrgId()).userEmail(accountEmail)
                        .role("member").joinedAt(Instant.now()).build());
                }
            }
            user.setLastLoginAt(Instant.now());
            return users.save(user);
        }

        if (users.existsByEmail(normalizedEmail)) {
            throw new BusinessException(AppErrorCode.SSO_ACCOUNT_LINK_REQUIRED, HttpStatus.CONFLICT.value());
        }
        if (invitationTokenHash == null || invitationTokenHash.isBlank()) {
            throw new BusinessException(AppErrorCode.INVITATION_REQUIRED, HttpStatus.FORBIDDEN.value());
        }

        OrganizationInvitation invitation = invitations.requireUsableHash(invitationTokenHash, normalizedEmail);
        invitations.consume(invitation, normalizedEmail);
        User user = users.save(User.builder()
            .email(normalizedEmail)
            .displayName(displayName == null || displayName.isBlank()
                ? normalizedEmail.substring(0, normalizedEmail.indexOf('@')) : displayName)
            .passwordHash(passwordEncoder.encode(randomUnusablePassword()))
            .emailVerified(true)
            .status("active")
            .lastLoginAt(Instant.now())
            .build());
        members.save(Member.builder().orgId(invitation.getOrgId()).userEmail(normalizedEmail)
            .role("member").joinedAt(Instant.now()).build());
        identities.save(LinkedIdentity.builder().provider(provider).subject(subject).userEmail(normalizedEmail)
            .providerEmail(normalizedEmail).linkedAt(Instant.now()).build());
        return user;
    }

    private String randomUnusablePassword() {
        byte[] secret = new byte[48];
        secureRandom.nextBytes(secret);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
}
