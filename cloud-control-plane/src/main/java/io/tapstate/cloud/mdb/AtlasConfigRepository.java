package io.tapstate.cloud.mdb;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Spring Data Mongo repository for {@link AtlasConfig}.
 * <p>
 * Key query: {@link #findTopByStatusOrderByUpdatedAtDesc(String)} is called by
 * AtlasMdbService / AtlasApiClient on every Atlas Admin API invocation to get
 * the current Activated key. The Activated-only unique invariant is enforced
 * at the collection level via a partialFilterExpression unique index.
 */
public interface AtlasConfigRepository extends MongoRepository<AtlasConfig, ObjectId> {

    /**
     * Find the currently-activated AtlasConfig — returns empty if admin has not
     * seeded one (AtlasMdbService should treat this as a fast-fail path).
     */
    Optional<AtlasConfig> findTopByStatusOrderByUpdatedAtDesc(String status);

    /** Administrative lookup — find any config by status (not just Activated). */
    Optional<AtlasConfig> findFirstByStatus(String status);
}
