package io.tapstate.cloud.tawk;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TawkWebhookEventRepository extends MongoRepository<TawkWebhookEvent, String> {

    List<TawkWebhookEvent> findTop20ByDeliveryStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
        TawkWebhookEvent.DeliveryStatus deliveryStatus, Instant now);
}
