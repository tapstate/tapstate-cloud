package io.tapstate.cloud.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Organization-scoped alert policy.
 * <p>
 * A policy matches events by type pattern and minimum severity, then routes the
 * resulting alert to its recipients. Cooldown and quiet hours are evaluated in
 * the policy's own IANA timezone.
 */
@Document(collection = "alert_policies")
@CompoundIndex(name = "alert_policy_org_enabled", def = "{'orgId': 1, 'enabled': 1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertPolicy {

    @Id
    private ObjectId id;

    private ObjectId orgId;

    private String name;

    @Builder.Default
    private boolean enabled = true;

    /** Event type patterns; {@code *} matches everything and a trailing {@code *} is a prefix match. */
    private List<String> eventTypes;

    @Builder.Default
    private EventSeverity minSeverity = EventSeverity.INFO;

    private List<String> recipients;

    @Builder.Default
    private String channel = "EMAIL";

    @Builder.Default
    private long cooldownSeconds = 300;

    /** Local start of the quiet window ({@code HH:mm}); null disables quiet hours. */
    private String quietHoursStart;

    /** Local end of the quiet window ({@code HH:mm}); null disables quiet hours. */
    private String quietHoursEnd;

    @Builder.Default
    private String timezone = "UTC";

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
