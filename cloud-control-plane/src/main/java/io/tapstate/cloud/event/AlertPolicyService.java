package io.tapstate.cloud.event;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Alert-policy administration. Policies are organization-scoped and may only be
 * changed by an organization administrator.
 */
@Service
@RequiredArgsConstructor
public class AlertPolicyService {

    static final int MAX_POLICIES_PER_ORG = 50;
    static final int MAX_RECIPIENTS = 20;
    static final int MAX_EVENT_TYPES = 20;
    static final long MAX_COOLDOWN_SECONDS = 86_400;

    private final AlertPolicyRepository policyRepository;

    public List<AlertPolicy> list(ObjectId orgId) {
        return policyRepository.findByOrgIdOrderByCreatedAtDesc(orgId);
    }

    public AlertPolicy create(ObjectId orgId, AlertPolicyRequest request) {
        if (policyRepository.countByOrgId(orgId) >= MAX_POLICIES_PER_ORG) {
            throw new BusinessException(AppErrorCode.ALERT_POLICY_LIMIT_REACHED, 409, orgId.toHexString());
        }
        AlertPolicy policy = AlertPolicy.builder()
            .orgId(orgId)
            .name(requireName(request.name()))
            .enabled(request.enabled() == null || request.enabled())
            .eventTypes(validateEventTypes(request.eventTypes()))
            .minSeverity(request.minSeverity() == null ? EventSeverity.INFO : request.minSeverity())
            .recipients(validateRecipients(request.recipients()))
            .channel(validateChannel(request.channel()))
            .cooldownSeconds(validateCooldown(request.cooldownSeconds()))
            .quietHoursStart(request.quietHoursStart())
            .quietHoursEnd(request.quietHoursEnd())
            .timezone(validateTimezone(request.timezone()))
            .build();
        validateQuietHours(policy);
        return policyRepository.save(policy);
    }

    public AlertPolicy update(ObjectId orgId, String policyId, AlertPolicyRequest request) {
        AlertPolicy policy = requirePolicy(orgId, policyId);
        if (request.name() != null) {
            policy.setName(requireName(request.name()));
        }
        if (request.enabled() != null) {
            policy.setEnabled(request.enabled());
        }
        if (request.eventTypes() != null) {
            policy.setEventTypes(validateEventTypes(request.eventTypes()));
        }
        if (request.minSeverity() != null) {
            policy.setMinSeverity(request.minSeverity());
        }
        if (request.recipients() != null) {
            policy.setRecipients(validateRecipients(request.recipients()));
        }
        if (request.channel() != null) {
            policy.setChannel(validateChannel(request.channel()));
        }
        if (request.cooldownSeconds() != null) {
            policy.setCooldownSeconds(validateCooldown(request.cooldownSeconds()));
        }
        if (request.quietHoursStart() != null || request.quietHoursEnd() != null) {
            policy.setQuietHoursStart(request.quietHoursStart());
            policy.setQuietHoursEnd(request.quietHoursEnd());
        }
        if (request.timezone() != null) {
            policy.setTimezone(validateTimezone(request.timezone()));
        }
        validateQuietHours(policy);
        return policyRepository.save(policy);
    }

    public void delete(ObjectId orgId, String policyId) {
        policyRepository.delete(requirePolicy(orgId, policyId));
    }

    public AlertPolicy requirePolicy(ObjectId orgId, String policyId) {
        ObjectId id;
        try {
            id = new ObjectId(policyId);
        } catch (RuntimeException e) {
            throw new BusinessException(AppErrorCode.ALERT_POLICY_NOT_FOUND, 404, policyId);
        }
        AlertPolicy policy = policyRepository.findById(id)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ALERT_POLICY_NOT_FOUND, 404, policyId));
        if (!orgId.equals(policy.getOrgId())) {
            throw new BusinessException(AppErrorCode.ALERT_POLICY_NOT_FOUND, 404, policyId);
        }
        return policy;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 120) {
            throw invalid("name must be at most 120 characters");
        }
        return trimmed;
    }

    private List<String> validateEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return List.of("*");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : eventTypes) {
            if (raw == null || raw.isBlank()) {
                throw invalid("event type patterns must not be blank");
            }
            String pattern = raw.trim();
            if (pattern.length() > 120 || !pattern.matches("[A-Za-z0-9._:*-]+")) {
                throw invalid("invalid event type pattern: " + pattern);
            }
            unique.add(pattern);
        }
        if (unique.size() > MAX_EVENT_TYPES) {
            throw invalid("at most " + MAX_EVENT_TYPES + " event type patterns are allowed");
        }
        return List.copyOf(unique);
    }

    /**
     * Alert recipients are addresses, not accounts: an administrator may route
     * alerts to any valid mailbox, including one outside the organization.
     */
    private List<String> validateRecipients(List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            throw invalid("at least one recipient is required");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : recipients) {
            if (raw == null || raw.isBlank()) {
                throw invalid("recipient emails must not be blank");
            }
            String email = raw.trim().toLowerCase(Locale.ROOT);
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw invalid("invalid recipient email: " + raw);
            }
            unique.add(email);
        }
        if (unique.size() > MAX_RECIPIENTS) {
            throw invalid("at most " + MAX_RECIPIENTS + " recipients are allowed");
        }
        return List.copyOf(unique);
    }

    private String validateChannel(String channel) {
        String value = channel == null || channel.isBlank() ? "EMAIL" : channel.trim().toUpperCase(Locale.ROOT);
        if (!"EMAIL".equals(value)) {
            throw invalid("only the EMAIL channel is supported in this release");
        }
        return value;
    }

    private long validateCooldown(Long cooldownSeconds) {
        long value = cooldownSeconds == null ? 300 : cooldownSeconds;
        if (value < 0 || value > MAX_COOLDOWN_SECONDS) {
            throw invalid("cooldownSeconds must be between 0 and " + MAX_COOLDOWN_SECONDS);
        }
        return value;
    }

    private String validateTimezone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
        try {
            ZoneId.of(value);
        } catch (RuntimeException e) {
            throw invalid("unknown timezone: " + value);
        }
        return value;
    }

    private void validateQuietHours(AlertPolicy policy) {
        String start = policy.getQuietHoursStart();
        String end = policy.getQuietHoursEnd();
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) {
            policy.setQuietHoursStart(null);
            policy.setQuietHoursEnd(null);
            return;
        }
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            throw invalid("quietHoursStart and quietHoursEnd must be set together");
        }
        LocalTime startTime;
        LocalTime endTime;
        try {
            startTime = LocalTime.parse(start);
            endTime = LocalTime.parse(end);
        } catch (DateTimeParseException e) {
            throw invalid("quiet hours must use HH:mm");
        }
        if (startTime.equals(endTime)) {
            throw invalid("quiet hours start and end must differ");
        }
    }

    private BusinessException invalid(String reason) {
        return new BusinessException(AppErrorCode.ALERT_POLICY_INVALID, 400, reason);
    }
}
