package io.tapstate.cloud.mdb;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data Mongo repository for {@link MdbCluster}.
 * <p>
 * The allocation engine uses {@link #findByStatusAndRegionOrderByCurrentAllocationsAsc}
 * (and the no-region fallback) to pick the least-loaded ACTIVE cluster for a
 * given region before atomically reserving a slot via MongoTemplate findAndModify.
 */
public interface MdbClusterRepository extends MongoRepository<MdbCluster, ObjectId> {

    /** Least-loaded first — ACTIVE clusters matching region, ordered by currentAllocations asc. */
    List<MdbCluster> findByStatusAndRegionOrderByCurrentAllocationsAsc(String status, String region);

    /** No-region fallback — pick any ACTIVE cluster, least-loaded first. */
    List<MdbCluster> findByStatusOrderByCurrentAllocationsAsc(String status);

    Optional<MdbCluster> findByIdAndStatus(ObjectId id, String status);

    long countByStatus(String status);
}
