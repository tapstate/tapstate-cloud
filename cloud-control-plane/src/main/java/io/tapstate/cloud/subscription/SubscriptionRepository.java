package io.tapstate.cloud.subscription;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link Subscription}.
 */
public interface SubscriptionRepository extends MongoRepository<Subscription, String> {

    Optional<Subscription> findByOrgId(String orgId);

    boolean existsByOrgId(String orgId);
}
