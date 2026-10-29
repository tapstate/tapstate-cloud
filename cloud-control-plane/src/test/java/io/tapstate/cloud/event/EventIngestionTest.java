package io.tapstate.cloud.event;

import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventIngestionTest {

    @Mock
    private PlatformEventRepository eventRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private AlertEvaluationService evaluationService;
    @Mock
    private EventCenterMetrics metrics;

    private EventCenterService service;

    @BeforeEach
    void setUp() {
        service = new EventCenterService(eventRepository, mongoTemplate, evaluationService, metrics, 30, 100);
    }

    @Test
    @DisplayName("a repeated dedupe key returns the stored event and never evaluates twice")
    void duplicateDedupeKeyReturnsExistingEvent() {
        PlatformEvent existing = PlatformEvent.builder()
            .id(new ObjectId())
            .orgId(new ObjectId())
            .source("external")
            .dedupeKey("event-1")
            .type("test.alert")
            .severity(EventSeverity.INFO)
            .occurredAt(Instant.now())
            .build();
        when(eventRepository.findByOrgIdAndSourceAndDedupeKey(
            existing.getOrgId(), "external", "event-1")).thenReturn(Optional.of(existing));

        PlatformEvent result = service.ingest(new EventIngestRequest(
            existing.getOrgId().toHexString(), "external", "test.alert", EventSeverity.INFO,
            "subject", "actor", Instant.now(), "event-1", Map.of()));

        assertThat(result).isSameAs(existing);
        verify(eventRepository, never()).save(any());
        verify(evaluationService, never()).evaluate(any(), any());
    }

    @Test
    @DisplayName("payload secrets are redacted and long strings are truncated before storage")
    void payloadIsSanitized() {
        ObjectId orgId = new ObjectId();
        when(eventRepository.findByOrgIdAndSourceAndDedupeKey(orgId, "external", "event-2"))
            .thenReturn(Optional.empty());
        when(eventRepository.save(any(PlatformEvent.class))).thenAnswer(invocation -> {
            PlatformEvent event = invocation.getArgument(0);
            event.setId(new ObjectId());
            return event;
        });

        String longText = "x".repeat(900);
        PlatformEvent result = service.ingest(new EventIngestRequest(
            orgId.toHexString(), "external", "test.alert", EventSeverity.INFO,
            "subject", "actor", Instant.now(), "event-2",
            Map.of("password", "super-secret", "note", longText)));

        assertThat(result.getPayload().get("password")).isEqualTo("[redacted]");
        assertThat((String) result.getPayload().get("note")).hasSize(500);
        verify(evaluationService).evaluate(result, result.getOccurredAt());
    }

    @Test
    @DisplayName("an invalid event type is rejected before it reaches the store")
    void invalidTypeIsRejected() {
        assertThatThrownBy(() -> service.ingest(new EventIngestRequest(
            new ObjectId().toHexString(), "external", "bad type with spaces", EventSeverity.INFO,
            "subject", "actor", Instant.now(), "event-3", Map.of())))
            .isInstanceOf(BusinessException.class)
            .satisfies(throwable -> assertThat(((BusinessException) throwable).getParams()[0].toString())
                .contains("type"));
        verify(eventRepository, never()).save(any());
    }
}
