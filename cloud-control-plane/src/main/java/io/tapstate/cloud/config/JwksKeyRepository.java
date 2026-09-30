package io.tapstate.cloud.config;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/**
 * Spring Data MongoDB repository for JWKS key management.
 */
public interface JwksKeyRepository extends MongoRepository<JwksKey, ObjectId> {

    /**
     * Find the most recently created active key.
     */
    Optional<JwksKey> findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus status);

    /**
     * Find all keys with the specified statuses.
     */
    List<JwksKey> findAllByStatusIn(List<JwksKey.KeyStatus> statuses);

    /**
     * Find a key by its key ID (kid).
     */
    Optional<JwksKey> findByKid(String kid);

}
