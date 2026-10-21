package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class TawkWebhookServiceTest {

    @Mock
    private TawkWebhookEventRepository eventRepository;

    @Mock
    private FeishuBotClient feishuBotClient;

    @Mock
    private TaskScheduler taskScheduler;

    @InjectMocks
    private TawkWebhookService webhookService;

    @Test
    void storesOnlyTheMinimalEventFieldsAndIgnoresDuplicateIds() {
        TawkWebhookPayload payload = new TawkWebhookPayload(
            "chat:start", "property-1", "Cloud Dev", "chat-123", null);
        when(eventRepository.insert(any(TawkWebhookEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(webhookService.record("event-1", payload));

        ArgumentCaptor<TawkWebhookEvent> eventCaptor = ArgumentCaptor.forClass(TawkWebhookEvent.class);
        verify(eventRepository).insert(eventCaptor.capture());
        TawkWebhookEvent stored = eventCaptor.getValue();
        assertEquals("event-1", stored.getEventId());
        assertEquals("chat-123", stored.getRecordId());
        assertEquals(TawkWebhookEvent.DeliveryStatus.PENDING, stored.getDeliveryStatus());

        when(eventRepository.insert(any(TawkWebhookEvent.class)))
            .thenThrow(new DuplicateKeyException("duplicate event"));
        assertFalse(webhookService.record("event-1", payload));
        verify(taskScheduler, org.mockito.Mockito.times(1))
            .schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void storesOfflineSubmissionAsASeparateIdempotentEventWithoutFormData() {
        when(eventRepository.insert(any(TawkWebhookEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(webhookService.recordOfflineSubmission(
            "d63a5409-7796-48cb-9c94-f0420dcb0c31", "property-1"));

        ArgumentCaptor<TawkWebhookEvent> eventCaptor = ArgumentCaptor.forClass(TawkWebhookEvent.class);
        verify(eventRepository).insert(eventCaptor.capture());
        TawkWebhookEvent stored = eventCaptor.getValue();
        assertEquals("offline:d63a5409-7796-48cb-9c94-f0420dcb0c31", stored.getEventId());
        assertEquals("offline:form_submit", stored.getEventType());
        assertEquals("property-1", stored.getPropertyId());
        assertNull(stored.getRecordId());
        assertNull(stored.getPropertyName());
    }

    @Test
    void schedulesAnAcceptedEventForImmediateDelivery() {
        TawkWebhookPayload payload = new TawkWebhookPayload(
            "chat:start", "property-1", "Cloud Dev", "chat-123", null);
        when(eventRepository.insert(any(TawkWebhookEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(feishuBotClient.send(anyString())).thenReturn(FeishuBotClient.DeliveryResult.success());

        ArgumentCaptor<Runnable> deliveryTask = ArgumentCaptor.forClass(Runnable.class);
        assertTrue(webhookService.record("event-immediate", payload));
        verify(taskScheduler).schedule(deliveryTask.capture(), any(Instant.class));

        TawkWebhookEvent accepted = TawkWebhookEvent.builder()
            .eventId("event-immediate")
            .propertyId("property-1")
            .propertyName("Cloud Dev")
            .eventType("chat:start")
            .recordId("chat-123")
            .deliveryStatus(TawkWebhookEvent.DeliveryStatus.PENDING)
            .attemptCount(0)
            .build();
        when(eventRepository.findById("event-immediate")).thenReturn(Optional.of(accepted));

        deliveryTask.getValue().run();

        assertEquals(TawkWebhookEvent.DeliveryStatus.DELIVERED, accepted.getDeliveryStatus());
        verify(feishuBotClient).send(anyString());
    }

    @Test
    void failedDeliveryRemainsPersistedForRetryWithoutSendingFeedbackContent() {
        Instant now = Instant.now();
        TawkWebhookEvent pending = TawkWebhookEvent.builder()
            .eventId("event-2")
            .propertyId("property-1")
            .propertyName("Cloud Prod")
            .eventType("chat:start")
            .recordId("chat-123")
            .deliveryStatus(TawkWebhookEvent.DeliveryStatus.PENDING)
            .attemptCount(0)
            .nextAttemptAt(now.minusSeconds(1))
            .build();
        when(eventRepository.findTop20ByDeliveryStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            org.mockito.ArgumentMatchers.eq(TawkWebhookEvent.DeliveryStatus.PENDING),
            org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of(pending));
        when(feishuBotClient.send(anyString())).thenReturn(FeishuBotClient.DeliveryResult.failed("network_error"));

        webhookService.dispatchPending();

        assertEquals(1, pending.getAttemptCount());
        assertEquals("network_error", pending.getLastFailureCategory());
        assertTrue(pending.getNextAttemptAt().isAfter(pending.getLastAttemptAt()));
        verify(eventRepository).save(pending);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(feishuBotClient).send(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("Tapstate Cloud feedback notification"));
        assertTrue(messageCaptor.getValue().contains("Property: Cloud Prod"));
        assertTrue(messageCaptor.getValue().contains("Event: chat:start (new online feedback)"));
        assertTrue(messageCaptor.getValue().contains(
            "https://dashboard.tawk.to/#/inbox/property-1/all"));
        assertFalse(messageCaptor.getValue().contains("private-feedback"));
    }

    @Test
    void offlineSubmissionSendsOnlyAPropertyLevelInboxNotice() {
        Instant now = Instant.now();
        TawkWebhookEvent pending = TawkWebhookEvent.builder()
            .eventId("offline:d63a5409-7796-48cb-9c94-f0420dcb0c31")
            .propertyId("property-1")
            .eventType("offline:form_submit")
            .deliveryStatus(TawkWebhookEvent.DeliveryStatus.PENDING)
            .attemptCount(0)
            .nextAttemptAt(now.minusSeconds(1))
            .build();
        when(eventRepository.findTop20ByDeliveryStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            org.mockito.ArgumentMatchers.eq(TawkWebhookEvent.DeliveryStatus.PENDING),
            org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of(pending));
        when(feishuBotClient.send(anyString())).thenReturn(FeishuBotClient.DeliveryResult.success());

        webhookService.dispatchPending();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(feishuBotClient).send(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("Event: offline:form_submit (new offline feedback)"));
        assertTrue(messageCaptor.getValue().contains("https://dashboard.tawk.to/#/inbox/property-1/all"));
        verify(eventRepository).save(pending);
    }

    @Test
    void pendingDeliveryUsesAnIsolatedScheduler() throws Exception {
        Scheduled scheduled = TawkWebhookService.class
            .getMethod("dispatchPending")
            .getAnnotation(Scheduled.class);

        assertEquals("tawkWebhookTaskScheduler", scheduled.scheduler());
    }

    @Test
    void doesNotCallFeishuWhenThereAreNoDueEvents() {
        when(eventRepository.findTop20ByDeliveryStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            any(), any())).thenReturn(List.of());

        webhookService.dispatchPending();

        verify(feishuBotClient, never()).send(anyString());
    }
}
