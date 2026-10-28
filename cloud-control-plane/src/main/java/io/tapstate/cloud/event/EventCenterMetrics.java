package io.tapstate.cloud.event;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.tapstate.cloud.notification.DeliveryStatus;
import io.tapstate.cloud.notification.NotificationDelivery;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for the event pipeline. Counters move with the work; backlog gauges
 * are refreshed on a short interval so a scrape never runs an unbounded query.
 */
@Component
@RequiredArgsConstructor
public class EventCenterMetrics {

    private final MeterRegistry registry;
    private final MongoTemplate mongoTemplate;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    @PostConstruct
    void registerGauges() {
        Gauge.builder("tapstate.notifications.outbox.backlog", backlog, AtomicLong::get)
            .description("Notification deliveries waiting, retrying or leased")
            .register(registry);
        Gauge.builder("tapstate.notifications.outbox.oldest.pending.seconds",
                oldestPendingSeconds, AtomicLong::get)
            .description("Age of the oldest due notification delivery")
            .register(registry);
    }

    public void ingested() {
        registry.counter("tapstate.events.ingested").increment();
    }

    public void alertCreated() {
        registry.counter("tapstate.events.alerts.created").increment();
    }

    public void alertSuppressed() {
        registry.counter("tapstate.events.alerts.suppressed").increment();
    }

    public void delivered() {
        registry.counter("tapstate.notifications.delivered").increment();
    }

    public void deliveryFailed() {
        registry.counter("tapstate.notifications.failed").increment();
    }

    @Scheduled(fixedDelayString = "${tapstate.cloud.events.metrics-refresh-interval-ms:30000}")
    public void refreshBacklog() {
        long count = mongoTemplate.count(
            new Query(Criteria.where("status").in(
                DeliveryStatus.PENDING, DeliveryStatus.SENDING, DeliveryStatus.FAILED)),
            NotificationDelivery.class);
        backlog.set(count);

        NotificationDelivery oldest = mongoTemplate.findOne(
            new Query(Criteria.where("status").in(
                    DeliveryStatus.PENDING, DeliveryStatus.SENDING, DeliveryStatus.FAILED))
                .with(Sort.by(Sort.Direction.ASC, "nextAttemptAt"))
                .limit(1),
            NotificationDelivery.class);
        oldestPendingSeconds.set(oldest == null || oldest.getNextAttemptAt() == null
            ? 0
            : Math.max(0, Duration.between(oldest.getNextAttemptAt(), Instant.now()).getSeconds()));
    }
}
