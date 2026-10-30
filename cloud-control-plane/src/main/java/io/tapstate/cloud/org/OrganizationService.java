package io.tapstate.cloud.org;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;

/**
 * Organization management — create, list, membership.
 * <p>
 * Isolation boundary (ADR-0122 D3): all Cluster records, deployments,
 * and audit logs belong to exactly one Organization. A new Organization
 * starts with no subscriptions; subscriptions (each carrying one Cluster +
 * one Atlas MdbAllocation) are created explicitly via the subscription API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public Organization createTrialOrganization(String name, String ownerEmail) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new BusinessException(AppErrorCode.BAD_REQUEST, 400);
        }
        return saveOrganization(name.trim(), ownerEmail);
    }

    @Transactional
    public Organization createOrganization(String name, String ownerEmail) {
        validateOrganizationName(name);
        if (!memberRepository.findByUserEmail(ownerEmail).isEmpty()) {
            throw new BusinessException(AppErrorCode.ORG_LIMIT_REACHED, 409);
        }
        return saveOrganization(name.trim(), ownerEmail);
    }

    private Organization saveOrganization(String name, String ownerEmail) {
        Organization org = Organization.builder()
            .name(name)
            .ownerEmail(ownerEmail)
            .status("active")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        Organization saved = organizationRepository.save(org);

        // Add owner as member
        Member ownerMember = Member.builder()
            .orgId(saved.getId())
            .userEmail(ownerEmail)
            .role("owner")
            .joinedAt(Instant.now())
            .build();
        memberRepository.save(ownerMember);

        log.info("Organization created: {} (owner: {}, orgId: {})", saved.getName(), ownerEmail, saved.getId());
        return saved;
    }

    private void validateOrganizationName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new BusinessException(AppErrorCode.BAD_REQUEST, 400);
        }
    }

    @Transactional
    public Organization createDefaultOrganizationForUser(String userEmail) {
        // Check if user already has an org
        List<Member> existing = memberRepository.findByUserEmail(userEmail);
        if (!existing.isEmpty()) {
            return organizationRepository.findById(existing.get(0).getOrgId())
                .orElseThrow(() -> new BusinessException(
                    AppErrorCode.ORG_NOT_FOUND, 404, existing.get(0).getOrgId()));
        }

        String defaultOrgName = userEmail.split("@")[0] + "'s Workspace";
        return createOrganization(defaultOrgName, userEmail);
    }

    public List<Organization> listUserOrganizations(String userEmail) {
        List<ObjectId> orgIds = memberRepository.findByUserEmail(userEmail)
            .stream()
            .map(Member::getOrgId)
            .toList();

        return organizationRepository.findAllById(orgIds);
    }

    public boolean isMember(String orgId, String userEmail) {
        return memberRepository.existsByOrgIdAndUserEmail(new ObjectId(orgId), userEmail);
    }

}
