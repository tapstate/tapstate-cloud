package io.tapstate.cloud.identity;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.bson.types.ObjectId;

/**
 * {@link User} repository — MongoDB Atlas, collection {@code users}.
 */
@Repository
public interface UserRepository extends MongoRepository<User, ObjectId> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
