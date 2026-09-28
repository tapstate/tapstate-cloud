package io.tapstate.cloud.mdb;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data Mongo repository for {@link MdbAllocation}. */
public interface MdbAllocationRepository extends MongoRepository<MdbAllocation, String> {

    Optional<MdbAllocation> findByClusterId(String clusterId);

    List<MdbAllocation> findByOrgId(String orgId);

    List<MdbAllocation> findByStatus(MdbAllocationStatus status);

    List<MdbAllocation> findByAtlasInstanceId(String atlasInstanceId);
}
