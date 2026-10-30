package io.tapstate.cloud.notification;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
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
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryRetryTest {

    @Mock
    private NotificationDeliveryRepository deliveryRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private io.tapstate.cloud.event.EventCenterMetrics metrics;

    private FakeChannel channel;
    private NotificationDeliveryService service;

    @BeforeEach
    void setUp() {
        channel = new FakeChannel();
        service = new NotificationDeliveryService(
            deliveryRepository, mongoTemplate, List.of(channel), new EmailTemplateRenderer(), metrics, 3, 10, 60);
    }

    @Test
    @DisplayName("a failed send records one attempt and schedules a retry")
    void failedSendSchedulesRetry() {
        channel.failuresRemaining = 1;
        NotificationDelivery delivery = delivery(0);
        when(mongoTemplate.find(any(Query.class), eq(NotificationDelivery.class)))
            .thenReturn(List.of(delivery));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
            eq(NotificationDelivery.class))).thenReturn(delivery);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(NotificationDelivery.class)))
            .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.processDue(10);

        Document set = capturedSet();
        assertThat(set.get("status")).isEqualTo(DeliveryStatus.FAILED);
        assertThat(set.get("attempts")).isEqualTo(1);
        assertThat(set.get("nextAttemptAt")).isInstanceOf(Instant.class);
        assertThat(set.get("lastError")).asString().contains("smtp down");
    }

    @Test
    @DisplayName("the last allowed attempt moves the delivery to DEAD")
    void exhaustedAttemptsBecomeDead() {
        channel.failuresRemaining = 1;
        NotificationDelivery delivery = delivery(2);
        when(mongoTemplate.find(any(Query.class), eq(NotificationDelivery.class)))
            .thenReturn(List.of(delivery));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
            eq(NotificationDelivery.class))).thenReturn(delivery);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(NotificationDelivery.class)))
            .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.processDue(10);

        Document set = capturedSet();
        assertThat(set.get("status")).isEqualTo(DeliveryStatus.DEAD);
        assertThat(set.get("attempts")).isEqualTo(3);
        assertThat(set.get("nextAttemptAt")).isNull();
    }

    @Test
    @DisplayName("a successful send marks the row SENT exactly once")
    void successfulSendMarksSent() {
        NotificationDelivery delivery = delivery(0);
        when(mongoTemplate.find(any(Query.class), eq(NotificationDelivery.class)))
            .thenReturn(List.of(delivery));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
            eq(NotificationDelivery.class))).thenReturn(delivery);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(NotificationDelivery.class)))
            .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        int sent = service.processDue(10);

        assertThat(sent).isEqualTo(1);
        assertThat(channel.sent).isEqualTo(1);
        assertThat(capturedSet().get("status")).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    @DisplayName("retry delay doubles per attempt and is capped at one hour")
    void backoffIsExponentialAndCapped() {
        assertThat(service.backoffSeconds(1)).isEqualTo(10);
        assertThat(service.backoffSeconds(2)).isEqualTo(20);
        assertThat(service.backoffSeconds(3)).isEqualTo(40);
        assertThat(service.backoffSeconds(4)).isEqualTo(80);
        assertThat(service.backoffSeconds(5)).isEqualTo(160);
        assertThat(service.backoffSeconds(6)).isEqualTo(320);
        assertThat(service.backoffSeconds(10)).isEqualTo(3600);
    }

    @Test
    @DisplayName("a row another replica already claimed is not sent")
    void lostClaimIsNotSent() {
        NotificationDelivery delivery = delivery(0);
        when(mongoTemplate.find(any(Query.class), eq(NotificationDelivery.class)))
            .thenReturn(List.of(delivery));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
            eq(NotificationDelivery.class))).thenReturn(null);

        int sent = service.processDue(10);

        assertThat(sent).isZero();
        assertThat(channel.sent).isZero();
    }

    private Document capturedSet() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        org.mockito.Mockito.verify(mongoTemplate, org.mockito.Mockito.atLeastOnce())
            .updateFirst(any(Query.class), captor.capture(), eq(NotificationDelivery.class));
        return captor.getValue().getUpdateObject().get("$set", Document.class);
    }

    private NotificationDelivery delivery(int attempts) {
        return NotificationDelivery.builder()
            .id(new ObjectId())
            .orgId(new ObjectId())
            .alertId(new ObjectId())
            .channel("EMAIL")
            .recipient("owner@example.com")
            .subject("subject")
            .body("body")
            .status(DeliveryStatus.PENDING)
            .attempts(attempts)
            .nextAttemptAt(Instant.now().minusSeconds(5))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    }

    private static final class FakeChannel implements NotificationChannel {
        private int failuresRemaining;
        private int sent;

        @Override
        public String name() {
            return "EMAIL";
        }

        @Override
        public void send(NotificationMessage message) {
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new NotificationDeliveryException("smtp down");
            }
            sent++;
        }
    }
}
