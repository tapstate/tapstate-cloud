package io.tapstate.cloud.event;

import io.tapstate.cloud.notification.NotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Evaluates an ingested event against the organization's enabled policies.
 * <p>
 * Cooldown is enforced by a unique {@code (policyId, dedupeKey)} index rather
 * than a read-then-write check, so concurrent replicas evaluate the same event
 * window exactly once. Quiet hours produce a visible suppressed alert and no
 * delivery, and are not replayed later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertEvaluationService {

    private final AlertPolicyRepository policyRepository;
    private final AlertRepository alertRepository;
    private final NotificationDeliveryService deliveryService;
    private final EventCenterMetrics metrics;

    public int evaluate(PlatformEvent event, Instant now) {
        if (event.getOrgId() == null) {
            return 0;
        }
        List<AlertPolicy> policies = policyRepository.findByOrgIdAndEnabledTrue(event.getOrgId());
        int created = 0;
        for (AlertPolicy policy : policies) {
            if (!matches(policy, event)) {
                continue;
            }
            AlertRecord alert = AlertRecord.builder()
                .orgId(event.getOrgId())
                .policyId(policy.getId())
                .policyName(policy.getName())
                .eventId(event.getId())
                .eventType(event.getType())
                .severity(event.getSeverity())
                .subject(event.getSubject())
                .actor(event.getActor())
                .status(QuietHours.isQuiet(policy, now) ? AlertStatus.SUPPRESSED : AlertStatus.PENDING)
                .dedupeKey(dedupeKey(policy, event))
                .createdAt(now)
                .build();
            try {
                AlertRecord saved = alertRepository.save(alert);
                created++;
                if (saved.getStatus() == AlertStatus.PENDING) {
                    metrics.alertCreated();
                    deliveryService.createDeliveries(saved, policy);
                } else {
                    metrics.alertSuppressed();
                    log.info("Alert {} suppressed by quiet hours for policy {}",
                        saved.getId(), policy.getId());
                }
            } catch (DuplicateKeyException e) {
                log.debug("Alert suppressed by cooldown for policy {} event {}",
                    policy.getId(), event.getId());
            }
        }
        return created;
    }

    boolean matches(AlertPolicy policy, PlatformEvent event) {
        if (event.getSeverity() == null || policy.getMinSeverity() == null
            || !event.getSeverity().atLeast(policy.getMinSeverity())) {
            return false;
        }
        List<String> patterns = policy.getEventTypes();
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        for (String pattern : patterns) {
            if ("*".equals(pattern) || pattern.equals(event.getType())) {
                return true;
            }
            if (pattern.endsWith("*") && event.getType() != null) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (event.getType().startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String dedupeKey(AlertPolicy policy, PlatformEvent event) {
        if (policy.getCooldownSeconds() <= 0) {
            return event.getId() == null ? event.getDedupeKey() : event.getId().toHexString();
        }
        long bucket = event.getOccurredAt().getEpochSecond() / policy.getCooldownSeconds();
        return event.getType() + ":" + bucket;
    }
}
