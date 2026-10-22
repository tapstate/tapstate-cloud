package io.tapstate.cloud.notification;

import io.tapstate.cloud.event.AlertPolicy;
import io.tapstate.cloud.event.AlertRecord;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable notification outbox.
 * <p>
 * Delivery is at-least-once with a bounded retry budget. A row is claimed with a
 * conditional {@code findAndModify}, so two replicas cannot both send it; a
 * crashed worker's lease expires and the row becomes claimable again.
 */
@Service
@Slf4j
public class NotificationDeliveryService {

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final NotificationDeliveryRepository deliveryRepository;
    private final MongoTemplate mongoTemplate;
    private final Map<String, NotificationChannel> channels;
    private final io.tapstate.cloud.event.EventCenterMetrics metrics;
    private final int maxAttempts;
    private final long retryBaseSeconds;
    private final long leaseSeconds;

    public NotificationDeliveryService(
            NotificationDeliveryRepository deliveryRepository,
            MongoTemplate mongoTemplate,
            List<NotificationChannel> channels,
            io.tapstate.cloud.event.EventCenterMetrics metrics,
            @Value("${tapstate.cloud.notifications.max-attempts:5}") int maxAttempts,
            @Value("${tapstate.cloud.notifications.retry-base-seconds:30}") long retryBaseSeconds,
            @Value("${tapstate.cloud.notifications.lease-seconds:60}") long leaseSeconds) {
        this.deliveryRepository = deliveryRepository;
        this.mongoTemplate = mongoTemplate;
        this.channels = new HashMap<>();
        for (NotificationChannel channel : channels) {
            this.channels.put(channel.name().toUpperCase(), channel);
        }
        this.metrics = metrics;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBaseSeconds = Math.max(1, retryBaseSeconds);
        this.leaseSeconds = Math.max(10, leaseSeconds);
    }

    /**
     * Fan an alert out to every policy recipient. The unique index makes a
     * repeated policy evaluation a no-op for rows that already exist.
     */
    public void createDeliveries(AlertRecord alert, AlertPolicy policy) {
        for (String recipient : policy.getRecipients()) {
            NotificationDelivery delivery = NotificationDelivery.builder()
                .orgId(alert.getOrgId())
                .alertId(alert.getId())
                .channel(policy.getChannel())
                .recipient(recipient)
                .subject(subject(alert))
                .body(body(alert))
                .status(DeliveryStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
            try {
                deliveryRepository.save(delivery);
            } catch (DuplicateKeyException e) {
                log.debug("Delivery already exists for alert {} recipient {} — skipping",
                    alert.getId(), recipient);
            }
        }
    }

    /** Scheduled drain. ShedLock keeps one replica polling at a time. */
    @Scheduled(fixedDelayString = "${tapstate.cloud.notifications.poll-interval-ms:5000}")
    @SchedulerLock(name = "notificationDeliveryLock", lockAtMostFor = "5m")
    public void drain() {
        processDue(50);
    }

    /**
     * Claim and send up to {@code batchSize} due rows. Public so tests and
     * operators can drive the same path the scheduler uses.
     *
     * @return number of rows this call sent successfully
     */
    public int processDue(int batchSize) {
        Instant now = Instant.now();
        Query candidates = new Query(dueCriteria(now))
            .with(Sort.by(Sort.Direction.ASC, "nextAttemptAt"))
            .limit(Math.max(1, batchSize));
        List<NotificationDelivery> rows = mongoTemplate.find(candidates, NotificationDelivery.class);
        int sent = 0;
        for (NotificationDelivery row : rows) {
            String leaseOwner = UUID.randomUUID().toString();
            NotificationDelivery claimed = claim(row.getId(), now, leaseOwner);
            if (claimed == null) {
                continue;
            }
            if (send(claimed, leaseOwner)) {
                sent++;
            }
        }
        return sent;
    }

    private NotificationDelivery claim(ObjectId id, Instant now, String leaseOwner) {
        Query query = new Query(new Criteria().andOperator(
            Criteria.where("_id").is(id),
            dueCriteria(now)));
        Update update = new Update()
            .set("status", DeliveryStatus.SENDING)
            .set("leaseOwner", leaseOwner)
            .set("leaseUntil", now.plusSeconds(leaseSeconds))
            .set("updatedAt", now);
        return mongoTemplate.findAndModify(query, update,
            FindAndModifyOptions.options().returnNew(true), NotificationDelivery.class);
    }

    private boolean send(NotificationDelivery delivery, String leaseOwner) {
        NotificationChannel channel = channels.get(delivery.getChannel().toUpperCase());
        if (channel == null) {
            fail(delivery, leaseOwner, "channel is not configured: " + delivery.getChannel());
            return false;
        }
        try {
            channel.send(new NotificationMessage(delivery.getRecipient(), delivery.getSubject(), delivery.getBody()));
            markSent(delivery.getId(), leaseOwner);
            metrics.delivered();
            return true;
        } catch (RuntimeException e) {
            fail(delivery, leaseOwner, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private void markSent(ObjectId id, String leaseOwner) {
        Instant now = Instant.now();
        mongoTemplate.updateFirst(
            new Query(new Criteria().andOperator(
                Criteria.where("_id").is(id),
                Criteria.where("status").is(DeliveryStatus.SENDING),
                Criteria.where("leaseOwner").is(leaseOwner))),
            new Update()
                .set("status", DeliveryStatus.SENT)
                .set("sentAt", now)
                .set("updatedAt", now)
                .set("nextAttemptAt", null)
                .set("leaseOwner", null)
                .set("leaseUntil", null),
            NotificationDelivery.class);
    }

    private void fail(NotificationDelivery delivery, String leaseOwner, String error) {
        Instant now = Instant.now();
        int attempts = delivery.getAttempts() + 1;
        boolean dead = attempts >= maxAttempts;
        Update update = new Update()
            .set("attempts", attempts)
            .set("status", dead ? DeliveryStatus.DEAD : DeliveryStatus.FAILED)
            .set("lastError", truncate(error, 500))
            .set("updatedAt", now)
            .set("leaseOwner", null)
            .set("leaseUntil", null);
        if (dead) {
            update.set("nextAttemptAt", null);
        } else {
            update.set("nextAttemptAt", now.plusSeconds(backoffSeconds(attempts)));
        }
        mongoTemplate.updateFirst(
            new Query(new Criteria().andOperator(
                Criteria.where("_id").is(delivery.getId()),
                Criteria.where("status").is(DeliveryStatus.SENDING),
                Criteria.where("leaseOwner").is(leaseOwner))),
            update,
            NotificationDelivery.class);
        metrics.deliveryFailed();
        log.warn("Notification delivery {} failed ({}/{}): {}",
            delivery.getId(), attempts, maxAttempts, error);
    }

    private long backoffSeconds(int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 10);
        return Math.min(3600, retryBaseSeconds * multiplier);
    }

    private Criteria dueCriteria(Instant now) {
        return new Criteria().orOperator(
            new Criteria().andOperator(
                Criteria.where("status").in(DeliveryStatus.PENDING, DeliveryStatus.FAILED),
                Criteria.where("nextAttemptAt").lte(now)),
            new Criteria().andOperator(
                Criteria.where("status").is(DeliveryStatus.SENDING),
                Criteria.where("leaseUntil").lte(now)));
    }

    private String subject(AlertRecord alert) {
        return truncate("[Tapstate Cloud][" + alert.getSeverity() + "] " + alert.getSubject(), 300);
    }

    private String body(AlertRecord alert) {
        String text = """
            A Tapstate Cloud alert matched an alert policy.

            Policy: %s
            Severity: %s
            Event type: %s
            Subject: %s
            Actor: %s
            Alert ID: %s
            Time: %s

            This is an automated message. Do not reply.
            """.formatted(
            alert.getPolicyName(),
            alert.getSeverity(),
            alert.getEventType(),
            alert.getSubject(),
            alert.getActor() == null ? "-" : alert.getActor(),
            alert.getId() == null ? "-" : alert.getId().toHexString(),
            alert.getCreatedAt() == null ? "-" : TIME_FORMAT.format(alert.getCreatedAt()));
        return truncate(text, 4000);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
