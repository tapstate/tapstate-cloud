package io.tapstate.cloud.event;

import com.mongodb.client.result.DeleteResult;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventRetentionTest {

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
    @DisplayName("retention removes terminal rows and protects alerts with unfinished deliveries")
    void retentionProtectsUnfinishedDeliveries() {
        ObjectId activeAlertId = new ObjectId();
        when(mongoTemplate.remove(any(Query.class), eq(PlatformEvent.class)))
            .thenReturn(DeleteResult.acknowledged(2));
        when(mongoTemplate.findDistinct(any(Query.class), eq("alertId"),
            eq(io.tapstate.cloud.notification.NotificationDelivery.class), eq(ObjectId.class)))
            .thenReturn(List.of(activeAlertId));
        when(mongoTemplate.remove(any(Query.class), eq(io.tapstate.cloud.notification.NotificationDelivery.class)))
            .thenReturn(DeleteResult.acknowledged(3));
        when(mongoTemplate.remove(any(Query.class), eq(AlertRecord.class)))
            .thenReturn(DeleteResult.acknowledged(1));

        service.purgeExpired();

        ArgumentCaptor<Query> alertQuery = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(alertQuery.capture(), eq(AlertRecord.class));
        org.bson.Document idCondition =
            (org.bson.Document) alertQuery.getValue().getQueryObject().get("_id");
        @SuppressWarnings("unchecked")
        List<ObjectId> excludedAlertIds = (List<ObjectId>) idCondition.get("$nin");
        assertThat(excludedAlertIds).contains(activeAlertId);
    }
}
