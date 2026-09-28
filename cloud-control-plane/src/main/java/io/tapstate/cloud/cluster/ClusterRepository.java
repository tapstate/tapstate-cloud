package io.tapstate.cloud.cluster;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClusterRepository extends MongoRepository<Cluster, String> {

    List<Cluster> findByOrgId(String orgId);

    List<Cluster> findByOrgIdAndRegion(String orgId, String region);

    List<Cluster> findByOrgIdAndState(String orgId, ClusterState state);

    Optional<Cluster> findByIdAndOrgId(String id, String orgId);

    boolean existsByOrgIdAndRegionAndName(String orgId, String region, String name);

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
