package io.tapstate.cloud.event;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertPolicyRepository extends MongoRepository<AlertPolicy, ObjectId> {

    List<AlertPolicy> findByOrgIdOrderByCreatedAtDesc(ObjectId orgId);

    List<AlertPolicy> findByOrgIdAndEnabledTrue(ObjectId orgId);

    long countByOrgId(ObjectId orgId);
}
