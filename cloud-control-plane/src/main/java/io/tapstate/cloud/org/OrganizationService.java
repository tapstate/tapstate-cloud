package io.tapstate.cloud.org;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Organization management — create, list, membership.
 * <p>
 * Isolation boundary (ADR-0122 D3): all Cluster records, deployments,
 * and audit logs belong to exactly one Organization. Each new Organization
 * auto-receives a default TRIAL subscription with the two standard billable
 * items (Cluster Pipeline Runtime + Atlas Database Storage).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MemberRepository memberRepository;
    private final SubscriptionService subscriptionService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public Organization createOrganization(String name, String slug, String ownerEmail) {
        // Auto-generate slug if not provided
        String effectiveSlug = slug != null ? slug : generateSlug(name);

        Organization org = Organization.builder()
            .id(generateId())
            .name(name)
            .slug(effectiveSlug)
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

        // Auto-create default trial subscription with two standard billable items
        subscriptionService.createDefaultSubscription(saved.getId());

        log.info("Organization created: {} (owner: {}, orgId: {})", saved.getName(), ownerEmail, saved.getId());
        return saved;
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

        // Create default org — auto-triggers subscription creation
        String defaultOrgName = userEmail.split("@")[0] + "'s Workspace";
        return createOrganization(defaultOrgName, null, userEmail);
    }

    public List<Organization> listUserOrganizations(String userEmail) {
        List<String> orgIds = memberRepository.findByUserEmail(userEmail)
            .stream()
            .map(Member::getOrgId)
            .toList();

        return organizationRepository.findAllById(orgIds);
    }

    public boolean isMember(String orgId, String userEmail) {
        return memberRepository.existsByOrgIdAndUserEmail(orgId, userEmail);
    }

    // ========== Helpers ==========

    private String generateId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String generateSlug(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    }
}
