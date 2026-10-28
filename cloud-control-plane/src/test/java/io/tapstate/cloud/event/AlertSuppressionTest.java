package io.tapstate.cloud.event;

import io.tapstate.cloud.notification.NotificationDeliveryService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertSuppressionTest {

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
    @DisplayName("an alert inside the quiet window is recorded as suppressed and not delivered")
    void quietHoursSuppressDelivery() {
        ObjectId orgId = new ObjectId();
        AlertPolicy policy = AlertPolicy.builder()
            .id(new ObjectId())
            .orgId(orgId)
            .name("night")
            .enabled(true)
            .eventTypes(List.of("*"))
            .minSeverity(EventSeverity.INFO)
            .recipients(List.of("owner@example.com"))
            .channel("EMAIL")
            .cooldownSeconds(60)
            .quietHoursStart("22:00")
            .quietHoursEnd("07:00")
            .timezone("UTC")
            .build();
        when(policyRepository.findByOrgIdAndEnabledTrue(orgId)).thenReturn(List.of(policy));
        when(alertRepository.save(any(AlertRecord.class))).thenAnswer(invocation -> {
            AlertRecord alert = invocation.getArgument(0);
            alert.setId(new ObjectId());
            return alert;
        });

        Instant atNight = Instant.parse("2026-10-22T23:30:00Z");
        int created = service.evaluate(event(orgId, atNight), atNight);

        assertThat(created).isEqualTo(1);
        verify(alertRepository).save(org.mockito.ArgumentMatchers.argThat(
            alert -> alert.getStatus() == AlertStatus.SUPPRESSED));
        verify(deliveryService, never()).createDeliveries(any(), any());
    }

    @Test
    @DisplayName("quiet hours cross midnight and are evaluated in the policy timezone")
    void quietHoursCrossMidnight() {
        AlertPolicy policy = AlertPolicy.builder()
            .quietHoursStart("22:00")
            .quietHoursEnd("07:00")
            .timezone("Asia/Shanghai")
            .build();

        assertThat(QuietHours.isQuiet(policy, Instant.parse("2026-10-22T15:00:00Z"))).isTrue();
        assertThat(QuietHours.isQuiet(policy, Instant.parse("2026-10-22T04:00:00Z"))).isFalse();
    }

    @Test
    @DisplayName("no quiet window means the alert is deliverable")
    void noQuietWindowDoesNotSuppress() {
        AlertPolicy policy = AlertPolicy.builder().timezone("UTC").build();
        assertThat(QuietHours.isQuiet(policy, Instant.parse("2026-10-22T23:30:00Z"))).isFalse();
    }

    private PlatformEvent event(ObjectId orgId, Instant at) {
        return PlatformEvent.builder()
            .id(new ObjectId())
            .orgId(orgId)
            .source("test")
            .dedupeKey("event-" + at)
            .type("pipeline.failed")
            .severity(EventSeverity.WARNING)
            .subject("subject")
            .actor("owner@example.com")
            .occurredAt(at)
            .build();
    }
}
