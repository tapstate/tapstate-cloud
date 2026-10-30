package io.tapstate.cloud.identity;

import io.tapstate.cloud.config.LegalConfig;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.org.OrganizationService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationCommitServiceTest {

    @Mock private InvitationService invitations;
    @Mock private UserRepository users;
    @Mock private MemberRepository members;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private OrganizationService organizations;
    @Mock private LegalConfig legalConfig;

    @Test
    void registrationWithoutInvitationCreatesItsOwnOrganization() {
        Instant now = Instant.now();
        EmailVerificationChallenge challenge = EmailVerificationChallenge.builder()
            .id(new ObjectId()).codeHmac("verified-hmac").build();
        User user = User.builder().email("guest@example.com").status("active").build();
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(EmailVerificationChallenge.class))).thenReturn(challenge);
        when(users.existsByEmail("guest@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenReturn(user);
        when(legalConfig.getCurrentVersion()).thenReturn("legal-v1.0-test");

        RegistrationCommitService service = new RegistrationCommitService(invitations, users, members,
            mongoTemplate, passwordEncoder, organizations, legalConfig);
        User result = service.complete(null, challenge, "guest@example.com", "password-123", "Guest",
            "Guest Workspace", now);

        assertSame(user, result);
        verify(organizations).createTrialOrganization("Guest Workspace", "guest@example.com");
        verifyNoInteractions(invitations, members);
    }

    @Test
    void deferredTrialRegistrationCreatesTheAccountWithoutAnOrganization() {
        Instant now = Instant.now();
        EmailVerificationChallenge challenge = EmailVerificationChallenge.builder()
            .id(new ObjectId()).codeHmac("verified-hmac").build();
        OrganizationInvitation invitation = OrganizationInvitation.builder()
            .id(new ObjectId()).invitedEmail("guest@example.com").build();
        User user = User.builder().email("guest@example.com").status("active").build();
        when(invitations.isTrial(invitation)).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(EmailVerificationChallenge.class))).thenReturn(challenge);
        when(users.existsByEmail("guest@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenReturn(user);
        when(legalConfig.getCurrentVersion()).thenReturn("legal-v1.0-test");

        RegistrationCommitService service = new RegistrationCommitService(invitations, users, members,
            mongoTemplate, passwordEncoder, organizations, legalConfig);
        User result = service.complete(invitation, challenge, "guest@example.com", "password-123", "Guest",
            null, now, true);

        assertSame(user, result);
        verify(invitations).consume(invitation, "guest@example.com");
        verify(organizations, never()).createTrialOrganization(any(), any());
        verifyNoInteractions(members);
    }

    @Test
    void deferredOrganizationInvitationRegistrationStillJoinsTheTargetOrganization() {
        Instant now = Instant.now();
        ObjectId targetOrganizationId = new ObjectId();
        EmailVerificationChallenge challenge = EmailVerificationChallenge.builder()
            .id(new ObjectId()).codeHmac("verified-hmac").build();
        OrganizationInvitation invitation = OrganizationInvitation.builder()
            .id(new ObjectId()).orgId(targetOrganizationId).invitedEmail("member@example.com").build();
        User user = User.builder().email("member@example.com").status("active").build();
        when(invitations.isTrial(invitation)).thenReturn(false);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(EmailVerificationChallenge.class))).thenReturn(challenge);
        when(users.existsByEmail("member@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenReturn(user);
        when(legalConfig.getCurrentVersion()).thenReturn("legal-v1.0-test");

        RegistrationCommitService service = new RegistrationCommitService(invitations, users, members,
            mongoTemplate, passwordEncoder, organizations, legalConfig);
        User result = service.complete(invitation, challenge, "member@example.com", "password-123", "Member",
            null, now, true);

        assertSame(user, result);
        verify(invitations).consume(invitation, "member@example.com");
        verify(members).save(argThat(member -> targetOrganizationId.equals(member.getOrgId())
            && "member@example.com".equals(member.getUserEmail()) && "member".equals(member.getRole())));
        verifyNoInteractions(organizations);
    }
}
