package io.tapstate.cloud.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * A single platform event collected by the event center.
 * <p>
 * The {@code (orgId, source, dedupeKey)} tuple is unique: sources may retry a
 * delivery and the event center must still hold exactly one event for it. A
 * system event may omit {@code orgId}; it is stored but not evaluated against an
 * organization's alert policies.
 */
@Document(collection = "events")
@CompoundIndex(name = "event_org_dedupe_unique",
    def = "{'orgId': 1, 'source': 1, 'dedupeKey': 1}", unique = true)
@CompoundIndex(name = "event_org_occurred",
    def = "{'orgId': 1, 'occurredAt': -1, '_id': -1}")
@CompoundIndex(name = "event_org_type_occurred",
    def = "{'orgId': 1, 'type': 1, 'occurredAt': -1}")
@CompoundIndex(name = "event_org_severity_occurred",
    def = "{'orgId': 1, 'severity': 1, 'occurredAt': -1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformEvent {

    @Id
    private ObjectId id;

    private ObjectId orgId;

    private String source;

    private String type;

    private EventSeverity severity;

    private String subject;

    private String actor;

    private Instant occurredAt;

    private String dedupeKey;

    @Builder.Default
    private int schemaVersion = 1;

    private Map<String, Object> payload;

    @CreatedDate
    private Instant createdAt;
}
