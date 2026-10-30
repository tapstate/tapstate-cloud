package io.tapstate.cloud.org;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationServiceTest {

    @Test
    void organizationCreationWithoutTrialGrantRespectsExistingMembership() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        when(members.findByUserEmail("owner@example.com")).thenReturn(List.of(
            Member.builder().orgId(new ObjectId()).userEmail("owner@example.com").role("owner").build()));

        OrganizationService service = new OrganizationService(organizations, members);

        assertThatThrownBy(() -> service.createOrganization("Acme Data", "owner@example.com"))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(AppErrorCode.ORG_LIMIT_REACHED));
    }

    @Test
    void createsOrganizationWithoutSlugWhenOwnerHasNoOrganization() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        when(members.findByUserEmail("owner@example.com")).thenReturn(List.of());
        when(organizations.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            organization.setId(new ObjectId());
            return organization;
        });

        Organization result = new OrganizationService(organizations, members)
            .createOrganization("Second Workspace", "owner@example.com");

        assertThat(result.getName()).isEqualTo("Second Workspace");
        assertThat(result.getSlug()).isNull();
    }

    @Test
    void legacyTrialRegistrationDoesNotCreateANewOrganizationSlug() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        when(members.findByUserEmail("owner@example.com")).thenReturn(List.of(
            Member.builder().orgId(new ObjectId()).userEmail("owner@example.com").role("owner").build()));
        when(organizations.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            organization.setId(new ObjectId());
            return organization;
        });

        Organization result = new OrganizationService(organizations, members)
            .createTrialOrganization("Second Workspace", "owner@example.com");

        assertThat(result.getSlug()).isNull();
    }

}
