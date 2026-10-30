package io.tapstate.cloud.event;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformEventRepository extends MongoRepository<PlatformEvent, ObjectId> {

    Optional<PlatformEvent> findByOrgIdAndSourceAndDedupeKey(ObjectId orgId, String source, String dedupeKey);
}
