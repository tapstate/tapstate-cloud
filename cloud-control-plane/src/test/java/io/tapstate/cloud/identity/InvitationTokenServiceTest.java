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
        assertTrue(issued.token().length() >= 40);
        assertTrue(stored.getCreatedAt().compareTo(before) >= 0 && stored.getCreatedAt().compareTo(after) <= 0);
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
}
