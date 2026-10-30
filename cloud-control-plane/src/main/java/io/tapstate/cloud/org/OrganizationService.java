package io.tapstate.cloud.org;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
        return saveOrganization(name.trim(), "trial-" + java.util.UUID.randomUUID(), ownerEmail, true, null);
    }

    @Transactional
    public Organization createOrganization(String name, String slug, String ownerEmail) {
        if (!memberRepository.findByUserEmail(ownerEmail).isEmpty()) {
            throw new BusinessException(AppErrorCode.ORG_LIMIT_REACHED, 409);
        }
        return saveOrganization(name, slug, ownerEmail, true, ownerCreationKey(ownerEmail));
    }

    @Transactional
    public Organization createOrganizationWithoutSlug(String name, String ownerEmail,
                                                       boolean allowExistingMembership) {
        return createOrganizationWithoutSlug(name, ownerEmail, allowExistingMembership,
            ownerCreationKey(ownerEmail));
    }

    @Transactional
    public Organization createOrganizationWithoutSlug(String name, String ownerEmail,
                                                       boolean allowExistingMembership, String creationKey) {
        validateOrganizationName(name);
        Optional<Organization> existing = findCreatedOrganization(creationKey, ownerEmail, name);
        if (existing.isPresent()) return existing.get();
        List<Member> existingMemberships = memberRepository.findByUserEmail(ownerEmail);
        if (!allowExistingMembership && !existingMemberships.isEmpty()) {
            throw new BusinessException(AppErrorCode.ORG_LIMIT_REACHED, 409);
        }
        return saveOrganization(name.trim(), null, ownerEmail, false, creationKey);
    }

    public Optional<Organization> findCreatedOrganization(String creationKey, String ownerEmail, String name) {
        Organization existing = organizationRepository.findByCreationKey(creationKey).orElse(null);
        if (existing == null) return Optional.empty();
        if (existing.getName().equals(name.trim())
                && ownerCreationKey(existing.getOwnerEmail()).equals(ownerCreationKey(ownerEmail))) {
            return Optional.of(existing);
        }
        throw new BusinessException(AppErrorCode.ORG_LIMIT_REACHED, 409);
    }

    private Organization saveOrganization(String name, String slug, String ownerEmail,
                                          boolean generateSlugWhenMissing, String creationKey) {
        String effectiveSlug = slug;
        if (effectiveSlug == null && generateSlugWhenMissing) {
            effectiveSlug = generateSlug(name);
        }

        Organization org = Organization.builder()
            .name(name)
            .slug(effectiveSlug)
            .ownerEmail(ownerEmail)
            .creationKey(creationKey)
            .status("active")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        Organization saved;
        try {
            saved = organizationRepository.save(org);
        } catch (DuplicateKeyException exception) {
            String detail = exception.getMessage() == null ? "" : exception.getMessage();
            if (detail.contains("organization_creation_key_unique")) {
                throw new BusinessException(AppErrorCode.ORG_LIMIT_REACHED, 409);
            }
            throw exception;
        }

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

    private String ownerCreationKey(String ownerEmail) {
        return "owner:" + ownerEmail.trim().toLowerCase(Locale.ROOT);
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
        return createOrganization(defaultOrgName, null, userEmail);
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

    // ========== Helpers ==========

    private String generateSlug(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    }
}
