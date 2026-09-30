package io.tapstate.cloud.subscription;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import org.bson.types.ObjectId;

/**
 * Spring Data MongoDB repository for {@link SubscriptionItem}.
 */
public interface SubscriptionItemRepository extends MongoRepository<SubscriptionItem, ObjectId> {

    List<SubscriptionItem> findBySubscriptionId(ObjectId subscriptionId);

    List<SubscriptionItem> findByOrgId(ObjectId orgId);
}
