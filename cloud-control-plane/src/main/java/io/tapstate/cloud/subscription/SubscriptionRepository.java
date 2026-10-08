package io.tapstate.cloud.subscription;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/**
 * Spring Data MongoDB repository for {@link Subscription}.
 * <p>
 * An Organization owns many Subscriptions; each Subscription owns exactly one
 * Cluster + one MdbAllocation. Lookups are therefore list-by-org or
 * id-scoped-by-org — there is deliberately no unique org lookup.
 */
public interface SubscriptionRepository extends MongoRepository<Subscription, ObjectId> {

    List<Subscription> findByOrgIdOrderByCreatedAtDesc(ObjectId orgId);

    List<Subscription> findByStatus(SubscriptionStatus status);

    Optional<Subscription> findByIdAndOrgId(ObjectId id, ObjectId orgId);
}
