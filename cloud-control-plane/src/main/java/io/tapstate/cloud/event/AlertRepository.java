package io.tapstate.cloud.event;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AlertRepository extends MongoRepository<AlertRecord, ObjectId> {

    Optional<AlertRecord> findByIdAndOrgId(ObjectId id, ObjectId orgId);
}
