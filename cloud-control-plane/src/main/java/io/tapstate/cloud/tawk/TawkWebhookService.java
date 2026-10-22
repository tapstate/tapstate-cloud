package io.tapstate.cloud.tawk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TawkWebhookService {

    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final TawkWebhookEventRepository eventRepository;
    private final FeishuBotClient feishuBotClient;
    private final TaskScheduler taskScheduler;

    public TawkWebhookService(
            TawkWebhookEventRepository eventRepository,
            FeishuBotClient feishuBotClient,
            @Qualifier("tawkWebhookTaskScheduler") TaskScheduler taskScheduler) {
        this.eventRepository = eventRepository;
        this.feishuBotClient = feishuBotClient;
        this.taskScheduler = taskScheduler;
    }

    public boolean accepts(String eventType) {
        return "chat:start".equals(eventType) || "ticket:create".equals(eventType);
    }

    public boolean record(String eventId, TawkWebhookPayload payload) {
        Instant now = Instant.now();
        TawkWebhookEvent event = TawkWebhookEvent.builder()
            .eventId(eventId)
            .propertyId(payload.propertyId())
            .propertyName(payload.propertyName())
            .eventType(payload.eventType())
            .recordId(payload.recordId())
            .recordNumber(payload.recordNumber())
            .deliveryStatus(TawkWebhookEvent.DeliveryStatus.PENDING)
            .attemptCount(0)
            // Leave a recovery window for the immediate attempt before the polling retry path sees it.
            .nextAttemptAt(now.plus(retryDelay(1)))
            .receivedAt(now)
            .build();
        try {
            eventRepository.insert(event);
            scheduleImmediateDelivery(eventId);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("Duplicate tawk.to event ignored: {}", eventId);
            return false;
        }
    }

    public boolean recordOfflineSubmission(String eventId, String propertyId) {
        TawkWebhookPayload payload = new TawkWebhookPayload(
            "offline:form_submit", propertyId, null, null, null);
        return record("offline:" + eventId, payload);
    }

    private void scheduleImmediateDelivery(String eventId) {
        try {
            taskScheduler.schedule(() -> deliverAcceptedEvent(eventId), Instant.now());
        } catch (RuntimeException e) {
            // The persisted event remains eligible for the scheduled recovery scan.
            log.warn("Could not schedule immediate Tawk notification: eventId={} category={}",
                eventId, e.getClass().getSimpleName());
        }
    }

    private void deliverAcceptedEvent(String eventId) {
        eventRepository.findById(eventId)
            .filter(event -> event.getDeliveryStatus() == TawkWebhookEvent.DeliveryStatus.PENDING)
            .ifPresent(event -> deliver(event, Instant.now()));
    }

    @Scheduled(
        fixedDelayString = "${tapstate.cloud.tawk.retry-interval-ms:30000}",
        scheduler = "tawkWebhookTaskScheduler")
    @SchedulerLock(name = "tawkWebhookDelivery", lockAtMostFor = "PT10M")
    public void dispatchPending() {
        Instant now = Instant.now();
        List<TawkWebhookEvent> pending = eventRepository
            .findTop20ByDeliveryStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                TawkWebhookEvent.DeliveryStatus.PENDING, now);
        for (TawkWebhookEvent event : pending) {
            deliver(event, now);
        }
    }

    private void deliver(TawkWebhookEvent event, Instant now) {
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setLastAttemptAt(now);

        FeishuBotClient.DeliveryResult result = feishuBotClient.send(
            event.getPropertyId(), event.getEventId(), formatMessage(event));
        if (result.delivered()) {
            event.setDeliveryStatus(TawkWebhookEvent.DeliveryStatus.DELIVERED);
            event.setDeliveredAt(now);
            event.setDeliveredTtlAt(now);
            event.setLastFailureCategory(null);
            event.setNextAttemptAt(null);
        } else {
            event.setLastFailureCategory(result.failureCategory());
            event.setNextAttemptAt(now.plus(retryDelay(event.getAttemptCount())));
            log.warn("Feishu notification pending retry: eventId={} category={} attempt={}",
                event.getEventId(), result.failureCategory(), event.getAttemptCount());
        }
        eventRepository.save(event);
    }

    private static Duration retryDelay(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 10);
        Duration delay = Duration.ofSeconds(30L * (1L << exponent));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private static String formatMessage(TawkWebhookEvent event) {
        String label = switch (event.getEventType()) {
            case "ticket:create" -> "new Tawk ticket";
            case "offline:form_submit" -> "new offline feedback";
            default -> "new online feedback";
        };
        StringBuilder message = new StringBuilder()
            .append("Tapstate Cloud feedback notification\n")
            .append("Property: ").append(nonBlank(event.getPropertyName(), event.getPropertyId())).append('\n')
            .append("Event: ").append(event.getEventType()).append(" (").append(label).append(")\n");
        if (event.getRecordNumber() != null) {
            message.append("Ticket: ").append(event.getRecordNumber()).append('\n');
        } else if (event.getRecordId() != null) {
            message.append("Record ID: ").append(event.getRecordId()).append('\n');
        }
        message.append("Inbox: https://dashboard.tawk.to/#/inbox/")
            .append(event.getPropertyId()).append("/all");
        return message.toString();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
