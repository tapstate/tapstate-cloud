package io.tapstate.cloud.identity;

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

        RegistrationCommitService service = new RegistrationCommitService(invitations, users, members,
            mongoTemplate, passwordEncoder, organizations);
        User result = service.complete(null, challenge, "guest@example.com", "password-123", "Guest",
            "Guest Workspace", now);

        assertSame(user, result);
        verify(organizations).createTrialOrganization("Guest Workspace", "guest@example.com");
        verifyNoInteractions(invitations, members);
    }
}
