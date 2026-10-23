package io.tapstate.cloud.notification;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationDeliveryRepository extends MongoRepository<NotificationDelivery, ObjectId> {

    List<NotificationDelivery> findTop50ByOrgIdOrderByCreatedAtDesc(ObjectId orgId);

    List<NotificationDelivery> findTop50ByOrgIdAndRecipientOrderByCreatedAtDesc(ObjectId orgId, String recipient);

    Optional<NotificationDelivery> findByIdAndOrgId(ObjectId id, ObjectId orgId);
}
