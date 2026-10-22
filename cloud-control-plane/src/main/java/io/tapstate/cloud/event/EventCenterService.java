package io.tapstate.cloud.event;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.notification.DeliveryStatus;
import io.tapstate.cloud.notification.NotificationDelivery;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The event center: one ingestion contract for user operations, scheduled jobs
 * and external notifications; one organization-scoped read surface; one bounded
 * retention job.
 */
@Service
@Slf4j
public class EventCenterService {

    private final PlatformEventRepository eventRepository;
    private final MongoTemplate mongoTemplate;
    private final AlertEvaluationService evaluationService;
    private final EventCenterMetrics metrics;
    private final int retentionDays;
    private final int maxPageSize;

    public EventCenterService(
            PlatformEventRepository eventRepository,
            MongoTemplate mongoTemplate,
            AlertEvaluationService evaluationService,
            EventCenterMetrics metrics,
            @Value("${tapstate.cloud.events.retention-days:30}") int retentionDays,
            @Value("${tapstate.cloud.events.max-page-size:100}") int maxPageSize) {
        this.eventRepository = eventRepository;
        this.mongoTemplate = mongoTemplate;
        this.evaluationService = evaluationService;
        this.metrics = metrics;
        this.retentionDays = Math.max(1, retentionDays);
        this.maxPageSize = Math.max(1, maxPageSize);
    }

    /**
     * Idempotent ingestion. A duplicate {@code (orgId, source, dedupeKey)} returns
     * the stored event and does not re-evaluate policies.
     */
    public PlatformEvent ingest(EventIngestRequest request) {
        String source = requireToken("source", request.source(), 40, "^[A-Za-z0-9._-]+$");
        String type = requireToken("type", request.type(), 120, "^[A-Za-z0-9._:*-]+$");
        String dedupeKey = request.dedupeKey() == null || request.dedupeKey().isBlank()
            ? UUID.randomUUID().toString()
            : requireToken("dedupeKey", request.dedupeKey(), 200, "^[A-Za-z0-9._:@-]+$");
        String subject = request.subject() == null || request.subject().isBlank()
            ? type : truncate(request.subject().trim(), 300);
        String actor = request.actor() == null ? null : truncate(request.actor().trim(), 200);
        EventSeverity severity = request.severity() == null ? EventSeverity.INFO : request.severity();
        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();
        ObjectId orgId = parseOptionalOrgId(request.orgId());

        Optional<PlatformEvent> existing =
            eventRepository.findByOrgIdAndSourceAndDedupeKey(orgId, source, dedupeKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        PlatformEvent event = PlatformEvent.builder()
            .orgId(orgId)
            .source(source)
            .type(type)
            .severity(severity)
            .subject(subject)
            .actor(actor)
            .occurredAt(occurredAt)
            .dedupeKey(dedupeKey)
            .payload(EventPayloadSanitizer.sanitize(request.payload()))
            .createdAt(Instant.now())
            .build();
        try {
            PlatformEvent saved = eventRepository.save(event);
            metrics.ingested();
            evaluateBestEffort(saved);
            return saved;
        } catch (DuplicateKeyException e) {
            return eventRepository.findByOrgIdAndSourceAndDedupeKey(orgId, source, dedupeKey)
                .orElseThrow(() -> e);
        }
    }

    /** Record an event without letting an event-center failure break the caller. */
    public void recordBestEffort(EventIngestRequest request) {
        try {
            ingest(request);
        } catch (RuntimeException e) {
            log.warn("Event ingestion failed for type {}: {}", request.type(), e.getMessage());
        }
    }

    public EventPage list(String orgId, String viewerEmail, boolean admin,
                          String from, String to, String source, String type,
                          EventSeverity severity, String cursor, Integer limit) {
        ObjectId org = parseRequiredOrgId(orgId);
        Criteria criteria = Criteria.where("orgId").is(org);
        if (!admin) {
            criteria = criteria.and("actor").is(viewerEmail);
        }
        if (from != null && !from.isBlank()) {
            criteria = criteria.and("occurredAt").gte(parseInstant(from, "from"));
        }
        if (to != null && !to.isBlank()) {
            criteria = criteria.and("occurredAt").lte(parseInstant(to, "to"));
        }
        if (source != null && !source.isBlank()) {
            criteria = criteria.and("source").is(source);
        }
        if (type != null && !type.isBlank()) {
            criteria = criteria.and("type").is(type);
        }
        if (severity != null) {
            criteria = criteria.and("severity").is(severity);
        }
        if (cursor != null && !cursor.isBlank()) {
            EventCursor decoded;
            try {
                decoded = EventCursor.decode(cursor);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(AppErrorCode.EVENT_INVALID, 400, "cursor is invalid");
            }
            criteria = criteria.andOperator(new Criteria().orOperator(
                Criteria.where("occurredAt").lt(decoded.occurredAt()),
                new Criteria().andOperator(
                    Criteria.where("occurredAt").is(decoded.occurredAt()),
                    Criteria.where("_id").lt(new ObjectId(decoded.id())))));
        }
        int size = clampLimit(limit);
        Query query = new Query(criteria)
            .with(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("_id")))
            .limit(size + 1);
        List<PlatformEvent> rows = mongoTemplate.find(query, PlatformEvent.class);
        boolean more = rows.size() > size;
        List<PlatformEvent> items = more ? new ArrayList<>(rows.subList(0, size)) : rows;
        String next = null;
        if (more && !items.isEmpty()) {
            PlatformEvent last = items.get(items.size() - 1);
            next = EventCursor.encode(last.getOccurredAt(), last.getId().toHexString());
        }
        return new EventPage(items, next);
    }

    public PlatformEvent get(String orgId, String eventId, String viewerEmail, boolean admin) {
        ObjectId org = parseRequiredOrgId(orgId);
        ObjectId id = parseObjectId(eventId, AppErrorCode.EVENT_NOT_FOUND);
        PlatformEvent event = eventRepository.findById(id)
            .orElseThrow(() -> new BusinessException(AppErrorCode.EVENT_NOT_FOUND, 404, eventId));
        if (!org.equals(event.getOrgId())) {
            throw new BusinessException(AppErrorCode.EVENT_NOT_FOUND, 404, eventId);
        }
        if (!admin && !viewerEmail.equals(event.getActor())) {
            throw new BusinessException(AppErrorCode.EVENT_NOT_FOUND, 404, eventId);
        }
        return event;
    }

    /**
     * Bounded retention. Terminal deliveries and their alerts expire; rows still
     * pending, sending or retryable are never removed.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "eventRetentionLock", lockAtMostFor = "30m")
    public void purgeExpired() {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L);
        long events = mongoTemplate.remove(
            new Query(Criteria.where("occurredAt").lt(cutoff)), PlatformEvent.class).getDeletedCount();

        List<ObjectId> activeAlertIds = mongoTemplate.findDistinct(
            new Query(Criteria.where("status").in(
                DeliveryStatus.PENDING, DeliveryStatus.SENDING, DeliveryStatus.FAILED)),
            "alertId", NotificationDelivery.class, ObjectId.class);

        long deliveries = mongoTemplate.remove(
            new Query(Criteria.where("status").in(DeliveryStatus.SENT, DeliveryStatus.DEAD)
                .and("updatedAt").lt(cutoff)),
            NotificationDelivery.class).getDeletedCount();

        Criteria alertCriteria = Criteria.where("createdAt").lt(cutoff);
        if (!activeAlertIds.isEmpty()) {
            alertCriteria = alertCriteria.and("_id").nin(activeAlertIds);
        }
        long alerts = mongoTemplate.remove(new Query(alertCriteria), AlertRecord.class).getDeletedCount();

        if (events + deliveries + alerts > 0) {
            log.info("[Event Retention] removed events={} deliveries={} alerts={} cutoff={}",
                events, deliveries, alerts, cutoff);
        }
    }

    private void evaluateBestEffort(PlatformEvent event) {
        try {
            evaluationService.evaluate(event, event.getOccurredAt());
        } catch (RuntimeException e) {
            log.warn("Alert evaluation failed for event {}: {}", event.getId(), e.getMessage());
        }
    }

    private ObjectId parseOptionalOrgId(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return parseRequiredOrgId(orgId);
    }

    private ObjectId parseRequiredOrgId(String orgId) {
        try {
            return new ObjectId(orgId);
        } catch (RuntimeException e) {
            throw new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId);
        }
    }

    private ObjectId parseObjectId(String value, AppErrorCode code) {
        try {
            return new ObjectId(value);
        } catch (RuntimeException e) {
            throw new BusinessException(code, 404, value);
        }
    }

    private String requireToken(String field, String value, int max, String pattern) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(AppErrorCode.EVENT_INVALID, 400, field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max || !trimmed.matches(pattern)) {
            throw new BusinessException(AppErrorCode.EVENT_INVALID, 400,
                field + " has an invalid format");
        }
        return trimmed;
    }

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new BusinessException(AppErrorCode.EVENT_INVALID, 400,
                field + " must be an ISO-8601 instant");
        }
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 50;
        }
        return Math.min(limit, maxPageSize);
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
