package io.tapstate.cloud.mdb;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/** Spring Data Mongo repository for {@link MdbAllocation}. */
public interface MdbAllocationRepository extends MongoRepository<MdbAllocation, ObjectId> {

    Optional<MdbAllocation> findByClusterId(ObjectId clusterId);

    List<MdbAllocation> findByOrgId(ObjectId orgId);

    List<MdbAllocation> findByStatus(MdbAllocationStatus status);

    List<MdbAllocation> findByMdbClusterId(ObjectId mdbClusterId);

    List<MdbAllocation> findBySubscriptionId(ObjectId subscriptionId);
}
