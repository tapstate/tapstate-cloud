package io.tapstate.cloud.notification;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationDeliveryRepository extends MongoRepository<NotificationDelivery, ObjectId> {

    Page<NotificationDelivery> findByOrgIdOrderByCreatedAtDesc(ObjectId orgId, Pageable pageable);

    Page<NotificationDelivery> findByOrgIdAndRecipientOrderByCreatedAtDesc(
        ObjectId orgId, String recipient, Pageable pageable);

    Optional<NotificationDelivery> findByIdAndOrgId(ObjectId id, ObjectId orgId);

    Optional<NotificationDelivery> findByNotificationKey(String notificationKey);
}
