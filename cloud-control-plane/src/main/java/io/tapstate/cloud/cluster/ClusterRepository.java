package io.tapstate.cloud.cluster;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

@Repository
public interface ClusterRepository extends MongoRepository<Cluster, ObjectId> {

    List<Cluster> findByOrgId(ObjectId orgId);

    List<Cluster> findByOrgIdAndRegion(ObjectId orgId, String region);

    List<Cluster> findByOrgIdAndState(ObjectId orgId, ClusterState state);

    Optional<Cluster> findByIdAndOrgId(ObjectId id, ObjectId orgId);

    List<Cluster> findBySubscriptionId(ObjectId subscriptionId);

    Optional<Cluster> findByIdAndSubscriptionId(ObjectId id, ObjectId subscriptionId);

    boolean existsByOrgIdAndRegionAndName(ObjectId orgId, String region, String name);

    /**
     * Find READY clusters whose last heartbeat was more than 5 minutes ago.
     * Used by scheduled task to auto-transition to DEGRADED.
     */
    @Query("{ 'state': ?0, 'lastHeartbeatAt': { $lt: ?1 } }")
    List<Cluster> findReadyClustersWithExpiredHeartbeat(ClusterState state, Instant threshold);

    /**
     * Find DEGRADED clusters whose heartbeat has recovered.
     */
    @Query("{ 'state': ?0, 'lastHeartbeatAt': { $gte: ?1 } }")
    List<Cluster> findDegradedClustersWithRecentHeartbeat(ClusterState state, Instant threshold);

    /**
     * Find Cluster by SDK static token — used by C2 status-report authentication
     * (runtime authenticates itself with its per-Cluster Bearer token).
     */
    Optional<Cluster> findBySdkStaticToken(String sdkStaticToken);
}
