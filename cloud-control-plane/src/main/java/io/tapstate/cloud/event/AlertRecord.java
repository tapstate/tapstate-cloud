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

/**
 * A matched alert. The unique {@code (policyId, dedupeKey)} index is what makes
 * cooldown suppression safe across replicas: concurrent evaluations of the same
 * event window race on one insert and only one alert survives.
 */
@Document(collection = "alerts")
@CompoundIndex(name = "alert_policy_dedupe_unique",
    def = "{'policyId': 1, 'dedupeKey': 1}", unique = true)
@CompoundIndex(name = "alert_org_created", def = "{'orgId': 1, 'createdAt': -1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRecord {

    @Id
    private ObjectId id;

    private ObjectId orgId;

    private ObjectId policyId;

    private String policyName;

    private ObjectId eventId;

    private String eventType;

    private EventSeverity severity;

    private String subject;

    private String actor;

    private AlertStatus status;

    private String dedupeKey;

    @CreatedDate
    private Instant createdAt;
}
