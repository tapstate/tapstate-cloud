package io.tapstate.cloud.org;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Organization membership and administrator checks shared by the event center
 * and alert-policy APIs. Cross-organization access is always rejected here,
 * never left to a query filter that could return an empty list.
 */
@Service
@RequiredArgsConstructor
public class OrgAccessService {

    private final OrganizationRepository organizationRepository;
    private final MemberRepository memberRepository;

    public ObjectId parseOrgId(String orgId) {
        try {
            return new ObjectId(orgId);
        } catch (RuntimeException e) {
            throw new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId);
        }
    }

    public Organization requireMember(String orgId, String userEmail) {
        ObjectId id = parseOrgId(orgId);
        Organization org = organizationRepository.findById(id)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId));
        if (!memberRepository.existsByOrgIdAndUserEmail(id, userEmail)) {
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }
        return org;
    }

    public Organization requireAdmin(String orgId, String userEmail) {
        Organization org = requireMember(orgId, userEmail);
        if (!isAdmin(org, userEmail)) {
            throw new BusinessException(AppErrorCode.ORG_ADMIN_REQUIRED, 403);
        }
        return org;
    }

    public boolean isAdmin(Organization org, String userEmail) {
        if (userEmail == null) {
            return false;
        }
        if (org.getOwnerEmail() != null && org.getOwnerEmail().equalsIgnoreCase(userEmail)) {
            return true;
        }
        return memberRepository.findByOrgIdAndUserEmail(org.getId(), userEmail)
            .map(member -> "owner".equalsIgnoreCase(member.getRole())
                || "admin".equalsIgnoreCase(member.getRole()))
            .orElse(false);
    }
}
