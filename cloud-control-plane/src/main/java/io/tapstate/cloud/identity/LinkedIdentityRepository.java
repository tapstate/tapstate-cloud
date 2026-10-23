package io.tapstate.cloud.identity;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LinkedIdentityRepository extends MongoRepository<LinkedIdentity, ObjectId> {

    Optional<LinkedIdentity> findByProviderAndSubject(String provider, String subject);

    List<LinkedIdentity> findByUserEmailOrderByLinkedAtAsc(String userEmail);

    boolean existsByUserEmailAndProvider(String userEmail, String provider);
}
