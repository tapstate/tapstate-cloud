package io.tapstate.cloud.subscription;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;
import org.bson.types.ObjectId;

/**
 * Spring Data MongoDB repository for {@link Subscription}.
 */
public interface SubscriptionRepository extends MongoRepository<Subscription, ObjectId> {

    Optional<Subscription> findByOrgId(ObjectId orgId);

    boolean existsByOrgId(ObjectId orgId);
}
