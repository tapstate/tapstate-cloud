package io.tapstate.cloud.subscription;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spring Data MongoDB repository for {@link SubscriptionItem}.
 */
public interface SubscriptionItemRepository extends MongoRepository<SubscriptionItem, String> {

    List<SubscriptionItem> findBySubscriptionId(String subscriptionId);

    List<SubscriptionItem> findByOrgId(String orgId);
}
