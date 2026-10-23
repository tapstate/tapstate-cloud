package io.tapstate.cloud.identity;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.Member;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.org.Organization;
import io.tapstate.cloud.org.OrganizationRepository;
import io.tapstate.cloud.org.OrganizationService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationTokenServiceTest {

    @Mock private OrganizationInvitationRepository invitationRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private UserRepository userRepository;
    @Mock private MongoTemplate mongoTemplate;

    private InvitationService service;
    private ObjectId organizationId;

    @BeforeEach
    void setUp() {
        service = new InvitationService(invitationRepository, organizationRepository, memberRepository,
            userRepository, mongoTemplate, mock(OrganizationService.class));
        organizationId = new ObjectId();
    }

    @Test
    void createStoresOnlyTokenDigestAndReturnsSevenDaySingleUseLink() {
        String owner = "owner@example.com";
        when(memberRepository.findByOrgIdAndUserEmail(organizationId, owner))
            .thenReturn(Optional.of(Member.builder().orgId(organizationId).userEmail(owner).role("owner").build()));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(
            Organization.builder().id(organizationId).ownerEmail(owner).build()));
        when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(invocation -> {
            OrganizationInvitation invitation = invocation.getArgument(0);
            invitation.setId(new ObjectId());
            return invitation;
        });

        Instant before = Instant.now();
        InvitationService.IssuedInvitation issued = service.create(organizationId.toHexString(), owner, " Guest@Example.com ");
        Instant after = Instant.now();

        ArgumentCaptor<OrganizationInvitation> storedCaptor = ArgumentCaptor.forClass(OrganizationInvitation.class);
        verify(invitationRepository).save(storedCaptor.capture());
        OrganizationInvitation stored = storedCaptor.getValue();
        assertNotEquals(issued.token(), stored.getTokenHash());
        assertEquals(InvitationService.hash(issued.token()), stored.getTokenHash());
        assertEquals("guest@example.com", stored.getInvitedEmail());
        assertTrue(Duration.between(before.plus(Duration.ofDays(7)), stored.getExpiresAt()).abs().toMillis() < 1000);
        assertEquals("active", issued.invitation().status());
        assertTrue(issued.token().matches("[2-9A-HJ-NP-Z]{12}"));
        assertTrue(stored.getCreatedAt().compareTo(before) >= 0 && stored.getCreatedAt().compareTo(after) <= 0);
    }

    @Test
    void rejectsLegacyFortyThreeCharacterInvitationTokens() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.requireUsable("A".repeat(43), null));

        assertEquals(AppErrorCode.INVITATION_INVALID, exception.getErrorCode());
        verifyNoInteractions(invitationRepository);
    }

    @Test
    void invitationCodesAreCaseInsensitive() {
        assertEquals(InvitationService.hash("A2B3C4D5E6F7"),
            InvitationService.hash("a2b3c4d5e6f7"));
    }

    @Test
    void memberCannotManageAnOrganizationInvitation() {
        when(memberRepository.findByOrgIdAndUserEmail(organizationId, "member@example.com"))
            .thenReturn(Optional.of(Member.builder().orgId(organizationId).userEmail("member@example.com").role("member").build()));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(
            Organization.builder().id(organizationId).ownerEmail("owner@example.com").build()));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.create(organizationId.toHexString(), "member@example.com", null));

        assertEquals(AppErrorCode.ORG_OWNER_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(invitationRepository);
    }
    @Test
    void botIssuanceUpsertsOnlyDigestAndPreservesExistingExpiry() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "trialIssuerEmail", "issuer@example.com");
        Instant originalExpiry = Instant.now().plusSeconds(604800);
        OrganizationInvitation existing = OrganizationInvitation.builder().id(new ObjectId())
            .tokenHash(InvitationService.hash("A2B3C4D5E6F7")).purpose("trial")
            .createdAt(Instant.now()).expiresAt(originalExpiry).build();
        when(mongoTemplate.findAndModify(any(org.springframework.data.mongodb.core.query.Query.class),
            any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(OrganizationInvitation.class)))
            .thenReturn(existing);
        var first = service.createBotTrialCode("issuer@example.com", "A2B3C4D5E6F7");
        var retry = service.createBotTrialCode("issuer@example.com", "A2B3C4D5E6F7");
        assertEquals(first, retry);
        assertEquals(originalExpiry, retry.invitation().expiresAt());
        var update = ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate, times(2)).findAndModify(any(org.springframework.data.mongodb.core.query.Query.class),
            update.capture(), any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(OrganizationInvitation.class));
        assertFalse(update.getValue().getUpdateObject().toString().contains("A2B3C4D5E6F7"));
        assertTrue(update.getValue().getUpdateObject().containsKey("$setOnInsert"));
        verifyNoInteractions(invitationRepository);
    }

    @Test
    void botIssuanceRequiresConfiguredIssuer() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "trialIssuerEmail", "issuer@example.com");
        assertThrows(BusinessException.class, () -> service.createBotTrialCode("stranger@example.com", "A2B3C4D5E6F7"));
        verifyNoInteractions(mongoTemplate, invitationRepository);
    }

}
