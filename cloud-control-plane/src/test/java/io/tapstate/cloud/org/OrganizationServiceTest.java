package io.tapstate.cloud.org;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationServiceTest {

    @Test
    void concurrentCreationForOneOwnerReturnsTheOrganizationLimitError() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        when(members.findByUserEmail("owner@example.com")).thenReturn(List.of());
        when(organizations.save(any(Organization.class))).thenThrow(new DuplicateKeyException(
            "E11000 duplicate key error index: organization_creation_key_unique"));

        OrganizationService service = new OrganizationService(organizations, members);

        assertThatThrownBy(() -> service.createOrganizationWithoutSlug(
            "Acme Data", "owner@example.com", false))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(AppErrorCode.ORG_LIMIT_REACHED));
    }

    @Test
    void retryForTheSameOwnerAndNameReturnsThePreviouslyCreatedOrganization() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        Organization existing = Organization.builder()
            .id(new ObjectId()).name("Acme Data").ownerEmail("owner@example.com")
            .creationKey("owner:owner@example.com").build();
        when(organizations.findByCreationKey("owner:owner@example.com")).thenReturn(Optional.of(existing));

        Organization result = new OrganizationService(organizations, members)
            .createOrganizationWithoutSlug("Acme Data", "owner@example.com", false);

        assertThat(result).isSameAs(existing);
    }

    @Test
    void deferredTrialGrantAllowsAnExistingOwnerToCreateAnotherOrganization() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        Member existingOwnerMembership = Member.builder()
            .orgId(new ObjectId()).userEmail("owner@example.com").role("owner").build();
        when(members.findByUserEmail("owner@example.com")).thenReturn(List.of(existingOwnerMembership));
        when(organizations.findByCreationKey("trial:grant-key")).thenReturn(Optional.empty());
        when(organizations.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            organization.setId(new ObjectId());
            return organization;
        });

        Organization result = new OrganizationService(organizations, members)
            .createOrganizationWithoutSlug("Second Workspace", "owner@example.com", true, "trial:grant-key");

        assertThat(result.getName()).isEqualTo("Second Workspace");
        assertThat(result.getSlug()).isNull();
        assertThat(result.getCreationKey()).isEqualTo("trial:grant-key");
    }

    @Test
    void legacyTrialRegistrationStillCreatesOrganizationsWithoutADeferredCreationKey() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        when(organizations.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            organization.setId(new ObjectId());
            return organization;
        });

        Organization result = new OrganizationService(organizations, members)
            .createTrialOrganization("Second Workspace", "owner@example.com");

        assertThat(result.getSlug()).startsWith("trial-");
        assertThat(result.getCreationKey()).isNull();
    }

}
