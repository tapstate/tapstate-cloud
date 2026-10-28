package io.tapstate.cloud.event;

import io.tapstate.cloud.notification.NotificationDeliveryService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertPolicyEvaluatorTest {

    @Mock
    private AlertPolicyRepository policyRepository;
    @Mock
    private AlertRepository alertRepository;
    @Mock
    private NotificationDeliveryService deliveryService;
    @Mock
    private EventCenterMetrics metrics;

    private AlertEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new AlertEvaluationService(policyRepository, alertRepository, deliveryService, metrics);
    }

    @Test
    @DisplayName("a matching policy creates one alert and one delivery fan-out")
    void matchingPolicyCreatesAlertAndDeliveries() {
        ObjectId orgId = new ObjectId();
        AlertPolicy policy = policy(orgId, List.of("pipeline.*"), EventSeverity.WARNING);
        when(policyRepository.findByOrgIdAndEnabledTrue(orgId)).thenReturn(List.of(policy));
        when(alertRepository.save(any(AlertRecord.class))).thenAnswer(invocation -> {
            AlertRecord alert = invocation.getArgument(0);
            alert.setId(new ObjectId());
            return alert;
        });

        int created = service.evaluate(event(orgId, "pipeline.failed", EventSeverity.CRITICAL), Instant.now());

        assertThat(created).isEqualTo(1);
        verify(deliveryService).createDeliveries(any(AlertRecord.class), any(AlertPolicy.class));
    }

    @Test
    @DisplayName("a non-matching event type produces no alert")
    void nonMatchingTypeDoesNotAlert() {
        ObjectId orgId = new ObjectId();
        AlertPolicy policy = policy(orgId, List.of("pipeline.*"), EventSeverity.INFO);
        when(policyRepository.findByOrgIdAndEnabledTrue(orgId)).thenReturn(List.of(policy));

        int created = service.evaluate(event(orgId, "source.failed", EventSeverity.CRITICAL), Instant.now());

        assertThat(created).isZero();
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("an event below the policy severity threshold is ignored")
    void severityBelowThresholdDoesNotAlert() {
        ObjectId orgId = new ObjectId();
        AlertPolicy policy = policy(orgId, List.of("*"), EventSeverity.CRITICAL);
        when(policyRepository.findByOrgIdAndEnabledTrue(orgId)).thenReturn(List.of(policy));

        int created = service.evaluate(event(orgId, "pipeline.failed", EventSeverity.WARNING), Instant.now());

        assertThat(created).isZero();
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("the unique policy/dedupe key suppresses a concurrent duplicate")
    void duplicateAlertIsSuppressed() {
        ObjectId orgId = new ObjectId();
        AlertPolicy policy = policy(orgId, List.of("*"), EventSeverity.INFO);
        when(policyRepository.findByOrgIdAndEnabledTrue(orgId)).thenReturn(List.of(policy));
        when(alertRepository.save(any(AlertRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));

        int created = service.evaluate(event(orgId, "pipeline.failed", EventSeverity.INFO), Instant.now());

        assertThat(created).isZero();
        verify(deliveryService, never()).createDeliveries(any(), any());
    }

    private AlertPolicy policy(ObjectId orgId, List<String> types, EventSeverity min) {
        return AlertPolicy.builder()
            .id(new ObjectId())
            .orgId(orgId)
            .name("policy")
            .enabled(true)
            .eventTypes(types)
            .minSeverity(min)
            .recipients(List.of("owner@example.com"))
            .channel("EMAIL")
            .cooldownSeconds(60)
            .timezone("UTC")
            .build();
    }

    private PlatformEvent event(ObjectId orgId, String type, EventSeverity severity) {
        return PlatformEvent.builder()
            .id(new ObjectId())
            .orgId(orgId)
            .source("test")
            .dedupeKey("event-" + type)
            .type(type)
            .severity(severity)
            .subject("subject")
            .actor("owner@example.com")
            .occurredAt(Instant.parse("2026-10-22T10:00:00Z"))
            .build();
    }
}
