package io.tapstate.cloud.progress;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Repository for {@link MockProvisionFailure} fixtures.
 */
public interface MockProvisionFailureRepository extends MongoRepository<MockProvisionFailure, ObjectId> {

    /** Unconsumed fixtures targeting one Organization, newest first. */
    List<MockProvisionFailure> findByOrgIdAndConsumedFalseOrderByCreatedAtDesc(ObjectId orgId);
}
